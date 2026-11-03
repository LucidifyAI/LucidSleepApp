package com.lucidify.lucidsleep.core.cloudrecording

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Produces a read-only health snapshot for the device-side clinical recording spool.
 *
 * This class never deletes or mutates recording data. Its output is suitable for UI,
 * logging, support diagnostics, and deciding whether a new recording can safely begin.
 */
class CloudRecordingHealthMonitor(
	context: Context,
	private val store: DurableCloudSessionStore
) {
	private val filesDir = context.filesDir
	private val spoolRoot = File(filesDir, "cloud_recording_spool")
	private val backupRoot = File(filesDir, "cloud_recording_backups")
	private val workRoot = File(filesDir, "cloud_recording_work")

	fun snapshot(nowEpochMs: Long = System.currentTimeMillis()): CloudRecordingHealth {
		val stat = StatFs(filesDir.absolutePath)
		val availableBytes = stat.availableBytes
		val totalBytes = stat.totalBytes
		val sessions = store.listSessions()
		val recoverable = sessions.filter { session ->
			session.syncEnabled &&
				session.state != CloudSessionState.FINALIZED &&
				session.state != CloudSessionState.ABANDONED
		}
		val chunks = sessions.flatMap { store.listChunks(it.localSessionId) }
		val cloudSessionIds = sessions.filter { it.syncEnabled }.map { it.localSessionId }.toSet()
		val pending = chunks.filter { it.localSessionId in cloudSessionIds && it.state != ChunkUploadState.VERIFIED }
		val permanentFailures = chunks.count { it.localSessionId in cloudSessionIds && it.state == ChunkUploadState.FAILED_PERMANENT }
		val pendingBytes = pending.sumOf { it.bytes.coerceAtLeast(0L) }
		val verifiedRetainedBytes = chunks
			.filter { it.state == ChunkUploadState.VERIFIED }
			.sumOf { chunk ->
				val payload = store.payloadFile(chunk)
				if (payload.isFile) payload.length() else 0L
			}
		val oldestPendingEpochMs = pending.minOfOrNull { chunk ->
			sessions.firstOrNull { it.localSessionId == chunk.localSessionId }?.startedAtEpochMs
				?: nowEpochMs
		}
		val oldestPendingAgeMs = oldestPendingEpochMs?.let { (nowEpochMs - it).coerceAtLeast(0L) }
		val estimatedBytesPerHour = estimateBytesPerRecordingHour(sessions)
		val estimatedRecordingHoursRemaining = if (estimatedBytesPerHour > 0L) {
			availableBytes.toDouble() / estimatedBytesPerHour.toDouble()
		} else {
			Double.POSITIVE_INFINITY
		}

		val severity = when {
			availableBytes < CRITICAL_FREE_BYTES -> CloudRecordingHealthSeverity.CRITICAL
			permanentFailures > 0 -> CloudRecordingHealthSeverity.ERROR
			availableBytes < WARNING_FREE_BYTES -> CloudRecordingHealthSeverity.WARNING
			oldestPendingAgeMs != null && oldestPendingAgeMs >= STALE_BACKLOG_MS ->
				CloudRecordingHealthSeverity.WARNING
			else -> CloudRecordingHealthSeverity.HEALTHY
		}

		return CloudRecordingHealth(
			checkedAtEpochMs = nowEpochMs,
			severity = severity,
			availableBytes = availableBytes,
			totalBytes = totalBytes,
			spoolBytes = directoryBytes(spoolRoot),
			backupBytes = directoryBytes(backupRoot),
			workingBytes = directoryBytes(workRoot),
			pendingUploadBytes = pendingBytes,
			verifiedRetainedBytes = verifiedRetainedBytes,
			totalSessions = sessions.size,
			recoverableSessions = recoverable.size,
			pendingChunks = pending.size,
			permanentFailureChunks = permanentFailures,
			oldestPendingAgeMs = oldestPendingAgeMs,
			estimatedRecordingHoursRemaining = estimatedRecordingHoursRemaining,
			canStartNewRecording = availableBytes >= MINIMUM_START_FREE_BYTES
		)
	}

	private fun estimateBytesPerRecordingHour(sessions: List<DurableCloudSession>): Long {
		val sampleRate = sessions.maxOfOrNull { it.sampleRateHz }?.coerceAtLeast(1) ?: 500
		// Current CGX packets are 36 bytes per sample/frame. During acquisition the app keeps
		// both the durable chunk spool and the continuous raw backup, so budget for two copies.
		return sampleRate.toLong() * CGX_PACKET_BYTES * 3_600L * ACTIVE_LOCAL_COPIES
	}

	private fun directoryBytes(root: File): Long {
		if (!root.exists()) return 0L
		return root.walkTopDown()
			.filter { it.isFile }
			.fold(0L) { total, file -> total + file.length() }
	}

	companion object {
		const val CGX_PACKET_BYTES = 36L
		const val ACTIVE_LOCAL_COPIES = 2L
		const val MINIMUM_START_FREE_BYTES = 512L * 1024L * 1024L
		const val WARNING_FREE_BYTES = 1024L * 1024L * 1024L
		const val CRITICAL_FREE_BYTES = 256L * 1024L * 1024L
		const val STALE_BACKLOG_MS = 6L * 60L * 60L * 1000L
	}
}

data class CloudRecordingHealth(
	val checkedAtEpochMs: Long,
	val severity: CloudRecordingHealthSeverity,
	val availableBytes: Long,
	val totalBytes: Long,
	val spoolBytes: Long,
	val backupBytes: Long,
	val workingBytes: Long,
	val pendingUploadBytes: Long,
	val verifiedRetainedBytes: Long,
	val totalSessions: Int,
	val recoverableSessions: Int,
	val pendingChunks: Int,
	val permanentFailureChunks: Int,
	val oldestPendingAgeMs: Long?,
	val estimatedRecordingHoursRemaining: Double,
	val canStartNewRecording: Boolean
) {
	companion object {
		val UNKNOWN = CloudRecordingHealth(
			checkedAtEpochMs = 0L,
			severity = CloudRecordingHealthSeverity.UNKNOWN,
			availableBytes = 0L,
			totalBytes = 0L,
			spoolBytes = 0L,
			backupBytes = 0L,
			workingBytes = 0L,
			pendingUploadBytes = 0L,
			verifiedRetainedBytes = 0L,
			totalSessions = 0,
			recoverableSessions = 0,
			pendingChunks = 0,
			permanentFailureChunks = 0,
			oldestPendingAgeMs = null,
			estimatedRecordingHoursRemaining = 0.0,
			canStartNewRecording = false
		)
	}
}

enum class CloudRecordingHealthSeverity {
	UNKNOWN,
	HEALTHY,
	WARNING,
	ERROR,
	CRITICAL
}