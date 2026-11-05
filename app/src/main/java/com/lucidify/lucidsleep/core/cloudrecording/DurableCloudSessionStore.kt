package com.lucidify.lucidsleep.core.cloudrecording

import android.content.Context
import android.util.Base64
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable, dependency-free storage for cloud recording sessions and their chunks.
 *
 * This class deliberately stores metadata as atomic JSON files beside immutable
 * chunk payloads. It does not perform network I/O. A later synchronization layer
 * can query recoverable sessions and pending chunks, then update their state as
 * cloud operations succeed or fail.
 *
 * Directory layout:
 *
 * files/cloud_recording_spool/<localSessionId>/
 *     session.json
 *     chunks/
 *         00000000.cgxchunk
 *         00000000.upload.json
 *         00000001.cgxchunk
 *         00000001.upload.json
 */
class DurableCloudSessionStore(context: Context) {

	private val cipher = ClinicalDataCipher()
	private val fileLocks = ConcurrentHashMap<String, Any>()
	private val filesDir = context.filesDir
	private val rootDir = File(filesDir, ROOT_DIRECTORY).apply {
		if (!exists() && !mkdirs()) {
			throw IllegalStateException("Unable to create cloud recording spool: $absolutePath")
		}
	}
	private val backupRootDir = File(filesDir, BACKUP_ROOT_DIRECTORY)
	private val workRootDir = File(filesDir, WORK_ROOT_DIRECTORY)

	fun createSession(
		orgId: String,
		subjectId: String,
		sampleRateHz: Int,
		channels: List<String>,
		chunkSeconds: Int,
		localSessionId: String = UUID.randomUUID().toString(),
		startedAtEpochMs: Long = System.currentTimeMillis(),
		startedAtElapsedRealtimeMs: Long? = null,
		syncEnabled: Boolean = true,
		localPatientId: String? = null
	): DurableCloudSession {
		if (syncEnabled) {
			require(orgId.isNotBlank()) { "orgId must not be blank for cloud synchronization" }
			require(subjectId.isNotBlank()) { "subjectId must not be blank for cloud synchronization" }
		}
		if (!syncEnabled) require(!localPatientId.isNullOrBlank()) { "localPatientId is required for local-only recording" }
		require(sampleRateHz > 0) { "sampleRateHz must be positive" }
		require(channels.isNotEmpty()) { "channels must not be empty" }
		require(chunkSeconds > 0) { "chunkSeconds must be positive" }
		require(localSessionId.isNotBlank()) { "localSessionId must not be blank" }

		val sessionFile = sessionMetadataFile(localSessionId)
		return withFileLock(sessionFile) {
			val sessionDir = sessionDirectory(localSessionId)
			if (sessionDir.exists()) {
				throw IllegalStateException("Session already exists: $localSessionId")
			}
			if (!sessionDir.mkdirs()) {
				throw IllegalStateException("Unable to create session directory: ${sessionDir.absolutePath}")
			}
			val chunksDir = File(sessionDir, CHUNKS_DIRECTORY)
			if (!chunksDir.mkdirs()) {
				throw IllegalStateException("Unable to create chunks directory: ${chunksDir.absolutePath}")
			}

			val session = DurableCloudSession(
			localSessionId = localSessionId,
			cloudSessionId = null,
			syncEnabled = syncEnabled,
			localPatientId = localPatientId,
			orgId = orgId,
			subjectId = subjectId,
			sampleRateHz = sampleRateHz,
			channels = channels.toList(),
			chunkSeconds = chunkSeconds,
			startedAtEpochMs = startedAtEpochMs,
			startedAtElapsedRealtimeMs = startedAtElapsedRealtimeMs,
			endedAtEpochMs = null,
			state = CloudSessionState.LOCAL_ONLY,
			finalSeqExclusive = null,
			lastError = null,
			eligibleForCleanupAtEpochMs = null,
			cleanupStartedAtEpochMs = null,
			createdAtEpochMs = System.currentTimeMillis(),
			updatedAtEpochMs = System.currentTimeMillis()
		)
			writeSessionUnlocked(session)
			session
		}
	}

	fun loadSession(localSessionId: String): DurableCloudSession? {
		val file = sessionMetadataFile(localSessionId)
		if (!file.isFile) return null
		return parseSession(readJson(file))
	}

	fun listSessions(): List<DurableCloudSession> =
		rootDir.listFiles()
			.orEmpty()
			.asSequence()
			.filter { it.isDirectory }
			.mapNotNull { dir ->
				runCatching {
					val file = File(dir, SESSION_METADATA_FILENAME)
					if (file.isFile) parseSession(readJson(file)) else null
				}.getOrNull()
			}
			.sortedBy { it.startedAtEpochMs }
			.toList()

	fun listRecoverableSessions(): List<DurableCloudSession> =
		listSessions().filter { session ->
			// A FINALIZED session stays recoverable while any clinical marker is
			// still waiting for Cloud acknowledgement. Its EEG is complete; only the
			// marker sync remains.
			session.syncEnabled &&
				session.state != CloudSessionState.ABANDONED &&
				(session.state != CloudSessionState.FINALIZED || hasUnsyncedMarkers(session.localSessionId))
		}

	fun bindCloudSession(
		localSessionId: String,
		cloudSessionId: String,
		cloudStartedAtEpochMs: Long? = null
	): DurableCloudSession {
		require(cloudSessionId.isNotBlank()) { "cloudSessionId must not be blank" }
		return updateSession(localSessionId) { current ->
			if (current.cloudSessionId != null && current.cloudSessionId != cloudSessionId) {
				throw IllegalStateException(
					"Session $localSessionId is already bound to ${current.cloudSessionId}"
				)
			}
			current.copy(
				cloudSessionId = cloudSessionId,
				startedAtEpochMs = cloudStartedAtEpochMs ?: current.startedAtEpochMs,
				state = CloudSessionState.ACTIVE,
				lastError = null,
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}
	}

	fun markSessionActive(localSessionId: String): DurableCloudSession =
		updateSession(localSessionId) {
			it.copy(
				state = CloudSessionState.ACTIVE,
				lastError = null,
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}

	/**
	 * Reopens an interrupted recording without finalizing it. This is used only
	 * for explicit Resume. Existing verified/local chunks remain untouched.
	 */
	fun reopenSession(localSessionId: String): DurableCloudSession =
		updateSession(localSessionId) {
			it.copy(
				state = CloudSessionState.ACTIVE,
				endedAtEpochMs = null,
				finalSeqExclusive = null,
				lastError = null,
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}

	fun markSessionStopping(
		localSessionId: String,
		finalSeqExclusive: Int,
		endedAtEpochMs: Long = System.currentTimeMillis()
	): DurableCloudSession {
		require(finalSeqExclusive >= 0) { "finalSeqExclusive must not be negative" }
		return updateSession(localSessionId) {
			it.copy(
				state = CloudSessionState.STOPPING,
				finalSeqExclusive = finalSeqExclusive,
				endedAtEpochMs = endedAtEpochMs,
				lastError = null,
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}
	}

	fun markLocalSessionComplete(
		localSessionId: String,
		finalSeqExclusive: Int,
		endedAtEpochMs: Long = System.currentTimeMillis()
	): DurableCloudSession {
		require(finalSeqExclusive >= 0) { "finalSeqExclusive must not be negative" }
		return updateSession(localSessionId) { current ->
			require(!current.syncEnabled) { "Cloud-synchronized session cannot be completed as local-only" }
			current.copy(
				state = CloudSessionState.LOCAL_COMPLETE,
				finalSeqExclusive = finalSeqExclusive,
				endedAtEpochMs = endedAtEpochMs,
				lastError = null,
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}
	}

	/**
	 * Explicitly promotes a completed LOCAL recording into the normal cloud-sync
	 * pipeline. This is only called after the researcher presses Upload to Cloud.
	 * The original localPatientId is retained so verified chunks remain available
	 * on-device until the researcher explicitly deletes the local copy.
	 */
	fun promoteLocalSessionToCloud(
		localSessionId: String,
		orgId: String,
		subjectId: String
	): DurableCloudSession {
		require(orgId.isNotBlank()) { "orgId must not be blank" }
		require(subjectId.isNotBlank()) { "subjectId must not be blank" }
		val promoted = updateSession(localSessionId) { current ->
			require(!current.syncEnabled) { "Recording is already configured for cloud synchronization" }
			require(!current.localPatientId.isNullOrBlank()) { "Recording is not a LOCAL clinical recording" }
			require(current.finalSeqExclusive != null) { "Recording must be stopped before upload" }
			require(current.state == CloudSessionState.LOCAL_COMPLETE || current.state == CloudSessionState.ERROR) {
				"Recording is not ready for upload"
			}
			current.copy(
				syncEnabled = true,
				orgId = orgId,
				subjectId = subjectId,
				state = CloudSessionState.STOPPING,
				lastError = null,
				eligibleForCleanupAtEpochMs = null,
				cleanupStartedAtEpochMs = null,
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}
		for (chunk in listChunks(localSessionId)) {
			if (chunk.state == ChunkUploadState.LOCAL_ONLY || chunk.state == ChunkUploadState.FAILED_PERMANENT) {
				markChunkQueued(localSessionId, chunk.seq)
			}
		}
		return promoted
	}

	/**
	 * Persists the intent to finalize after every locally-known chunk has been
	 * cloud-verified. This state is deliberately durable: a network failure, app
	 * restart, or ambiguous finalize response must not turn a stopped recording
	 * back into a generic ERROR state or cause the finalize request to be forgotten.
	 */
	fun markSessionFinalizationPending(
		localSessionId: String,
		message: String? = null
	): DurableCloudSession {
		val unverified = listUnverifiedChunks(localSessionId)
		if (unverified.isNotEmpty()) {
			throw IllegalStateException(
				"Session $localSessionId still has ${unverified.size} unverified chunks"
			)
		}
		return updateSession(localSessionId) { current ->
			if (current.state == CloudSessionState.FINALIZED) return@updateSession current
			require(current.finalSeqExclusive != null) {
				"Session $localSessionId has no persisted final sequence"
			}
			current.copy(
				state = CloudSessionState.FINALIZATION_PENDING,
				lastError = message?.take(MAX_ERROR_LENGTH),
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}
	}

	/** Backward-compatible wrapper for older callers/state-machine terminology. */
	fun markSessionAwaitingFinalization(localSessionId: String): DurableCloudSession =
		markSessionFinalizationPending(localSessionId)

	/**
	 * Records an unsuccessful/ambiguous finalize attempt while preserving the
	 * durable finalization intent. Future foreground and WorkManager sync passes
	 * will resubmit the same idempotent finalize request until cloud readback
	 * confirms the manifest is finalized.
	 */
	fun markSessionFinalizationRetry(localSessionId: String, message: String): DurableCloudSession =
		updateSession(localSessionId) { current ->
			if (current.state == CloudSessionState.FINALIZED) return@updateSession current
			require(current.finalSeqExclusive != null) {
				"Session $localSessionId has no persisted final sequence"
			}
			current.copy(
				state = CloudSessionState.FINALIZATION_PENDING,
				lastError = message.take(MAX_ERROR_LENGTH),
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}

	fun markSessionFinalized(
		localSessionId: String,
		retentionMs: Long = DEFAULT_FINALIZED_RETENTION_MS
	): DurableCloudSession {
		require(retentionMs >= 0L) { "retentionMs must not be negative" }
		val now = System.currentTimeMillis()
		return updateSession(localSessionId) { current ->
			current.copy(
				state = CloudSessionState.FINALIZED,
				eligibleForCleanupAtEpochMs = current.eligibleForCleanupAtEpochMs
					?: (now + retentionMs),
				lastError = null,
				updatedAtEpochMs = now
			)
		}
	}

	/**
	 * Deletes device-side clinical data only after the session is finalized, its
	 * retention deadline has passed, and the local metadata proves that every
	 * expected sequence is cloud-verified. Deletion is deliberately resumable:
	 * chunk payloads and sidecars are removed first, and session.json is removed
	 * last so an interrupted cleanup can safely continue on the next pass.
	 */
	fun cleanupExpiredSessions(
		nowEpochMs: Long = System.currentTimeMillis(),
		retentionMsForLegacyFinalized: Long = DEFAULT_FINALIZED_RETENTION_MS
	): CloudSessionCleanupReport {
		require(retentionMsForLegacyFinalized >= 0L) {
			"retentionMsForLegacyFinalized must not be negative"
		}

		var scheduled = 0
		var deleted = 0
		var skippedNotReady = 0
		var failed = 0
		var bytesFreed = 0L

		for (snapshot in listSessions()) {
			if (snapshot.state != CloudSessionState.FINALIZED) continue
			// Explicit LOCAL recordings stay on the tablet until the researcher
			// chooses Delete local copy from Device Recordings.
			if (snapshot.localPatientId != null) continue
			// Markers live beside the spool. Never delete a session while any marker
			// is still waiting for Cloud acknowledgement.
			if (hasUnsyncedMarkers(snapshot.localSessionId)) {
				skippedNotReady++
				continue
			}
			// LucidSleep keeps a finished night until its morning diary is answered or skipped.
			if (cleanupHeld(snapshot.localSessionId, nowEpochMs)) {
				skippedNotReady++
				continue
			}

			var session = snapshot
			if (session.eligibleForCleanupAtEpochMs == null) {
				val deadline = nowEpochMs + retentionMsForLegacyFinalized
				session = updateSession(session.localSessionId) { current ->
					if (current.state != CloudSessionState.FINALIZED ||
						current.eligibleForCleanupAtEpochMs != null
					) current
					else current.copy(
						eligibleForCleanupAtEpochMs = deadline,
						updatedAtEpochMs = nowEpochMs
					)
				}
				scheduled++
			}

			val deadline = session.eligibleForCleanupAtEpochMs ?: continue
			if (deadline > nowEpochMs) continue

			if (session.cleanupStartedAtEpochMs == null) {
				val chunks = listChunks(session.localSessionId)
				if (!isLocallyCompleteFinalizedSession(session, chunks)) {
					skippedNotReady++
					continue
				}
				// Persist the cleanup decision before deleting anything. If Android or
				// the process stops midway, the next pass resumes deletion without
				// requiring already-removed chunk sidecars to still be present.
				session = updateSession(session.localSessionId) { current ->
					if (current.state != CloudSessionState.FINALIZED) current
					else current.copy(
						cleanupStartedAtEpochMs = current.cleanupStartedAtEpochMs ?: nowEpochMs,
						updatedAtEpochMs = nowEpochMs
					)
				}
				if (session.cleanupStartedAtEpochMs == null) {
					skippedNotReady++
					continue
				}
			}

			val sessionDir = sessionDirectory(session.localSessionId)
			val backupDir = File(backupRootDir, session.localSessionId)
			val workDir = File(workRootDir, session.localSessionId)
			val before = directoryBytes(sessionDir) + directoryBytes(backupDir) + directoryBytes(workDir)

			try {
				// Delete chunk files before session.json. If the process dies midway,
				// the remaining session record allows a later cleanup pass to resume.
				val chunksDir = File(sessionDir, CHUNKS_DIRECTORY)
				deleteTreeContents(chunksDir)
				if (chunksDir.exists() && !chunksDir.delete()) {
					throw IllegalStateException("Unable to remove finalized chunk directory")
				}

				deleteTree(backupDir)
				deleteTree(workDir)

				val sessionFile = sessionMetadataFile(session.localSessionId)
				withFileLock(sessionFile) {
					val current = loadSession(session.localSessionId)
					if (current != null && current.state != CloudSessionState.FINALIZED) {
						throw IllegalStateException("Session changed state during cleanup")
					}
					if (sessionFile.exists() && !sessionFile.delete()) {
						throw IllegalStateException("Unable to remove finalized session metadata")
					}
				}
				deleteTreeContents(sessionDir)
				if (sessionDir.exists() && !sessionDir.delete()) {
					throw IllegalStateException("Unable to remove finalized session directory")
				}

				bytesFreed += before
				deleted++
			} catch (_: Throwable) {
				failed++
			}
		}

		return CloudSessionCleanupReport(
			scheduledLegacySessions = scheduled,
			deletedSessions = deleted,
			skippedNotReadySessions = skippedNotReady,
			failedSessions = failed,
			bytesFreed = bytesFreed
		)
	}

	private fun isLocallyCompleteFinalizedSession(
		session: DurableCloudSession,
		chunks: List<DurableCloudChunk>
	): Boolean {
		if (session.state != CloudSessionState.FINALIZED) return false
		val finalSeqExclusive = session.finalSeqExclusive ?: return false
		if (finalSeqExclusive < 0) return false
		// Verified chunks are retired immediately, so absence of historical local
		// sidecars is expected. Any remaining non-verified chunk still represents
		// unsatisfied cloud liability and blocks local session cleanup.
		return chunks.none { it.state != ChunkUploadState.VERIFIED }
	}

	private fun directoryBytes(root: File): Long {
		if (!root.exists()) return 0L
		return root.walkTopDown()
			.filter { it.isFile }
			.fold(0L) { total, file -> total + file.length() }
	}

	private fun deleteTreeContents(root: File) {
		if (!root.isDirectory) return
		root.listFiles().orEmpty().forEach { child ->
			if (child.isDirectory) deleteTree(child)
			else if (child.exists() && !child.delete()) {
				throw IllegalStateException("Unable to delete local clinical file")
			}
		}
	}

	private fun deleteTree(root: File) {
		if (!root.exists()) return
		if (root.isDirectory) deleteTreeContents(root)
		if (root.exists() && !root.delete()) {
			throw IllegalStateException("Unable to delete local clinical directory")
		}
	}

	fun markSessionError(localSessionId: String, message: String): DurableCloudSession =
		updateSession(localSessionId) {
			it.copy(
				state = CloudSessionState.ERROR,
				lastError = message.take(MAX_ERROR_LENGTH),
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}

	fun abandonSession(localSessionId: String, reason: String): DurableCloudSession =
		updateSession(localSessionId) {
			it.copy(
				state = CloudSessionState.ABANDONED,
				lastError = reason.take(MAX_ERROR_LENGTH),
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}

	/**
	 * Atomically installs a completed immutable chunk payload into the spool.
	 * The source file is copied, fsynced, checksummed, and then exposed by rename.
	 */
	fun registerChunk(
		localSessionId: String,
		seq: Int,
		firstSampleIndex: Long,
		sampleCount: Int,
		tStartMs: Long,
		tEndMs: Long,
		sourceFile: File,
		createdAtEpochMs: Long = System.currentTimeMillis()
	): DurableCloudChunk {
		require(seq >= 0) { "seq must not be negative" }
		require(firstSampleIndex >= 0L) { "firstSampleIndex must not be negative" }
		require(sampleCount > 0) { "sampleCount must be positive" }
		require(tStartMs >= 0L) { "tStartMs must not be negative" }
		require(tEndMs >= tStartMs) { "tEndMs must be greater than or equal to tStartMs" }
		require(sourceFile.isFile) { "Chunk source file does not exist: ${sourceFile.absolutePath}" }
		require(sourceFile.length() > 0L) { "Chunk source file is empty: ${sourceFile.absolutePath}" }
		requireSession(localSessionId)

		val metadataFile = chunkMetadataFile(localSessionId, seq)
		val registered = withFileLock(metadataFile) {
			val existing = loadChunk(localSessionId, seq)
			if (existing != null) {
				if (existing.bytes == sourceFile.length() && verifyChunkPayload(existing)) return@withFileLock existing
				throw IllegalStateException("Chunk $seq already exists with different content")
			}

			val chunksDir = chunksDirectory(localSessionId)
			val destination = File(chunksDir, chunkPayloadFilename(seq))
			val temp = File(chunksDir, ".${destination.name}.${UUID.randomUUID()}.tmp")
			val bytes = sourceFile.length()
			val sha256Base64 = sha256Base64(sourceFile)
			cipher.encryptFile(sourceFile, temp)
			atomicReplace(temp, destination)

			val chunk = DurableCloudChunk(
				localSessionId = localSessionId,
				seq = seq,
				firstSampleIndex = firstSampleIndex,
				sampleCount = sampleCount,
				tStartMs = tStartMs,
				tEndMs = tEndMs,
				payloadRelativePath = "$CHUNKS_DIRECTORY/${destination.name}",
				bytes = bytes,
				sha256Base64 = sha256Base64,
				state = ChunkUploadState.LOCAL_ONLY,
				cloudChunkId = null,
				cloudObjectPath = null,
				uploadAttempts = 0,
				lastAttemptAtEpochMs = null,
				nextAttemptAtEpochMs = null,
				verifiedAtEpochMs = null,
				lastError = null,
				createdAtEpochMs = createdAtEpochMs,
				updatedAtEpochMs = System.currentTimeMillis()
			)
			writeChunkUnlocked(chunk)
			chunk
		}
		// Durable high-water mark. Verified chunks are deleted from the device, so the
		// session itself must remember how far chunk numbering and samples have advanced;
		// otherwise a resume restarts at seq 0 and collides with chunks already in Cloud.
		runCatching {
			advanceHighWater(localSessionId, seq + 1, firstSampleIndex + sampleCount)
		}.onFailure {
			android.util.Log.w("DurableCloudSessionStore", "High-water update failed $localSessionId/$seq", it)
		}
		return registered
	}

	/** Raises (never lowers) the session's next-sequence and next-sample high-water marks. */
	fun advanceHighWater(
		localSessionId: String,
		seqExclusive: Int,
		sampleIndexExclusive: Long
	): DurableCloudSession =
		updateSession(localSessionId) { current ->
			val nextSeq = maxOf(current.nextSeqExclusive ?: 0, seqExclusive)
			val nextSample = maxOf(current.nextSampleIndex ?: 0L, sampleIndexExclusive)
			if (nextSeq == current.nextSeqExclusive && nextSample == current.nextSampleIndex) {
				current
			} else {
				current.copy(
					nextSeqExclusive = nextSeq,
					nextSampleIndex = nextSample,
					updatedAtEpochMs = System.currentTimeMillis()
				)
			}
		}

	fun loadChunk(localSessionId: String, seq: Int): DurableCloudChunk? {
		val file = chunkMetadataFile(localSessionId, seq)
		if (!file.isFile) return null
		return parseChunk(readJson(file))
	}


	/**
	 * Recovers complete fixed-size CGX packets left in working .part files after
	 * process death or device restart. Recovered sessions are closed as
	 * interrupted so they can be synchronized and finalized without pretending
	 * acquisition continued while the process was absent.
	 */
	fun recoverInterruptedWorkingChunks(
		workRoot: File,
		packetSizeBytes: Int = 36
	): List<RecoveredWorkingChunk> {
		require(packetSizeBytes > 0) { "packetSizeBytes must be positive" }
		val recovered = ArrayList<RecoveredWorkingChunk>()
		val recoveryCandidates = listSessions().filter { session ->
			session.state !in setOf(CloudSessionState.FINALIZED, CloudSessionState.ABANDONED, CloudSessionState.LOCAL_COMPLETE) &&
				session.finalSeqExclusive == null
		}
		for (session in recoveryCandidates) {
			val sessionWorkDir = File(workRoot, session.localSessionId)
			if (!sessionWorkDir.isDirectory) continue
			val existing = listChunks(session.localSessionId)
			val bySeq = existing.associateBy { it.seq }
			val partFiles = sessionWorkDir.listFiles { file ->
				file.isFile && file.name.matches(Regex("\\d{8}\\.part"))
			}.orEmpty().sortedBy { it.name }
			var highestSeqExclusive = maxOf(
				(existing.maxOfOrNull { it.seq } ?: -1) + 1,
				session.nextSeqExclusive ?: 0
			)
			for (part in partFiles) {
				val seq = part.name.substringBefore('.').toIntOrNull() ?: continue
				if (bySeq.containsKey(seq)) {
					part.delete()
					continue
				}
				val completeBytes = (part.length() / packetSizeBytes) * packetSizeBytes
				val trailingBytes = part.length() - completeBytes
				if (completeBytes <= 0L) {
					if (part.length() > 0L) {
						part.renameTo(File(part.parentFile, part.name + ".unrecoverable"))
					} else part.delete()
					continue
				}
				val clean = File(part.parentFile, part.name + ".recovered")
				part.inputStream().use { input ->
					FileOutputStream(clean).use { output ->
						var remaining = completeBytes
						val buffer = ByteArray(64 * 1024)
						while (remaining > 0L) {
							val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
							if (count < 0) break
							output.write(buffer, 0, count)
							remaining -= count
						}
						output.flush()
						output.fd.sync()
					}
				}
				if (trailingBytes > 0L) {
					val tail = File(part.parentFile, part.name + ".trailing-bytes")
					part.inputStream().use { input ->
						input.skip(completeBytes)
						FileOutputStream(tail).use { output ->
							input.copyTo(output)
							output.flush()
							output.fd.sync()
						}
					}
				}
				val sampleCount = (completeBytes / packetSizeBytes).toInt()
				val priorChunkEnd = listChunks(session.localSessionId)
					.filter { it.seq < seq }
					.maxOfOrNull { it.firstSampleIndex + it.sampleCount }
				// Verified chunks are retired from the device, so use the session's durable
				// high-water mark before estimating from the sequence number.
				val highWaterSample = loadSession(session.localSessionId)?.nextSampleIndex
				val firstSampleIndex = if (priorChunkEnd != null || highWaterSample != null) {
					maxOf(priorChunkEnd ?: 0L, highWaterSample ?: 0L)
				} else {
					seq.toLong() * session.sampleRateHz.toLong() * session.chunkSeconds.toLong()
				}
				val tStartMs = firstSampleIndex * 1000L / session.sampleRateHz
				val tEndMs = (firstSampleIndex + sampleCount) * 1000L / session.sampleRateHz
				registerChunk(
					localSessionId = session.localSessionId,
					seq = seq,
					firstSampleIndex = firstSampleIndex,
					sampleCount = sampleCount,
					tStartMs = tStartMs,
					tEndMs = tEndMs,
					sourceFile = clean
				)
				if (session.syncEnabled) markChunkQueued(session.localSessionId, seq)
				clean.delete()
				part.delete()
				highestSeqExclusive = maxOf(highestSeqExclusive, seq + 1)
				recovered.add(RecoveredWorkingChunk(session.localSessionId, seq, sampleCount, trailingBytes))
			}
			if (!session.syncEnabled) {
				// A LOCAL recording must never become a cloud synchronization candidate.
				// If the process died, recover every complete packet and close the local
				// recording at the last durable sequence. The researcher can still upload
				// it explicitly later after patient reconciliation.
				val finalExclusive = maxOf(
					highestSeqExclusive,
					(listChunks(session.localSessionId).maxOfOrNull { it.seq } ?: -1) + 1
				)
				markLocalSessionComplete(session.localSessionId, finalExclusive)
			}
			// Cloud-mode process death remains an interruption, not an explicit Stop.
			// Its recovered session stays open so the user can resume it.
		}
		return recovered
	}

	fun requeueChunk(localSessionId: String, seq: Int, reason: String): DurableCloudChunk =
		updateChunk(localSessionId, seq) {
			it.copy(
				state = ChunkUploadState.QUEUED,
				nextAttemptAtEpochMs = null,
				lastError = reason.take(MAX_ERROR_LENGTH),
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}

	fun listChunks(localSessionId: String): List<DurableCloudChunk> {
		val dir = File(sessionDirectory(localSessionId), CHUNKS_DIRECTORY)
		if (!dir.isDirectory) return emptyList()
		return dir
			.listFiles { file -> file.isFile && file.name.endsWith(CHUNK_METADATA_SUFFIX) }
			.orEmpty()
			.mapNotNull { file -> runCatching { parseChunk(readJson(file)) }.getOrNull() }
			.sortedBy { it.seq }
	}

	/**
	 * Every chunk that has not yet reached VERIFIED, including retry-wait and
	 * permanent-failure rows. Finalization must never use the due-for-retry view
	 * because a future nextAttemptAtEpochMs would otherwise look like completion.
	 */
	fun listUnverifiedChunks(localSessionId: String): List<DurableCloudChunk> =
		listChunks(localSessionId).filter { it.state != ChunkUploadState.VERIFIED }

	fun listPendingChunks(
		localSessionId: String,
		nowEpochMs: Long = System.currentTimeMillis()
	): List<DurableCloudChunk> =
		listUnverifiedChunks(localSessionId).filter { chunk ->
			chunk.state != ChunkUploadState.FAILED_PERMANENT &&
				(chunk.nextAttemptAtEpochMs == null || chunk.nextAttemptAtEpochMs <= nowEpochMs)
		}

	fun markChunkQueued(localSessionId: String, seq: Int): DurableCloudChunk =
		updateChunk(localSessionId, seq) {
			it.copy(
				state = ChunkUploadState.QUEUED,
				lastError = null,
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}

	fun markChunkUploading(localSessionId: String, seq: Int): DurableCloudChunk {
		val now = System.currentTimeMillis()
		return updateChunk(localSessionId, seq) {
			it.copy(
				state = ChunkUploadState.UPLOADING,
				uploadAttempts = it.uploadAttempts + 1,
				lastAttemptAtEpochMs = now,
				nextAttemptAtEpochMs = null,
				lastError = null,
				updatedAtEpochMs = now
			)
		}
	}

	fun markChunkCloudAllocated(
		localSessionId: String,
		seq: Int,
		cloudChunkId: String,
		cloudObjectPath: String?
	): DurableCloudChunk {
		require(cloudChunkId.isNotBlank()) { "cloudChunkId must not be blank" }
		return updateChunk(localSessionId, seq) {
			it.copy(
				cloudChunkId = cloudChunkId,
				cloudObjectPath = cloudObjectPath,
				state = ChunkUploadState.UPLOADING,
				lastError = null,
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}
	}

	fun markChunkPutComplete(localSessionId: String, seq: Int): DurableCloudChunk =
		updateChunk(localSessionId, seq) {
			it.copy(
				state = ChunkUploadState.PUT_COMPLETE,
				lastError = null,
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}

	fun markChunkPermanentFailure(
		localSessionId: String,
		seq: Int,
		message: String
	): DurableCloudChunk = updateChunk(localSessionId, seq) {
		it.copy(
			state = ChunkUploadState.FAILED_PERMANENT,
			nextAttemptAtEpochMs = null,
			lastError = message.take(MAX_ERROR_LENGTH),
			updatedAtEpochMs = System.currentTimeMillis()
		)
	}

	fun markChunkVerified(
		localSessionId: String,
		seq: Int,
		verifiedAtEpochMs: Long = System.currentTimeMillis()
	): DurableCloudChunk =
		updateChunk(localSessionId, seq) {
			it.copy(
				state = ChunkUploadState.VERIFIED,
				verifiedAtEpochMs = verifiedAtEpochMs,
				nextAttemptAtEpochMs = null,
				lastError = null,
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}

	fun markChunkRetryableFailure(
		localSessionId: String,
		seq: Int,
		message: String,
		nextAttemptAtEpochMs: Long,
		preservePutComplete: Boolean = false
	): DurableCloudChunk =
		updateChunk(localSessionId, seq) {
			it.copy(
				state = if (preservePutComplete) {
					ChunkUploadState.PUT_COMPLETE
				} else {
					ChunkUploadState.RETRY_WAIT
				},
				nextAttemptAtEpochMs = nextAttemptAtEpochMs,
				lastError = message.take(MAX_ERROR_LENGTH),
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}

	fun resetChunkForRetry(localSessionId: String, seq: Int): DurableCloudChunk =
		updateChunk(localSessionId, seq) {
			it.copy(
				state = ChunkUploadState.QUEUED,
				nextAttemptAtEpochMs = null,
				lastError = null,
				updatedAtEpochMs = System.currentTimeMillis()
			)
		}

	// ---- Clinical markers ----------------------------------------------------
	// Markers ("fell asleep", "propofol administered", free-text notes) live in one
	// encrypted file beside session.json. The list is small, so every change
	// rewrites it atomically under the file lock.

	fun appendMarker(localSessionId: String, marker: DurableSessionMarker): DurableSessionMarker {
		require(marker.clientEventId.isNotBlank()) { "clientEventId must not be blank" }
		require(marker.label.isNotBlank()) { "label must not be blank" }
		require(marker.sampleIndex >= 0L) { "sampleIndex must not be negative" }
		requireSession(localSessionId)
		val file = markersFile(localSessionId)
		return withFileLock(file) {
			val current = readMarkers(file)
			val existing = current.firstOrNull { it.clientEventId == marker.clientEventId }
			if (existing != null) {
				existing
			} else {
				check(current.size < MAX_MARKERS_PER_SESSION) { "Marker limit reached for this recording" }
				writeMarkers(file, current + marker)
				marker
			}
		}
	}

	/**
	 * Keeps a finished session's folder (and so its markers file) on the phone until
	 * [untilEpochMs], even once it is finalized, so markers can still be added to it.
	 */
	fun holdCleanup(localSessionId: String, untilEpochMs: Long) {
		requireSession(localSessionId)
		val file = File(sessionDirectory(localSessionId), CLEANUP_HOLD_FILENAME)
		withFileLock(file) { file.writeText(untilEpochMs.toString()) }
	}

	fun releaseCleanupHold(localSessionId: String) {
		val file = File(sessionDirectory(localSessionId), CLEANUP_HOLD_FILENAME)
		if (file.isFile) withFileLock(file) { file.delete() }
	}

	private fun cleanupHeld(localSessionId: String, nowEpochMs: Long): Boolean = runCatching {
		val file = File(sessionDirectory(localSessionId), CLEANUP_HOLD_FILENAME)
		file.isFile && (file.readText().trim().toLongOrNull() ?: 0L) > nowEpochMs
	}.getOrDefault(false)

	fun listMarkers(localSessionId: String): List<DurableSessionMarker> =
		readMarkers(markersFile(localSessionId))
			.sortedWith(compareBy<DurableSessionMarker>({ it.sampleIndex }, { it.createdAtEpochMs }))

	fun listPendingMarkers(
		localSessionId: String,
		nowEpochMs: Long = System.currentTimeMillis()
	): List<DurableSessionMarker> =
		listMarkers(localSessionId).filter { marker ->
			marker.state == MarkerSyncState.PENDING &&
				(marker.nextAttemptAtEpochMs == null || marker.nextAttemptAtEpochMs <= nowEpochMs)
		}

	/** True while any marker still needs Cloud acknowledgement (including ones in retry back-off). */
	fun hasUnsyncedMarkers(localSessionId: String): Boolean =
		runCatching { listMarkers(localSessionId).any { it.state == MarkerSyncState.PENDING } }
			.getOrDefault(false)

	fun applyMarkerSyncOutcomes(localSessionId: String, outcomes: Map<String, MarkerSyncOutcome>) {
		if (outcomes.isEmpty()) return
		val file = markersFile(localSessionId)
		withFileLock(file) {
			val now = System.currentTimeMillis()
			val updated = readMarkers(file).map { marker ->
				when (val outcome = outcomes[marker.clientEventId]) {
					null -> marker
					is MarkerSyncOutcome.Synced -> marker.copy(
						state = MarkerSyncState.SYNCED,
						cloudEventId = outcome.cloudEventId ?: marker.cloudEventId,
						attempts = marker.attempts + 1,
						nextAttemptAtEpochMs = null,
						lastError = null,
						syncedAtEpochMs = now
					)
					is MarkerSyncOutcome.Rejected -> if (marker.state == MarkerSyncState.SYNCED) marker else marker.copy(
						state = MarkerSyncState.FAILED_PERMANENT,
						attempts = marker.attempts + 1,
						nextAttemptAtEpochMs = null,
						lastError = outcome.message.take(MAX_ERROR_LENGTH)
					)
					is MarkerSyncOutcome.Retry -> if (marker.state == MarkerSyncState.SYNCED) marker else marker.copy(
						attempts = marker.attempts + 1,
						nextAttemptAtEpochMs = outcome.nextAttemptAtEpochMs,
						lastError = outcome.message.take(MAX_ERROR_LENGTH)
					)
				}
			}
			writeMarkers(file, updated)
		}
	}

	private fun markersFile(localSessionId: String): File =
		File(sessionDirectory(localSessionId), MARKERS_FILENAME)

	private fun readMarkers(file: File): List<DurableSessionMarker> {
		if (!file.isFile) return emptyList()
		val array = readJson(file).optJSONArray("markers") ?: return emptyList()
		return List(array.length()) { index -> parseMarker(array.getJSONObject(index)) }
	}

	private fun writeMarkers(file: File, markers: List<DurableSessionMarker>) {
		val array = JSONArray()
		for (marker in markers) array.put(marker.toJson())
		writeJsonAtomic(file, JSONObject().apply {
			put("schemaVersion", MARKER_SCHEMA_VERSION)
			put("markers", array)
		})
	}

	private fun parseMarker(json: JSONObject): DurableSessionMarker = DurableSessionMarker(
		clientEventId = json.getString("clientEventId"),
		kind = json.getString("kind"),
		code = json.optNullableString("code"),
		label = json.getString("label"),
		note = json.optNullableString("note"),
		sampleIndex = json.getLong("sampleIndex"),
		tOffsetMs = json.getLong("tOffsetMs"),
		wallClockEpochMs = json.getLong("wallClockEpochMs"),
		createdAtEpochMs = json.getLong("createdAtEpochMs"),
		state = runCatching { MarkerSyncState.valueOf(json.getString("state")) }
			.getOrDefault(MarkerSyncState.PENDING),
		cloudEventId = json.optNullableString("cloudEventId"),
		attempts = json.optInt("attempts", 0),
		nextAttemptAtEpochMs = json.optNullableLong("nextAttemptAtEpochMs"),
		lastError = json.optNullableString("lastError"),
		syncedAtEpochMs = json.optNullableLong("syncedAtEpochMs")
	)

	private fun DurableSessionMarker.toJson(): JSONObject = JSONObject().apply {
		put("clientEventId", clientEventId)
		put("kind", kind)
		putNullable("code", code)
		put("label", label)
		putNullable("note", note)
		put("sampleIndex", sampleIndex)
		put("tOffsetMs", tOffsetMs)
		put("wallClockEpochMs", wallClockEpochMs)
		put("createdAtEpochMs", createdAtEpochMs)
		put("state", state.name)
		putNullable("cloudEventId", cloudEventId)
		put("attempts", attempts)
		putNullable("nextAttemptAtEpochMs", nextAttemptAtEpochMs)
		putNullable("lastError", lastError)
		putNullable("syncedAtEpochMs", syncedAtEpochMs)
	}

	fun payloadFile(chunk: DurableCloudChunk): File =
		File(sessionDirectory(chunk.localSessionId), chunk.payloadRelativePath)

	/**
	 * Decrypts one durable clinical chunk directly into memory for local playback.
	 * No plaintext recording copy is created on disk.
	 */
	fun readChunkPlaintextBytes(chunk: DurableCloudChunk): ByteArray {
		val encrypted = payloadFile(chunk)
		require(encrypted.isFile) { "Chunk payload is missing: ${encrypted.absolutePath}" }
		val plain = cipher.decryptBytes(encrypted.readBytes())
		require(plain.size.toLong() == chunk.bytes) {
			"Decrypted payload length mismatch for seq=${chunk.seq}"
		}
		return plain
	}

	/**
	 * Produces a short-lived plaintext copy for an authenticated upload operation.
	 * The caller must delete the returned file in a finally block.
	 */
	fun createPlaintextUploadFile(chunk: DurableCloudChunk, destinationDir: File): File {
		val encrypted = payloadFile(chunk)
		require(encrypted.isFile) { "Chunk payload is missing: ${encrypted.absolutePath}" }
		if (!destinationDir.exists() && !destinationDir.mkdirs()) {
			throw IllegalStateException("Unable to create upload work directory: ${destinationDir.absolutePath}")
		}
		val output = File(destinationDir, "${chunk.localSessionId}-${chunk.seq}-${UUID.randomUUID()}.upload")
		cipher.decryptFile(encrypted, output)
		if (output.length() != chunk.bytes || sha256Base64(output) != chunk.sha256Base64) {
			output.delete()
			throw IllegalStateException("Decrypted payload failed integrity verification for seq=${chunk.seq}")
		}
		return output
	}

	fun verifyChunkPayload(chunk: DurableCloudChunk): Boolean {
		val file = payloadFile(chunk)
		if (!file.isFile) return false
		val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.verify")
		return try {
			cipher.decryptFile(file, temp)
			val valid = temp.length() == chunk.bytes && sha256Base64(temp) == chunk.sha256Base64
			if (valid && !cipher.isEncrypted(file)) migrateLegacyPayload(file, temp)
			valid
		} catch (_: Throwable) {
			false
		} finally {
			temp.delete()
		}
	}

	private fun migrateLegacyPayload(destination: File, plaintext: File) {
		val encryptedTemp = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.encrypt")
		cipher.encryptFile(plaintext, encryptedTemp)
		withFileLock(destination) {
			if (!cipher.isEncrypted(destination)) atomicReplace(encryptedTemp, destination)
			else encryptedTemp.delete()
		}
	}

	/**
	 * Retires a chunk from the device once cloud verification is authoritative.
	 * Android is a durable write-behind buffer, not a long-term archive: after the
	 * cloud confirms the chunk, both encrypted payload and metadata sidecar are
	 * removed so live sync cost remains bounded by outstanding work rather than by
	 * total recording duration.
	 */
	fun retireVerifiedChunk(localSessionId: String, seq: Int): Boolean {
		// A recording that originated in explicit LOCAL mode remains a local file
		// after upload. Keep its verified chunks so Device Recordings can still
		// preview/export/delete the local copy independently of Cloud.
		val session = loadSession(localSessionId)
		if (session?.localPatientId != null) return true

		val metadataFile = chunkMetadataFile(localSessionId, seq)
		val key = metadataFile.absolutePath
		val lock = fileLocks.computeIfAbsent(key) { Any() }
		val retired = synchronized(lock) {
			val chunk = loadChunk(localSessionId, seq) ?: return@synchronized true
			check(chunk.state == ChunkUploadState.VERIFIED) {
				"Only verified chunks may be retired"
			}
			val payload = payloadFile(chunk)
			if (payload.exists() && !payload.delete()) return@synchronized false
			if (metadataFile.exists() && !metadataFile.delete()) return@synchronized false
			true
		}
		if (retired) fileLocks.remove(key, lock)
		return retired
	}

	/** Compatibility wrapper for older callers. */
	fun purgeVerifiedPayload(localSessionId: String, seq: Int): Boolean =
		retireVerifiedChunk(localSessionId, seq)

	private fun updateSession(
		localSessionId: String,
		transform: (DurableCloudSession) -> DurableCloudSession
	): DurableCloudSession {
		val file = sessionMetadataFile(localSessionId)
		return withFileLock(file) {
			val updated = transform(requireSession(localSessionId))
			writeSessionUnlocked(updated)
			updated
		}
	}

	private fun updateChunk(
		localSessionId: String,
		seq: Int,
		transform: (DurableCloudChunk) -> DurableCloudChunk
	): DurableCloudChunk {
		val file = chunkMetadataFile(localSessionId, seq)
		return withFileLock(file) {
			val updated = transform(requireChunk(localSessionId, seq))
			writeChunkUnlocked(updated)
			updated
		}
	}

	private fun requireSession(localSessionId: String): DurableCloudSession =
		loadSession(localSessionId)
			?: throw IllegalArgumentException("Unknown local session: $localSessionId")

	private fun requireChunk(localSessionId: String, seq: Int): DurableCloudChunk =
		loadChunk(localSessionId, seq)
			?: throw IllegalArgumentException("Unknown chunk $seq for session $localSessionId")

	private fun writeSession(session: DurableCloudSession) {
		val file = sessionMetadataFile(session.localSessionId)
		withFileLock(file) { writeSessionUnlocked(session) }
	}

	private fun writeSessionUnlocked(session: DurableCloudSession) {
		writeJsonAtomic(sessionMetadataFile(session.localSessionId), session.toJson())
	}

	private fun writeChunk(chunk: DurableCloudChunk) {
		val file = chunkMetadataFile(chunk.localSessionId, chunk.seq)
		withFileLock(file) { writeChunkUnlocked(chunk) }
	}

	private fun writeChunkUnlocked(chunk: DurableCloudChunk) {
		writeJsonAtomic(chunkMetadataFile(chunk.localSessionId, chunk.seq), chunk.toJson())
	}

	private inline fun <T> withFileLock(file: File, block: () -> T): T {
		val key = file.absolutePath
		val lock = fileLocks.computeIfAbsent(key) { Any() }
		return synchronized(lock) { block() }
	}

	private fun sessionDirectory(localSessionId: String): File = File(rootDir, localSessionId)

	private fun chunksDirectory(localSessionId: String): File =
		File(sessionDirectory(localSessionId), CHUNKS_DIRECTORY).apply {
			if (!exists() && !mkdirs()) {
				throw IllegalStateException("Unable to create chunks directory: $absolutePath")
			}
		}

	private fun sessionMetadataFile(localSessionId: String): File =
		File(sessionDirectory(localSessionId), SESSION_METADATA_FILENAME)

	private fun chunkMetadataFile(localSessionId: String, seq: Int): File =
		File(chunksDirectory(localSessionId), chunkMetadataFilename(seq))

	private fun writeJsonAtomic(destination: File, json: JSONObject) {
		destination.parentFile?.let { parent ->
			if (!parent.exists() && !parent.mkdirs()) {
				throw IllegalStateException("Unable to create directory: ${parent.absolutePath}")
			}
		}
		val temp = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
		val encrypted = cipher.encryptBytes(json.toString(2).toByteArray(Charsets.UTF_8))
		FileOutputStream(temp).use { output ->
			output.write(encrypted)
			output.flush()
			output.fd.sync()
		}
		atomicReplace(temp, destination)
	}

	private fun readJson(file: File): JSONObject {
		val stored = file.readBytes()
		val plaintext = cipher.decryptBytes(stored)
		return JSONObject(plaintext.toString(Charsets.UTF_8))
	}

	private fun copyAndSync(source: File, destination: File) {
		source.inputStream().use { input ->
			FileOutputStream(destination).use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
				output.flush()
				output.fd.sync()
			}
		}
	}

	private fun atomicReplace(source: File, destination: File) {
		try {
			// POSIX rename is atomic on Android when source and destination share a filesystem.
			Os.rename(source.absolutePath, destination.absolutePath)
		} catch (error: Throwable) {
			source.delete()
			throw IllegalStateException(
				"Unable to atomically move ${source.absolutePath} to ${destination.absolutePath}",
				error
			)
		}
	}

	private fun sha256Base64(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		file.inputStream().use { input ->
			val buffer = ByteArray(64 * 1024)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				if (count > 0) digest.update(buffer, 0, count)
			}
		}
		return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
	}

	private fun parseSession(json: JSONObject): DurableCloudSession = DurableCloudSession(
		localSessionId = json.getString("localSessionId"),
		cloudSessionId = json.optNullableString("cloudSessionId"),
		syncEnabled = json.optBoolean("syncEnabled", true),
		localPatientId = json.optNullableString("localPatientId"),
		orgId = json.optString("orgId"),
		subjectId = json.getString("subjectId"),
		sampleRateHz = json.getInt("sampleRateHz"),
		channels = json.getJSONArray("channels").toStringList(),
		chunkSeconds = json.getInt("chunkSeconds"),
		startedAtEpochMs = json.getLong("startedAtEpochMs"),
		startedAtElapsedRealtimeMs = json.optNullableLong("startedAtElapsedRealtimeMs"),
		endedAtEpochMs = json.optNullableLong("endedAtEpochMs"),
		state = when (val storedState = json.getString("state")) {
			"AWAITING_FINALIZATION" -> CloudSessionState.FINALIZATION_PENDING
			else -> CloudSessionState.valueOf(storedState)
		},
		finalSeqExclusive = json.optNullableInt("finalSeqExclusive"),
		lastError = json.optNullableString("lastError"),
		eligibleForCleanupAtEpochMs = json.optNullableLong("eligibleForCleanupAtEpochMs"),
		cleanupStartedAtEpochMs = json.optNullableLong("cleanupStartedAtEpochMs"),
		createdAtEpochMs = json.getLong("createdAtEpochMs"),
		updatedAtEpochMs = json.getLong("updatedAtEpochMs"),
		nextSeqExclusive = json.optNullableInt("nextSeqExclusive"),
		nextSampleIndex = json.optNullableLong("nextSampleIndex")
	)

	private fun parseChunk(json: JSONObject): DurableCloudChunk = DurableCloudChunk(
		localSessionId = json.getString("localSessionId"),
		seq = json.getInt("seq"),
		firstSampleIndex = json.getLong("firstSampleIndex"),
		sampleCount = json.getInt("sampleCount"),
		tStartMs = json.getLong("tStartMs"),
		tEndMs = json.getLong("tEndMs"),
		payloadRelativePath = json.getString("payloadRelativePath"),
		bytes = json.getLong("bytes"),
		sha256Base64 = json.getString("sha256Base64"),
		state = ChunkUploadState.valueOf(json.getString("state")),
		cloudChunkId = json.optNullableString("cloudChunkId"),
		cloudObjectPath = json.optNullableString("cloudObjectPath"),
		uploadAttempts = json.getInt("uploadAttempts"),
		lastAttemptAtEpochMs = json.optNullableLong("lastAttemptAtEpochMs"),
		nextAttemptAtEpochMs = json.optNullableLong("nextAttemptAtEpochMs"),
		verifiedAtEpochMs = json.optNullableLong("verifiedAtEpochMs"),
		lastError = json.optNullableString("lastError"),
		createdAtEpochMs = json.getLong("createdAtEpochMs"),
		updatedAtEpochMs = json.getLong("updatedAtEpochMs")
	)

	private fun DurableCloudSession.toJson(): JSONObject = JSONObject().apply {
		put("schemaVersion", SCHEMA_VERSION)
		put("localSessionId", localSessionId)
		putNullable("cloudSessionId", cloudSessionId)
		put("syncEnabled", syncEnabled)
		putNullable("localPatientId", localPatientId)
		put("orgId", orgId)
		put("subjectId", subjectId)
		put("sampleRateHz", sampleRateHz)
		put("channels", JSONArray(channels))
		put("chunkSeconds", chunkSeconds)
		put("startedAtEpochMs", startedAtEpochMs)
		putNullable("startedAtElapsedRealtimeMs", startedAtElapsedRealtimeMs)
		putNullable("endedAtEpochMs", endedAtEpochMs)
		put("state", state.name)
		putNullable("finalSeqExclusive", finalSeqExclusive)
		putNullable("lastError", lastError)
		putNullable("eligibleForCleanupAtEpochMs", eligibleForCleanupAtEpochMs)
		putNullable("cleanupStartedAtEpochMs", cleanupStartedAtEpochMs)
		put("createdAtEpochMs", createdAtEpochMs)
		put("updatedAtEpochMs", updatedAtEpochMs)
		putNullable("nextSeqExclusive", nextSeqExclusive)
		putNullable("nextSampleIndex", nextSampleIndex)
	}

	private fun DurableCloudChunk.toJson(): JSONObject = JSONObject().apply {
		put("schemaVersion", SCHEMA_VERSION)
		put("localSessionId", localSessionId)
		put("seq", seq)
		put("firstSampleIndex", firstSampleIndex)
		put("sampleCount", sampleCount)
		put("tStartMs", tStartMs)
		put("tEndMs", tEndMs)
		put("payloadRelativePath", payloadRelativePath)
		put("bytes", bytes)
		put("sha256Base64", sha256Base64)
		put("state", state.name)
		putNullable("cloudChunkId", cloudChunkId)
		putNullable("cloudObjectPath", cloudObjectPath)
		put("uploadAttempts", uploadAttempts)
		putNullable("lastAttemptAtEpochMs", lastAttemptAtEpochMs)
		putNullable("nextAttemptAtEpochMs", nextAttemptAtEpochMs)
		putNullable("verifiedAtEpochMs", verifiedAtEpochMs)
		putNullable("lastError", lastError)
		put("createdAtEpochMs", createdAtEpochMs)
		put("updatedAtEpochMs", updatedAtEpochMs)
	}

	private fun JSONObject.putNullable(key: String, value: Any?) {
		if (value == null) put(key, JSONObject.NULL) else put(key, value)
	}

	private fun JSONObject.optNullableString(key: String): String? =
		if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

	private fun JSONObject.optNullableLong(key: String): Long? =
		if (!has(key) || isNull(key)) null else getLong(key)

	private fun JSONObject.optNullableInt(key: String): Int? =
		if (!has(key) || isNull(key)) null else getInt(key)

	private fun JSONArray.toStringList(): List<String> =
		List(length()) { index -> getString(index) }

	private fun chunkPayloadFilename(seq: Int): String = "%08d.cgxchunk".format(seq)

	private fun chunkMetadataFilename(seq: Int): String =
		"%08d%s".format(seq, CHUNK_METADATA_SUFFIX)

	companion object {
		private const val ROOT_DIRECTORY = "cloud_recording_spool"
		private const val BACKUP_ROOT_DIRECTORY = "cloud_recording_backups"
		private const val WORK_ROOT_DIRECTORY = "cloud_recording_work"
		private const val CHUNKS_DIRECTORY = "chunks"
		private const val SESSION_METADATA_FILENAME = "session.json"
		private const val CHUNK_METADATA_SUFFIX = ".upload.json"
		private const val MARKERS_FILENAME = "markers.json"
		private const val MARKER_SCHEMA_VERSION = 1
		private const val MAX_MARKERS_PER_SESSION = 5_000
		private const val CLEANUP_HOLD_FILENAME = "cleanup_hold.txt"
		private const val SCHEMA_VERSION = 4
		private const val MAX_ERROR_LENGTH = 2_000
		const val DEFAULT_FINALIZED_RETENTION_MS = 0L
	}
}

data class CloudSessionCleanupReport(
	val scheduledLegacySessions: Int,
	val deletedSessions: Int,
	val skippedNotReadySessions: Int,
	val failedSessions: Int,
	val bytesFreed: Long
)

data class RecoveredWorkingChunk(
	val localSessionId: String,
	val seq: Int,
	val sampleCount: Int,
	val trailingBytes: Long
)

/**
 * A clinical marker. sampleIndex is the acquisition sample index (the same
 * index space as DurableCloudChunk.firstSampleIndex) and is authoritative.
 */
data class DurableSessionMarker(
	val clientEventId: String,
	val kind: String, // PRESET or TEXT
	val code: String?,
	val label: String,
	val note: String?,
	val sampleIndex: Long,
	val tOffsetMs: Long,
	val wallClockEpochMs: Long,
	val createdAtEpochMs: Long,
	val state: MarkerSyncState = MarkerSyncState.PENDING,
	val cloudEventId: String? = null,
	val attempts: Int = 0,
	val nextAttemptAtEpochMs: Long? = null,
	val lastError: String? = null,
	val syncedAtEpochMs: Long? = null
)

enum class MarkerSyncState {
	PENDING,
	SYNCED,
	FAILED_PERMANENT
}

sealed class MarkerSyncOutcome {
	data class Synced(val cloudEventId: String?) : MarkerSyncOutcome()
	data class Rejected(val message: String) : MarkerSyncOutcome()
	data class Retry(val message: String, val nextAttemptAtEpochMs: Long) : MarkerSyncOutcome()
}

data class DurableCloudSession(
	val localSessionId: String,
	val cloudSessionId: String?,
	val syncEnabled: Boolean = true,
	val localPatientId: String? = null,
	val orgId: String,
	val subjectId: String,
	val sampleRateHz: Int,
	val channels: List<String>,
	val chunkSeconds: Int,
	val startedAtEpochMs: Long,
	val startedAtElapsedRealtimeMs: Long?,
	val endedAtEpochMs: Long?,
	val state: CloudSessionState,
	val finalSeqExclusive: Int?,
	val lastError: String?,
	val eligibleForCleanupAtEpochMs: Long?,
	val cleanupStartedAtEpochMs: Long?,
	val createdAtEpochMs: Long,
	val updatedAtEpochMs: Long,
	/** Next chunk sequence never yet used by this session; survives chunk retirement. */
	val nextSeqExclusive: Int? = null,
	/** Next acquisition sample index never yet used by this session. */
	val nextSampleIndex: Long? = null
)

data class DurableCloudChunk(
	val localSessionId: String,
	val seq: Int,
	val firstSampleIndex: Long,
	val sampleCount: Int,
	val tStartMs: Long,
	val tEndMs: Long,
	val payloadRelativePath: String,
	val bytes: Long,
	val sha256Base64: String,
	val state: ChunkUploadState,
	val cloudChunkId: String?,
	val cloudObjectPath: String?,
	val uploadAttempts: Int,
	val lastAttemptAtEpochMs: Long?,
	val nextAttemptAtEpochMs: Long?,
	val verifiedAtEpochMs: Long?,
	val lastError: String?,
	val createdAtEpochMs: Long,
	val updatedAtEpochMs: Long
)

enum class CloudSessionState {
	LOCAL_ONLY,
	LOCAL_COMPLETE,
	ACTIVE,
	STOPPING,
	FINALIZATION_PENDING,
	@Deprecated("Legacy persisted state; parsed as FINALIZATION_PENDING")
	AWAITING_FINALIZATION,
	FINALIZED,
	ERROR,
	ABANDONED
}

enum class ChunkUploadState {
	LOCAL_ONLY,
	QUEUED,
	UPLOADING,
	PUT_COMPLETE,
	RETRY_WAIT,
	FAILED_PERMANENT,
	VERIFIED
}