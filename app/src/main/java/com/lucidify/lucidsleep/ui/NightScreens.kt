package com.lucidify.lucidsleep.ui

import android.app.Activity
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import java.text.SimpleDateFormat
import java.util.Locale
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lucidify.lucidsleep.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.lucidify.lucidsleep.AppGraph
import com.lucidify.lucidsleep.core.patch.PatchContactMonitor
import com.lucidify.lucidsleep.recording.NightRecorder
import com.lucidify.lucidsleep.series.SeriesStore
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.saveable.rememberSaveable
import java.io.File
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------------------------------
// 7. Home
// ---------------------------------------------------------------------------------------

@Composable
fun HomeScreen(graph: AppGraph, onReady: () -> Unit, onNights: () -> Unit, onHelp: () -> Unit) {
	val setup by graph.setup.setup.collectAsState()
	val nights by graph.series.nights.collectAsState()
	val required = setup.enrollment?.nightsRequired ?: 5
	val progress = remember(nights, required) { graph.series.progress(required) }
	val label = setup.enrollment?.label
	val rec by graph.recorder.state.collectAsState()
	val lastNight = nights.lastOrNull { it.status != SeriesStore.Status.RECORDING }

	// Nights and Help are in the tab bar under this screen.
	ScreenFrame(
		title = if (progress.complete) "All nights done" else "Night ${progress.nextNightNumber} of ${progress.required}",
		subtitle = label,
		bottom = {
			if (!progress.complete) BigButton("Get ready for tonight", onClick = onReady)
		}
	) {
		SeriesBar(progress)
		Note("${progress.counted} of ${progress.required} nights counted")
		lastNight?.let { LastNightCard(it, rec, onNights) }
		if (progress.complete) {
			Callout("You've recorded all ${progress.required} nights. Thank you! Please pack the patch and charger in the return mailer and send the kit back to Lucidify.", Palette.Good)
		} else {
			Note("Each evening: put your phone on its charger, check the patch, and start recording before you go to sleep.")
			if (progress.shortNights > 0) {
				Note("${progress.shortNights} night${if (progress.shortNights == 1) " was" else "s were"} too short to count, so we've added ${if (progress.shortNights == 1) "one more night" else "more nights"}.")
			}
		}
	}
}

@Composable
private fun SeriesBar(p: SeriesStore.Progress) {
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		repeat(p.required) { i ->
			val color = if (i < p.counted) Palette.Good else Palette.NavyLine
			Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(color))
		}
	}
	Note("${p.counted} of ${p.required} nights recorded")
}

// ---------------------------------------------------------------------------------------
// 8. Ready for tonight
// ---------------------------------------------------------------------------------------

private fun phoneCharging(context: Context): Boolean {
	val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
	return (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
}

private fun internetReady(context: Context): Boolean {
	val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
	val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
	return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private const val MIN_PATCH_BATTERY_PERCENT = 50f
private const val STUCK_AFTER_SECONDS = 30
private const val REOFFER_SECONDS = 120

@Composable
fun ReadyCheckScreen(graph: AppGraph, onNext: () -> Unit, onBack: () -> Unit, onHelp: () -> Unit) {
	val context = LocalContext.current
	val rec by graph.recorder.state.collectAsState()
	var tick by remember { mutableIntStateOf(0) }
	LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }

	val charging = remember(tick) { phoneCharging(context) }
	val online = remember(tick) { internetReady(context) }
	val btOn = remember(tick) { graph.ble.isBluetoothReady() }
	val storage = remember(tick) { freeStorageBytes(context) >= MIN_FREE_BYTES }
	val perms = remember(tick) { hasPermissions(context, blePermissions()) }
	val patchUp = rec.link == NightRecorder.Link.STREAMING
	// Seconds the patch has been out of reach on this screen; after 30, say what usually causes it.
	val downSince = remember(patchUp) { tick }
	val stuck = !patchUp && tick - downSince >= STUCK_AFTER_SECONDS
	// Offer to switch when the phone hears another patch; "Keep looking" waits before offering again.
	var offerDismissedAt by remember { mutableIntStateOf(-1) }
	val offerSwitch = !patchUp && rec.otherPatchNearby && !rec.switchingPatch &&
		(offerDismissedAt < 0 || tick - offerDismissedAt >= REOFFER_SECONDS)
	val battery = rec.batteryPercent
	val batteryOk = battery != null && battery >= MIN_PATCH_BATTERY_PERCENT
	val allGood = charging && btOn && storage && perms && patchUp && batteryOk

	ScreenFrame(
		title = "Ready for tonight",
		subtitle = if (allGood) "Everything is ready." else "Fix anything marked below.",
		onHelp = onHelp,
		bottom = {
			BigButton("Next: put on the patch", enabled = allGood, onClick = onNext)
			QuietButton("Back", onClick = onBack)
		}
	) {
		CheckRow("Phone is on its charger", if (charging) CheckState.OK else CheckState.PROBLEM,
			if (charging) null else "Plug your phone in. A full night of recording drains the battery.")
		CheckRow("Bluetooth is on", if (btOn && perms) CheckState.OK else CheckState.PROBLEM)
		CheckRow(
			"Patch is connected",
			when {
				patchUp -> CheckState.OK
				rec.link == NightRecorder.Link.OFF -> CheckState.PROBLEM
				else -> CheckState.PENDING
			},
			when {
				rec.link == NightRecorder.Link.STREAMING -> null
				stuck -> "Can't reach the patch. Is it the one you set up? To use a different patch, go to Help. " +
					"If another app is connected to it, close that app, then turn the patch off and on again."
				rec.link == NightRecorder.Link.OFF -> "Press the round button on your patch to turn it on. We'll keep trying."
				else -> "Connecting…"
			}
		)
		// The phone hears a patch, but not the paired one: most likely the patches were swapped.
		if (rec.switchingPatch) {
			Callout("Switching to the patch near your phone…", Palette.InkMuted)
		}
		// A dialog dims the screen, so these two choices are the only things to tap.
		if (offerSwitch) {
			// Tapping the dimmed screen does nothing; the phone's Back button counts as "Keep looking".
			Dialog(
				onDismissRequest = { offerDismissedAt = tick },
				properties = DialogProperties(dismissOnClickOutside = false)
			) {
				Column(
					Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.NavyRaised).padding(24.dp),
					verticalArrangement = Arrangement.spacedBy(14.dp)
				) {
					Text("A different patch is nearby", style = MaterialTheme.typography.headlineMedium)
					Note(
						"Your phone can hear a patch, but it isn't the one this app is paired with. " +
							"If you're using a different patch tonight, switch to it.",
						Palette.Ink
					)
					BigButton("Switch to the detected patch") {
						graph.recorder.switchToNearbyPatch(graph.setup.enrollment?.patchSerial)
					}
					QuietButton("Keep looking for my patch") { offerDismissedAt = tick }
				}
			}
		}
		CheckRow(
			"Patch battery",
			when {
				battery == null -> CheckState.PENDING
				batteryOk -> CheckState.OK
				else -> CheckState.PROBLEM
			},
			when {
				battery == null -> "Shown once the patch is connected."
				batteryOk -> "${battery.toInt()}%"
				else -> "${battery.toInt()}%. Charge the patch before tonight; a flashing yellow light also means it needs charging."
			}
		)
		CheckRow("Space on your phone", if (storage) CheckState.OK else CheckState.PROBLEM,
			if (storage) null else "Free up at least 1 GB.")
		CheckRow("Wi-Fi", if (online) CheckState.OK else CheckState.PENDING,
			if (online) null else "Not needed to record. Your night uploads when Wi-Fi is back.")
	}
}

// ---------------------------------------------------------------------------------------
// 9. Put on the patch / contact check
// ---------------------------------------------------------------------------------------

@Composable
fun ContactCheckScreen(graph: AppGraph, onStarted: () -> Unit, onBack: () -> Unit, onHelp: () -> Unit, onGuide: () -> Unit) {
	val scope = rememberCoroutineScope()
	val rec by graph.recorder.state.collectAsState()
	val shownAt = remember { System.currentTimeMillis() }
	var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
	var starting by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }
	LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }

	val c = rec.contact
	val streaming = rec.link == NightRecorder.Link.STREAMING
	val good = c == PatchContactMonitor.State.GOOD && streaming
	val anywayInSeconds = ((ANYWAY_AFTER_MS - (now - shownAt)) / 1000L).coerceAtLeast(0L)
	val allowAnyway = anywayInSeconds == 0L && streaming
	// The link can drop here too (the patch goes quiet and won't take a new connection).
	val downSince = remember(streaming) { System.currentTimeMillis() }
	val stuck = !streaming && now - downSince >= STUCK_AFTER_SECONDS * 1_000L
	val center = electrodeState(c, isAfz = true)
	val side = electrodeState(c, isAfz = false)
	val reference = referenceState(c)
	fun shown(e: Electrode) = if (streaming) e else Electrode.CHECKING

	fun start(poorContact: Boolean) {
		starting = true
		error = null
		scope.launch {
			if (poorContact) graph.log.event("START_WITH_POOR_CONTACT", "contact" to c.name)
			graph.recorder.startNight()
				.onSuccess { onStarted() }
				.onFailure { error = "Recording didn't start: ${it.message}. Try again." }
			starting = false
		}
	}

	ScreenFrame(
		title = "Put on the patch",
		onHelp = onHelp,
		bottom = {
			BigButton(if (starting) "Starting…" else "Start recording", enabled = good && !starting) { start(false) }
			if (!good && allowAnyway) QuietButton("Start anyway, contact isn't good", enabled = !starting) { start(true) }
			QuietButton("Back", onClick = onBack)
		}
	) {
		Note("1. Clean your forehead with the alcohol wipe from tonight's pouch and let it dry.")
		Note("2. Brush your hair aside. Peel the cover off the sticker.")
		Note("3. Place the patch centered on your forehead, below the hairline and above the eyebrows.")
		Note("4. Press firmly all around the edges for 10 seconds and smooth out any wrinkles.")
		TextButton(onClick = onGuide) { Text("Show me how", color = Palette.Amber, fontSize = 18.sp) }
		Spacer(Modifier.height(4.dp))
		Note("The patch has three sensors on your forehead. Each one turns green when it touches your skin well.")
		ForeheadDiagram(center = shown(center), right = shown(side), left = shown(reference))
		ElectrodeTile("Middle sensor", shown(center), rec.impAfzOhms)
		ElectrodeTile("Right sensor", shown(side), rec.impFp2Ohms)
		// The left sensor is the reference: it has no reading of its own (judged from the patch's overall check).
		ElectrodeTile("Left sensor", shown(reference), Float.NaN)
		when {
			stuck -> Callout("Can't reach the patch. Hold its round button to turn it off, wait a few seconds, then press it again to turn it on. The app reconnects by itself.")
			!streaming -> Callout("Waiting for the patch to connect…", Palette.InkMuted)
			c == PatchContactMonitor.State.CHECKING -> Callout("Checking contact… keep still for a few seconds.", Palette.InkMuted)
			c == PatchContactMonitor.State.REFERENCE_OR_CONTACT_BAD || c == PatchContactMonitor.State.BOTH_BAD ->
				Callout("The patch isn't touching your skin well. Press firmly all over the sticker again. If the patch's red light keeps flashing, use a new sticker.")
			!good -> Callout("Press firmly on the patch over each sensor shown in yellow, for about 10 seconds.")
			else -> Callout("Good contact. You're ready to start.", Palette.Good)
		}
		if (streaming && rec.impAfzOhms.isFinite()) {
			// Prototype: the measured numbers, so contact problems can be reported and tuned.
			Note(
				"Details: middle ${ohmsText(rec.impAfzOhms)}, ${mvText(rec.afzDcMv)} · " +
					"right ${ohmsText(rec.impFp2Ohms)}, ${mvText(rec.fp2DcMv)}"
			)
		}
		if (!good && streaming && anywayInSeconds > 0L) {
			Note("Can't get both sensors green? You can start anyway in $anywayInSeconds s, but the night may not count.")
		}
		error?.let { Callout(it, Palette.Bad) }
	}
}

private fun ohmsText(ohms: Float): String = when {
	!ohms.isFinite() -> "–"
	ohms < 1000f -> "under 1 kΩ"
	else -> "${(ohms / 1000f).toInt()} kΩ"
}

private fun mvText(mv: Float): String = if (mv.isFinite()) "${mv.toInt()} mV" else "–"

/** How long the contact check waits before offering "Start anyway". */
private const val ANYWAY_AFTER_MS = 90_000L

private enum class Electrode { CHECKING, GOOD, POOR }

private fun electrodeColor(state: Electrode): Color = when (state) {
	Electrode.CHECKING -> Palette.InkMuted
	Electrode.GOOD -> Palette.Good
	Electrode.POOR -> Palette.Warn
}

/** patch_front's width / height. */
private const val PATCH_ASPECT = 530.1f / 160.8f

/**
 * The patch seen from the front, with a ring on each of its three sensors colored by its
 * contact: the middle sensor under the center button, the right sensor in the wing on the
 * wearer's right (the viewer's left), and the left sensor, the reference, in the other wing.
 */
@Composable
private fun ForeheadDiagram(center: Electrode, right: Electrode, left: Electrode) {
	val centerColor = electrodeColor(center)
	val rightColor = electrodeColor(right)
	val leftColor = electrodeColor(left)
	Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
		Box(Modifier.fillMaxWidth(0.92f).aspectRatio(PATCH_ASPECT)) {
			Image(
				painter = painterResource(R.drawable.patch_front),
				contentDescription = "The patch seen from the front, with its two sensors marked",
				modifier = Modifier.fillMaxSize()
			)
			Canvas(Modifier.fillMaxSize()) {
				val radius = size.width * 0.06f
				fun ring(color: Color, fx: Float, fy: Float) {
					val at = Offset(size.width * fx, size.height * fy)
					drawCircle(color.copy(alpha = 0.35f), radius = radius, center = at)
					drawCircle(color, radius = radius, center = at, style = Stroke(width = 4.dp.toPx()))
				}
				ring(centerColor, 0.507f, 0.463f)
				ring(rightColor, 0.082f, 0.721f)
				ring(leftColor, 0.906f, 0.665f)
			}
		}
		Row(Modifier.fillMaxWidth(0.92f)) {
			Text("your right", color = Palette.InkMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
			Text("your left", color = Palette.InkMuted, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
		}
	}
}

private fun electrodeState(s: PatchContactMonitor.State, isAfz: Boolean): Electrode = when (s) {
	PatchContactMonitor.State.CHECKING -> Electrode.CHECKING
	PatchContactMonitor.State.GOOD -> Electrode.GOOD
	PatchContactMonitor.State.BOTH_BAD, PatchContactMonitor.State.REFERENCE_OR_CONTACT_BAD -> Electrode.POOR
	PatchContactMonitor.State.AFZ_BAD -> if (isAfz) Electrode.POOR else Electrode.GOOD
	PatchContactMonitor.State.FP2_BAD -> if (isAfz) Electrode.GOOD else Electrode.POOR
}

/**
 * The reference sensor has no impedance reading of its own. The patch's overall check (DC
 * offset) flags it, and it is in doubt when both measured sensors are bad together.
 */
private fun referenceState(s: PatchContactMonitor.State): Electrode = when (s) {
	PatchContactMonitor.State.CHECKING -> Electrode.CHECKING
	PatchContactMonitor.State.REFERENCE_OR_CONTACT_BAD, PatchContactMonitor.State.BOTH_BAD -> Electrode.POOR
	PatchContactMonitor.State.GOOD, PatchContactMonitor.State.AFZ_BAD, PatchContactMonitor.State.FP2_BAD -> Electrode.GOOD
}

@Composable
private fun ElectrodeTile(label: String, state: Electrode, ohms: Float) {
	val color = electrodeColor(state)
	val word = when (state) {
		Electrode.CHECKING -> "Checking…"
		Electrode.GOOD -> "Good"
		Electrode.POOR -> "Not touching well"
	}
	Row(
		Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.16f)).padding(16.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
		Column(horizontalAlignment = Alignment.End) {
			Text(word, color = color, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
			if (ohms.isFinite() && state != Electrode.CHECKING) {
				Text(ohmsText(ohms), color = Palette.InkMuted, fontSize = 14.sp)
			}
		}
	}
}

// ---------------------------------------------------------------------------------------
// 10. Recording
// ---------------------------------------------------------------------------------------

@Composable
fun RecordingScreen(graph: AppGraph) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val rec by graph.recorder.state.collectAsState()
	var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
	var confirmStop by remember { mutableStateOf(false) }
	var stopping by remember { mutableStateOf(false) }
	// A tap brightens the screen for 20 seconds so the time and Stop are easy to read.
	var awakeTap by remember { mutableIntStateOf(0) }
	var awake by remember { mutableStateOf(false) }
	// The patch has been silent for a minute: a full-screen red alert until it's back (or hidden).
	var alertHiddenUntil by remember { mutableLongStateOf(0L) }
	val nights by graph.series.nights.collectAsState()
	val nightNumber = nights.indexOfFirst { it.localSessionId == rec.localSessionId }.let { if (it >= 0) it + 1 else nights.size }
	// "I'm awake": 0 = the button, 1 = what woke you, 2 = noted. Goes back to the button by itself.
	var awakeMode by remember { mutableIntStateOf(0) }
	var awakeAt by remember { mutableLongStateOf(0L) }
	var awakeReason by remember { mutableStateOf<String?>(null) }
	var awakeEvent by remember { mutableStateOf<Deferred<String?>?>(null) }
	LaunchedEffect(awakeMode) {
		if (awakeMode != 0) {
			delay(AWAKE_PANEL_MS)
			awakeMode = 0
		}
	}
	val alert = NightRecorder.patchAlert(rec, now) && now >= alertHiddenUntil
	val bright = awake || confirmStop || alert
	LaunchedEffect(Unit) { while (true) { delay(5_000); now = System.currentTimeMillis() } }
	DisposableEffect(alert) {
		val window = (context as? Activity)?.window
		if (alert) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		onDispose { if (alert) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
	}
	LaunchedEffect(awakeTap) {
		if (awakeTap > 0) {
			awake = true
			now = System.currentTimeMillis()
			delay(20_000)
			awake = false
		}
	}

	// Dim the screen while this page is open, unless just tapped; the phone can also be locked.
	DisposableEffect(bright) {
		val window = (context as? Activity)?.window
		val previous = window?.attributes?.screenBrightness
		window?.let {
			it.attributes = it.attributes.apply {
				screenBrightness = if (bright) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else 0.02f
			}
			it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		}
		onDispose {
			window?.let {
				it.attributes = it.attributes.apply {
					screenBrightness = previous ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
				}
			}
		}
	}

	// Text is faint while dim (it is a dark room) and full strength after a tap.
	val ink = if (bright) Palette.Ink else Palette.InkMuted.copy(alpha = 0.8f)
	val faint = if (bright) Palette.InkMuted else Palette.InkMuted.copy(alpha = 0.55f)

	Column(
		Modifier
			.fillMaxSize()
			.background(Palette.Night)
			.pointerInput(Unit) { detectTapGestures { awakeTap++ } }
			.statusBarsPadding()
			.navigationBarsPadding()
			.padding(28.dp),
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		Spacer(Modifier.weight(1f))
		Text("Recording · night $nightNumber", color = faint, fontSize = 22.sp)
		Spacer(Modifier.height(10.dp))
		Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), color = ink,
			fontSize = 64.sp, fontWeight = FontWeight.Light)
		Spacer(Modifier.height(18.dp))
		val patchLine = when {
			rec.outageActive -> "Patch reconnecting…"
			rec.link == NightRecorder.Link.STREAMING -> "Patch connected"
			else -> "Patch connecting…"
		}
		Text(patchLine, color = if (rec.outageActive) Palette.Warn else faint, fontSize = 17.sp)
		Text(
			"Started at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(rec.nightStartedAtEpochMs))} · " +
				"${formatDuration(rec.recordedMs)} recorded",
			color = faint, fontSize = 15.sp
		)
		Text(
			if (rec.cloudReachable) "Saved on phone • ${rec.uploadedPercent}% uploaded" else "Saved on phone • uploads when Wi-Fi is back",
			color = faint, fontSize = 15.sp
		)
		rec.lastError?.let { Text(it, color = Palette.Bad.copy(alpha = 0.7f), fontSize = 15.sp) }
		Spacer(Modifier.height(32.dp))
		when (awakeMode) {
			0 -> {
				OutlinedButton(
					onClick = {
						awakeTap++
						awakeAt = System.currentTimeMillis()
						awakeReason = null
						awakeEvent = scope.async { graph.recorder.markAwake() }
						awakeMode = 1
					},
					modifier = Modifier.fillMaxWidth().height(72.dp),
					shape = RoundedCornerShape(36.dp),
					border = BorderStroke(2.dp, Palette.Amber.copy(alpha = if (bright) 1f else 0.6f))
				) {
					Text("I'm awake", color = Palette.Amber.copy(alpha = if (bright) 1f else 0.8f),
						fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
				}
				Spacer(Modifier.height(8.dp))
				Text("Tap it whenever you wake in the night. It helps us read your night.",
					color = faint, fontSize = 15.sp, textAlign = TextAlign.Center)
			}
			1 -> {
				Text("What woke you? (optional)", color = ink, fontSize = 19.sp)
				Spacer(Modifier.height(10.dp))
				WAKE_REASONS.chunked(2).forEach { row ->
					Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						row.forEach { reason ->
							OutlinedButton(
								onClick = {
									awakeTap++
									awakeReason = reason
									awakeMode = 2
									val event = awakeEvent
									scope.launch { event?.await()?.let { graph.recorder.addAwakeReason(it, reason) } }
								},
								modifier = Modifier.weight(1f).heightIn(min = 56.dp),
								shape = RoundedCornerShape(14.dp),
								border = BorderStroke(1.dp, faint)
							) { Text(reason, color = ink, fontSize = 16.sp, textAlign = TextAlign.Center) }
						}
					}
					Spacer(Modifier.height(8.dp))
				}
				TextButton(onClick = { awakeMode = 2 }) { Text("Skip", color = faint, fontSize = 17.sp) }
			}
			else -> {
				Text(
					"Noted at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(awakeAt))}" +
						(awakeReason?.let { " · $it" } ?: ""),
					color = ink, fontSize = 19.sp, textAlign = TextAlign.Center
				)
				Text("Recording continues. Rest when you can.", color = faint, fontSize = 16.sp)
				TextButton(onClick = { awakeMode = 0 }) { Text("Done", color = Palette.Amber, fontSize = 18.sp) }
			}
		}
		Spacer(Modifier.weight(1f))
		Text(
			if (bright) "You can lock your phone. Leave it on the charger." else "Tap the screen to brighten it.",
			color = faint, fontSize = 15.sp
		)
		Spacer(Modifier.height(16.dp))
		OutlinedButton(
			onClick = { confirmStop = true },
			enabled = !stopping,
			modifier = Modifier.fillMaxWidth().height(56.dp),
			border = BorderStroke(2.dp, Palette.Amber.copy(alpha = if (bright) 1f else 0.6f))
		) {
			Text(
				if (stopping) "Stopping…" else "I'm up — stop recording",
				color = Palette.Amber.copy(alpha = if (bright) 1f else 0.75f), fontSize = 19.sp, fontWeight = FontWeight.SemiBold
			)
		}
	}

	if (alert) {
		PatchLostAlert(
			silentMinutes = ((now - rec.outageSinceEpochMs) / 60_000L).coerceAtLeast(1L),
			batteryPercent = rec.batteryPercent,
			onHide = { alertHiddenUntil = System.currentTimeMillis() + ALERT_HIDE_MS },
			onStop = { confirmStop = true }
		)
	}

	if (confirmStop) {
		AlertDialog(
			onDismissRequest = { confirmStop = false },
			title = { Text("Stop recording?") },
			text = { Text("Only stop when you're up for the day.") },
			confirmButton = {
				TextButton(onClick = {
					confirmStop = false
					stopping = true
					scope.launch { graph.recorder.stopNight(); stopping = false }
				}) { Text("Yes, stop", color = Palette.Amber) }
			},
			dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep recording") } }
		)
	}
}

private const val ALERT_HIDE_MS = 15 * 60_000L
private const val AWAKE_PANEL_MS = 90_000L
private val WAKE_REASONS = listOf("Bathroom", "Hot flash or sweats", "Pain", "Partner, child or noise", "Mind racing", "Just awake")

/** Full screen, flashing red: the patch stopped sending during the night and needs a hand. */
@Composable
private fun PatchLostAlert(silentMinutes: Long, batteryPercent: Float?, onHide: () -> Unit, onStop: () -> Unit) {
	val flash by rememberInfiniteTransition(label = "patchAlert").animateColor(
		initialValue = Color(0xFF6E0F12),
		targetValue = Color(0xFFD32F2F),
		animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
		label = "patchAlertColor"
	)
	val white = Color.White
	Column(
		Modifier
			.fillMaxSize()
			.background(flash)
			// Swallow taps so nothing behind the alert is pressed by accident.
			.pointerInput(Unit) { detectTapGestures { } }
			.statusBarsPadding()
			.navigationBarsPadding()
			.padding(28.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp)
	) {
		Spacer(Modifier.weight(1f))
		Text("Patch disconnected", color = white, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
		Text(
			"Your night isn't being recorded. No signal for $silentMinutes min.",
			color = white, fontSize = 21.sp, lineHeight = 29.sp
		)
		if ((batteryPercent ?: 100f) <= LOW_PATCH_BATTERY_PERCENT) {
			Text("The patch battery was low. It may have run out.", color = white, fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
		}
		Text(
			"Look at the patch's white light.\n" +
				"• Light off: press the round button to turn the patch on. If it won't turn on, charge it, then turn it on.\n" +
				"• Light on: hold the button to turn the patch off, wait a few seconds, then turn it on again.",
			color = white, fontSize = 19.sp, lineHeight = 28.sp
		)
		Text("Recording picks up again by itself when the patch reconnects.", color = white.copy(alpha = 0.9f), fontSize = 17.sp, lineHeight = 24.sp)
		Spacer(Modifier.weight(1f))
		OutlinedButton(
			onClick = onHide,
			modifier = Modifier.fillMaxWidth().height(56.dp),
			border = BorderStroke(2.dp, white)
		) { Text("Hide for 15 minutes", color = white, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
		OutlinedButton(
			onClick = onStop,
			modifier = Modifier.fillMaxWidth().height(56.dp),
			border = BorderStroke(2.dp, white.copy(alpha = 0.7f))
		) { Text("I'm up — stop recording", color = white, fontSize = 19.sp) }
	}
}

private const val LOW_PATCH_BATTERY_PERCENT = 15f

// ---------------------------------------------------------------------------------------
// 11. Good morning
// ---------------------------------------------------------------------------------------

@Composable
fun GoodMorningScreen(graph: AppGraph, night: SeriesStore.Night) {
	val rec by graph.recorder.state.collectAsState()
	val setup by graph.setup.setup.collectAsState()
	val nights by graph.series.nights.collectAsState()
	val required = setup.enrollment?.nightsRequired ?: 5
	val progress = remember(nights, required) { graph.series.progress(required) }
	val counted = night.status == SeriesStore.Status.COUNTED
	val thisNight = rec.localSessionId == night.localSessionId
	val scope = rememberCoroutineScope()
	var diary by rememberSaveable { mutableStateOf(false) }
	LaunchedEffect(Unit) { graph.recorder.wakeUploads() }

	if (diary) {
		MorningDiaryScreen(
			graph, night,
			onSaved = { graph.series.markReviewed(night.localSessionId) },
			onBack = { diary = false }
		)
		return
	}

	val nightNumber = nights.indexOfFirst { it.localSessionId == night.localSessionId } + 1
	val stoppedAt = night.endedAtEpochMs?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
	ScreenFrame(
		title = "Good morning",
		subtitle = listOfNotNull(if (nightNumber > 0) "Night $nightNumber" else null, stoppedAt?.let { "stopped at $it" })
			.joinToString(" · ").ifBlank { null },
		bottom = {
			BigButton("Answer 4 quick questions") { diary = true }
			QuietButton("Skip today") {
				scope.launch(Dispatchers.IO) { graph.recorder.answers.skipDiary(night.localSessionId) }
				graph.series.markReviewed(night.localSessionId)
			}
		}
	) {
		Text("You recorded ${formatDuration(night.recordedMs)}.", style = MaterialTheme.typography.titleLarge)
		if (counted) {
			Callout("This night counts. ${progress.counted} of ${progress.required} done.", Palette.Good)
		} else {
			Callout(
				if (night.partial) {
					"The patch was disconnected for ${formatDuration(night.gapMs)}, so less than 4 hours was recorded. " +
						"This night won't count; we've added one more night."
				} else {
					"This night was shorter than 4 hours, so it won't count. We've added one more night."
				},
				Palette.Warn
			)
		}
		if (thisNight) {
			if (rec.uploadedPercent >= 100) {
				Note("All of last night has reached Lucidify.")
			} else {
				Note("Uploading last night: ${rec.uploadedPercent}%. Keep the app open on Wi-Fi for a few minutes.")
				LinearProgressIndicator(
					progress = { rec.uploadedPercent / 100f },
					modifier = Modifier.fillMaxWidth(),
					color = Palette.Amber,
					trackColor = Palette.NavyLine
				)
			}
		}
		Note("Now take off the patch, throw away the sticker, and put the patch on its charger.")
	}
}

// ---------------------------------------------------------------------------------------
// 13. My nights
// ---------------------------------------------------------------------------------------

@Composable
fun MyNightsScreen(graph: AppGraph, onBack: () -> Unit) {
	val nights by graph.series.nights.collectAsState()
	val setup by graph.setup.setup.collectAsState()
	val required = setup.enrollment?.nightsRequired ?: 5
	val progress = remember(nights, required) { graph.series.progress(required) }
	val day = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
	val clock = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
	val makeUps = progress.shortNights
	// Back is the phone's back button; Home and Help are in the tab bar.
	ScreenFrame(
		title = "My nights",
		subtitle = "${progress.counted} of ${progress.required} counted" +
			if (makeUps > 0) " · $makeUps make-up night${if (makeUps == 1) "" else "s"} added" else ""
	) {
		nights.forEachIndexed { i, n ->
			val (word, color) = when {
				n.status == SeriesStore.Status.RECORDING -> "Recording now" to Palette.InkMuted
				n.status == SeriesStore.Status.COUNTED -> "Counted · ${formatDuration(n.recordedMs)}" to Palette.Good
				n.partial -> "Partial · didn't count" to Palette.Warn
				else -> "Too short · didn't count" to Palette.Warn
			}
			val from = n.firstGapAtEpochMs?.let { " from ${clock.format(Date(it))}" } ?: ""
			val detail = when {
				n.status == SeriesStore.Status.SHORT && n.partial ->
					"The patch was disconnected for ${formatDuration(n.gapMs)}$from, so only ${formatDuration(n.recordedMs)} was recorded. " +
						"We added a make-up night, so nothing else changes for you."
				n.status == SeriesStore.Status.SHORT ->
					"${formatDuration(n.recordedMs)} recorded; a night needs 4 hours. We added a make-up night, so nothing else changes for you."
				n.status == SeriesStore.Status.COUNTED && n.gapMs >= NOTE_GAP_MS ->
					"The patch was disconnected for ${formatDuration(n.gapMs)}$from."
				else -> null
			}
			NightRow("Night ${i + 1} · ${day.format(Date(n.startedAtEpochMs))}", word, color, detail)
		}
		// The nights still to come; ones beyond the planned number are make-up nights.
		if (!progress.complete) {
			val toCome = progress.required - progress.counted - (if (progress.recording) 1 else 0)
			repeat(toCome.coerceAtLeast(0)) { k ->
				val number = nights.size + k + 1
				val tonight = k == 0 && !progress.recording
				val tag = when {
					number > progress.required -> " · make-up"
					tonight -> " · tonight"
					else -> ""
				}
				NightRow(
					"Night $number$tag",
					if (tonight) "Ready check when you head to bed" else "Still to come",
					Palette.InkMuted,
					null
				)
			}
		}
		Note(
			if (progress.complete) "All nights are done. Home says how to return your kit."
			else "After your last counted night, the app tells you how to return your kit."
		)
	}
}

// ---------------------------------------------------------------------------------------
// 15. Help and Send diagnostics
// ---------------------------------------------------------------------------------------

@Composable
fun HelpScreen(graph: AppGraph, onBack: () -> Unit, onGuide: () -> Unit) {
	val context = LocalContext.current
	var ticket by remember { mutableStateOf<String?>(null) }
	var confirmSwitch by remember { mutableStateOf(false) }
	ScreenFrame(
		title = "Help",
		bottom = {
			BigButton("Send diagnostics") { ticket = sendDiagnostics(context, graph) }
			QuietButton("Use a different patch") { confirmSwitch = true }
		}
	) {
		if (confirmSwitch) {
			AlertDialog(
				onDismissRequest = { confirmSwitch = false },
				title = { Text("Use a different patch?") },
				text = { Text("The app forgets your current patch and finds the one near your phone. Turn the new patch on and keep any others away from the phone.") },
				confirmButton = {
					TextButton(onClick = {
						confirmSwitch = false
						// Back to "Find your patch" in setup; the recorder refuses during a night.
						if (graph.recorder.forgetPatch()) onBack()
					}) { Text("Find the new patch", color = Palette.Amber) }
				},
				dismissButton = { TextButton(onClick = { confirmSwitch = false }) { Text("Cancel") } }
			)
		}
		QuietButton("Watch how to put on the patch", onClick = onGuide)
		HelpItem("The patch won't connect", "Make sure its white light is on and it's near your phone. Turn Bluetooth off and on. If it still won't connect, hold the round button to turn the patch off, wait 30 seconds, and turn it on again.")
		HelpItem("Red light flashing on the patch", "The sticker isn't touching your skin well. Press the edges firmly. If the red light keeps flashing, replace the sticker with a new one.")
		HelpItem("Yellow light flashing", "The patch battery is low. Charge it before your next night.")
		HelpItem("The night didn't count", "A night needs at least 4 hours of recording. If it was shorter, or the patch was disconnected for part of it, we add one more night automatically, so there's nothing you need to do. My nights shows what happened.")
		HelpItem("Returning your kit", "After your last counted night, Home tells you you're done. Put the patch, its charger and any unused pouches in the return mailer that came with your kit, seal it and send it back. Used stickers and wipes go in the trash.")
		HelpItem("Still stuck?", "Tap Send diagnostics below. It shares the app's logs (no sleep recording and nothing personal) and gives you a ticket code, so support can see what happened on your phone.")
		ticket?.let { Callout("Your ticket code is $it. Tell Lucidify this code when you contact us.", Palette.Good) }
		ContactSupport(ticket)
	}
}

@Composable
private fun ContactSupport(ticket: String?) {
	if (!SupportInfo.available) return
	val context = LocalContext.current
	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.NavyRaised).padding(16.dp)) {
		Text("Contact support", style = MaterialTheme.typography.titleLarge, fontSize = 19.sp)
		if (SupportInfo.PHONE.isNotBlank()) {
			TextButton(onClick = {
				runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + SupportInfo.PHONE))) }
			}) { Text("Call or text ${SupportInfo.PHONE}", color = Palette.Amber, fontSize = 18.sp) }
		}
		if (SupportInfo.EMAIL.isNotBlank()) {
			TextButton(onClick = {
				val subject = "LucidSleep help" + (ticket?.let { " · ticket $it" } ?: "")
				runCatching {
					context.startActivity(
						Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + SupportInfo.EMAIL)).putExtra(Intent.EXTRA_SUBJECT, subject)
					)
				}
			}) { Text("Email ${SupportInfo.EMAIL}", color = Palette.Amber, fontSize = 18.sp) }
		}
		if (SupportInfo.HOURS.isNotBlank()) Note(SupportInfo.HOURS)
	}
}

/** One night in My nights: its name, whether it counted, and what happened if it didn't. */
@Composable
private fun NightRow(title: String, status: String, color: Color, detail: String?) {
	Column(
		Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.NavyRaised).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp)
	) {
		Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
		Text(status, color = color, fontSize = 17.sp)
		detail?.let { Note(it) }
	}
}

/** Home's card for the most recent finished night; tapping it opens My nights. */
@Composable
private fun LastNightCard(night: SeriesStore.Night, rec: NightRecorder.State, onClick: () -> Unit) {
	val ended = night.endedAtEpochMs ?: return
	val name = if (System.currentTimeMillis() - ended < 20 * 3_600_000L) "Last night"
	else "Your last night (${SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(night.startedAtEpochMs))})"
	val verdict = when {
		night.status == SeriesStore.Status.COUNTED -> "counted"
		night.partial -> "didn't count: the patch was disconnected"
		else -> "didn't count: too short"
	}
	val upload = when {
		rec.localSessionId != night.localSessionId -> ""
		rec.uploadedPercent >= 100 -> " · all uploaded"
		else -> " · ${rec.uploadedPercent}% uploaded"
	}
	Row(
		Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.NavyRaised)
			.clickable(onClick = onClick).padding(16.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Column(Modifier.weight(1f)) {
			Text("$name $verdict", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
				color = if (night.status == SeriesStore.Status.COUNTED) Palette.Ink else Palette.Warn)
			Note("${formatDuration(night.recordedMs)} recorded$upload")
		}
		Text("›", fontSize = 30.sp, color = Palette.InkMuted)
	}
}

/** A counted night's disconnections are mentioned in My nights once they add up to this. */
private const val NOTE_GAP_MS = 15L * 60_000L

@Composable
private fun HelpItem(title: String, body: String) {
	Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.NavyRaised).padding(16.dp)) {
		Text(title, style = MaterialTheme.typography.titleLarge, fontSize = 19.sp)
		Spacer(Modifier.height(4.dp))
		Note(body)
	}
}

/** Shares the night logs (no EEG, no personal data) with a short ticket code. */
private fun sendDiagnostics(context: Context, graph: AppGraph): String {
	val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
	val code = (1..6).map { alphabet.random() }.joinToString("")
	graph.log.event("DIAGNOSTICS", "ticket" to code, "phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
		"android" to Build.VERSION.SDK_INT, "state" to graph.recorder.state.value.toString())
	val files = graph.log.files().take(6)
	val uris = ArrayList(files.map { f: File ->
		FileProvider.getUriForFile(context, "${context.packageName}.files", f)
	})
	val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
		type = "text/plain"
		putExtra(Intent.EXTRA_SUBJECT, "LucidSleep diagnostics $code")
		putExtra(Intent.EXTRA_TEXT, "Ticket $code")
		putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
		addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
	}
	context.startActivity(Intent.createChooser(intent, "Send diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
	return code
}