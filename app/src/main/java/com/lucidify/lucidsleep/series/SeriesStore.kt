package com.lucidify.lucidsleep.series

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The sleeper's series of nights, kept on the phone until org-service has series tables.
 *
 * Rule from the planning doc: a series needs [Progress.required] valid nights (5 by
 * default). A night counts when at least [MIN_VALID_NIGHT_MS] of EEG was recorded; a
 * shorter night is marked SHORT and simply adds one more night to the schedule.
 */
class SeriesStore(context: Context) {

	enum class Status { RECORDING, COUNTED, SHORT }

	data class Night(
		val localSessionId: String,
		val startedAtEpochMs: Long,
		val endedAtEpochMs: Long?,
		val recordedMs: Long,
		val status: Status,
		/** True once the sleeper has seen the Good morning summary for this night. */
		val reviewed: Boolean
	)

	data class Progress(
		val required: Int,
		val counted: Int,
		val shortNights: Int,
		val recording: Boolean
	) {
		val complete: Boolean get() = counted >= required
		/** The night number shown on Home ("Night 3 of 5"). */
		val nextNightNumber: Int get() = (counted + 1).coerceAtMost(required)
	}

	private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
	private val _nights = MutableStateFlow(load())
	val nights: StateFlow<List<Night>> = _nights.asStateFlow()

	fun progress(required: Int): Progress {
		val list = _nights.value
		return Progress(
			required = required,
			counted = list.count { it.status == Status.COUNTED },
			shortNights = list.count { it.status == Status.SHORT },
			recording = list.any { it.status == Status.RECORDING }
		)
	}

	@Synchronized
	fun nightStarted(localSessionId: String, startedAtEpochMs: Long) {
		val others = _nights.value.filterNot { it.localSessionId == localSessionId }
		save(others + Night(localSessionId, startedAtEpochMs, null, 0L, Status.RECORDING, reviewed = false))
	}

	@Synchronized
	fun nightEnded(localSessionId: String, endedAtEpochMs: Long, recordedMs: Long) {
		val status = if (recordedMs >= MIN_VALID_NIGHT_MS) Status.COUNTED else Status.SHORT
		val list = _nights.value
		val existing = list.firstOrNull { it.localSessionId == localSessionId }
		val ended = (existing ?: Night(localSessionId, endedAtEpochMs - recordedMs, null, 0L, status, false))
			.copy(endedAtEpochMs = endedAtEpochMs, recordedMs = recordedMs, status = status, reviewed = false)
		save(list.filterNot { it.localSessionId == localSessionId } + ended)
	}

	/** The most recent finished night the sleeper has not reviewed yet, if any. */
	fun unreviewedNight(): Night? =
		_nights.value.filter { it.status != Status.RECORDING && !it.reviewed }.maxByOrNull { it.startedAtEpochMs }

	@Synchronized
	fun markReviewed(localSessionId: String) {
		save(_nights.value.map { if (it.localSessionId == localSessionId) it.copy(reviewed = true) else it })
	}

	/** Starts a new series (for example the re-measure after an intervention). */
	@Synchronized
	fun reset() = save(emptyList())

	private fun save(list: List<Night>) {
		val sorted = list.sortedBy { it.startedAtEpochMs }
		val arr = JSONArray()
		for (n in sorted) {
			arr.put(
				JSONObject()
					.put("id", n.localSessionId)
					.put("startedAt", n.startedAtEpochMs)
					.put("endedAt", n.endedAtEpochMs ?: -1L)
					.put("recordedMs", n.recordedMs)
					.put("status", n.status.name)
					.put("reviewed", n.reviewed)
			)
		}
		prefs.edit().putString(KEY_NIGHTS, arr.toString()).apply()
		_nights.value = sorted
	}

	private fun load(): List<Night> = runCatching {
		val arr = JSONArray(prefs.getString(KEY_NIGHTS, "[]"))
		(0 until arr.length()).map { i ->
			val o = arr.getJSONObject(i)
			Night(
				localSessionId = o.getString("id"),
				startedAtEpochMs = o.getLong("startedAt"),
				endedAtEpochMs = o.optLong("endedAt", -1L).takeIf { it > 0L },
				recordedMs = o.optLong("recordedMs", 0L),
				status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.SHORT),
				reviewed = o.optBoolean("reviewed", true)
			)
		}
	}.getOrDefault(emptyList())

	companion object {
		private const val PREFS = "lucidsleep_series"
		private const val KEY_NIGHTS = "nights"
		/** Provisional: 4 hours of recorded EEG makes a night count. */
		const val MIN_VALID_NIGHT_MS = 4L * 60L * 60L * 1000L
	}
}