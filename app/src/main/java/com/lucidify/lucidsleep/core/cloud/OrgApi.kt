package com.lucidify.lucidsleep.core.cloud

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import com.lucidify.lucidsleep.core.cloudplayback.ChunkMeta
import com.lucidify.lucidsleep.core.cloudplayback.SessionManifest

data class OrgRow(val id: String, val name: String, val orgType: String, val role: String, val subOrgId: String?)
data class SubOrgRow(val id: String, val name: String)
data class SubjectRow(val id: String, val subOrgId: String, val subjectCode: String, val displayName: String)
data class UploadRow(val id: String, val originalFilename: String?, val bytes: Long, val contentType: String?, val uploadedAt: String?)

data class CloudSessionRow(
	val sessionId: String,
	val uploadId: String?,
	val clientSessionId: String?,
	val status: String,
	val format: String?,
	val formatVersion: Int,
	val sampleRateHz: Int,
	val channels: List<String>,
	val chunkSeconds: Int,
	val startedAtEpochMs: Long,
	val endedAtEpochMs: Long?,
	val finalSeqExclusive: Int?,
	val verifiedChunkCount: Int,
	val verifiedSampleCount: Long,
	val verifiedBytes: Long,
	val lastVerifiedSeq: Int?,
	val lastVerifiedSample: Long?,
	val lastChunkEndMs: Long?,
	val lastChunkUpdatedAt: String?,
	val finalizedAt: String?,
	val createdAt: String?,
	val updatedAt: String?
)
data class UploadInitResp(
    val uploadId: String,
    val objectName: String,
    val signedPutUrl: String,
    val ext: String,
    val contentType: String
)

data class UploadCompleteResp(
    val upload: UploadRow,
    val edf: org.json.JSONObject? = null
)
data class SessionInitResp(
	val sessionId: String,
	val chunkSeconds: Int,
	val sessionStartEpochMs: Long,
	val reused: Boolean
)

data class ChunkInitResp(
	val chunkId: String,
	val gcsPath: String,
	val signedPutUrl: String?,
	val uploadRequired: Boolean,
	val alreadyVerified: Boolean
)

data class ChunkCompleteResp(
	val status: String,
	val verified: Boolean,
	val bytes: Long?,
	val sha256: String?
)



data class CoverageRange(val firstSampleIndex: Long, val lastSampleIndexInclusive: Long)
data class SessionCoverageResp(
	val contiguousThroughSample: Long?,
	val verifiedRanges: List<CoverageRange>,
	val missingRanges: List<CoverageRange>,
	val finalized: Boolean
)

data class SessionFinalizeResp(
	val status: String,
	val manifestPath: String?,
	val finalized: Boolean
)

/** One clinical marker sent to POST .../session/:sessionId/events. */
data class SessionEventUpload(
	val clientEventId: String,
	val kind: String,
	val code: String?,
	val label: String,
	val note: String?,
	val sampleIndex: Long,
	val tOffsetMs: Long,
	val wallClockEpochMs: Long,
	val clientCreatedAtEpochMs: Long
)

/**
 * Per-event server verdict. status is "created" or "existing" (acknowledged),
 * or "conflict" / "rejected" (permanent for that event).
 */
data class SessionEventResult(
	val clientEventId: String?,
	val status: String,
	val eventId: String?,
	val error: String?
)

class CloudApiException(
	val statusCode: Int,
	val responseBody: String,
	val retryable: Boolean
) : RuntimeException("HTTP $statusCode: $responseBody")
class OrgApi(
    private val apiBase: String,
    private val http: OkHttpClient
) {
    private fun get(url: String): String {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw CloudApiException(
                    statusCode = resp.code,
                    responseBody = body,
                    retryable = resp.code == 408 || resp.code == 409 || resp.code == 425 ||
                        resp.code == 429 || resp.code >= 500
                )
            }
            return body
        }
    }

    fun orgMe(): List<OrgRow> {
        val json = JSONObject(get("$apiBase/v1/org/me"))
        val arr = json.optJSONArray("orgs") ?: JSONArray()
        val out = ArrayList<OrgRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                OrgRow(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    orgType = o.optString("org_type"),
                    role = o.optString("role"),
                    subOrgId = o.optString("sub_org_id").ifBlank { null }
                )
            )
        }
        return out.filter { it.id.isNotBlank() }
    }

    fun subOrgs(orgId: String): List<SubOrgRow> {
        val json = JSONObject(get("$apiBase/v1/org/$orgId/suborgs"))
        val arr = json.optJSONArray("sub_orgs") ?: JSONArray()
        val out = ArrayList<SubOrgRow>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            out.add(SubOrgRow(id = s.optString("id"), name = s.optString("name")))
        }
        return out.filter { it.id.isNotBlank() }
    }

    fun subjects(orgId: String): List<SubjectRow> {
        val json = JSONObject(get("$apiBase/v1/org/$orgId/subjects"))
        val arr = json.optJSONArray("subjects") ?: JSONArray()
        val out = ArrayList<SubjectRow>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            out.add(
                SubjectRow(
                    id = s.optString("id"),
                    subOrgId = s.optString("sub_org_id"),
                    subjectCode = s.optString("subject_code"),
                    displayName = s.optString("display_name")
                )
            )
        }
        return out.filter { it.id.isNotBlank() }
    }

    fun createSubject(orgId: String, subOrgId: String, subjectCode: String, displayName: String): SubjectRow {
        val payload = JSONObject()
            .put("sub_org_id", subOrgId.trim())
            .put("subject_code", subjectCode.trim())
            .put("display_name", displayName.trim())
        val row = JSONObject(postJson("$apiBase/v1/org/$orgId/subjects", payload))
        return SubjectRow(
            id = row.optString("id"),
            subOrgId = row.optString("sub_org_id"),
            subjectCode = row.optString("subject_code"),
            displayName = row.optString("display_name")
        ).also { require(it.id.isNotBlank()) { "Cloud did not return the new patient ID" } }
    }


	fun sessions(orgId: String, subjectId: String): List<CloudSessionRow> {
		val json = JSONObject(get("$apiBase/v1/org/$orgId/subjects/$subjectId/sessions/manage"))
		val arr = json.optJSONArray("sessions") ?: JSONArray()
		val out = ArrayList<CloudSessionRow>(arr.length())

		fun JSONObject.nullableString(name: String): String? =
			if (!has(name) || isNull(name)) null else optString(name).ifBlank { null }

		fun JSONObject.nullableLong(name: String): Long? =
			if (!has(name) || isNull(name)) null else optLong(name)

		fun JSONObject.nullableInt(name: String): Int? =
			if (!has(name) || isNull(name)) null else optInt(name)

		for (i in 0 until arr.length()) {
			val row = arr.optJSONObject(i) ?: continue
			val channelArray = row.optJSONArray("channels") ?: JSONArray()
			val channels = List(channelArray.length()) { index ->
				channelArray.optString(index)
			}.filter { it.isNotBlank() }

			out.add(
				CloudSessionRow(
					sessionId = row.optString("sessionId"),
					uploadId = row.nullableString("uploadId"),
					clientSessionId = row.nullableString("clientSessionId"),
					status = row.optString("status"),
					format = row.nullableString("format"),
					formatVersion = row.optInt("formatVersion", 1),
					sampleRateHz = row.optInt("sampleRateHz", 500),
					channels = channels,
					chunkSeconds = row.optInt("chunkSeconds", 5),
					startedAtEpochMs = row.optLong("startedAtEpochMs", 0L),
					endedAtEpochMs = row.nullableLong("endedAtEpochMs"),
					finalSeqExclusive = row.nullableInt("finalSeqExclusive"),
					verifiedChunkCount = row.optInt("verifiedChunkCount", 0),
					verifiedSampleCount = row.optLong("verifiedSampleCount", 0L),
					verifiedBytes = row.optLong("verifiedBytes", 0L),
					lastVerifiedSeq = row.nullableInt("lastVerifiedSeq"),
					lastVerifiedSample = row.nullableLong("lastVerifiedSample"),
					lastChunkEndMs = row.nullableLong("lastChunkEndMs"),
					lastChunkUpdatedAt = row.nullableString("lastChunkUpdatedAt"),
					finalizedAt = row.nullableString("finalizedAt"),
					createdAt = row.nullableString("createdAt"),
					updatedAt = row.nullableString("updatedAt")
				)
			)
		}

		return out
			.filter { it.sessionId.isNotBlank() }
			.sortedByDescending { it.startedAtEpochMs }
	}

    fun uploads(orgId: String, subjectId: String): List<UploadRow> {
        val json = JSONObject(get("$apiBase/v1/org/$orgId/subjects/$subjectId/uploads"))
        val arr = json.optJSONArray("uploads") ?: JSONArray()
        val out = ArrayList<UploadRow>(arr.length())
        for (i in 0 until arr.length()) {
            val u = arr.getJSONObject(i)
            out.add(
                UploadRow(
                    id = u.optString("id"),
                    originalFilename = u.optString("original_filename").ifBlank { null },
                    bytes = u.optLong("bytes", 0L),
                    contentType = u.optString("content_type").ifBlank { null },
                    uploadedAt = u.optString("uploaded_at").ifBlank { null }
                )
            )
        }
        return out.filter { it.id.isNotBlank() }
    }
	private fun postJson(
		url: String,
		payload: JSONObject,
		idempotencyKey: String? = null
	): String {
		val body = payload.toString()
			.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
		val requestBuilder = Request.Builder()
			.url(url)
			.post(body)
		if (!idempotencyKey.isNullOrBlank()) {
			requestBuilder.header("Idempotency-Key", idempotencyKey)
			requestBuilder.header("X-Client-Operation-Id", idempotencyKey)
		}
		val req = requestBuilder.build()
		Log.d("CloudSession_OrgApi", "POST ${req.url}")
		http.newCall(req).execute().use { resp ->
			val txt = resp.body?.string().orEmpty()
			if (!resp.isSuccessful) {
				throw CloudApiException(
					statusCode = resp.code,
					responseBody = txt,
					retryable = resp.code == 408 || resp.code == 409 || resp.code == 425 ||
						resp.code == 429 || resp.code >= 500
				)
			}
			return txt
		}
	}
    fun uploadsInit(
        orgId: String,
        subjectId: String,
        originalFilename: String,
        contentType: String?
    ): UploadInitResp {
        val payload = JSONObject().apply {
            put("original_filename", originalFilename)
            if (!contentType.isNullOrBlank()) put("content_type", contentType)
        }
        val json = JSONObject(
            postJson("$apiBase/v1/org/$orgId/subjects/$subjectId/uploads/init", payload)
        )
        return UploadInitResp(
            uploadId = json.getString("upload_id"),
            objectName = json.getString("object_name"),
            signedPutUrl = json.getString("signed_put_url"),
            ext = json.optString("ext"),
            contentType = json.optString("content_type").ifBlank {
                contentType ?: "application/octet-stream"
            }
        )
    }

    fun uploadsComplete(
        orgId: String,
        subjectId: String,
        uploadId: String,
        originalFilename: String
    ): UploadCompleteResp {
        val payload = JSONObject().apply {
            put("original_filename", originalFilename)
        }
        val json = JSONObject(
            postJson("$apiBase/v1/org/$orgId/subjects/$subjectId/uploads/$uploadId/complete", payload)
        )

        val up = json.getJSONObject("upload")
        val uploadRow = UploadRow(
            id = up.optString("id"),
            originalFilename = up.optString("original_filename").ifBlank { null },
            bytes = up.optLong("bytes", 0L),
            contentType = up.optString("content_type").ifBlank { null },
            uploadedAt = up.optString("uploaded_at").ifBlank { null }
        )

        return UploadCompleteResp(
            upload = uploadRow,
            edf = json.optJSONObject("edf")
        )
    }
	fun sessionInit(
		orgId: String,
		subjectId: String,
		clientSessionId: String,
		startedAtEpochMs: Long,
		formatVersion: Int,
		sampleRateHz: Int,
		channels: List<String>,
		chunkSeconds: Int
	): SessionInitResp {
		require(clientSessionId.isNotBlank()) { "clientSessionId must not be blank" }
		val payload = JSONObject().apply {
			put("clientSessionId", clientSessionId)
			put("startedAtEpochMs", startedAtEpochMs)
			put("format", "cgx")
			put("formatVersion", formatVersion)
			put("sampleRateHz", sampleRateHz)
			put("channels", JSONArray(channels))
			put("chunkSeconds", chunkSeconds)
		}
		val json = JSONObject(
			postJson(
				"$apiBase/v1/org/$orgId/subjects/$subjectId/session/init",
				payload,
				idempotencyKey = "session:$clientSessionId:init"
			)
		)
		return SessionInitResp(
			sessionId = json.getString("sessionId"),
			chunkSeconds = json.getInt("chunkSeconds"),
			sessionStartEpochMs = json.getLong("sessionStartEpochMs"),
			reused = json.optBoolean("reused", false)
		)
	}

	fun sessionChunkInit(
		orgId: String,
		subjectId: String,
		sessionId: String,
		clientChunkKey: String,
		seq: Int,
		firstSampleIndex: Long,
		sampleCount: Int,
		tStartMs: Long,
		tEndMs: Long,
		bytes: Long,
		sha256b64: String? = null
	): ChunkInitResp {
		require(clientChunkKey.isNotBlank()) { "clientChunkKey must not be blank" }
		val payload = JSONObject().apply {
			put("clientChunkKey", clientChunkKey)
			put("seq", seq)
			put("firstSampleIndex", firstSampleIndex)
			put("sampleCount", sampleCount)
			put("tStartMs", tStartMs)
			put("tEndMs", tEndMs)
			put("bytes", bytes)
			put("contentType", "application/octet-stream")
			if (!sha256b64.isNullOrBlank()) put("sha256", sha256b64)
		}
		val json = JSONObject(
			postJson(
				"$apiBase/v1/org/$orgId/subjects/$subjectId/session/$sessionId/chunk/init",
				payload,
				idempotencyKey = "chunk:$clientChunkKey:init"
			)
		)
		return ChunkInitResp(
			chunkId = json.getString("chunkId"),
			gcsPath = json.getString("gcsPath"),
			signedPutUrl = json.optString("signedPutUrl").ifBlank { null },
			uploadRequired = json.optBoolean("uploadRequired", true),
			alreadyVerified = json.optBoolean("alreadyVerified", false)
		)
	}

	fun sessionChunkComplete(
		orgId: String,
		subjectId: String,
		sessionId: String,
		clientChunkKey: String,
		chunkId: String,
		bytes: Long,
		sha256b64: String? = null
	): ChunkCompleteResp {
		val payload = JSONObject().apply {
			put("clientChunkKey", clientChunkKey)
			put("bytes", bytes)
			if (!sha256b64.isNullOrBlank()) put("sha256", sha256b64)
		}
		val text = postJson(
			"$apiBase/v1/org/$orgId/subjects/$subjectId/session/$sessionId/chunk/$chunkId/complete",
			payload,
			idempotencyKey = "chunk:$clientChunkKey:complete"
		)
		val json = if (text.isBlank()) JSONObject() else JSONObject(text)
		val status = json.optString("status", "")
		val verified = json.optBoolean("verified", status.equals("verified", ignoreCase = true))
		return ChunkCompleteResp(
			status = status,
			verified = verified,
			bytes = if (json.has("bytes")) json.optLong("bytes") else null,
			sha256 = json.optString("sha256").ifBlank { null }
		)
	}

	fun sessionCoverage(
		orgId: String,
		subjectId: String,
		sessionId: String
	): SessionCoverageResp {
		val json = JSONObject(get("$apiBase/v1/org/$orgId/subjects/$subjectId/session/$sessionId/coverage"))
		fun parseRanges(name: String): List<CoverageRange> {
			val arr = json.optJSONArray(name) ?: JSONArray()
			val out = ArrayList<CoverageRange>(arr.length())
			for (i in 0 until arr.length()) {
				val item = arr.opt(i)
				when (item) {
					is JSONArray -> if (item.length() >= 2) {
						out.add(CoverageRange(item.optLong(0), item.optLong(1)))
					}
					is JSONObject -> {
						val first = item.optLong("firstSampleIndex", item.optLong("start", -1L))
						val last = item.optLong("lastSampleIndexInclusive", item.optLong("end", -1L))
						if (first >= 0L && last >= first) out.add(CoverageRange(first, last))
					}
				}
			}
			return out.sortedBy { it.firstSampleIndex }
		}
		return SessionCoverageResp(
			contiguousThroughSample = if (json.has("contiguousThroughSample")) json.optLong("contiguousThroughSample") else null,
			verifiedRanges = parseRanges("verifiedRanges"),
			missingRanges = parseRanges("missingRanges"),
			finalized = json.optBoolean("finalized", false)
		)
	}

	fun sessionFinalizationIntent(
		orgId: String,
		subjectId: String,
		sessionId: String,
		clientSessionId: String,
		finalSeqExclusive: Int,
		endedAtEpochMs: Long
	) {
		val payload = JSONObject().apply {
			put("clientSessionId", clientSessionId)
			put("finalSeqExclusive", finalSeqExclusive)
			put("endedAtEpochMs", endedAtEpochMs)
		}
		postJson(
			"$apiBase/v1/org/$orgId/subjects/$subjectId/session/$sessionId/finalization-intent",
			payload,
			idempotencyKey = "session:$clientSessionId:finalization-intent"
		)
	}

	fun sessionFinalize(
		orgId: String,
		subjectId: String,
		sessionId: String,
		clientSessionId: String,
		finalSeqExclusive: Int,
		endedAtEpochMs: Long
	): SessionFinalizeResp {
		val payload = JSONObject().apply {
			put("clientSessionId", clientSessionId)
			put("finalSeqExclusive", finalSeqExclusive)
			put("endedAtEpochMs", endedAtEpochMs)
		}
		val json = JSONObject(
			postJson(
				"$apiBase/v1/org/$orgId/subjects/$subjectId/session/$sessionId/finalize",
				payload,
				idempotencyKey = "session:$clientSessionId:finalize"
			)
		)
		val status = json.optString("status", "")
		return SessionFinalizeResp(
			status = status,
			manifestPath = json.optString("manifestPath").ifBlank { null },
			finalized = json.optBoolean("finalized", status.equals("finalized", ignoreCase = true))
		)
	}
	/**
	 * Uploads a batch of clinical markers. The server is idempotent per
	 * clientEventId, so a retried batch is always safe.
	 */
	fun sessionEventsPost(
		orgId: String,
		subjectId: String,
		sessionId: String,
		events: List<SessionEventUpload>
	): List<SessionEventResult> {
		require(events.isNotEmpty()) { "events must not be empty" }
		val array = JSONArray()
		for (event in events) {
			array.put(JSONObject().apply {
				put("clientEventId", event.clientEventId)
				put("kind", event.kind)
				if (!event.code.isNullOrBlank()) put("code", event.code)
				put("label", event.label)
				if (!event.note.isNullOrBlank()) put("note", event.note)
				put("sampleIndex", event.sampleIndex)
				put("tOffsetMs", event.tOffsetMs)
				put("wallClockEpochMs", event.wallClockEpochMs)
				put("clientCreatedAtEpochMs", event.clientCreatedAtEpochMs)
			})
		}
		val text = postJson(
			"$apiBase/v1/org/$orgId/subjects/$subjectId/session/$sessionId/events",
			JSONObject().put("events", array)
		)
		val json = if (text.isBlank()) JSONObject() else JSONObject(text)
		val results = json.optJSONArray("results") ?: JSONArray()

		fun JSONObject.nullableString(name: String): String? =
			if (!has(name) || isNull(name)) null else optString(name).ifBlank { null }

		return List(results.length()) { index ->
			val row = results.optJSONObject(index) ?: JSONObject()
			SessionEventResult(
				clientEventId = row.nullableString("clientEventId"),
				status = row.optString("status"),
				eventId = row.optJSONObject("event")?.nullableString("eventId"),
				error = row.nullableString("error")
			)
		}
	}

    // ---------------------------
    // Cloud session playback APIs
    // ---------------------------

    /**
     * Fetch session manifest JSON from org service and parse into SessionManifest.
     *
     * Expected response shape (flexible):
     * {
     *   "sessionId": "...",
     *   "sampleRateHz": 500,
     *   "channels": ["FP1","FP2","AFz"],
     *   "finalized": false,
     *   "chunks": [
     *     { "seq": 0, "t0Ms": 0, "durMs": 5000, "bytes": 123, "sha256": "...", "getUrl": "https://..." }
     *   ]
     * }
     *
     * Also supports chunk entries using tStartMs/tEndMs instead of t0Ms/durMs.
     */
    fun sessionManifest(
        orgId: String,
        subjectId: String,
        sessionId: String
    ): SessionManifest {
        // urls=0: only chunk numbers are needed here. Signing a download URL per chunk made
        // the manifest of a long recording take minutes, longer than the HTTP call timeout.
        val txt = get("$apiBase/v1/org/$orgId/subjects/$subjectId/session/$sessionId/manifest?urls=0")
        val json = JSONObject(txt)

        val sr = json.optInt("sampleRateHz", json.optInt("sample_rate_hz", 500))
        val finalized = json.optBoolean("finalized", false)

        val chArr = json.optJSONArray("channels") ?: JSONArray()
        val channels = ArrayList<String>(chArr.length())
        for (i in 0 until chArr.length()) channels.add(chArr.optString(i))

        val chunksArr = json.optJSONArray("chunks") ?: JSONArray()
        val chunks = ArrayList<ChunkMeta>(chunksArr.length())

        for (i in 0 until chunksArr.length()) {
            val c = chunksArr.optJSONObject(i) ?: continue
            val seq = c.optInt("seq", -1)
            if (seq < 0) continue

            val t0 = when {
                c.has("t0Ms") -> c.optLong("t0Ms", 0L)
                c.has("tStartMs") -> c.optLong("tStartMs", 0L)
                else -> 0L
            }

            val t1 = when {
                c.has("t1Ms") -> c.optLong("t1Ms", t0)
                c.has("tEndMs") -> c.optLong("tEndMs", t0)
                c.has("durMs") -> (t0 + c.optLong("durMs", 0L)).coerceAtLeast(t0)
                else -> t0
            }

            val bytes = if (c.has("bytes")) c.optLong("bytes") else null
            val sha = c.optString("sha256").ifBlank { null }
            val getUrl = c.optString("getUrl").ifBlank { null }

            chunks.add(
                ChunkMeta(
                    seq = seq,
                    t0Ms = t0,
                    t1Ms = t1,
                    bytes = bytes,
                    sha256 = sha,
                    getUrl = getUrl
                )
            )
        }

        chunks.sortBy { it.seq }

        return SessionManifest(
            sessionId = json.optString("sessionId", sessionId),
            sampleRateHz = sr,
            channels = channels,
            finalized = finalized,
            chunks = chunks
        )
    }

    /**
     * Get a signed GET URL for one chunk.
     *
     * Expected response:
     * { "signedGetUrl": "https://..." }
     *
     * If your server uses a different key, change here.
     */
    fun sessionChunkSignedGetUrl(
        orgId: String,
        subjectId: String,
        sessionId: String,
        seq: Int
    ): String {
        val txt = get("$apiBase/v1/org/$orgId/subjects/$subjectId/session/$sessionId/chunk/$seq/signedGet")
        val json = JSONObject(txt)
        return json.optString("signedGetUrl").ifBlank {
            json.optString("signed_get_url")
        }.ifBlank {
            throw RuntimeException("signedGetUrl missing in response for seq=$seq")
        }
    }
    fun sessionSidecarSignedGetUrl(
        orgId: String,
        subjectId: String,
        sessionId: String,
        seq: Int
    ): String {
        val txt = get("$apiBase/v1/org/$orgId/subjects/$subjectId/session/$sessionId/sidecar/$seq/signedGet")
        val json = JSONObject(txt)
        return json.optString("signedGetUrl").ifBlank {
            json.optString("signed_get_url")
        }.ifBlank {
            throw RuntimeException("signedGetUrl missing in sidecar response for seq=$seq")
        }
    }

}