// CgxBleClient.kt  (Device Management v2 drop-in replacement)
//
// Adds multi-device scan support while preserving the existing streaming behavior:
// 1) Keeps existing scanHits Flow<ScanHit> so current AppController code can keep compiling.
// 2) Adds discoveredDevices Flow<List<DiscoveredCgxDevice>> with de-duped scan results.
// 3) Adds local remembered-device storage using SharedPreferences + org.json.
// 4) Adds remember/rename/forget helpers that the UI/controller can call after user confirmation.
// 5) Keeps serialized CCCD writes and tagged notify chunks from the current version.
//
// Notes:
// - This version uses BLE address as the first-pass identity key because that is what your
//   current connection path already uses. If the CGX exposes a serial/device-id GATT
//   characteristic later, migrate remembered devices to that stable id.
// - Naming/remembering does not auto-connect by itself. The controller/UI should still decide
//   whether to connect automatically or show the picker list.

package com.lucidify.lucidsleep.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max

/** Existing low-level scan event. Kept source-compatible with your current code. */
data class ScanHit(
    val name: String?,
    val address: String,
    val rssi: Int,
    val lastSeenMs: Long = System.currentTimeMillis(),
    val nickname: String? = null,
    val isRemembered: Boolean = false
)

/** Higher-level, de-duped item for a device picker UI. */
data class DiscoveredCgxDevice(
    val address: String,
    val advertisedName: String?,
    val rssi: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val nickname: String? = null,
    val isRemembered: Boolean = false
) {
    val hardwareLabel: String?
        get() = advertisedName?.trim()?.takeIf { it.isNotBlank() }

    val displayName: String
        get() {
            val friendly = nickname?.takeIf { it.isNotBlank() }
            val hardware = hardwareLabel
            return when {
                friendly != null && hardware != null -> "$friendly - $hardware"
                friendly != null -> friendly
                hardware != null -> hardware
                else -> "Unconfigured CGX Patch"
            }
        }
}

/** Locally persisted device identity/name. */
data class RememberedCgxDevice(
    val address: String,
    val nickname: String,
    val advertisedName: String?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val lastConnectedMs: Long?
)

/**
 * Tagged notify payload so consumer can differentiate CHAR_A vs CHAR_B.
 * atElapsedMs is the arrival time (SystemClock.elapsedRealtime) so the recorder can
 * measure real Bluetooth outage gaps independently of decoder backlog.
 */
data class NotifyChunk(
    val uuid: UUID,
    val bytes: ByteArray,
    val atElapsedMs: Long = android.os.SystemClock.elapsedRealtime()
)

class CgxBleClient(private val ctx: Context) {

    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = manager.adapter
    private val scanner get() = adapter.bluetoothLeScanner

    private val SERVICE_UUID = UUID.fromString("2456E1B9-26E2-8F83-E744-F34F01E9D701")
    private val CHAR_A = UUID.fromString("2456E1B9-26E2-8F83-E744-F34F01E9D703")
    private val CHAR_B = UUID.fromString("2456E1B9-26E2-8F83-E744-F34F01E9D704")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val prefs = ctx.getSharedPreferences("cgx_ble_devices", Context.MODE_PRIVATE)
    private val rememberedKey = "remembered_devices_json"
    private val lastConnectedKey = "last_connected_address"

    /** The Patch this tablet last connected to, kept across app restarts. */
    fun lastConnectedAddress(): String? =
        prefs.getString(lastConnectedKey, null)?.takeIf { it.isNotBlank() }

    /**
     * Devices Android reports as connected over GATT right now, by any app or by a link left
     * open after this app was closed. A Patch held this way does not advertise, so a scan
     * cannot find it, but connecting to it by address can.
     */
    @SuppressLint("MissingPermission")
    fun systemGattConnectedAddresses(): Set<String> = runCatching {
        manager.getConnectedDevices(BluetoothProfile.GATT)
            .mapNotNull { it.address?.uppercase(java.util.Locale.ROOT) }
            .toSet()
    }.getOrDefault(emptySet())

    private var gatt: BluetoothGatt? = null
    private var scanCb: ScanCallback? = null

    private enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        DISCOVERING_SERVICES,
        ENABLING_NOTIFICATIONS,
        CONNECTED
    }

    @Volatile private var connectionState = ConnectionState.DISCONNECTED
    @Volatile private var connectedAddress: String? = null

    private val discoveredByAddress = LinkedHashMap<String, DiscoveredCgxDevice>()

    @Volatile private var connectingAddress: String? = null

    private val _events = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    private val _scanHits = MutableSharedFlow<ScanHit>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val scanHits = _scanHits.asSharedFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredCgxDevice>>(emptyList())
    val discoveredDevices = _discoveredDevices.asStateFlow()

    private val _rememberedDevices = MutableStateFlow(loadRememberedDevices())
    val rememberedDevices = _rememberedDevices.asStateFlow()

    // Raw notify pipeline: do not silently drop without visibility.
    private val notifyChannel = Channel<NotifyChunk>(
        capacity = 8192,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val notifyBytes: Flow<NotifyChunk> = notifyChannel.receiveAsFlow()

    @Volatile private var notifyDropCount: Long = 0

    private val notifyBytesCounter = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Bytes delivered by Patch notifications since this client was created; only grows.
     * Counted on the Bluetooth callback thread, before any app-side decoding, so a busy
     * decoder or main thread cannot make a live link look silent.
     */
    val notifyBytesReceived: Long
        get() = notifyBytesCounter.get()

    // Notification enable state (serialize CCCD writes)
    private val pendingEnable = ArrayDeque<BluetoothGattCharacteristic>()
    @Volatile private var enabling = false

    // Device-management / command transport state. CGX documents the command
    // framing, but not which native-BLE characteristic carries host -> Patch
    // UART traffic. We discover that at runtime rather than hard-coding it.
    @Volatile private var negotiatedMtu: Int = 23
    @Volatile private var commandWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var commandTxInProgress = false
    private val commandTxQueue = ArrayDeque<ByteArray>()
    private var commandTxWriteWithResponse = true
    private var pendingCommandType: Int? = null
    private var pendingCommandCrc: ByteArray? = null
    private val commandRxBuffer = ArrayList<Byte>(512)
    private var awaitingReadResponse: Boolean = false
    private val commandReadPacketBuffer = ArrayList<Byte>(128)

    fun isBluetoothReady(): Boolean = adapter != null && adapter.isEnabled

    fun gattCapabilitySummary(): String {
        val g = gatt ?: return "Not connected"
        val svc = g.getService(SERVICE_UUID) ?: return "CGX service not discovered"
        return svc.characteristics.joinToString("\n") { ch ->
            val flags = buildList {
                if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
                if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
                if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
                if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
                if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
            }.joinToString("|").ifBlank { "none" }
            "${ch.uuid}  $flags"
        }
    }

    fun writableCharacteristicUuid(): String? = commandWriteCharacteristic?.uuid?.toString()

    /**
     * Abandon a device-management command that never completed. This is used by
     * the UI/controller watchdog so a missed BLE response cannot permanently
     * leave Device Management in a busy state. It does not disconnect the Patch.
     */
    @Synchronized
    fun cancelPendingDeviceCommand() {
        Log.i("CGXProbe", "cancel pending command type=${pendingCommandType?.let { "0x" + it.toString(16).uppercase().padStart(2, '0') } ?: "none"}")
        commandTxQueue.clear()
        commandTxInProgress = false
        pendingCommandType = null
        pendingCommandCrc = null
        commandRxBuffer.clear()
        awaitingReadResponse = false
        commandReadPacketBuffer.clear()
        _events.tryEmit("command_cancelled")
    }

    /**
     * Experimental but protocol-grounded test: read NVRAM slot 15 (command 0x2F).
     * Unlike ReadTime, this opcode is explicitly present in the supplied CGX docs.
     */
    @SuppressLint("MissingPermission")
    fun readSlot15(): Boolean = readDeviceSlot(15)

    @SuppressLint("MissingPermission")
    fun readDeviceSlot(slot: Int): Boolean {
        if (slot !in 0..15) return false
        return sendCgxCommand(0x20 + slot, ByteArray(63))
    }

    /**
     * Diagnostic-only read probe for the two undocumented command bytes adjacent
     * to the documented WriteTime (0x42). We intentionally restrict this to
     * 0x40 and 0x41; arbitrary unknown opcodes are not exposed to the UI.
     */
    @SuppressLint("MissingPermission")
    fun probeReadOpcode(opcode: Int): Boolean {
        if (opcode != 0x40 && opcode != 0x41) return false
        Log.i("CGXProbe", "send probe opcode=0x${opcode.toString(16).uppercase().padStart(2, '0')}")
        return sendCgxCommand(opcode, ByteArray(63))
    }

    /** Write a zero-padded ASCII value to one 63-byte NVRAM slot. */
    @SuppressLint("MissingPermission")
    fun writeDeviceSlot(slot: Int, value: String): Boolean {
        if (slot !in 0..15) return false
        val ascii = value.toByteArray(Charsets.US_ASCII)
        if (ascii.size > 63) return false
        val data = ByteArray(63)
        ascii.copyInto(data)
        return sendCgxCommand(0x10 + slot, data)
    }

    /**
     * Write Android's current local date/time to the Patch using documented opcode 0x42.
     * The supplied docs do NOT include the numeric ReadTime opcode, so v1 intentionally
     * does not guess it.
     */
    @SuppressLint("MissingPermission")
    fun syncTimeToAndroid(now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
        val data = ByteArray(63)
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = now.get(java.util.Calendar.MINUTE)
        val second = now.get(java.util.Calendar.SECOND)
        val year = now.get(java.util.Calendar.YEAR) % 100
        val month = now.get(java.util.Calendar.MONTH) + 1
        val day = now.get(java.util.Calendar.DAY_OF_MONTH)
        val values = intArrayOf(hour, minute, second, year, month, day)
        var i = 0
        values.forEach { v ->
            data[i++] = (v / 10).toByte()
            data[i++] = (v % 10).toByte()
        }
        return sendCgxCommand(0x42, data)
    }

    fun getRememberedDevice(address: String): RememberedCgxDevice? {
        return _rememberedDevices.value.firstOrNull { it.address.equals(address, ignoreCase = true) }
    }

    /**
     * Resolve a locally remembered Patch by transport address only.
     *
     * Advertised names are not unique device identifiers. Prototype/firmware builds may
     * reuse or change names, so matching remembered devices by advertised name can assign
     * one physical Patch's nickname/identity to another. A fresh scan result always owns
     * its own advertised name and address.
     */
    private fun getRememberedDevice(address: String, advertisedName: String?): RememberedCgxDevice? {
        val exact = getRememberedDevice(address) ?: return null
        val freshName = advertisedName?.trim().orEmpty()
        val rememberedName = exact.advertisedName?.trim().orEmpty()
        // If the same address is now advertising a materially different name, do not
        // project stale remembered identity onto the fresh discovery result.
        if (freshName.isNotBlank() && rememberedName.isNotBlank() &&
            !freshName.equals(rememberedName, ignoreCase = true)) return null
        return exact
    }

    fun defaultNicknameFor(address: String, advertisedName: String?): String {
        val base = advertisedName?.takeIf { it.isNotBlank() } ?: "CGX Patch"
        val existingNames = _rememberedDevices.value.map { it.nickname }.toSet()
        if (base !in existingNames) return base

        var i = 2
        while ("$base $i" in existingNames) i++
        return "$base $i"
    }

    fun rememberDevice(
        address: String,
        nickname: String,
        advertisedName: String? = null
    ) {
        if (address.isBlank()) return

        val now = System.currentTimeMillis()
        val existing = getRememberedDevice(address)
        val fromScan = discoveredByAddress[address]

        val updated = RememberedCgxDevice(
            address = address,
            nickname = nickname.ifBlank { defaultNicknameFor(address, advertisedName ?: fromScan?.advertisedName) },
            advertisedName = advertisedName ?: fromScan?.advertisedName ?: existing?.advertisedName,
            firstSeenMs = existing?.firstSeenMs ?: fromScan?.firstSeenMs ?: now,
            lastSeenMs = max(existing?.lastSeenMs ?: 0L, fromScan?.lastSeenMs ?: now),
            lastConnectedMs = existing?.lastConnectedMs
        )

        upsertRememberedDevice(updated)
        refreshDiscoveredRememberedState()
        _events.tryEmit("device_remembered:$address")
    }

    fun renameDevice(address: String, nickname: String) {
        val existing = getRememberedDevice(address) ?: return
        upsertRememberedDevice(existing.copy(nickname = nickname.ifBlank { existing.nickname }))
        refreshDiscoveredRememberedState()
        _events.tryEmit("device_renamed:$address")
    }

    fun forgetDevice(address: String) {
        val updated = _rememberedDevices.value.filterNot { it.address == address }
        saveRememberedDevices(updated)
        _rememberedDevices.value = updated
        refreshDiscoveredRememberedState()
        _events.tryEmit("device_forgotten:$address")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan(clearPrevious: Boolean = true) {
        stopScan()
        if (clearPrevious) clearDiscoveredDevices()

        if (!isBluetoothReady()) {
            _events.tryEmit("error: bluetooth_disabled")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCb = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                _events.tryEmit("scan_failed:$errorCode")
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handleScanResult(it) }
            }
        }

        scanner.startScan(listOf(filter), settings, scanCb)
        _events.tryEmit("scan_started")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        scanCb?.let { cb ->
            try { scanner.stopScan(cb) } catch (_: Throwable) {}
        }
        scanCb = null
    }

    fun clearDiscoveredDevices() {
        discoveredByAddress.clear()
        _discoveredDevices.value = emptyList()
    }

    fun isConnectionActive(address: String? = null): Boolean {
        val active = connectionState != ConnectionState.DISCONNECTED && gatt != null
        if (!active) return false
        return address == null || address == connectingAddress || address == connectedAddress
    }

    fun currentConnectionAddress(): String? = connectedAddress ?: connectingAddress

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Synchronized
    fun connect(address: String) {
        if (address.isBlank()) return

        if (isConnectionActive(address)) {
            _events.tryEmit("connect_ignored:${connectionState.name.lowercase()}:$address")
            return
        }

        // A target change is an explicit replacement. Clear the old GATT before
        // creating the new one so stale callbacks cannot close the new connection.
        disconnectInternal(emitEvent = false)

        connectingAddress = address
        connectedAddress = null
        connectionState = ConnectionState.CONNECTING

        val dev = adapter.getRemoteDevice(address)
        _events.tryEmit("connecting:$address")
        Log.i("CGX", "CONNECT address=$address")

        val newGatt = dev.connectGatt(
            ctx,
            false,
            gattCb,
            BluetoothDevice.TRANSPORT_LE
        )
        gatt = newGatt

        if (newGatt == null) {
            connectionState = ConnectionState.DISCONNECTED
            connectingAddress = null
            _events.tryEmit("conn_error:connectGatt_returned_null")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(device: DiscoveredCgxDevice) {
        connect(device.address)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Synchronized
    fun disconnect() {
        disconnectInternal(emitEvent = false)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun disconnectInternal(emitEvent: Boolean) {
        enabling = false
        pendingEnable.clear()

        val oldGatt = gatt
        val oldAddress = connectedAddress ?: connectingAddress

        // Null the authoritative reference first. Any callback from oldGatt is now stale.
        gatt = null
        connectionState = ConnectionState.DISCONNECTED
        connectedAddress = null
        connectingAddress = null

        try { oldGatt?.disconnect() } catch (_: Throwable) {}
        try { oldGatt?.close() } catch (_: Throwable) {}

        if (oldGatt != null) Log.i("CGX", "DISCONNECT address=${oldAddress ?: "unknown"}")
        if (emitEvent) _events.tryEmit("disconnected:${oldAddress ?: "unknown"}")
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val address = result.device?.address ?: return
        if (address.isBlank()) return

        val now = System.currentTimeMillis()
        val advertisedName = result.device?.name
        val remembered = getRememberedDevice(address, advertisedName)
        val existing = discoveredByAddress[address]

        val discovered = DiscoveredCgxDevice(
            address = address,
            advertisedName = advertisedName ?: existing?.advertisedName,
            rssi = result.rssi,
            firstSeenMs = existing?.firstSeenMs ?: remembered?.firstSeenMs ?: now,
            lastSeenMs = now,
            nickname = remembered?.nickname,
            isRemembered = remembered != null
        )

        discoveredByAddress[address] = discovered
        emitDiscoveredList()

        _scanHits.tryEmit(
            ScanHit(
                name = discovered.advertisedName,
                address = discovered.address,
                rssi = discovered.rssi,
                lastSeenMs = discovered.lastSeenMs,
                nickname = discovered.nickname,
                isRemembered = discovered.isRemembered
            )
        )

        if (remembered != null && now > remembered.lastSeenMs) {
            // Exact-address refresh only. Never migrate a remembered identity between
            // physical devices merely because their advertised names match.
            upsertRememberedDevice(
                remembered.copy(
                    advertisedName = discovered.advertisedName ?: remembered.advertisedName,
                    lastSeenMs = now
                ),
                emitEvent = false
            )
        }
    }

    private fun emitDiscoveredList() {
        _discoveredDevices.value = discoveredByAddress.values
            .sortedWith(
                compareBy<DiscoveredCgxDevice> { it.displayName.lowercase(java.util.Locale.ROOT) }
                    .thenBy { it.address.lowercase(java.util.Locale.ROOT) }
            )
    }

    private fun refreshDiscoveredRememberedState() {
        if (discoveredByAddress.isEmpty()) return

        val rememberedByAddress = _rememberedDevices.value.associateBy { it.address.lowercase(java.util.Locale.ROOT) }
        val refreshed = discoveredByAddress.mapValues { (_, d) ->
            val candidate = rememberedByAddress[d.address.lowercase(java.util.Locale.ROOT)]
            val freshName = d.advertisedName?.trim().orEmpty()
            val rememberedName = candidate?.advertisedName?.trim().orEmpty()
            val r = candidate?.takeUnless {
                freshName.isNotBlank() && rememberedName.isNotBlank() &&
                    !freshName.equals(rememberedName, ignoreCase = true)
            }
            d.copy(
                nickname = r?.nickname,
                // A current scan owns its advertised identity. Only fall back to the
                // remembered value when Android did not provide a name at all.
                advertisedName = d.advertisedName ?: r?.advertisedName,
                isRemembered = r != null
            )
        }

        discoveredByAddress.clear()
        discoveredByAddress.putAll(refreshed)
        emitDiscoveredList()
    }

    private fun markConnected(address: String) {
        val existing = getRememberedDevice(address) ?: return
        upsertRememberedDevice(existing.copy(lastConnectedMs = System.currentTimeMillis()), emitEvent = false)
    }

    private fun upsertRememberedDevice(device: RememberedCgxDevice, emitEvent: Boolean = true) {
        val current = _rememberedDevices.value.toMutableList()
        val idx = current.indexOfFirst { it.address == device.address }
        if (idx >= 0) current[idx] = device else current.add(device)

        val sorted = current.sortedByDescending { it.lastConnectedMs ?: it.lastSeenMs }
        saveRememberedDevices(sorted)
        _rememberedDevices.value = sorted
        if (emitEvent) _events.tryEmit("remembered_devices_updated:${sorted.size}")
    }

    private fun loadRememberedDevices(): List<RememberedCgxDevice> {
        val raw = prefs.getString(rememberedKey, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val address = o.optString("address", "")
                    val nickname = o.optString("nickname", "")
                    if (address.isBlank() || nickname.isBlank()) continue
                    add(
                        RememberedCgxDevice(
                            address = address,
                            nickname = nickname,
                            advertisedName = o.optString("advertisedName", "").takeIf { it.isNotBlank() },
                            firstSeenMs = o.optLong("firstSeenMs", 0L),
                            lastSeenMs = o.optLong("lastSeenMs", 0L),
                            lastConnectedMs = o.optLong("lastConnectedMs", -1L).takeIf { it >= 0L }
                        )
                    )
                }
            }
        }.getOrElse {
            emptyList()
        }
    }

    private fun saveRememberedDevices(devices: List<RememberedCgxDevice>) {
        val arr = JSONArray()
        devices.forEach { d ->
            arr.put(
                JSONObject()
                    .put("address", d.address)
                    .put("nickname", d.nickname)
                    .put("advertisedName", d.advertisedName ?: "")
                    .put("firstSeenMs", d.firstSeenMs)
                    .put("lastSeenMs", d.lastSeenMs)
                    .put("lastConnectedMs", d.lastConnectedMs ?: -1L)
            )
        }
        prefs.edit().putString(rememberedKey, arr.toString()).apply()
    }

    private fun sendNotifyBytes(uuid: UUID, value: ByteArray) {
        notifyBytesCounter.addAndGet(value.size.toLong())
        inspectCommandResponse(value)
        // Defensive copy: some stacks reuse characteristic.value buffers.
        val res = notifyChannel.trySend(NotifyChunk(uuid, value.copyOf()))
        if (!res.isSuccess) {
            notifyDropCount++
            _events.tryEmit("notify_drop:$notifyDropCount")
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun sendCgxCommand(cmdType: Int, data: ByteArray): Boolean {
        val g = gatt ?: run {
            _events.tryEmit("command_unavailable:not_connected")
            return false
        }
        val ch = commandWriteCharacteristic ?: run {
            _events.tryEmit("command_unavailable:no_writable_characteristic")
            return false
        }
        if (data.size != 63) return false
        if (commandTxInProgress) {
            _events.tryEmit("command_unavailable:busy")
            return false
        }

        val cmdAndData = byteArrayOf(cmdType.toByte()) + data
        val crc = cgxCrc(cmdAndData)
        val packet = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
        ) + cmdAndData + crc + byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x00, 0x00
        )

        pendingCommandType = cmdType
        pendingCommandCrc = crc
        commandRxBuffer.clear()
        awaitingReadResponse = false
        commandReadPacketBuffer.clear()
        commandTxQueue.clear()

        val maxPayload = (negotiatedMtu - 3).coerceAtLeast(20)
        packet.toList().chunked(maxPayload).forEach { part ->
            commandTxQueue.addLast(part.toByteArray())
        }
        commandTxWriteWithResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        commandTxInProgress = true
        _events.tryEmit("command_sending:${cmdType.toString(16).padStart(2, '0')}:${ch.uuid}:mtu=$negotiatedMtu")
        sendNextCommandChunk()
        return true
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun sendNextCommandChunk() {
        if (!commandTxInProgress) return
        val g = gatt ?: return finishCommandTx("command_write_failed:no_gatt")
        val ch = commandWriteCharacteristic ?: return finishCommandTx("command_write_failed:no_characteristic")
        val chunk = commandTxQueue.removeFirstOrNull()
        if (chunk == null) {
            commandTxInProgress = false
            _events.tryEmit("command_sent:${pendingCommandType ?: -1}")
            return
        }

        val writeType = if (commandTxWriteWithResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        val queued = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, chunk, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = writeType
                ch.value = chunk
                g.writeCharacteristic(ch)
            }
        }

        if (!queued) {
            finishCommandTx("command_write_failed:not_queued")
            return
        }

        // WRITE_NO_RESPONSE has no completion callback. The command stream is
        // serial UART data, so continue immediately with the next BLE chunk.
        if (!commandTxWriteWithResponse) sendNextCommandChunk()
    }

    @Synchronized
    private fun finishCommandTx(event: String) {
        commandTxQueue.clear()
        commandTxInProgress = false
        _events.tryEmit(event)
    }

    private fun inspectCommandResponse(bytes: ByteArray) {
        val cmd = pendingCommandType ?: return
        val expectedCrc = pendingCommandCrc ?: return
        if (cmd == 0x40 || cmd == 0x41) {
            Log.d("CGXProbe", "rx notify cmd=0x${cmd.toString(16).uppercase().padStart(2, '0')} bytes=${bytes.size} awaitingRead=$awaitingReadResponse")
        }

        // For read commands, the CGX reference implementation first waits for a
        // 6-byte ACK (FF FF FF FF + original CRC) and then reads one complete
        // 77-byte command packet: Sync(4) + CmdType(1) + Data(63) + CRC(2) + Tail(7).
        // Notifications may split either item across BLE packets, so accumulate them.
        if (awaitingReadResponse) {
            bytes.forEach { commandReadPacketBuffer.add(it) }
            extractReadResponsePacket(cmd)
            return
        }

        bytes.forEach { commandRxBuffer.add(it) }
        if (commandRxBuffer.size > 2048) {
            commandRxBuffer.subList(0, commandRxBuffer.size - 2048).clear()
        }

        val ackIndex = findAck(commandRxBuffer, expectedCrc)
        if (ackIndex < 0) return

        if (cmd == 0x40 || cmd == 0x41) Log.i("CGXProbe", "matching ACK found for opcode=0x${cmd.toString(16).uppercase().padStart(2, '0')}")
        _events.tryEmit("command_ack:$cmd")

        val bytesAfterAck = commandRxBuffer.drop(ackIndex + 6)
        commandRxBuffer.clear()

        if (cmd in 0x20..0x2F || cmd == 0x40 || cmd == 0x41) {
            // Known NVRAM reads plus the two diagnostic read candidates. Keep listening after the ACK for the framed 77-byte reply.
            awaitingReadResponse = true
            commandReadPacketBuffer.clear()
            commandReadPacketBuffer.addAll(bytesAfterAck)
            _events.tryEmit("command_read_waiting:$cmd")
            extractReadResponsePacket(cmd)
        } else {
            pendingCommandType = null
            pendingCommandCrc = null
        }
    }

    private fun findAck(buffer: List<Byte>, expectedCrc: ByteArray): Int {
        if (buffer.size < 6) return -1
        for (i in 0..buffer.size - 6) {
            if (buffer[i] == 0xFF.toByte() &&
                buffer[i + 1] == 0xFF.toByte() &&
                buffer[i + 2] == 0xFF.toByte() &&
                buffer[i + 3] == 0xFF.toByte() &&
                buffer[i + 4] == expectedCrc[0] &&
                buffer[i + 5] == expectedCrc[1]
            ) return i
        }
        return -1
    }

    private fun extractReadResponsePacket(cmd: Int) {
        val sync = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val tail = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
        )
        val packetLength = 77

        while (commandReadPacketBuffer.size >= 4) {
            val syncIndex = findSequence(commandReadPacketBuffer, sync)
            if (syncIndex < 0) {
                // Retain at most the final 3 bytes in case they are the start of sync.
                while (commandReadPacketBuffer.size > 3) commandReadPacketBuffer.removeAt(0)
                return
            }
            repeat(syncIndex) { commandReadPacketBuffer.removeAt(0) }
            if (commandReadPacketBuffer.size < packetLength) return

            val packet = commandReadPacketBuffer.take(packetLength).toByteArray()
            val packetTail = packet.copyOfRange(70, 77)
            if (!packetTail.contentEquals(tail)) {
                commandReadPacketBuffer.removeAt(0)
                continue
            }

            val responseCmd = packet[4].toInt() and 0xFF
            if (cmd == 0x40 || cmd == 0x41) Log.i("CGXProbe", "framed response candidate request=0x${cmd.toString(16).uppercase().padStart(2, '0')} response=0x${responseCmd.toString(16).uppercase().padStart(2, '0')}")
            val data = packet.copyOfRange(5, 68)
            val actualCrc = packet.copyOfRange(68, 70)
            val expectedPacketCrc = cgxCrc(byteArrayOf(packet[4]) + data)
            if (!actualCrc.contentEquals(expectedPacketCrc)) {
                _events.tryEmit("command_read_crc_failed:$cmd")
                commandReadPacketBuffer.removeAt(0)
                continue
            }

            if (cmd == 0x40 || cmd == 0x41) {
                val ascii = data.toAsciiDiagnostic()
                val hex = data.toHexStringLocal()
                _events.tryEmit("probe_read_data:$cmd:$responseCmd:$hex")
                _events.tryEmit("probe_read_ascii:$cmd:$ascii")
                _events.tryEmit("command_read_complete:$cmd:$responseCmd")
            }

            if (cmd in 0x20..0x2F) {
                val slot = cmd - 0x20
                val ascii = data.toAsciiDiagnostic()
                val hex = data.toHexStringLocal()
                _events.tryEmit("slot_data:$slot:$hex")
                _events.tryEmit("slot_ascii:$slot:$ascii")
                if (slot == 15) {
                    _events.tryEmit("slot15_data:$hex")
                    _events.tryEmit("slot15_ascii:$ascii")
                }
                _events.tryEmit("command_read_complete:$cmd:$responseCmd")
            }

            repeat(packetLength) { commandReadPacketBuffer.removeAt(0) }
            awaitingReadResponse = false
            pendingCommandType = null
            pendingCommandCrc = null
            return
        }
    }

    private fun findSequence(buffer: List<Byte>, sequence: ByteArray): Int {
        if (buffer.size < sequence.size) return -1
        outer@ for (i in 0..buffer.size - sequence.size) {
            for (j in sequence.indices) {
                if (buffer[i + j] != sequence[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun cgxCrc(data: ByteArray): ByteArray {
        val table = intArrayOf(
            0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
            0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF
        )
        var crc = 0
        for (b in data) {
            var i = ((crc ushr 12) xor ((b.toInt() and 0xFF) ushr 4)) and 0x0F
            crc = (table[i] xor (crc shl 4)) and 0xFFFF
            i = ((crc ushr 12) xor (b.toInt() and 0x0F)) and 0x0F
            crc = (table[i] xor (crc shl 4)) and 0xFFFF
        }
        return byteArrayOf((crc ushr 8).toByte(), (crc and 0xFF).toByte())
    }

    private fun ByteArray.toHexStringLocal(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun ByteArray.toAsciiDiagnostic(): String = buildString {
        for (b in this@toAsciiDiagnostic) {
            val v = b.toInt() and 0xFF
            append(if (v in 32..126) v.toChar() else '.')
        }
    }

    private fun uuidShort(u: UUID): String {
        val s = u.toString()
        return if (s.length >= 8) s.substring(0, 8) else s
    }

    private val gattCb = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(
                "CGX",
                "STATE status=$status newState=$newState gatt=${System.identityHashCode(callbackGatt)} current=${callbackGatt === gatt}"
            )

            // Callbacks from a previously closed GATT must never mutate the current connection.
            if (callbackGatt !== gatt) {
                try { callbackGatt.close() } catch (_: Throwable) {}
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                val address = connectedAddress ?: connectingAddress ?: "unknown"
                _events.tryEmit("gatt_error:$status:$address")
                enabling = false
                pendingEnable.clear()
                gatt = null
                connectionState = ConnectionState.DISCONNECTED
                connectedAddress = null
                connectingAddress = null
                try { callbackGatt.close() } catch (_: Throwable) {}
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val address = connectingAddress ?: callbackGatt.device?.address ?: "unknown"
                    connectedAddress = address
                    connectingAddress = address
                    connectionState = ConnectionState.DISCOVERING_SERVICES
                    markConnected(address)
                    if (address != "unknown") prefs.edit().putString(lastConnectedKey, address).apply()
                    _events.tryEmit("connected:$address")

                    // Keep the initial GATT transaction sequence strictly serialized.
                    // Some Android BLE stacks tolerate an MTU request and service
                    // discovery being started back-to-back, while others do not.
                    // Discover services first; request a larger MTU only after service
                    // discovery has completed.
                    val started = callbackGatt.discoverServices()
                    _events.tryEmit("svc_discovery_requested:$address started=$started")
                    if (!started) {
                        _events.tryEmit("svc_error:discover_services_not_started:$address")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val address = connectedAddress ?: connectingAddress ?: "unknown"
                    enabling = false
                    pendingEnable.clear()
                    gatt = null
                    connectionState = ConnectionState.DISCONNECTED
                    connectedAddress = null
                    connectingAddress = null
                    try { callbackGatt.close() } catch (_: Throwable) {}
                    _events.tryEmit("disconnected:$address")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== this@CgxBleClient.gatt) {
                try { g.close() } catch (_: Throwable) {}
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _events.tryEmit("services_failed:$status")
                return
            }
            connectionState = ConnectionState.ENABLING_NOTIFICATIONS
            val svc = g.getService(SERVICE_UUID)
            if (svc == null) {
                _events.tryEmit("svc_missing")
                return
            }

            commandWriteCharacteristic = svc.characteristics.firstOrNull { ch ->
                ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            } ?: svc.characteristics.firstOrNull { ch ->
                ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            }

            _events.tryEmit("services_ok:${connectedAddress ?: connectingAddress ?: "unknown"}")
            _events.tryEmit("gatt_caps:${gattCapabilitySummary().replace("\n", "; ")}")
            _events.tryEmit("command_write_char:${writableCharacteristicUuid() ?: "none"}")

            // Service discovery is complete, so it is now safe to enqueue the MTU
            // negotiation. Notification setup starts only after onMtuChanged, keeping
            // one outstanding GATT operation at a time on stricter Android stacks.
            val mtuStarted = runCatching { g.requestMtu(247) }.getOrDefault(false)
            _events.tryEmit("mtu_requested:247 started=$mtuStarted")
            if (!mtuStarted) {
                _events.tryEmit("mtu_request_skipped_or_failed")
                enableNotifications(g)
            }
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(g: BluetoothGatt) {
            val svc = g.getService(SERVICE_UUID) ?: run {
                _events.tryEmit("svc_missing")
                return
            }
            val a = svc.getCharacteristic(CHAR_A) ?: run {
                _events.tryEmit("charA_missing")
                return
            }
            val b = svc.getCharacteristic(CHAR_B) ?: run {
                _events.tryEmit("charB_missing")
                return
            }

            pendingEnable.clear()
            pendingEnable.addLast(a)
            pendingEnable.addLast(b)

            enabling = true
            enableNext(g)
        }

        @SuppressLint("MissingPermission")
        private fun enableNext(g: BluetoothGatt) {
            val ch = pendingEnable.removeFirstOrNull()
            if (ch == null) {
                enabling = false
                connectionState = ConnectionState.CONNECTED
                _events.tryEmit("notify_enabled:done")
                return
            }

            val ok1 = g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD)
            if (!ok1 || cccd == null) {
                enabling = false
                pendingEnable.clear()
                _events.tryEmit("notify_enable_fail:${uuidShort(ch.uuid)} ok1=$ok1 cccd=${cccd != null}")
                return
            }

            val ok2 = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(
                    cccd,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }

            _events.tryEmit("notify_enable_req:${uuidShort(ch.uuid)} ok1=$ok1 ok2=$ok2")
            if (!ok2) {
                enabling = false
                pendingEnable.clear()
                _events.tryEmit("notify_enable_failed_to_queue:${uuidShort(ch.uuid)}")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== this@CgxBleClient.gatt) return
            val chUuid = descriptor.characteristic?.uuid
            _events.tryEmit("cccd_written:${if (chUuid != null) uuidShort(chUuid) else "null"} status=$status")

            if (!enabling) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                enabling = false
                pendingEnable.clear()
                _events.tryEmit(
                    "notify_enable_failed:${if (chUuid != null) uuidShort(chUuid) else "null"} status=$status"
                )
                return
            }

            // Only advance after Android confirms that this CCCD write completed.
            enableNext(g)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !== this@CgxBleClient.gatt) return

            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) {
                mtu.coerceAtLeast(23)
            } else {
                23
            }
            _events.tryEmit("mtu:$mtu:$status effective=$negotiatedMtu")

            if (connectionState == ConnectionState.ENABLING_NOTIFICATIONS && !enabling) {
                enableNotifications(g)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (g !== this@CgxBleClient.gatt) return
            if (!commandTxInProgress || characteristic.uuid != commandWriteCharacteristic?.uuid) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                commandTxQueue.clear()
                commandTxInProgress = false
                _events.tryEmit("command_write_failed:$status")
                return
            }
            sendNextCommandChunk()
        }

        // Old overload
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (g !== this@CgxBleClient.gatt) return
            val v = characteristic.value ?: return
            sendNotifyBytes(characteristic.uuid, v)
        }

        // API 33+ overload
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (g !== this@CgxBleClient.gatt) return
            sendNotifyBytes(characteristic.uuid, value)
        }
    }
}