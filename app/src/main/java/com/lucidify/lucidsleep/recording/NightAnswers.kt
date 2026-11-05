package com.lucidify.lucidsleep.recording

import android.content.Context
import com.lucidify.lucidsleep.core.cloudrecording.CloudSyncScheduler
import com.lucidify.lucidsleep.core.cloudrecording.DurableCloudSessionStore
import com.lucidify.lucidsleep.core.cloudrecording.DurableSessionMarker
import org.json.JSONObject
import java.util.UUID

/**
 * What the sleeper tells us about each night, saved as markers on that night's recording.
 * Markers upload with the night to Lucidify Cloud through the same session events the
 * clinical app uses, so they show on the night's timeline there.
 *
 *  BEDTIME_CHECKIN  answers about the day, given before the ready check; placed at the
 *                   night's first sample when recording starts
 *  AWAKE            an "I'm awake" tap during the night
 *  AWAKE_REASON     what woke them, at the same moment (its note names the AWAKE marker)
 *  MORNING_DIARY    the morning questions; placed at the night's last sample
 *
 * Answers are JSON in the marker's note, e.g. {"v":1,"naps":"None","stress":4}. A question
 * the sleeper skipped is left out.
 */
class NightAnswers(
	context: Context,
	private val store: DurableCloudSessionStore,
	private val log: NightLog
) {
	private val appContext = context.applicationContext
	private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	/** Tonight's bedtime answers, kept until the night starts. */
	fun saveBedtime(answers: JSONObject) {
		prefs.edit()
			.putString(KEY_BEDTIME, answers.toString())
			.putLong(KEY_BEDTIME_AT, System.currentTimeMillis())
			.apply()
		log.event("BEDTIME_SAVED", "fields" to answers.length())
	}

	/** Bedtime answers saved in the last few hours and not yet attached to a night. */
	fun pendingBedtime(): JSONObject? {
		val at = prefs.getLong(KEY_BEDTIME_AT, 0L)
		if (at == 0L || System.currentTimeMillis() - at > BEDTIME_FRESH_MS) return null
		return prefs.getString(KEY_BEDTIME, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
	}

	/** As a night starts: attaches tonight's bedtime answers at its first sample. */
	fun attachBedtime(localSessionId: String, nightStartedAtEpochMs: Long) {
		val answers = pendingBedtime()
		prefs.edit().remove(KEY_BEDTIME).remove(KEY_BEDTIME_AT).apply()
		if (answers == null) return
		add(localSessionId, "BEDTIME_CHECKIN", "Bedtime check-in", answers.toString(), 0L, 0L, nightStartedAtEpochMs)
	}

	/** "I'm awake": returns the marker's id, so a reason can point at it. */
	fun awake(localSessionId: String, sampleIndex: Long, tOffsetMs: Long, atEpochMs: Long): String? =
		add(localSessionId, "AWAKE", "I'm awake", null, sampleIndex, tOffsetMs, atEpochMs)

	fun awakeReason(localSessionId: String, awakeEventId: String, reason: String, sampleIndex: Long, tOffsetMs: Long, atEpochMs: Long) {
		val note = JSONObject().put("v", 1).put("awakeEvent", awakeEventId).put("reason", reason).toString()
		add(localSessionId, "AWAKE_REASON", reason, note, sampleIndex, tOffsetMs, atEpochMs)
	}

	/**
	 * When a night stops: keeps its folder on the phone after it finishes uploading, so the
	 * morning diary can still be attached to it. Released by [saveDiary] or [skipDiary].
	 */
	fun holdForDiary(localSessionId: String) {
		runCatching { store.holdCleanup(localSessionId, System.currentTimeMillis() + DIARY_HOLD_MS) }
			.onFailure { log.event("DIARY_HOLD_FAILED", "error" to it.message?.take(120)) }
	}

	/** Attaches the morning diary at the night's last sample. False if the night is gone. */
	fun saveDiary(localSessionId: String, recordedMs: Long, answers: JSONObject): Boolean {
		val sampleIndex = recordedMs * NightRecorder.SAMPLE_RATE_HZ / 1000L
		val ok = add(localSessionId, "MORNING_DIARY", "Morning diary", answers.toString(),
			sampleIndex, recordedMs, System.currentTimeMillis()) != null
		runCatching { store.releaseCleanupHold(localSessionId) }
		CloudSyncScheduler.enqueueImmediate(appContext)
		return ok
	}

	fun skipDiary(localSessionId: String) {
		runCatching { store.releaseCleanupHold(localSessionId) }
		log.event("DIARY_SKIPPED")
	}

	private fun add(
		localSessionId: String,
		code: String,
		label: String,
		note: String?,
		sampleIndex: Long,
		tOffsetMs: Long,
		atEpochMs: Long
	): String? = runCatching {
		val id = "ls-" + UUID.randomUUID()
		store.appendMarker(
			localSessionId,
			DurableSessionMarker(
				clientEventId = id,
				kind = "PRESET",
				code = code,
				label = label,
				note = note,
				sampleIndex = sampleIndex.coerceAtLeast(0L),
				tOffsetMs = tOffsetMs.coerceAtLeast(0L),
				wallClockEpochMs = atEpochMs,
				createdAtEpochMs = System.currentTimeMillis()
			)
		)
		log.event("MARKER", "code" to code)
		id
	}.onFailure { log.event("MARKER_FAILED", "code" to code, "error" to it.message?.take(120)) }.getOrNull()

	companion object {
		private const val PREFS = "lucidsleep_answers"
		private const val KEY_BEDTIME = "bedtime_json"
		private const val KEY_BEDTIME_AT = "bedtime_at"
		/** Bedtime answers older than this belong to an evening that never started a night. */
		private const val BEDTIME_FRESH_MS = 6L * 3600_000L
		/** How long a finished night waits on the phone for its morning diary. */
		private const val DIARY_HOLD_MS = 36L * 3600_000L
		/** The longest free-text note the diary accepts (the server allows 2000 for the whole note). */
		const val MAX_NOTE_CHARS = 1000
	}
}