package com.lucidify.lucidsleep.core.patch
data class ParsedPacket(
    val channels: FloatArray, // 8 channels
    val batteryVoltage: Float?,
    val batteryPercent: Float?,
    val sdCardPresent: Boolean?,
    val impedanceAfzOhms: Float = Float.NaN,
    val impedanceFp2Ohms: Float = Float.NaN,
    val afzVolts: Float = Float.NaN,
    val fp2Volts: Float = Float.NaN
)

object PatchPacketParser {
    private const val SYNC: Byte = 0xFF.toByte()
    private const val PACKET_SIZE = 36
    private const val CHANNEL_OFFSET = 2
    private const val CHANNEL_COUNT = 10
    private const val BATTERY_OFFSET = 33
    private const val SD_CARD_STATUS_OFFSET = 35
    val OUTPUT_CHANNEL_LABELS: List<String> = listOf(
        "Fp2-AFz",   // channels[0] = fp2 - afz
        "-Fp2",      // channels[1] = -fp2
        "AFz",       // channels[2] = afz
        "Imp1",      // channels[3] = full[2]
        "Imp2",      // channels[4] = full[3]
        "AccX",      // channels[5] = full[7]
        "AccY",      // channels[6] = full[8]
        "AccZ"       // channels[7] = full[9]
    )
    const val OUTPUT_CHANNEL_COUNT: Int = 8
    private var rejectCtr = 0L

    fun tryParse(packet: ByteArray): ParsedPacket? {
        fun reject(reason: String): ParsedPacket? {
            rejectCtr++
            if (rejectCtr % 100L == 0L) {
                android.util.Log.w("PKT", "rejected=$rejectCtr reason=$reason")
            }
            return null
        }

        if (packet.size != PACKET_SIZE) return reject("size")
        if (packet[0] != SYNC) return reject("sync")

        val battByte = packet.getOrNull(BATTERY_OFFSET)?.toInt()?.and(0xFF)
            ?: return reject("batt_missing")
        val battVoltage = battByte / 25f

        val full = FloatArray(CHANNEL_COUNT)
        for (ch in 0 until CHANNEL_COUNT) {
            val i = CHANNEL_OFFSET + ch * 3
            if (i + 2 >= packet.size) return reject("chan_oob ch=$ch i=$i")

            val b0 = packet[i].toInt() and 0xFF
            val b1 = packet[i + 1].toInt() and 0xFF
            val b2 = packet[i + 2].toInt() and 0xFF
            var value = (b0 shl 16) or (b1 shl 8) or b2
            if (value and 0x800000 != 0) value = value or -0x1000000

            val shifted = value shl 8
            full[ch] = shifted * (4.8f / (6.0f * 16777216f)) * 10f

            if (!full[ch].isFinite()) return reject("nonfinite ch=$ch")
        }

        // Important: do not reject or throttle structurally valid packets based on
        // signal amplitude or step size. Electrode/contact changes can legitimately
        // produce abrupt EEG/impedance transitions; display and recording must continue.

        val afz = full[0]
        val fp2 = full[1]

        // Physical values used only by the contact-quality monitor. Keep these
        // independent of the existing display scaling above so waveform behavior
        // is unchanged. The BLE payload is reconstructed here as a signed 24-bit
        // value before conversion.
        fun signed24At(ch: Int): Int {
            val i = CHANNEL_OFFSET + ch * 3
            val b0 = packet[i].toInt() and 0xFF
            val b1 = packet[i + 1].toInt() and 0xFF
            val b2 = packet[i + 2].toInt() and 0xFF
            var v = (b0 shl 16) or (b1 shl 8) or b2
            if (v and 0x800000 != 0) v = v or -0x1000000
            return v
        }

        val eegScaleVoltsPerCount = 4.8f / (6.0f * 16777216f)
        val afzPhysicalVolts = signed24At(0) * eegScaleVoltsPerCount
        val fp2PhysicalVolts = signed24At(1) * eegScaleVoltsPerCount

        // Patch EEG Bluetooth spec: impedance channels 3/4 are reported directly
        // and convert to ohms as x * 27.388 - 28922.
        val impedanceAfzOhms = signed24At(2) * 27.388f - 28922f
        val impedanceFp2Ohms = signed24At(3) * 27.388f - 28922f

        val channels = floatArrayOf(
            fp2 - afz,
            -fp2,
            afz,
            full[2],
            full[3],
            full[7],
            full[8],
            full[9]
        )

        val battPercent = voltageToPercent(battVoltage)
        val sdStatusByte = packet.getOrNull(SD_CARD_STATUS_OFFSET)?.toInt()?.and(0xFF)
        val sdCardPresent = when (sdStatusByte) {
            0 -> false
            1 -> true
            else -> null
        }

        return ParsedPacket(
            channels = channels,
            batteryVoltage = battVoltage,
            batteryPercent = battPercent,
            sdCardPresent = sdCardPresent,
            impedanceAfzOhms = impedanceAfzOhms,
            impedanceFp2Ohms = impedanceFp2Ohms,
            afzVolts = afzPhysicalVolts,
            fp2Volts = fp2PhysicalVolts
        )
    }


    private fun voltageToPercent(v: Float): Float {
        return when {
            v >= 4.15f -> 100f
            v <= 3.5f -> 0f
            else -> ((v - 3.5f) / (4.15f - 3.5f)) * 100f
        }
    }
}