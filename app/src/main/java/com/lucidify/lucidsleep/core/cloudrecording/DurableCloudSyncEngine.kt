package com.lucidify.lucidsleep.core.cloudrecording

import android.util.Log
import com.lucidify.lucidsleep.core.cloud.CloudApiException
import com.lucidify.lucidsleep.core.cloud.OrgApi
import com.lucidify.lucidsleep.core.cloud.SessionCoverageResp
import com.lucidify.lucidsleep.core.cloud.SessionEventUpload
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Synchronizes the durable local spool with the cloud.
 *
 * Phase 3 scheduling rules:
 *  - Active recordings are serviced before completed/backlog sessions.
 *  - The newest completed chunk from an active recording is sent first.
 *  - Older chunks are then drained in small batches so live uploads do not
 *    permanently starve recovery work.
 *  - Authentication/authorization failures never mark local data permanent-fail.
 */
class DurableCloudSyncEngine(
	private val store: DurableCloudSessionStore,
	private val orgApi: OrgApi,
	private val httpClient: OkHttpClient
) {
	companion object {
		private const val TAG = "CloudSessionSync"
		private const val ACTIVE_BACKLOG_BATCH = 2
		// About 25 s of uploading at ~0.8 s per chunk, so a backlog after an outage drains
		// several times faster than new 5-second chunks arrive.
		private const val MAX_ACTIVE_CHUNKS_PER_PASS = 30
		// Per-session back-off for sessions that are not recording and made no progress in
		// their last pass (unfinished finalizations, failing backlogs). Without it every pass
		// re-scans and re-requests all of them, which slowed live uploads to ~10 s per 50 s.
		private val sessionBackoff = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()
		private const val SESSION_BACKOFF_BASE_MS = 30_000L
		private const val SESSION_BACKOFF_MAX_MS = 30L * 60L * 1000L
		// Cloud answered 404 for the session itself: retrying cannot succeed until someone
		// restores or re-creates it. Local data is kept; check again only rarely.
		private const val CLOUD_SESSION_MISSING = -1
		private const val CLOUD_SESSION_MISSING_RETRY_MS = 6L * 60L * 60L * 1000L
		private const val MARKER_BATCH_SIZE = 100
		private val syncRunning = AtomicBoolean(false)
		// In-memory back-off for sessions whose finalize keeps failing (for example a
		// 409 from Cloud). Without it, every 5-second pass re-sends finalization intent,
		// two coverage reads and a finalize for that session, indefinitely.
		private val finalizeBackoff = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()
		private const val FINALIZE_BACKOFF_BASE_MS = 5_000L
		private const val FINALIZE_BACKOFF_MAX_MS = 30L * 60L * 1000L
	}

	suspend fun synchronizeRecoverableSessions(activeSessionId: String? = null): CloudSyncOutcome {
		if (!syncRunning.compareAndSet(false, true)) {
			// A WorkManager pass that collides with the foreground sync loop must not
			// report success and disappear. Tell the caller that durable work remains
			// so WorkManager retries after the current serialized pass releases the lock.
			Log.d(TAG, "Synchronization already running; duplicate pass will retry")
			return CloudSyncOutcome(hasRetryableWork = true)
		}

		return try {
			var retryable = false
			var permanent = false
			var authRequired = false
			var activeRetryable = false
			var activePermanent = false
			var activeAuthRequired = false

			val allSessions = store.listRecoverableSessions().sortedWith(
				compareByDescending<DurableCloudSession> { it.isActivelyRecording }
					.thenByDescending { it.startedAtEpochMs }
			)
			val passStartMs = System.currentTimeMillis()
			// Sessions in back-off are skipped this pass but are still unfinished work.
			val waitingInBackoff = allSessions.filter {
				!it.isActivelyRecording && !isSessionDue(it.localSessionId, passStartMs)
			}
			if (waitingInBackoff.isNotEmpty()) retryable = true

			// Live acquisition must never wait behind historical recovery sessions.
			// If any session is still actively recording, service active sessions only
			// during this pass. Backlog/finalization sessions resume automatically once
			// no active recording remains.
			val activeSessions = allSessions.filter { it.isActivelyRecording }
			val sessions = if (activeSessions.isNotEmpty()) {
				val preferred = activeSessionId?.let { id ->
					activeSessions.sortedByDescending { it.localSessionId == id }
				} ?: activeSessions

				// A finalize POST/readback is tiny compared with chunk upload. Do not let a
				// newly-started live recording starve a previous recording whose chunks
				// are already fully uploaded and only need durable finalization confirmation.
				val finalizeReady = allSessions.filter { candidate ->
					!candidate.isActivelyRecording &&
						candidate.finalSeqExclusive != null &&
						isSessionDue(candidate.localSessionId, passStartMs) &&
						store.listUnverifiedChunks(candidate.localSessionId).isEmpty()
				}
				Log.d(
					TAG,
					"Live-priority pass: active=${preferred.size} finalizeReady=${finalizeReady.size} " +
						"inBackoff=${waitingInBackoff.size} " +
						"deferredBacklog=${allSessions.size - preferred.size - finalizeReady.size - waitingInBackoff.size}"
				)
				preferred + finalizeReady
			} else {
				allSessions.filter { it.isActivelyRecording || isSessionDue(it.localSessionId, passStartMs) }
			}

			for (snapshot in sessions) {
				if (!currentCoroutineContext().isActive) break

				val trackProgress = !snapshot.isActivelyRecording
				val unverifiedBefore = if (trackProgress) {
					runCatching { store.listUnverifiedChunks(snapshot.localSessionId).size }.getOrDefault(-1)
				} else -1
				val result = synchronizeOne(snapshot.localSessionId)
				if (trackProgress) noteSessionResult(snapshot.localSessionId, result, unverifiedBefore)
				when (result) {
					SyncResult.RETRYABLE_FAILURE -> retryable = true
					SyncResult.AUTH_REQUIRED -> authRequired = true
					SyncResult.PERMANENT_FAILURE -> permanent = true
					SyncResult.COMPLETE -> Unit
				}

				if (activeSessionId != null && snapshot.localSessionId == activeSessionId) {
					when (result) {
						SyncResult.RETRYABLE_FAILURE -> activeRetryable = true
						SyncResult.AUTH_REQUIRED -> activeAuthRequired = true
						SyncResult.PERMANENT_FAILURE -> activePermanent = true
						SyncResult.COMPLETE -> Unit
					}
				}
			}

			val cleanup = runCatching { store.cleanupExpiredSessions() }
				.onFailure { Log.w(TAG, "Finalized-session cleanup pass failed", it) }
				.getOrNull()
			if (cleanup != null && (cleanup.deletedSessions > 0 || cleanup.failedSessions > 0)) {
				Log.i(
					TAG,
					"Cleanup deleted=${cleanup.deletedSessions} failed=${cleanup.failedSessions} " +
						"bytesFreed=${cleanup.bytesFreed}"
				)
			}

			CloudSyncOutcome(
				hasRetryableWork = retryable,
				hasPermanentFailure = permanent,
				authRequired = authRequired,
				activeHasRetryableWork = activeRetryable,
				activeHasPermanentFailure = activePermanent,
				activeAuthRequired = activeAuthRequired
			)
		} finally {
			syncRunning.set(false)
		}
	}

	private fun isSessionDue(localSessionId: String, nowMs: Long): Boolean =
		(sessionBackoff[localSessionId]?.second ?: 0L) <= nowMs

	/**
	 * After a pass over a session that is not recording: clear its back-off when it finished
	 * or made progress (fewer unverified chunks), otherwise wait longer before the next try.
	 */
	private fun noteSessionResult(localSessionId: String, result: SyncResult, unverifiedBefore: Int) {
		val current = sessionBackoff[localSessionId]
		if (current?.first == CLOUD_SESSION_MISSING) return
		if (result == SyncResult.COMPLETE) {
			sessionBackoff.remove(localSessionId)
			return
		}
		val unverifiedAfter = runCatching { store.listUnverifiedChunks(localSessionId).size }.getOrDefault(-1)
		if (unverifiedBefore >= 0 && unverifiedAfter in 0 until unverifiedBefore) {
			sessionBackoff.remove(localSessionId)
			return
		}
		val attempts = (current?.first ?: 0) + 1
		val delayMs = (SESSION_BACKOFF_BASE_MS * (1L shl (attempts - 1).coerceIn(0, 10)))
			.coerceAtMost(SESSION_BACKOFF_MAX_MS)
		sessionBackoff[localSessionId] = attempts to (System.currentTimeMillis() + delayMs)
		Log.i(
			TAG,
			"SESSION_BACKOFF $localSessionId result=$result unverified=$unverifiedAfter " +
				"attempt=$attempts next in ${delayMs / 1000}s"
		)
	}

	private suspend fun synchronizeOne(localSessionId: String): SyncResult {
		var session = store.loadSession(localSessionId) ?: return SyncResult.COMPLETE
		if (!session.syncEnabled) return SyncResult.COMPLETE
		finalizeBackoff[localSessionId]?.let { (attempts, nextAttemptAt) ->
			val waitMs = nextAttemptAt - System.currentTimeMillis()
			if (session.finalSeqExclusive != null && waitMs > 0L) {
				Log.d(TAG, "Finalize back-off $localSessionId: attempt=$attempts retry in ${waitMs / 1000}s")
				return SyncResult.RETRYABLE_FAILURE
			}
		}
		recoverInterruptedUploads(localSessionId)

		if (session.finalSeqExclusive != null && session.state != CloudSessionState.FINALIZED) {
			val chunks = store.listChunks(localSessionId)
			val unverified = chunks.filter { it.state != ChunkUploadState.VERIFIED }
			Log.i(
				TAG,
				"LOCAL_FINALIZATION_DIAG session=$localSessionId state=${session.state} " +
					"finalSeqExclusive=${session.finalSeqExclusive} localChunks=${chunks.size} " +
					"unverified=${unverified.map { it.seq }}"
			)
			for (chunk in unverified) {
				val payload = store.payloadFile(chunk)
				Log.i(
					TAG,
					"LOCAL_FINALIZATION_CHUNK session=$localSessionId seq=${chunk.seq} " +
						"state=${chunk.state} bytes=${chunk.bytes} payloadExists=${payload.isFile} " +
						"payloadBytes=${if (payload.isFile) payload.length() else -1L} " +
						"attempts=${chunk.uploadAttempts} nextAttempt=${chunk.nextAttemptAtEpochMs} " +
						"cloudChunkId=${chunk.cloudChunkId} cloudObjectPath=${chunk.cloudObjectPath} " +
						"error=${chunk.lastError}"
				)
			}
		}

		if (session.cloudSessionId == null) {
			try {
				val init = orgApi.sessionInit(
					orgId = session.orgId,
					subjectId = session.subjectId,
					clientSessionId = session.localSessionId,
					startedAtEpochMs = session.startedAtEpochMs,
					formatVersion = 1,
					sampleRateHz = session.sampleRateHz,
					channels = session.channels,
					chunkSeconds = session.chunkSeconds
				)
				session = store.bindCloudSession(localSessionId, init.sessionId, init.sessionStartEpochMs)
			} catch (error: Throwable) {
				store.markSessionError(localSessionId, "Session init failed: ${error.message}")
				return classify(error)
			}
		}

		val cloudSessionId = session.cloudSessionId ?: return SyncResult.RETRYABLE_FAILURE

		// Clinical markers are tiny, so send them before EEG chunks to reach Cloud
		// promptly. A marker failure never blocks EEG upload or finalization; it only
		// keeps the session in the sync queue until the markers are acknowledged.
		val markerResult = syncMarkers(session)
		if (session.state == CloudSessionState.FINALIZED) return markerResult
		if (markerResult == SyncResult.AUTH_REQUIRED) return markerResult

		// Once Stop has been durably persisted, keep re-announcing the finalization
		// boundary until Cloud has acknowledged it. This is deliberately fail-open:
		// inability to update the control-plane status must not prevent remaining
		// chunks from uploading and verifying.
		if (session.finalSeqExclusive != null && session.state != CloudSessionState.FINALIZED) {
			runCatching {
				orgApi.sessionFinalizationIntent(
					orgId = session.orgId,
					subjectId = session.subjectId,
					sessionId = cloudSessionId,
					clientSessionId = session.localSessionId,
					finalSeqExclusive = session.finalSeqExclusive,
					endedAtEpochMs = session.endedAtEpochMs ?: System.currentTimeMillis()
				)
			}.onSuccess {
				Log.d(
					TAG,
					"FINALIZATION_INTENT_SYNCED localSessionId=${session.localSessionId} " +
						"cloudSessionId=$cloudSessionId finalSeqExclusive=${session.finalSeqExclusive}"
				)
			}.onFailure { error ->
				Log.w(
					TAG,
					"Finalization intent acknowledgement pending ${session.localSessionId}: ${error.message}"
				)
			}
		}

		val coverage = try {
			orgApi.sessionCoverage(session.orgId, session.subjectId, cloudSessionId)
		} catch (error: Throwable) {
			store.markSessionError(localSessionId, "Coverage check failed: ${error.message}")
			if (!session.isActivelyRecording && error is CloudApiException && error.statusCode == 404) {
				// The cloud session no longer exists. Keep every local chunk (this may be
				// the only copy) and stop re-requesting it every pass.
				val firstReport = sessionBackoff[localSessionId]?.first != CLOUD_SESSION_MISSING
				sessionBackoff[localSessionId] =
					CLOUD_SESSION_MISSING to (System.currentTimeMillis() + CLOUD_SESSION_MISSING_RETRY_MS)
				if (firstReport) {
					val localChunks = runCatching { store.listChunks(localSessionId).size }.getOrDefault(-1)
					Log.e(
						TAG,
						"CLOUD_SESSION_MISSING localSessionId=$localSessionId cloudSessionId=$cloudSessionId " +
							"localChunks=$localChunks finalSeqExclusive=${session.finalSeqExclusive}; " +
							"local data kept, next check in ${CLOUD_SESSION_MISSING_RETRY_MS / 3_600_000L} h"
					)
				}
				return SyncResult.RETRYABLE_FAILURE
			}
			return classify(error)
		}
		if (sessionBackoff[localSessionId]?.first == CLOUD_SESSION_MISSING) {
			// The session exists again (restored or re-created on the server).
			sessionBackoff.remove(localSessionId)
			Log.i(TAG, "CLOUD_SESSION_FOUND_AGAIN localSessionId=$localSessionId cloudSessionId=$cloudSessionId")
		}
		reconcileCoverage(session, coverage)

		val uploadResult = if (session.isActivelyRecording) {
			uploadActiveSessionChunks(session)
		} else {
			uploadBacklogChunks(session)
		}
		if (uploadResult != SyncResult.COMPLETE) return uploadResult

		session = store.loadSession(localSessionId) ?: return SyncResult.COMPLETE
		val unverified = store.listUnverifiedChunks(localSessionId)
		if (session.finalSeqExclusive != null && unverified.isEmpty()) {
			return withMarkers(finalizeSession(session), markerResult)
		}

		// RETRY_WAIT chunks whose retry time is still in the future are intentionally
		// absent from listPendingChunks(). They are still unfinished durable work and
		// must keep WorkManager alive instead of being mistaken for a completed sync.
		if (unverified.any { it.state == ChunkUploadState.FAILED_PERMANENT }) {
			return SyncResult.PERMANENT_FAILURE
		}
		return if (unverified.isNotEmpty()) SyncResult.RETRYABLE_FAILURE else withMarkers(SyncResult.COMPLETE, markerResult)
	}

	/**
	 * Sends PENDING markers whose retry time has arrived. Transport errors are
	 * always retried (never permanent), so a not-yet-deployed endpoint or an outage
	 * cannot lose markers. Only an explicit per-event server verdict
	 * ("conflict"/"rejected") marks a marker permanently failed.
	 */
	private fun syncMarkers(session: DurableCloudSession): SyncResult {
		val cloudSessionId = session.cloudSessionId ?: return SyncResult.RETRYABLE_FAILURE
		val now = System.currentTimeMillis()
		val pending = try {
			store.listPendingMarkers(session.localSessionId, now)
		} catch (error: Throwable) {
			Log.w(TAG, "Unable to read markers ${session.localSessionId}", error)
			return SyncResult.RETRYABLE_FAILURE
		}
		if (pending.isEmpty()) {
			// Markers still in retry back-off are unfinished work.
			return if (store.hasUnsyncedMarkers(session.localSessionId)) SyncResult.RETRYABLE_FAILURE
			else SyncResult.COMPLETE
		}

		var sawRetry = false
		for (batch in pending.chunked(MARKER_BATCH_SIZE)) {
			val outcomes = LinkedHashMap<String, MarkerSyncOutcome>()
			try {
				val results = orgApi.sessionEventsPost(
					orgId = session.orgId,
					subjectId = session.subjectId,
					sessionId = cloudSessionId,
					events = batch.map { marker ->
						SessionEventUpload(
							clientEventId = marker.clientEventId,
							kind = marker.kind,
							code = marker.code,
							label = marker.label,
							note = marker.note,
							sampleIndex = marker.sampleIndex,
							tOffsetMs = marker.tOffsetMs,
							wallClockEpochMs = marker.wallClockEpochMs,
							clientCreatedAtEpochMs = marker.createdAtEpochMs
						)
					}
				)
				val byClientId = results.mapNotNull { r -> r.clientEventId?.let { it to r } }.toMap()
				for (marker in batch) {
					val result = byClientId[marker.clientEventId]
					outcomes[marker.clientEventId] = when (result?.status) {
						"created", "existing" -> MarkerSyncOutcome.Synced(result.eventId)
						"conflict", "rejected" -> {
							Log.e(
								TAG,
								"Marker rejected by Cloud ${session.localSessionId}/${marker.clientEventId}: ${result.error}"
							)
							MarkerSyncOutcome.Rejected(result.error ?: result.status)
						}
						else -> {
							sawRetry = true
							MarkerSyncOutcome.Retry(
								"No server result for marker",
								now + retryDelayMs(marker.attempts)
							)
						}
					}
				}
			} catch (error: Throwable) {
				for (marker in batch) {
					outcomes[marker.clientEventId] = MarkerSyncOutcome.Retry(
						error.message ?: error.javaClass.simpleName,
						now + retryDelayMs(marker.attempts)
					)
				}
				store.applyMarkerSyncOutcomes(session.localSessionId, outcomes)
				Log.w(TAG, "Marker sync retry scheduled ${session.localSessionId}", error)
				return if (classify(error) == SyncResult.AUTH_REQUIRED) SyncResult.AUTH_REQUIRED
				else SyncResult.RETRYABLE_FAILURE
			}
			store.applyMarkerSyncOutcomes(session.localSessionId, outcomes)
		}
		return if (sawRetry) SyncResult.RETRYABLE_FAILURE else SyncResult.COMPLETE
	}

	/** EEG result wins; otherwise outstanding marker work keeps the sync alive. */
	private fun withMarkers(primary: SyncResult, markers: SyncResult): SyncResult = when {
		primary != SyncResult.COMPLETE -> primary
		markers == SyncResult.RETRYABLE_FAILURE || markers == SyncResult.AUTH_REQUIRED -> markers
		else -> SyncResult.COMPLETE
	}

	/**
	 * Keep the live edge near real time, then fairly drain a small amount of
	 * history. The candidate list is refreshed after every mini-batch so chunks
	 * completed while the pass is running can jump to the live lane.
	 */
	private suspend fun uploadActiveSessionChunks(session: DurableCloudSession): SyncResult {
		var attemptedThisPass = 0
		var sawRetryableFailure = false

		while (attemptedThisPass < MAX_ACTIVE_CHUNKS_PER_PASS) {
			if (!currentCoroutineContext().isActive) return SyncResult.RETRYABLE_FAILURE
			val pending = store.listPendingChunks(session.localSessionId)
			if (pending.isEmpty()) {
				return if (sawRetryableFailure) SyncResult.RETRYABLE_FAILURE else SyncResult.COMPLETE
			}

			Log.d(
				TAG,
				"Active queue ${session.localSessionId}: pending=${pending.size} " +
					"oldest=${pending.minOfOrNull { it.seq }} newest=${pending.maxOfOrNull { it.seq }}"
			)

			val newest = pending.maxByOrNull { it.seq } ?: break
			when (uploadChunk(session, newest.seq)) {
				SyncResult.COMPLETE -> Unit
				SyncResult.RETRYABLE_FAILURE -> sawRetryableFailure = true
				SyncResult.AUTH_REQUIRED -> return SyncResult.AUTH_REQUIRED
				SyncResult.PERMANENT_FAILURE -> return SyncResult.PERMANENT_FAILURE
			}
			attemptedThisPass++

			if (attemptedThisPass >= MAX_ACTIVE_CHUNKS_PER_PASS) break

			val backlog = store.listPendingChunks(session.localSessionId)
				.filter { it.seq != newest.seq }
				.sortedBy { it.seq }
				.take(minOf(ACTIVE_BACKLOG_BATCH, MAX_ACTIVE_CHUNKS_PER_PASS - attemptedThisPass))

			for (chunk in backlog) {
				if (!currentCoroutineContext().isActive) return SyncResult.RETRYABLE_FAILURE
				when (uploadChunk(session, chunk.seq)) {
					SyncResult.COMPLETE -> Unit
					SyncResult.RETRYABLE_FAILURE -> sawRetryableFailure = true
					SyncResult.AUTH_REQUIRED -> return SyncResult.AUTH_REQUIRED
					SyncResult.PERMANENT_FAILURE -> return SyncResult.PERMANENT_FAILURE
				}
				attemptedThisPass++
			}
		}

		val remain = store.listPendingChunks(session.localSessionId).size
		return if (remain > 0 || sawRetryableFailure) SyncResult.RETRYABLE_FAILURE else SyncResult.COMPLETE
	}

	private suspend fun uploadBacklogChunks(session: DurableCloudSession): SyncResult {
		for (chunk in store.listPendingChunks(session.localSessionId).sortedBy { it.seq }) {
			if (!currentCoroutineContext().isActive) return SyncResult.RETRYABLE_FAILURE
			val result = uploadChunk(session, chunk.seq)
			if (result != SyncResult.COMPLETE) return result
		}
		return SyncResult.COMPLETE
	}

	private fun recoverInterruptedUploads(localSessionId: String) {
		for (chunk in store.listChunks(localSessionId)) {
			if (chunk.state == ChunkUploadState.UPLOADING) {
				store.requeueChunk(localSessionId, chunk.seq, "Recovered interrupted upload")
				Log.w(TAG, "Recovered interrupted upload $localSessionId/${chunk.seq}")
			}
		}
	}

	private fun reconcileCoverage(session: DurableCloudSession, coverage: SessionCoverageResp) {
		for (chunk in store.listChunks(session.localSessionId)) {
			val first = chunk.firstSampleIndex
			val last = first + chunk.sampleCount - 1L
			val cloudVerified = coverage.verifiedRanges.any {
				it.firstSampleIndex <= first && it.lastSampleIndexInclusive >= last
			}
			val cloudMissing = coverage.missingRanges.any {
				it.firstSampleIndex <= last && it.lastSampleIndexInclusive >= first
			}
			when {
				cloudVerified && chunk.state != ChunkUploadState.VERIFIED -> {
					store.markChunkVerified(session.localSessionId, chunk.seq)
					if (!store.retireVerifiedChunk(session.localSessionId, chunk.seq)) {
						Log.w(TAG, "Verified chunk cleanup deferred ${session.localSessionId}/${chunk.seq}")
					}
				}
				cloudMissing && chunk.state == ChunkUploadState.VERIFIED ->
					store.requeueChunk(session.localSessionId, chunk.seq, "Cloud coverage reported this range missing")
			}
		}
		// Do not mark FINALIZED from coverage alone. finalizeSession() performs the
		// stronger acknowledgement protocol (coverage + finalized manifest readback)
		// before local cleanup is allowed.
	}

	private fun uploadChunk(session: DurableCloudSession, seq: Int): SyncResult {
		val cloudSessionId = session.cloudSessionId ?: return SyncResult.RETRYABLE_FAILURE
		var chunk = store.loadChunk(session.localSessionId, seq) ?: return SyncResult.COMPLETE
		if (!store.verifyChunkPayload(chunk)) {
			store.markChunkPermanentFailure(session.localSessionId, seq, "Local payload failed SHA-256 verification")
			store.markSessionError(session.localSessionId, "Local payload corrupt for seq=$seq")
			return SyncResult.PERMANENT_FAILURE
		}
		var putCompleted = chunk.state == ChunkUploadState.PUT_COMPLETE
		return try {
			val priorState = chunk.state
			chunk = store.markChunkUploading(session.localSessionId, seq)
			if (priorState != ChunkUploadState.PUT_COMPLETE) {
				val key = "${session.localSessionId}:${chunk.seq}"
				val init = orgApi.sessionChunkInit(
					session.orgId, session.subjectId, cloudSessionId, key, chunk.seq,
					chunk.firstSampleIndex, chunk.sampleCount, chunk.tStartMs, chunk.tEndMs,
					chunk.bytes, chunk.sha256Base64
				)
				chunk = store.markChunkCloudAllocated(session.localSessionId, seq, init.chunkId, init.gcsPath)
				if (init.alreadyVerified) {
					store.markChunkVerified(session.localSessionId, seq)
					if (!store.retireVerifiedChunk(session.localSessionId, seq)) {
						Log.w(TAG, "Verified chunk cleanup deferred ${session.localSessionId}/$seq")
					}
					return SyncResult.COMPLETE
				}
				if (init.uploadRequired) {
					val url = init.signedPutUrl ?: error("Cloud requested upload without signedPutUrl")
					val encryptedPayload = store.payloadFile(chunk)
					val uploadWorkDir = java.io.File(encryptedPayload.parentFile, ".upload-work")
					val plaintextPayload = store.createPlaintextUploadFile(chunk, uploadWorkDir)
					try {
						val request = Request.Builder().url(url)
							.put(plaintextPayload.asRequestBody("application/octet-stream".toMediaTypeOrNull())).build()
						httpClient.newCall(request).execute().use { response ->
							val body = response.body?.string().orEmpty()
							if (!response.isSuccessful) error("PUT failed HTTP ${response.code}: $body")
						}
					} finally {
						plaintextPayload.delete()
						uploadWorkDir.delete()
					}
				}
				chunk = store.markChunkPutComplete(session.localSessionId, seq)
				putCompleted = true
			}
			val chunkId = chunk.cloudChunkId ?: error("Cloud chunk ID missing")
			val completion = orgApi.sessionChunkComplete(
				session.orgId, session.subjectId, cloudSessionId,
				"${session.localSessionId}:${chunk.seq}", chunkId, chunk.bytes, chunk.sha256Base64
			)
			check(completion.verified) { "Cloud did not verify chunk status=${completion.status}" }
			check(completion.bytes == null || completion.bytes == chunk.bytes) { "Cloud byte count mismatch" }
			check(completion.sha256 == null || completion.sha256 == chunk.sha256Base64) { "Cloud checksum mismatch" }
			store.markChunkVerified(session.localSessionId, seq)
			if (!store.retireVerifiedChunk(session.localSessionId, seq)) {
				Log.w(TAG, "Verified chunk cleanup deferred ${session.localSessionId}/$seq")
			}
			SyncResult.COMPLETE
		} catch (error: Throwable) {
			when (classify(error)) {
				SyncResult.AUTH_REQUIRED -> {
					store.markChunkRetryableFailure(
						session.localSessionId, seq, "Authorization required: ${error.message}",
						System.currentTimeMillis() + retryDelayMs(chunk.uploadAttempts), putCompleted
					)
					store.markSessionError(session.localSessionId, "Authorization required before sync can continue")
					SyncResult.AUTH_REQUIRED
				}
				SyncResult.PERMANENT_FAILURE -> {
					store.markChunkPermanentFailure(session.localSessionId, seq, error.message ?: "Permanent API failure")
					store.markSessionError(session.localSessionId, "Permanent chunk failure seq=$seq: ${error.message}")
					SyncResult.PERMANENT_FAILURE
				}
				else -> {
					val latest = store.loadChunk(session.localSessionId, seq) ?: chunk
					store.markChunkRetryableFailure(
						session.localSessionId, seq, error.message ?: error.javaClass.simpleName,
						System.currentTimeMillis() + retryDelayMs(latest.uploadAttempts), putCompleted
					)
					Log.w(TAG, "Retrying ${session.localSessionId}/$seq", error)
					SyncResult.RETRYABLE_FAILURE
				}
			}
		}
	}

	private fun finalizeSession(session: DurableCloudSession): SyncResult {
		val cloudSessionId = session.cloudSessionId ?: return SyncResult.RETRYABLE_FAILURE
		val finalSeq = session.finalSeqExclusive ?: return SyncResult.COMPLETE
		return try {
			val preflight = orgApi.sessionCoverage(session.orgId, session.subjectId, cloudSessionId)
			reconcileCoverage(session, preflight)

			// If a previous finalize POST succeeded but Android never received the
			// acknowledgement, confirm the already-finalized cloud state first.
			if (preflight.finalized && preflight.missingRanges.isEmpty()) {
				confirmFinalizedManifest(session, cloudSessionId, finalSeq)
				store.markSessionFinalized(session.localSessionId)
				finalizeBackoff.remove(session.localSessionId)
				Log.i(TAG, "FINALIZATION_CONFIRMED ${session.localSessionId} (preflight readback)")
				return SyncResult.COMPLETE
			}

			if (preflight.missingRanges.isNotEmpty()) {
				// Logged so a session that can never finalize shows why (for example a sample-index
				// gap left by a resume or a Bluetooth outage that Cloud counts as missing data).
				Log.w(
					TAG,
					"FINALIZATION_BLOCKED_MISSING_RANGES localSessionId=${session.localSessionId} " +
						"cloudSessionId=$cloudSessionId finalSeqExclusive=$finalSeq " +
						"count=${preflight.missingRanges.size} ranges=" +
						preflight.missingRanges.take(6).joinToString(",") {
							"${it.firstSampleIndex}-${it.lastSampleIndexInclusive}"
						}
				)
				store.markSessionFinalizationRetry(
					session.localSessionId,
					"Finalize pending: cloud coverage still reports ${preflight.missingRanges.size} missing range(s)"
				)
				return SyncResult.RETRYABLE_FAILURE
			}

			if (store.listUnverifiedChunks(session.localSessionId).isNotEmpty()) {
				return SyncResult.RETRYABLE_FAILURE
			}

			val pending = if (session.state == CloudSessionState.FINALIZATION_PENDING) session
			else store.markSessionFinalizationPending(session.localSessionId)

			Log.i(
				TAG,
				"FINALIZATION_SUBMIT localSessionId=${pending.localSessionId} " +
					"cloudSessionId=$cloudSessionId finalSeqExclusive=$finalSeq"
			)
			val result = orgApi.sessionFinalize(
				pending.orgId, pending.subjectId, cloudSessionId, pending.localSessionId,
				finalSeq, pending.endedAtEpochMs ?: System.currentTimeMillis()
			)
			check(result.finalized) { "Cloud did not finalize session status=${result.status}" }

			val postflight = orgApi.sessionCoverage(session.orgId, session.subjectId, cloudSessionId)
			check(postflight.finalized) { "Coverage endpoint does not confirm finalization" }
			check(postflight.missingRanges.isEmpty()) { "Cloud reports missing ranges after finalization" }
			confirmFinalizedManifest(session, cloudSessionId, finalSeq)

			store.markSessionFinalized(session.localSessionId)
			finalizeBackoff.remove(session.localSessionId)
			Log.i(
				TAG,
				"FINALIZATION_CONFIRMED localSessionId=${session.localSessionId} " +
					"cloudSessionId=$cloudSessionId manifestPath=${result.manifestPath}"
			)
			SyncResult.COMPLETE
		} catch (error: Throwable) {
			// Preserve the stopped recording's durable finalization intent. Every future
			// foreground/WorkManager pass retries the same idempotent finalize request
			// until independent cloud readback confirms it was actually applied.
			store.markSessionFinalizationRetry(
				session.localSessionId,
				"Finalize pending: ${error.message ?: error.javaClass.simpleName}"
			)
			val attempts = (finalizeBackoff[session.localSessionId]?.first ?: 0) + 1
			val delayMs = (FINALIZE_BACKOFF_BASE_MS * (1L shl (attempts - 1).coerceIn(0, 12)))
				.coerceAtMost(FINALIZE_BACKOFF_MAX_MS)
			finalizeBackoff[session.localSessionId] = attempts to (System.currentTimeMillis() + delayMs)
			Log.w(
				TAG,
				"Finalization remains pending ${session.localSessionId} (attempt $attempts, next in ${delayMs / 1000}s)",
				error
			)
			classify(error)
		}
	}

	private fun confirmFinalizedManifest(
		session: DurableCloudSession,
		cloudSessionId: String,
		finalSeqExclusive: Int
	) {
		val manifest = orgApi.sessionManifest(session.orgId, session.subjectId, cloudSessionId)
		check(manifest.finalized) { "Manifest endpoint does not confirm finalization" }
		val sequences = manifest.chunks.map { it.seq }.toSet()
		val missingSequences = (0 until finalSeqExclusive).filterNot { it in sequences }
		check(missingSequences.isEmpty()) {
			"Finalized manifest is missing chunk sequences: ${missingSequences.take(8)}"
		}
	}

	private fun classify(error: Throwable): SyncResult {
		if (error is CloudApiException) {
			if (error.statusCode == 401 || error.statusCode == 403) return SyncResult.AUTH_REQUIRED
			if (!error.retryable) return SyncResult.PERMANENT_FAILURE
		}
		return SyncResult.RETRYABLE_FAILURE
	}

	private fun retryDelayMs(attempts: Int): Long =
		(1_000L * (1L shl attempts.coerceIn(0, 10))).coerceAtMost(15L * 60L * 1_000L)

	private val DurableCloudSession.isActivelyRecording: Boolean
		get() = finalSeqExclusive == null && state in setOf(
			CloudSessionState.LOCAL_ONLY,
			CloudSessionState.ACTIVE,
			CloudSessionState.ERROR
		)
}

data class CloudSyncOutcome(
	val hasRetryableWork: Boolean = false,
	val hasPermanentFailure: Boolean = false,
	val authRequired: Boolean = false,
	val activeHasRetryableWork: Boolean = false,
	val activeHasPermanentFailure: Boolean = false,
	val activeAuthRequired: Boolean = false
)

private enum class SyncResult {
	COMPLETE,
	RETRYABLE_FAILURE,
	AUTH_REQUIRED,
	PERMANENT_FAILURE
}