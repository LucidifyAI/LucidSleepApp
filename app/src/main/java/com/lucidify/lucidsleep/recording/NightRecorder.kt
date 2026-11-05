package com.lucidify.lucidsleep.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.lucidify.lucidsleep.core.CgxBleClient
import com.lucidify.lucidsleep.core.cloudrecording.CloudSessionState
import com.lucidify.lucidsleep.core.cloudrecording.CloudSyncScheduler
import com.lucidify.lucidsleep.core.cloudrecording.DurableCloudSessionStore
import com.lucidify.lucidsleep.core.cloudrecording.DurableCloudSyncEngine
import com.lucidify.lucidsleep.core.patch.PatchContactMonitor
import com.lucidify.lucidsleep.core.patch.PatchPacketParser
import com.lucidify.lucidsleep.enroll.EnrollmentStore
import com.lucidify.lucidsleep.series.SeriesStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Records one night from the Patch to the phone's encrypted spool and on to org-service.
 *
 * Built from the demo app's recording path (same chunk format, same spool, same upload
 * protocol) with these differences:
 *  - Bluetooth bytes are framed, parsed and written on a dedicated thread. Nothing in that
 *    path waits on the screen, and there is no display pacing.
 *  - Chunk encryption and registration run on a second thread, so a slow Keystore cannot
 *    stall packet handling.
 *  - The link watchdog counts raw Bluetooth bytes, and the reconnect counter resets only
 *    once data flows again.
 *  - Starting a night closes out any older night left open, so leftovers never slow uploads.
 *
 * Threading: everything named rec* below is owned by [packetThread]. Other threads reach it
 * only through withContext(packetThread).
 */
@SuppressLint("MissingPermission") // every Bluetooth call is gated by hasBlePermissions() or runCatching
class NightRecorder(
	private val context: Context,
	private val ble: CgxBleClient,
	private val store: DurableCloudSessionStore,
	private val engine: DurableCloudSyncEngine,
	private val setupStore: EnrollmentStore,
	private val series: SeriesStore,
	private val log: NightLog,
	/** The sleeper's bedtime answers, "I'm awake" taps and morning diary. */
	val answers: NightAnswers
) {
	enum class Link { OFF, SEARCHING, CONNECTING, CONNECTED, STREAMING }

	data class State(
		val link: Link = Link.OFF,
		val patchAddress: String? = null,
		val batteryPercent: Float? = null,
		val contact: PatchContactMonitor.State = PatchContactMonitor.State.CHECKING,
		val contactSeverity: PatchContactMonitor.Severity = PatchContactMonitor.Severity.NONE,
		val contactProblem: String = "",
		val impAfzOhms: Float = Float.NaN,
		val impFp2Ohms: Float = Float.NaN,
		/** Each electrode's DC offset (mV), for the contact check's details line. */
		val afzDcMv: Float = Float.NaN,
		val fp2DcMv: Float = Float.NaN,
		val recording: Boolean = false,
		val localSessionId: String? = null,
		val nightStartedAtEpochMs: Long = 0L,
		val samplesRecorded: Long = 0L,
		val chunksSpooled: Int = 0,
		val chunksUploaded: Int = 0,
		val outageActive: Boolean = false,
		val outageSinceEpochMs: Long = 0L,
		val cloudReachable: Boolean = true,
		val signInNeeded: Boolean = false,
		val lastError: String? = null,
		/** The last scan heard a patch, but not the paired one (a swapped patch, most likely). */
		val otherPatchNearby: Boolean = false,
		/** Pairing with a nearby patch in place of the paired one. */
		val switchingPatch: Boolean = false
	) {
		val recordedMs: Long get() = samplesRecorded * 1000L / SAMPLE_RATE_HZ
		val uploadedPercent: Int
			get() = if (chunksSpooled <= 0) 0 else (chunksUploaded * 100 / chunksSpooled).coerceIn(0, 100)
	}

	data class NightSummary(
		val localSessionId: String,
		val recordedMs: Long,
		val counted: Boolean,
		val uploadedPercent: Int
	)

	private val _state = MutableStateFlow(State())
	val state: StateFlow<State> = _state.asStateFlow()

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val packetThread = Executors.newSingleThreadExecutor { r ->
		Thread(r, "lucidsleep-packets").apply { priority = Thread.MAX_PRIORITY }
	}.asCoroutineDispatcher()
	private val spoolThread = Executors.newSingleThreadExecutor { r -> Thread(r, "lucidsleep-spool") }
		.asCoroutineDispatcher()
	/** Link supervision and Bluetooth events share counters, so they run on one thread. */
	private val linkThread = Executors.newSingleThreadExecutor { r -> Thread(r, "lucidsleep-link") }
		.asCoroutineDispatcher()
	/** Start, stop, resume and startup recovery never overlap. */
	private val lifecycle = Mutex()
	private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
	private val workRoot = File(context.filesDir, "cloud_recording_work")

	// ---- link supervision (supervisor coroutine, events coroutine) ----
	@Volatile private var wantLink = false
	@Volatile private var targetAddress: String? = null
	@Volatile private var connectStartedAtMs = 0L
	@Volatile private var resetAttemptsRequested = false
	private var attempts = 0
	private var nextAttemptAtMs = 0L
	private var lastBytes = -1L
	private var lastByteProgressMs = 0L
	private var scanningForTarget = false
	private var scanStartedAtMs = 0L
	private val scanStarts = ArrayDeque<Long>()
	@Volatile private var pairingInProgress = false

	// ---- recording (packetThread only) ----
	private var recActive = false
	private var recLocalId: String? = null
	private var recStartedAtEpochMs = 0L
	private var recSeq = 0
	private var recTotalFrames = 0L
	private var recFramesInChunk = 0
	private var recChunkStartFrame = 0L
	private var recChunkFile: File? = null
	private var recChunkOut: BufferedOutputStream? = null
	private var recChunkFos: FileOutputStream? = null
	private var recLastPacketArrivalMs = 0L
	private var recSamples = 0L
	/** Set after a resume: the first packet re-anchors the sample index to the wall clock. */
	private var recResumed = false
	private var recLastOpenFailMs = 0L
	/**
	 * Elapsed-realtime start of the current Bluetooth outage, 0 when the link is fine. Set by the
	 * link thread, cleared by the packet thread with compare-and-set, so neither can swallow the
	 * other's update. Packets that arrive after the start belong to the reconnected link.
	 */
	private val outageStartedElapsed = AtomicLong(0L)

	private sealed class SpoolJob {
		data class Chunk(
			val localSessionId: String,
			val seq: Int,
			val firstSampleIndex: Long,
			val sampleCount: Int,
			val file: File,
			val samplesRecordedSoFar: Long
		) : SpoolJob()
		data class HighWater(val localSessionId: String, val seqExclusive: Int, val sampleExclusive: Long) : SpoolJob()
		data class Drain(val done: CompletableDeferred<Boolean>) : SpoolJob()
	}
	private val spoolQueue = Channel<SpoolJob>(Channel.UNLIMITED)
	private val uploadWake = Channel<Unit>(Channel.CONFLATED)
	@Volatile private var started = false

	/** Starts the background loops once per process. Safe to call repeatedly. */
	@Synchronized
	fun start() {
		if (started) return
		started = true
		targetAddress = setupStore.setup.value.patchAddress
		_state.update { it.copy(patchAddress = targetAddress) }
		scope.launch(packetThread) { decodeLoop() }
		scope.launch(spoolThread) { spoolLoop() }
		scope.launch(linkThread) { eventLoop() }
		scope.launch(linkThread) { superviseLink() }
		scope.launch(Dispatchers.IO) { uploadLoop() }
		scope.launch(Dispatchers.IO) { recoverAndResume() }
		log.event("APP_START", "patch" to targetAddress)
	}

	// =====================================================================================
	// Link: setup pairing, evening connection, overnight reconnects
	// =====================================================================================

	/** Keep the Patch connected (Ready check, contact check, recording). */
	fun holdLink(hold: Boolean) {
		if (hold == wantLink) return
		wantLink = hold
		log.event("LINK_HOLD", "hold" to hold, "link" to _state.value.link)
		if (hold) {
			resetAttemptsRequested = true
		} else if (!_state.value.recording) {
			runCatching { ble.stopScan() }
			runCatching { ble.disconnect() }
			_state.update { it.copy(link = Link.OFF) }
		}
	}

	/**
	 * Pairs the nearest patch in place of the paired one (the Ready check's "Switch"). With a
	 * patch serial on the kit, only that patch qualifies. If none does, the paired patch stays.
	 */
	fun switchToNearbyPatch(expectedSerial: String?) {
		if (_state.value.recording || pairingInProgress || _state.value.switchingPatch) return
		log.event("PATCH_SWITCH_REQUESTED", "was" to targetAddress)
		_state.update { it.copy(switchingPatch = true) }
		scope.launch {
			pairPatch(expectedSerial)
				.onSuccess { log.event("PATCH_SWITCHED", "to" to targetAddress) }
				.onFailure { log.event("PATCH_SWITCH_FAILED", "error" to it.message) }
			resetAttemptsRequested = true
			_state.update { it.copy(switchingPatch = false, otherPatchNearby = false) }
		}
	}

	/**
	 * Forgets the paired Patch so setup finds another (a swapped or replacement patch).
	 * Refused while a night is being recorded.
	 */
	fun forgetPatch(): Boolean {
		if (_state.value.recording) return false
		val old = targetAddress
		wantLink = false
		runCatching { ble.stopScan() }
		runCatching { ble.disconnect() }
		targetAddress = null
		scanningForTarget = false
		setupStore.forgetPatch()
		_state.update { it.copy(link = Link.OFF, patchAddress = null, batteryPercent = null) }
		log.event("PATCH_FORGOTTEN", "was" to old)
		return true
	}

	/**
	 * First-time setup: find the Patch, confirm its serial (when the QR card names one) and
	 * remember its Bluetooth address. Returns the serial read, or null if no Patch matched.
	 */
	suspend fun pairPatch(expectedSerial: String?): Result<String?> = withContext(Dispatchers.Default) {
		if (!hasBlePermissions()) return@withContext Result.failure(IllegalStateException("Bluetooth permission needed"))
		if (!ble.isBluetoothReady()) return@withContext Result.failure(IllegalStateException("Bluetooth is off"))
		pairingInProgress = true
		var paired = false
		try {
			_state.update { it.copy(link = Link.SEARCHING) }
			runCatching { ble.disconnect() }
			runCatching { ble.startScan(clearPrevious = true) }
				.onFailure { return@withContext Result.failure(it) }
			log.event("PAIR_SCAN", "expectedSerial" to expectedSerial)
			delay(PAIR_SCAN_MS)
			runCatching { ble.stopScan() }
			val candidates = ble.discoveredDevices.value.sortedByDescending { it.rssi }
			log.event("PAIR_CANDIDATES", "count" to candidates.size)
			for (device in candidates) {
				val serial = tryReadSerial(device.address)
				log.event("PAIR_CANDIDATE", "address" to device.address, "rssi" to device.rssi, "serial" to serial)
				val matches = expectedSerial == null ||
					(serial != null && normalizeSerial(serial) == normalizeSerial(expectedSerial))
				if (matches) {
					setupStore.savePatch(device.address, serial?.ifBlank { null })
					targetAddress = device.address
					_state.update { it.copy(patchAddress = device.address) }
					log.event("PAIR_OK", "address" to device.address, "serial" to serial)
					// Paired: let the Patch go until the Ready check asks for it.
					runCatching { ble.disconnect() }
					_state.update { it.copy(link = Link.OFF) }
					paired = true
					return@withContext Result.success(serial)
				}
				runCatching { ble.disconnect() }
				delay(1_000)
			}
			_state.update { it.copy(link = Link.OFF) }
			Result.failure(IllegalStateException(if (candidates.isEmpty()) "No patch found" else "No patch matched the kit"))
		} finally {
			runCatching { ble.stopScan() }
			if (!paired) {
				runCatching { ble.disconnect() }
				_state.update { it.copy(link = Link.OFF) }
			}
			pairingInProgress = false
		}
	}

	private suspend fun tryReadSerial(address: String): String? {
		val linked = awaitEvent(PAIR_CONNECT_TIMEOUT_MS, trigger = { ble.connect(address) }) {
			it.startsWith("notify_enabled:") || it.startsWith("disconnected:") ||
				it.startsWith("gatt_error:") || it.startsWith("conn_error:")
		}
		if (linked == null || !linked.startsWith("notify_enabled:")) return null
		_state.update { it.copy(link = Link.CONNECTED) }
		delay(500)
		val ascii = awaitEvent(PAIR_SERIAL_TIMEOUT_MS, trigger = { ble.readSlot15() }) {
			it.startsWith("slot15_ascii:")
		} ?: return ""
		return ascii.removePrefix("slot15_ascii:").filter { it in ' '..'~' }.trim()
	}

	private fun normalizeSerial(s: String) = s.trim().uppercase().filter { it.isLetterOrDigit() }

	/** Subscribes before [trigger] runs, so a fast reply cannot be missed. */
	private suspend fun awaitEvent(timeoutMs: Long, trigger: () -> Unit, match: (String) -> Boolean): String? =
		coroutineScope {
			val waiter = async(start = CoroutineStart.UNDISPATCHED) {
				withTimeoutOrNull(timeoutMs) { ble.events.first(match) }
			}
			runCatching(trigger)
			waiter.await()
		}

	private suspend fun eventLoop() {
		ble.events.collect { e ->
			when {
				e.startsWith("connecting:") -> _state.update { it.copy(link = Link.CONNECTING) }
				e.startsWith("connected:") -> {
					_state.update { it.copy(link = Link.CONNECTED) }
					log.event("BLE_CONNECTED", "address" to e.substringAfter(':'))
				}
				e.startsWith("disconnected:") -> onLinkLost("disconnected")
				e.startsWith("gatt_error:") || e.startsWith("conn_error:") || e.startsWith("svc_error:") ||
					e.startsWith("services_failed:") || e == "svc_missing" || e == "charA_missing" ||
					e == "charB_missing" || e.startsWith("notify_enable_fail") -> {
					// A setup failure can leave the link half-open: tear it down so the next
					// attempt starts clean and the Patch can advertise again.
					log.event("BLE_ERROR", "event" to e)
					if (!pairingInProgress) {
						runCatching { ble.disconnect() }
						onLinkLost(e)
					}
				}
				e.startsWith("notify_drop:") -> log.event("BLE_NOTIFY_DROP", "count" to e.substringAfter(':'))
				e.startsWith("scan_failed:") -> log.event("BLE_SCAN_FAILED", "code" to e.substringAfter(':'))
			}
		}
	}

	private fun onLinkLost(reason: String) {
		if (pairingInProgress) {
			_state.update { it.copy(link = Link.OFF) }
			return
		}
		val wasUp = _state.value.link != Link.OFF
		_state.update { it.copy(link = Link.OFF) }
		connectStartedAtMs = 0L
		lastBytes = -1L
		if (_state.value.recording && outageStartedElapsed.compareAndSet(0L, SystemClock.elapsedRealtime())) {
			_state.update { it.copy(outageActive = true, outageSinceEpochMs = System.currentTimeMillis()) }
			log.event("OUTAGE_START", "reason" to reason)
		} else if (wasUp) {
			log.event("BLE_LINK_LOST", "reason" to reason, "hold" to wantLink)
		}
	}

	private suspend fun superviseLink() {
		while (scope.isActive) {
			runCatching { tickLink() }.onFailure { Log.w(TAG, "link tick failed", it) }
			delay(LINK_TICK_MS)
		}
	}

	private fun tickLink() {
		if (resetAttemptsRequested) {
			resetAttemptsRequested = false
			attempts = 0
			nextAttemptAtMs = 0L
		}
		if (pairingInProgress) return
		val target = targetAddress
		val recording = _state.value.recording
		if (target == null || !(wantLink || recording) || !hasBlePermissions()) return
		val now = SystemClock.elapsedRealtime()
		when (_state.value.link) {
			Link.OFF, Link.SEARCHING -> {
				if (scanningForTarget) {
					if (now - scanStartedAtMs >= TARGET_SCAN_MS) {
						scanningForTarget = false
						runCatching { ble.stopScan() }
						// Which patches the phone can hear: tells "patch off or busy" from "a different patch".
						val seen = ble.discoveredDevices.value
						val targetHeard = seen.any { it.address == target }
						log.event(
							"SCAN_SAW", "patches" to seen.size, "target" to targetHeard,
							"heard" to seen.joinToString("|") { it.address + "@" + it.rssi }
						)
						val other = !targetHeard && seen.isNotEmpty()
						if (_state.value.otherPatchNearby != other) _state.update { it.copy(otherPatchNearby = other) }
						connectTo(target, now)
					}
					return
				}
				if (now < nextAttemptAtMs) return
				attempts++
				nextAttemptAtMs = now + if (attempts % SETTLE_EVERY == 0) SETTLE_MS else RETRY_MS
				if (attempts % SCAN_EVERY == 0 && mayStartScan(now)) {
					// Refresh Android's cache of the Patch before the next direct attempt.
					log.event("RECONNECT_ATTEMPT", "attempt" to attempts, "mode" to "scan")
					scanningForTarget = true
					scanStartedAtMs = now
					runCatching { ble.startScan(clearPrevious = true) }
				} else {
					log.event("RECONNECT_ATTEMPT", "attempt" to attempts, "mode" to "direct")
					connectTo(target, now)
				}
			}
			Link.CONNECTING -> {
				if (connectStartedAtMs > 0L && now - connectStartedAtMs > CONNECT_TIMEOUT_MS) {
					log.event("CONNECT_TIMEOUT")
					runCatching { ble.disconnect() }
					onLinkLost("connect_timeout")
				}
			}
			Link.CONNECTED, Link.STREAMING -> {
				val bytes = ble.notifyBytesReceived
				if (bytes != lastBytes) {
					val flowed = lastBytes >= 0L
					lastBytes = bytes
					lastByteProgressMs = now
					if (flowed) {
						if (_state.value.link != Link.STREAMING) {
							_state.update { it.copy(link = Link.STREAMING, otherPatchNearby = false) }
							log.event("STREAMING", "attempts" to attempts)
						}
						if (attempts != 0) {
							log.event("DATA_FLOWING", "afterAttempts" to attempts)
							attempts = 0
							nextAttemptAtMs = 0L
						}
					}
				} else {
					val limit = if (_state.value.link == Link.STREAMING) NO_DATA_MS else FIRST_DATA_MS
					if (now - lastByteProgressMs > limit) {
						log.event("NO_DATA_WATCHDOG", "silentMs" to (now - lastByteProgressMs))
						runCatching { ble.disconnect() }
						onLinkLost("no_data")
					}
				}
			}
		}
	}

	private fun connectTo(address: String, now: Long) {
		connectStartedAtMs = now
		lastBytes = -1L
		lastByteProgressMs = now
		_state.update { it.copy(link = Link.CONNECTING) }
		runCatching { ble.stopScan() }
		runCatching { ble.connect(address) }.onFailure { onLinkLost("connect_failed:${it.message}") }
	}

	/** Android allows 5 scan starts per 30 s; stay under it so no start fails silently. */
	private fun mayStartScan(now: Long): Boolean {
		while (scanStarts.isNotEmpty() && now - scanStarts.first() > 32_000L) scanStarts.removeFirst()
		if (scanStarts.size >= 4) return false
		scanStarts.addLast(now)
		return true
	}

	// =====================================================================================
	// Packets: framing, parsing, contact quality, chunk writing (packetThread)
	// =====================================================================================

	private suspend fun decodeLoop() {
		val buf = ByteArray(256 * 1024)
		var head = 0
		var tail = 0
		val contact = PatchContactMonitor(SAMPLE_RATE_HZ)
		var battery: Float? = null
		var snap: PatchContactMonitor.Snapshot? = null
		var loggedContact: PatchContactMonitor.State? = null
		var lastPublishMs = 0L

		ble.notifyBytes.collect { chunk ->
			// Append, compacting when the tail reaches the end.
			val n = chunk.bytes.size
			if (tail + n > buf.size) {
				val live = tail - head
				if (live + n > buf.size) { head = 0; tail = 0 } else {
					System.arraycopy(buf, head, buf, 0, live); head = 0; tail = live
				}
			}
			System.arraycopy(chunk.bytes, 0, buf, tail, n)
			tail += n

			while (tail - head >= PACKET_SIZE) {
				if (buf[head] != SYNC) { head++; continue }
				val pkt = buf.copyOfRange(head, head + PACKET_SIZE)
				val parsed = PatchPacketParser.tryParse(pkt)
				if (parsed == null) { head++; continue }
				head += PACKET_SIZE

				battery = parsed.batteryPercent ?: battery
				contact.add(parsed.impedanceAfzOhms, parsed.impedanceFp2Ohms, parsed.afzVolts, parsed.fp2Volts)
					?.let {
						snap = it
						// Each change of contact state, with the numbers behind it, for diagnostics.
						if (it.state != loggedContact) {
							loggedContact = it.state
							log.event(
								"CONTACT", "state" to it.state.name,
								"afzOhms" to it.impAfzOhms.toInt(), "fp2Ohms" to it.impFp2Ohms.toInt(),
								"afzDcMv" to (it.afzDcVolts * 1000f).toInt(), "fp2DcMv" to (it.fp2DcVolts * 1000f).toInt()
							)
						}
					}
				if (recActive) writePacket(pkt, chunk.atElapsedMs)
			}
			if (head == tail) { head = 0; tail = 0 }

			val now = SystemClock.elapsedRealtime()
			if (now - lastPublishMs >= PUBLISH_MS) {
				lastPublishMs = now
				val s = snap
				val samples = recSamples
				_state.update {
					it.copy(
						batteryPercent = battery,
						contact = s?.state ?: it.contact,
						contactSeverity = s?.severity ?: it.contactSeverity,
						contactProblem = s?.problem ?: it.contactProblem,
						impAfzOhms = s?.impAfzOhms ?: it.impAfzOhms,
						impFp2Ohms = s?.impFp2Ohms ?: it.impFp2Ohms,
						afzDcMv = s?.let { v -> v.afzDcVolts * 1000f } ?: it.afzDcMv,
						fp2DcMv = s?.let { v -> v.fp2DcVolts * 1000f } ?: it.fp2DcMv,
						samplesRecorded = if (it.recording) samples else it.samplesRecorded
					)
				}
			}
		}
	}

	private fun writePacket(pkt: ByteArray, arrivalElapsedMs: Long) {
		// After a resume, the first packet re-anchors the sample index to the wall clock, so
		// the time the app was down (and any reconnect time after it) stays a gap.
		if (recResumed) {
			recResumed = false
			val arrivalWallMs = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - arrivalElapsedMs)
			val frame = ((arrivalWallMs - recStartedAtEpochMs).coerceAtLeast(0L) * SAMPLE_RATE_HZ) / 1000L
			if (frame > recTotalFrames) {
				closeChunk()
				log.event("RESUME_GAP", "insertedSamples" to (frame - recTotalFrames))
				val gapMs = (frame - recTotalFrames) * 1000L / SAMPLE_RATE_HZ
				if (gapMs >= SeriesStore.NOTABLE_GAP_MS) recLocalId?.let { series.noteGap(it, arrivalWallMs - gapMs, gapMs) }
				recTotalFrames = frame
				markHighWater()
			}
		}
		// The first packet after a reconnect closes the outage and advances the sample index
		// by the real time lost, so later data keeps its true position in the night.
		val outageStart = outageStartedElapsed.get()
		if (outageStart > 0L && arrivalElapsedMs > outageStart) {
			val last = recLastPacketArrivalMs
			val gapMs = if (last > 0L) (arrivalElapsedMs - last).coerceAtLeast(0L) else 0L
			val missing = ((gapMs * SAMPLE_RATE_HZ) / 1000L - 1L).coerceAtLeast(0L)
			if (missing > 0L) {
				closeChunk()
				recTotalFrames += missing
				markHighWater()
			}
			outageStartedElapsed.compareAndSet(outageStart, 0L)
			_state.update { it.copy(outageActive = false, outageSinceEpochMs = 0L) }
			log.event("OUTAGE_END", "gapMs" to gapMs, "insertedSamples" to missing)
			if (gapMs >= SeriesStore.NOTABLE_GAP_MS) {
				recLocalId?.let { series.noteGap(it, System.currentTimeMillis() - gapMs, gapMs) }
			}
		}
		recLastPacketArrivalMs = arrivalElapsedMs

		val out = recChunkOut ?: openChunk() ?: return
		runCatching { out.write(pkt) }.onFailure {
			log.event("CHUNK_WRITE_FAILED", "error" to it.message)
			_state.update { s -> s.copy(lastError = "Saving to the phone failed") }
			// Hand over the packets already written; recovery trims any partial packet.
			closeChunk()
			return
		}
		recFramesInChunk++
		recTotalFrames++
		recSamples++
		if (recFramesInChunk >= FRAMES_PER_CHUNK) closeChunk()
	}

	/**
	 * Records the sample index after a gap, so if the process dies before the next chunk is
	 * registered, recovery still places that chunk after the gap.
	 */
	private fun markHighWater() {
		val id = recLocalId ?: return
		spoolQueue.trySend(SpoolJob.HighWater(id, recSeq, recTotalFrames))
	}

	private fun openChunk(): BufferedOutputStream? {
		val id = recLocalId ?: return null
		val now = SystemClock.elapsedRealtime()
		if (recLastOpenFailMs > 0L && now - recLastOpenFailMs < 5_000L) return null
		return runCatching {
			val dir = File(workRoot, id).apply { mkdirs() }
			var file = File(dir, "%08d.part".format(recSeq))
			// Never overwrite a working file a previous process left behind.
			while (file.exists() && file.length() > 0L) {
				log.event("CHUNK_EXISTS", "seq" to recSeq)
				recSeq++
				file = File(dir, "%08d.part".format(recSeq))
			}
			val fos = FileOutputStream(file, false)
			recChunkFile = file
			recChunkFos = fos
			recFramesInChunk = 0
			recChunkStartFrame = recTotalFrames
			recLastOpenFailMs = 0L
			BufferedOutputStream(fos, 64 * 1024).also { recChunkOut = it }
		}.onFailure {
			recLastOpenFailMs = now
			log.event("CHUNK_OPEN_FAILED", "error" to it.message)
			_state.update { s -> s.copy(lastError = "Saving to the phone failed") }
		}.getOrNull()
	}

	/** Closes the open chunk file and hands it to the spool thread. Never blocks on crypto. */
	private fun closeChunk() {
		val id = recLocalId ?: return
		val file = recChunkFile ?: return
		val out = recChunkOut
		val fos = recChunkFos
		val count = recFramesInChunk
		runCatching { out?.flush() }
		runCatching { fos?.fd?.sync() }
		runCatching { out?.close() }
		recChunkOut = null
		recChunkFos = null
		recChunkFile = null
		recFramesInChunk = 0
		if (count <= 0) {
			runCatching { file.delete() }
			return
		}
		spoolQueue.trySend(SpoolJob.Chunk(id, recSeq, recChunkStartFrame, count, file, recSamples))
		recSeq++
	}

	// =====================================================================================
	// Spool: encrypt and register each closed chunk (spoolThread)
	// =====================================================================================

	private suspend fun spoolLoop() {
		val failed = ArrayDeque<SpoolJob.Chunk>()
		fun retryFailed() {
			val n = failed.size
			repeat(n) {
				val j = failed.removeFirst()
				if (!spool(j)) failed.addLast(j)
			}
		}
		for (job in spoolQueue) {
			when (job) {
				is SpoolJob.Drain -> {
					retryFailed()
					job.done.complete(failed.isEmpty())
				}
				is SpoolJob.HighWater -> runCatching {
					store.advanceHighWater(job.localSessionId, job.seqExclusive, job.sampleExclusive)
				}.onFailure { log.event("HIGH_WATER_FAILED", "error" to it.message) }
				is SpoolJob.Chunk -> {
					if (failed.isNotEmpty()) retryFailed()
					var ok = false
					for (attempt in 0 until 3) {
						if (spool(job)) { ok = true; break }
						Thread.sleep(500L shl attempt)
					}
					if (!ok) failed.addLast(job)
				}
			}
		}
	}

	/** Returns true when the chunk is registered (or its file is already gone). */
	private fun spool(job: SpoolJob.Chunk): Boolean {
		if (!job.file.exists()) return true
		val tStartMs = job.firstSampleIndex * 1000L / SAMPLE_RATE_HZ
		val tEndMs = (job.firstSampleIndex + job.sampleCount) * 1000L / SAMPLE_RATE_HZ
		return runCatching {
			store.registerChunk(
				localSessionId = job.localSessionId,
				seq = job.seq,
				firstSampleIndex = job.firstSampleIndex,
				sampleCount = job.sampleCount,
				tStartMs = tStartMs,
				tEndMs = tEndMs,
				sourceFile = job.file
			)
			store.markChunkQueued(job.localSessionId, job.seq)
		}.onSuccess {
			runCatching { job.file.delete() }
			// A late chunk from an earlier night must not overwrite tonight's counters.
			if (job.localSessionId == prefs.getString(KEY_NIGHT_ID, null)) {
				prefs.edit().putLong(KEY_SAMPLES, job.samplesRecordedSoFar).putInt(KEY_SPOOLED, job.seq + 1).apply()
			}
			if (job.localSessionId == _state.value.localSessionId) {
				_state.update { it.copy(chunksSpooled = maxOf(it.chunksSpooled, job.seq + 1)) }
			}
			uploadWake.trySend(Unit)
		}.onFailure {
			// The .part file stays in the work folder; it is retried here, and recovery
			// registers it on the next start if the night is still open.
			log.event("SPOOL_FAILED", "seq" to job.seq, "error" to it.message)
			_state.update { s -> s.copy(lastError = "Saving to the phone failed") }
		}.isSuccess
	}

	/** Waits for every closed chunk to be registered. False if any could not be. */
	private suspend fun drainSpool(): Boolean {
		val done = CompletableDeferred<Boolean>()
		spoolQueue.send(SpoolJob.Drain(done))
		return withTimeoutOrNull(DRAIN_TIMEOUT_MS) { done.await() } ?: false
	}

	// =====================================================================================
	// Night lifecycle
	// =====================================================================================

	/** Starts a night. The Patch must be connected and streaming. */
	suspend fun startNight(): Result<String> = withContext(Dispatchers.IO + NonCancellable) { lifecycle.withLock {
		val e = setupStore.enrollment ?: return@withContext Result.failure(IllegalStateException("Not set up"))
		if (_state.value.recording) return@withContext Result.failure(IllegalStateException("Already recording"))
		val address = targetAddress ?: return@withContext Result.failure(IllegalStateException("No patch paired"))
		closeOutLeftovers(exceptLocalSessionId = null)
		val now = System.currentTimeMillis()
		val session = runCatching {
			store.createSession(
				orgId = e.orgId,
				subjectId = e.subjectId,
				sampleRateHz = SAMPLE_RATE_HZ,
				channels = PatchPacketParser.OUTPUT_CHANNEL_LABELS,
				chunkSeconds = CHUNK_SECONDS,
				startedAtEpochMs = now,
				startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
			)
		}.getOrElse { return@withContext Result.failure(it) }
		val id = session.localSessionId
		prefs.edit()
			.putString(KEY_NIGHT_ID, id)
			.putString(KEY_NIGHT_PATCH, address)
			.putLong(KEY_NIGHT_STARTED, now)
			.putLong(KEY_SAMPLES, 0L)
			.putInt(KEY_SPOOLED, 0)
			.apply()
		series.nightStarted(id, now)
		log.beginNight(id)
		answers.attachBedtime(id, now)
		log.event("NIGHT_START", "localSessionId" to id, "patch" to address, "phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
			"android" to Build.VERSION.SDK_INT)
		withContext(packetThread) {
			beginRecording(id, now, seq = 0, totalFrames = 0L, samples = 0L)
		}
		_state.update {
			it.copy(recording = true, localSessionId = id, nightStartedAtEpochMs = now, samplesRecorded = 0L,
				chunksSpooled = 0, chunksUploaded = 0, outageActive = false, lastError = null)
		}
		if (!RecordingService.start(context)) log.event("FGS_DENIED", "where" to "start")
		uploadWake.trySend(Unit)
		Result.success(id)
	} }

	private fun beginRecording(id: String, startedAt: Long, seq: Int, totalFrames: Long, samples: Long) {
		recLocalId = id
		recStartedAtEpochMs = startedAt
		recSeq = seq
		recTotalFrames = totalFrames
		recFramesInChunk = 0
		recChunkStartFrame = totalFrames
		recLastPacketArrivalMs = 0L
		recSamples = samples
		recActive = true
	}

	/** "I'm awake" during a night: a marker at this moment. Returns its id, for [addAwakeReason]. */
	suspend fun markAwake(): String? = withContext(Dispatchers.IO) {
		val s = _state.value
		val id = s.localSessionId
		if (!s.recording || id == null) return@withContext null
		val now = System.currentTimeMillis()
		answers.awake(id, s.samplesRecorded, now - s.nightStartedAtEpochMs, now).also { uploadWake.trySend(Unit) }
	}

	/** What woke the sleeper, added at the moment it's chosen. */
	suspend fun addAwakeReason(awakeEventId: String, reason: String) = withContext(Dispatchers.IO) {
		val s = _state.value
		val id = s.localSessionId
		if (!s.recording || id == null) return@withContext
		val now = System.currentTimeMillis()
		answers.awakeReason(id, awakeEventId, reason, s.samplesRecorded, now - s.nightStartedAtEpochMs, now)
		uploadWake.trySend(Unit)
	}

	/** Ends the night, hands it to the upload path for finalizing and returns its summary. */
	suspend fun stopNight(): NightSummary? = withContext(Dispatchers.IO + NonCancellable) { lifecycle.withLock {
		if (!_state.value.recording) return@withContext null
		val id = _state.value.localSessionId ?: prefs.getString(KEY_NIGHT_ID, null) ?: return@withContext null
		val finalSeq = withContext(packetThread) {
			recActive = false
			closeChunk()
			recLocalId = null
			recSeq
		}
		val drained = drainSpool() || drainSpool()
		val samples = withContext(packetThread) { recSamples }
		val endedAt = System.currentTimeMillis()
		val recordedMs = samples * 1000L / SAMPLE_RATE_HZ
		if (drained) {
			// Before it can finish uploading: keep the night on the phone for its morning diary.
			answers.holdForDiary(id)
			runCatching {
				store.markSessionStopping(localSessionId = id, finalSeqExclusive = finalSeq, endedAtEpochMs = endedAt)
				CloudSyncScheduler.enqueueFinalization(context, id)
			}.onFailure { log.event("STOP_PERSIST_FAILED", "error" to it.message) }
		} else {
			// A chunk could not be registered. Leave the night open: the next app start
			// registers its working file, and the next night's start closes this one out.
			log.event("STOP_DRAIN_INCOMPLETE", "finalSeq" to finalSeq)
		}
		series.nightEnded(id, endedAt, recordedMs)
		log.event("NIGHT_STOP", "finalSeq" to finalSeq, "recordedMin" to recordedMs / 60_000L)
		log.endNight()
		prefs.edit().remove(KEY_NIGHT_ID).remove(KEY_NIGHT_PATCH).remove(KEY_NIGHT_STARTED).apply()
		outageStartedElapsed.set(0L)
		_state.update { it.copy(recording = false, outageActive = false, samplesRecorded = samples) }
		holdLink(false)
		RecordingService.stop(context)
		uploadWake.trySend(Unit)
		NightSummary(id, recordedMs, recordedMs >= SeriesStore.MIN_VALID_NIGHT_MS, _state.value.uploadedPercent)
	} }

	/**
	 * After the app was killed or the phone restarted mid-night: continue the same night,
	 * leaving a gap for the time it was down. A night older than [MAX_RESUME_AGE_MS] is
	 * closed out instead.
	 */
	suspend fun resumeIfInterrupted() = withContext(Dispatchers.IO + NonCancellable) { lifecycle.withLock { resumeLocked() } }

	/**
	 * Startup: register chunk files left by a process that died mid-night (for every open
	 * night), then resume this phone's night if one was in progress. Runs before any new
	 * night can start, so it never touches a chunk that is still being written.
	 */
	private suspend fun recoverAndResume() = withContext(NonCancellable) { lifecycle.withLock { resumeLocked() } }

	private suspend fun resumeLocked() {
		if (_state.value.recording) return
		// Register chunk files a dead process left behind (for every open night) before
		// anything reads chunk numbers. Nothing is being recorded in this process yet.
		val recovered = runCatching { store.recoverInterruptedWorkingChunks(workRoot = workRoot, packetSizeBytes = PACKET_SIZE) }
			.onSuccess { if (it.isNotEmpty()) log.event("RECOVERED_CHUNKS", "count" to it.size) }
			.onFailure { log.event("RECOVERY_FAILED", "error" to it.message) }
			.isSuccess
		val id = prefs.getString(KEY_NIGHT_ID, null) ?: return
		if (!recovered) return
		val startedAt = prefs.getLong(KEY_NIGHT_STARTED, 0L)
		val now = System.currentTimeMillis()
		val samplesSoFar = prefs.getLong(KEY_SAMPLES, 0L)
		val session = store.loadSession(id)
		if (session == null || session.finalSeqExclusive != null) {
			if (series.nights.value.any { it.localSessionId == id && it.status == SeriesStore.Status.RECORDING }) {
				series.nightEnded(id, session?.endedAtEpochMs ?: now, samplesSoFar * 1000L / SAMPLE_RATE_HZ)
			}
			prefs.edit().remove(KEY_NIGHT_ID).apply()
			return
		}
		val chunks = store.listChunks(id)
		val seq = maxOf(session.nextSeqExclusive ?: 0, (chunks.maxOfOrNull { it.seq } ?: -1) + 1)
		val nextSample = maxOf(
			session.nextSampleIndex ?: 0L,
			chunks.maxOfOrNull { it.firstSampleIndex + it.sampleCount } ?: 0L
		)
		val samples = prefs.getLong(KEY_SAMPLES, 0L)
		log.beginNight(id)
		if (now - startedAt > MAX_RESUME_AGE_MS) {
			runCatching {
				store.markSessionStopping(id, seq, startedAt + nextSample * 1000L / SAMPLE_RATE_HZ)
				CloudSyncScheduler.enqueueFinalization(context, id)
			}
			series.nightEnded(id, startedAt + nextSample * 1000L / SAMPLE_RATE_HZ, samples * 1000L / SAMPLE_RATE_HZ)
			log.event("NIGHT_CLOSED_TOO_OLD", "ageMin" to (now - startedAt) / 60_000L)
			log.endNight()
			prefs.edit().remove(KEY_NIGHT_ID).apply()
			return
		}
		// The first packet after the reconnect re-anchors to the wall clock (see writePacket),
		// so the time the app was down, and the reconnect after it, stays a gap.
		runCatching { store.reopenSession(id) }
		targetAddress = prefs.getString(KEY_NIGHT_PATCH, null) ?: targetAddress
		outageStartedElapsed.set(SystemClock.elapsedRealtime())
		withContext(packetThread) {
			beginRecording(id, if (startedAt > 0L) startedAt else session.startedAtEpochMs, seq, nextSample, samples)
			recResumed = true
		}
		_state.update {
			it.copy(recording = true, localSessionId = id, nightStartedAtEpochMs = startedAt,
				samplesRecorded = samples, chunksSpooled = seq, outageActive = true, outageSinceEpochMs = now,
				patchAddress = targetAddress)
		}
		log.event("NIGHT_RESUMED", "seq" to seq, "nextSample" to nextSample)
		if (!RecordingService.start(context)) log.event("FGS_DENIED", "where" to "resume")
		uploadWake.trySend(Unit)
	}

	/** Any other open night can no longer be resumed: give it its final boundary. */
	private fun closeOutLeftovers(exceptLocalSessionId: String?) {
		val leftovers = runCatching { store.listSessions() }.getOrDefault(emptyList()).filter { s ->
			s.localSessionId != exceptLocalSessionId && s.syncEnabled && s.localPatientId == null &&
				s.finalSeqExclusive == null &&
				s.state in setOf(CloudSessionState.LOCAL_ONLY, CloudSessionState.ACTIVE, CloudSessionState.ERROR)
		}
		for (s in leftovers) {
			runCatching {
				// This app writes the high-water mark with every chunk, so a session with neither
				// a high-water mark nor chunks recorded nothing and closes at zero chunks.
				val chunks = store.listChunks(s.localSessionId)
				val highest = chunks.maxOfOrNull { it.seq }
				val finalSeq = maxOf(s.nextSeqExclusive ?: 0, (highest ?: -1) + 1)
				val lastEndMs = maxOf(
					chunks.maxOfOrNull { it.tEndMs } ?: 0L,
					(s.nextSampleIndex ?: 0L) * 1000L / s.sampleRateHz.coerceAtLeast(1)
				)
				store.markSessionStopping(s.localSessionId, finalSeq, s.startedAtEpochMs + lastEndMs)
				CloudSyncScheduler.enqueueFinalization(context, s.localSessionId)
				if (series.nights.value.any { it.localSessionId == s.localSessionId && it.status == SeriesStore.Status.RECORDING }) {
					// Recorded time is unknown here; the time span is the best available measure.
					series.nightEnded(s.localSessionId, s.startedAtEpochMs + lastEndMs, lastEndMs)
				}
				log.event("LEFTOVER_CLOSED", "localSessionId" to s.localSessionId, "finalSeq" to finalSeq)
			}.onFailure { log.event("LEFTOVER_CLOSE_FAILED", "localSessionId" to s.localSessionId, "error" to it.message) }
		}
	}

	// =====================================================================================
	// Upload loop
	// =====================================================================================

	fun wakeUploads() {
		uploadWake.trySend(Unit)
	}

	private suspend fun uploadLoop() {
		var lastStatsMs = 0L
		while (scope.isActive) {
			val activeId = _state.value.localSessionId.takeIf { _state.value.recording }
			val reachable = isNetworkValidated()
			if (reachable) {
				runCatching { engine.synchronizeRecoverableSessions(activeSessionId = activeId) }
					.onSuccess { outcome ->
						_state.update { it.copy(signInNeeded = outcome.authRequired || outcome.activeAuthRequired) }
					}
					.onFailure { Log.w(TAG, "sync pass failed: ${it.message}") }
			}
			val now = SystemClock.elapsedRealtime()
			if (now - lastStatsMs >= STATS_MS) {
				lastStatsMs = now
				refreshUploadStats(reachable)
			}
			val wait = if (_state.value.recording) UPLOAD_TICK_RECORDING_MS else UPLOAD_TICK_IDLE_MS
			withTimeoutOrNull(wait) { uploadWake.receive() }
		}
	}

	private fun refreshUploadStats(reachable: Boolean) {
		val id = _state.value.localSessionId ?: run {
			_state.update { it.copy(cloudReachable = reachable) }
			return
		}
		val spooled = maxOf(_state.value.chunksSpooled, prefs.getInt(KEY_SPOOLED, 0))
		val pending = runCatching { store.listUnverifiedChunks(id).size }.getOrDefault(0)
		val uploaded = (spooled - pending).coerceAtLeast(0)
		_state.update { it.copy(chunksSpooled = spooled, chunksUploaded = uploaded, cloudReachable = reachable) }
		if (_state.value.recording) {
			log.event("UPLOAD_STATUS", "spooled" to spooled, "uploaded" to uploaded, "network" to reachable,
				"battery" to _state.value.batteryPercent?.toInt())
		}
	}

	private fun isNetworkValidated(): Boolean {
		val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
		val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
		return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
			caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
	}

	fun hasBlePermissions(): Boolean =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
				context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
		} else {
			context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
		}

	/** True when a night is recorded on this phone but this process has not resumed it yet. */
	fun hasInterruptedNight(): Boolean = prefs.getString(KEY_NIGHT_ID, null) != null

	companion object {
		/** A night without patch data for this long raises the full-screen "Patch disconnected" alert. */
		const val PATCH_ALERT_AFTER_MS = 60_000L

		/** True while a recording night has had no patch data for [PATCH_ALERT_AFTER_MS]. */
		fun patchAlert(s: State, nowEpochMs: Long): Boolean =
			s.recording && s.outageActive && s.outageSinceEpochMs > 0L &&
				nowEpochMs - s.outageSinceEpochMs >= PATCH_ALERT_AFTER_MS

		private const val TAG = "NightRecorder"
		private const val PREFS = "lucidsleep_night"
		private const val KEY_NIGHT_ID = "night_local_session_id"
		private const val KEY_NIGHT_PATCH = "night_patch_address"
		private const val KEY_NIGHT_STARTED = "night_started_at"
		private const val KEY_SAMPLES = "night_samples"
		private const val KEY_SPOOLED = "night_chunks_spooled"

		const val SAMPLE_RATE_HZ = 500
		private const val CHUNK_SECONDS = 5
		private const val FRAMES_PER_CHUNK = SAMPLE_RATE_HZ * CHUNK_SECONDS
		private const val PACKET_SIZE = 36
		private const val SYNC: Byte = 0xFF.toByte()

		private const val LINK_TICK_MS = 500L
		private const val RETRY_MS = 5_000L
		private const val SETTLE_MS = 15_000L
		private const val SETTLE_EVERY = 3
		private const val SCAN_EVERY = 4
		private const val TARGET_SCAN_MS = 4_000L
		private const val CONNECT_TIMEOUT_MS = 30_000L
		private const val FIRST_DATA_MS = 15_000L
		private const val NO_DATA_MS = 10_000L

		private const val PAIR_SCAN_MS = 8_000L
		private const val PAIR_CONNECT_TIMEOUT_MS = 20_000L
		private const val PAIR_SERIAL_TIMEOUT_MS = 5_000L

		private const val PUBLISH_MS = 500L
		private const val DRAIN_TIMEOUT_MS = 30_000L
		private const val STATS_MS = 30_000L
		private const val UPLOAD_TICK_RECORDING_MS = 5_000L
		private const val UPLOAD_TICK_IDLE_MS = 60_000L
		private const val MAX_RESUME_AGE_MS = 14L * 60L * 60L * 1000L
	}
}