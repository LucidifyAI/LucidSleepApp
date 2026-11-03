package com.lucidify.lucidsleep.core.patch

import kotlin.math.abs

/**
 * Rolling contact-quality monitor for the CGX forehead patch.
 *
 * CGX documents identify impedance as the primary contact-quality signal and
 * recommend a 1-2 second DC-offset sanity check because a railed EEG input can
 * make impedance appear artificially low when reference/ground contact is poor.
 *
 * The manufacturer documentation supplied with this project does not specify a
 * good/bad impedance threshold. IMPEDANCE_WARN_OHMS is therefore deliberately
 * isolated here as a provisional field-tunable value rather than being spread
 * through the UI/controller logic.
 *
 * Each electrode is also compared with its own recent level: contact that degrades
 * from a good baseline (e.g. 40 kΩ rising to 130 kΩ) is flagged before it crosses the
 * absolute limit. The absolute impedance conversion is not yet confirmed by CGX, which
 * a ratio does not depend on. The last 30 minutes are kept for an on-screen trace.
 *
 * Severity (change request item 13), separate from [State] so the trace and recent levels are unchanged:
 *  - AMBER: an electrode's impedance above [IMPEDANCE_WARN_OHMS], or above [RISE_FACTOR] x its recent level for
 *    [RISE_SUSTAIN_WINDOWS] windows in a row (60 s).
 *  - RED: an electrode's impedance at the top of its range (open circuit), or either electrode's DC offset beyond
 *    [DC_RED_VOLTS] (reference or contact failing; an input at the ADC rail lands here too).
 * On 20000 (15 Aug) Fp2 contact was lost while its DC offset reached -77 mV, which the 300 mV usability check
 * would not have caught; Signal marks DC beyond 50 mV as suspect for the same reason. Provisional, like the others.
 */
class PatchContactMonitor(
    private val sampleRateHz: Int = 500
) {
    companion object {
        // PROVISIONAL: tune after collecting known-good / deliberately-bad patch examples.
        const val IMPEDANCE_WARN_OHMS = 100_000f
        const val IMPEDANCE_CLEAR_OHMS = 80_000f

        // Explicit CGX usability recommendation.
        const val EEG_DC_WARN_VOLTS = 0.300f
        const val EEG_DC_CLEAR_VOLTS = 0.250f

        // PROVISIONAL relative rule: flag an electrode at RISE_FACTOR x its recent level,
        // clear below RISE_CLEAR_FACTOR x. The level is the median of up to the last
        // 5 minutes of windows in which that electrode was not flagged, once 1 minute exists.
        const val RISE_FACTOR = 3f
        const val RISE_CLEAR_FACTOR = 2f
        const val BASELINE_WINDOWS = 150
        const val MIN_BASELINE_WINDOWS = 30
        /** Windows kept for the on-screen trace: 30 minutes. */
        const val TREND_WINDOWS = 900

        /** RED: DC offset beyond this on either electrode; clears below [DC_RED_CLEAR_VOLTS]. */
        const val DC_RED_VOLTS = 0.050f
        const val DC_RED_CLEAR_VOLTS = 0.040f
        /** Impedance at the top of its range (the 24-bit maximum through the app formula): an open circuit. */
        const val IMPEDANCE_RAIL_OHMS = 8_388_607 * 27.388f - 28_922f
        /**
         * The conversion x * 27.388 - 28922 gives -28,922 Ω for a reading of 0, so a very good
         * contact can come out slightly below zero. Values down to this floor count as 0 Ω;
         * only values further below are treated as invalid.
         */
        const val NEGATIVE_FLOOR_OHMS = -30_000f
        /** AMBER for a relative rise only once it has lasted this many windows (60 s). */
        const val RISE_SUSTAIN_WINDOWS = 30

        private const val EVALUATION_WINDOW_SECONDS = 2
        private const val BAD_CONFIRM_WINDOWS = 2
        private const val GOOD_CONFIRM_WINDOWS = 2
    }

    enum class State {
        CHECKING,
        GOOD,
        AFZ_BAD,
        FP2_BAD,
        BOTH_BAD,
        REFERENCE_OR_CONTACT_BAD
    }

    enum class Severity { NONE, AMBER, RED }

    data class Snapshot(
        val state: State,
        val impAfzOhms: Float,
        val impFp2Ohms: Float,
        val afzDcVolts: Float,
        val fp2DcVolts: Float,
        /** Each electrode's recent level (NaN until 1 minute of unflagged contact exists). */
        val baselineAfzOhms: Float = Float.NaN,
        val baselineFp2Ohms: Float = Float.NaN,
        /** Whether each electrode is flagged for rising above its recent level. */
        val risingAfz: Boolean = false,
        val risingFp2: Boolean = false,
        /** One value per 2-s window, oldest first, up to 30 minutes. */
        val trendAfzOhms: FloatArray = FloatArray(0),
        val trendFp2Ohms: FloatArray = FloatArray(0),
        /** Item 13 alert level, confirmed over two windows, and what it is about (empty when NONE). */
        val severity: Severity = Severity.NONE,
        val problem: String = "",
        /** Which checks and electrodes the alert is about ("dc", "fp2-rail", "AFz,Fp2"…); changes only when they do. */
        val problemKey: String = ""
    )

    /** One electrode's recent level and trend. */
    private class Electrode {
        val baseline = ArrayDeque<Float>()
        val trend = ArrayDeque<Float>()
        var rising = false
        /** Consecutive windows this electrode has been rising. */
        var risingWindows = 0
        fun level(): Float {
            if (baseline.size < MIN_BASELINE_WINDOWS) return Float.NaN
            val s = baseline.sorted(); val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
        }
        /** Judges [imp] against the level before it is added, so a fault cannot raise its own baseline. */
        fun update(imp: Float, absoluteBad: Boolean): Boolean {
            trend.addLast(imp); while (trend.size > TREND_WINDOWS) trend.removeFirst()
            val level = level()
            rising = level.isFinite() && level > 0f &&
                imp > level * (if (rising) RISE_CLEAR_FACTOR else RISE_FACTOR)
            risingWindows = if (rising) risingWindows + 1 else 0
            if (!rising && !absoluteBad && imp >= 0f) {
                baseline.addLast(imp); while (baseline.size > BASELINE_WINDOWS) baseline.removeFirst()
            }
            return rising
        }
        fun reset() { baseline.clear(); trend.clear(); rising = false; risingWindows = 0 }
    }
    private val afz = Electrode()
    private val fp2 = Electrode()

    private val windowSamples = sampleRateHz * EVALUATION_WINDOW_SECONDS

    private var count = 0
    private var sumImp1 = 0.0
    private var sumImp2 = 0.0
    private var sumAfz = 0.0
    private var sumFp2 = 0.0

    private var stableState = State.CHECKING
    private var pendingState = State.CHECKING
    private var pendingWindows = 0
    private var severity = Severity.NONE
    private var severityKey = ""
    private var severityProblem = ""
    private var pendingSeverity = Severity.NONE
    private var pendingKey = ""
    private var pendingSeverityWindows = 0

    fun reset() {
        count = 0
        sumImp1 = 0.0
        sumImp2 = 0.0
        sumAfz = 0.0
        sumFp2 = 0.0
        stableState = State.CHECKING
        pendingState = State.CHECKING
        pendingWindows = 0
        severity = Severity.NONE; severityKey = ""; severityProblem = ""
        pendingSeverity = Severity.NONE; pendingKey = ""; pendingSeverityWindows = 0
        afz.reset()
        fp2.reset()
    }

    fun add(
        impAfzOhms: Float,
        impFp2Ohms: Float,
        afzVolts: Float,
        fp2Volts: Float
    ): Snapshot? {
        if (!impAfzOhms.isFinite() || !impFp2Ohms.isFinite() ||
            !afzVolts.isFinite() || !fp2Volts.isFinite()) return null

        sumImp1 += impAfzOhms.toDouble()
        sumImp2 += impFp2Ohms.toDouble()
        sumAfz += afzVolts.toDouble()
        sumFp2 += fp2Volts.toDouble()
        count++

        if (count < windowSamples) return null

        val imp1 = (sumImp1 / count).toFloat()
        val imp2 = (sumImp2 / count).toFloat()
        val afzDc = (sumAfz / count).toFloat()
        val fp2Dc = (sumFp2 / count).toFloat()

        count = 0
        sumImp1 = 0.0
        sumImp2 = 0.0
        sumAfz = 0.0
        sumFp2 = 0.0

        // Judge contact on the clamped values; the snapshot keeps the averages as measured.
        val contact1 = contactOhms(imp1)
        val contact2 = contactOhms(imp2)
        val candidate = classify(contact1, contact2, afzDc, fp2Dc)
        val levelAfz = afz.level()
        val levelFp2 = fp2.level()
        val required = if (candidate == State.GOOD) GOOD_CONFIRM_WINDOWS else BAD_CONFIRM_WINDOWS

        if (candidate != pendingState) {
            pendingState = candidate
            pendingWindows = 1
        } else {
            pendingWindows++
        }

        if (pendingWindows >= required) {
            stableState = candidate
        }

        // Severity: judged after classify() has updated each electrode, confirmed over two windows like the state.
        // The level and the electrodes involved (the key) decide a change; the wording carries live numbers.
        val (sev, key, problem) = severityOf(contact1, contact2, afzDc, fp2Dc, levelAfz, levelFp2)
        if (sev == pendingSeverity && key == pendingKey) pendingSeverityWindows++
        else { pendingSeverity = sev; pendingKey = key; pendingSeverityWindows = 1 }
        if (pendingSeverityWindows >= BAD_CONFIRM_WINDOWS) { severity = sev; severityKey = key }
        if (severity == sev && severityKey == key) severityProblem = problem

        return Snapshot(stableState, imp1, imp2, afzDc, fp2Dc, levelAfz, levelFp2, afz.rising, fp2.rising,
            afz.trend.toFloatArray(), fp2.trend.toFloatArray(), severity, severityProblem, severityKey)
    }

    private fun contactOhms(ohms: Float): Float =
        if (ohms < 0f && ohms >= NEGATIVE_FLOOR_OHMS) 0f else ohms

    /** The worst alert this window and a short description naming the electrode(s). */
    private fun severityOf(imp1: Float, imp2: Float, afzDc: Float, fp2Dc: Float, levelAfz: Float, levelFp2: Float): Triple<Severity, String, String> {
        fun kohm(v: Float) = if (v.isFinite()) "${kotlin.math.round(v / 1000f).toInt()} kΩ" else "?"
        fun mv(v: Float) = "${kotlin.math.round(v * 1000f).toInt()} mV"
        val dcLimit = if (severity == Severity.RED && "dc" in severityKey) DC_RED_CLEAR_VOLTS else DC_RED_VOLTS
        val red = ArrayList<String>(); val redKey = ArrayList<String>()
        if (abs(afzDc) > dcLimit || abs(fp2Dc) > dcLimit) {
            val worst = if (abs(afzDc) >= abs(fp2Dc)) "AFz ${mv(afzDc)}" else "Fp2 ${mv(fp2Dc)}"
            red += "DC offset beyond ±${(DC_RED_VOLTS * 1000).toInt()} mV ($worst): reference or contact"; redKey += "dc"
        }
        if (imp1 >= 0.99f * IMPEDANCE_RAIL_OHMS) { red += "AFz impedance at the top of its range"; redKey += "afz-rail" }
        if (imp2 >= 0.99f * IMPEDANCE_RAIL_OHMS) { red += "Fp2 impedance at the top of its range"; redKey += "fp2-rail" }
        if (red.isNotEmpty()) return Triple(Severity.RED, redKey.joinToString(","), red.joinToString("; "))
        val amber = ArrayList<String>(); val amberKey = ArrayList<String>()
        fun electrode(name: String, imp: Float, level: Float, e: Electrode) {
            if (imp > IMPEDANCE_WARN_OHMS) { amber += "$name impedance ${kohm(imp)}"; amberKey += name }
            else if (e.risingWindows >= RISE_SUSTAIN_WINDOWS) { amber += "$name impedance ${kohm(imp)}, was ${kohm(level)}"; amberKey += name }
        }
        electrode("AFz", imp1, levelAfz, afz); electrode("Fp2", imp2, levelFp2, fp2)
        return if (amber.isEmpty()) Triple(Severity.NONE, "", "") else Triple(Severity.AMBER, amberKey.joinToString(","), amber.joinToString("; "))
    }

    private fun classify(imp1: Float, imp2: Float, afzDc: Float, fp2Dc: Float): State {
        val dcLimit = if (stableState == State.REFERENCE_OR_CONTACT_BAD) EEG_DC_CLEAR_VOLTS else EEG_DC_WARN_VOLTS
        val dcBad = abs(afzDc) > dcLimit || abs(fp2Dc) > dcLimit

        val impLimit = if (stableState in setOf(State.AFZ_BAD, State.FP2_BAD, State.BOTH_BAD)) {
            IMPEDANCE_CLEAR_OHMS
        } else {
            IMPEDANCE_WARN_OHMS
        }

        // Both electrodes are updated every window (trend and recent level), whatever the outcome.
        val afzAbsolute = imp1 < 0f || imp1 > impLimit
        val fp2Absolute = imp2 < 0f || imp2 > impLimit
        val afzRising = afz.update(imp1, afzAbsolute || dcBad)
        val fp2Rising = fp2.update(imp2, fp2Absolute || dcBad)

        if (dcBad) return State.REFERENCE_OR_CONTACT_BAD
        val afzBad = afzAbsolute || afzRising
        val fp2Bad = fp2Absolute || fp2Rising

        return when {
            afzBad && fp2Bad -> State.BOTH_BAD
            afzBad -> State.AFZ_BAD
            fp2Bad -> State.FP2_BAD
            else -> State.GOOD
        }
    }
}