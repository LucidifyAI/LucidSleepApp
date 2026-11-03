package com.lucidify.lucidsleep.recording

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

/**
 * One plain-text log per night, plus an app-level log for events outside a night.
 *
 * Every line is "<UTC time> <EVENT> key=value ...". The files hold no EEG and no
 * personal data: only link, recording and upload events, so Send diagnostics can
 * share them as they are. Writes happen on their own thread and never block
 * packet handling.
 */
class NightLog(context: Context) {
	private val dir = File(context.filesDir, "night_logs").apply { mkdirs() }
	private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "lucidsleep-log") }

	@Volatile
	private var currentNightId: String? = null

	fun beginNight(localSessionId: String) {
		currentNightId = localSessionId
	}

	fun endNight() {
		currentNightId = null
	}

	fun event(name: String, vararg fields: Pair<String, Any?>) {
		val line = buildString {
			append(Instant.now().toString()).append(' ').append(name)
			for ((k, v) in fields) append(' ').append(k).append('=').append(v)
			append('\n')
		}
		Log.i(TAG, line.trimEnd())
		val target = currentNightId?.let { File(dir, "$it.log") } ?: File(dir, APP_LOG)
		writer.execute {
			runCatching {
				target.appendText(line)
				if (target.length() > MAX_BYTES) {
					target.writeText(target.readText().takeLast((MAX_BYTES / 2).toInt()))
				}
			}
		}
	}

	/** Log files, newest first, for Send diagnostics. */
	fun files(): List<File> =
		dir.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }

	companion object {
		private const val TAG = "NightLog"
		private const val APP_LOG = "app_events.log"
		private const val MAX_BYTES = 2L * 1024L * 1024L
	}
}