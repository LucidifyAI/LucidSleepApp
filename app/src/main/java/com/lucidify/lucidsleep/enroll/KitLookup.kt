package com.lucidify.lucidsleep.enroll

import com.lucidify.lucidsleep.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The sleeper's kit, from org-service:
 *   GET  /v1/me/sleep-kit        kits this Google account has already claimed
 *   POST /v1/me/sleep-kit/claim  claim a kit with the one-time code on the kit card
 *
 * The care team makes the code on the LucidSleep kits page without needing to know the
 * patient's Google account; the first account to use the code is linked to the kit.
 */
object KitLookup {

	/** One kit, plus what to show when the sleeper has to choose between several. */
	data class Kit(val enrollment: Enrollment, val orgName: String?)

	sealed class Result {
		data class Found(val email: String?, val kits: List<Kit>, val claimed: Kit? = null) : Result()
		data class NoKit(val email: String?) : Result()
		/** [message] is for the screen; [code] is for the night log. */
		data class Failed(val message: String, val code: String) : Result()
	}

	/**
	 * A kit code from a "lucidsleep://claim?k=..." link, kept until the sleeper has signed in.
	 * Held in memory only: it is a one-time secret.
	 */
	val pendingCode = MutableStateFlow<String?>(null)

	private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
	private const val CODE_LENGTH = 12
	private val JSON = "application/json; charset=utf-8".toMediaType()
	private const val OFFLINE = "We couldn't reach Lucidify. Check that you're online and try again."

	/**
	 * Canonical XXXX-XXXX-XXXX form of a typed or scanned kit code, or null. Accepts the
	 * lucidsleep://claim?k= link and the setup card URL, and forgives O/0 and I/L/1 slips.
	 */
	fun normalizeCode(raw: String?): String? {
		var s = raw?.trim().orEmpty()
		if (s.length > 300) return null
		Regex("[?&#]k=([^&#\\s]+)", RegexOption.IGNORE_CASE).find(s)?.let {
			s = runCatching { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }.getOrNull() ?: return null
		}
		s = s.uppercase().replace(Regex("[\\s-]"), "").replace('O', '0').replace('I', '1').replace('L', '1')
		if (s.length != CODE_LENGTH || s.any { it !in ALPHABET }) return null
		return s.chunked(4).joinToString("-")
	}

	/** Kits this account has already claimed. */
	suspend fun find(http: OkHttpClient, apiBase: String = AppGraph.DEFAULT_API_BASE): Result =
		call(http, Request.Builder().url("${apiBase.trimEnd('/')}/v1/me/sleep-kit").get(), apiBase)

	/** Claims the kit for [code] for this account; on success the result includes it as [Result.Found.claimed]. */
	suspend fun claim(http: OkHttpClient, code: String, apiBase: String = AppGraph.DEFAULT_API_BASE): Result {
		val body = JSONObject().put("code", code).toString().toRequestBody(JSON)
		return call(http, Request.Builder().url("${apiBase.trimEnd('/')}/v1/me/sleep-kit/claim").post(body), apiBase)
	}

	private suspend fun call(http: OkHttpClient, builder: Request.Builder, apiBase: String): Result =
		withContext(Dispatchers.IO) {
			val request = builder.header("Cache-Control", "no-store").build()
			try {
				http.newCall(request).execute().use { response ->
					val text = response.body?.string().orEmpty()
					val json = runCatching { JSONObject(text) }.getOrNull()
					if (response.isSuccessful && json != null) return@use parse(json, apiBase)
					val error = json?.optString("error").orEmpty()
					// org-service writes these messages for the sleeper; fall back to our own.
					val serverMessage = json?.optString("message")?.takeIf { it.isNotBlank() && error.isNotBlank() }
					val message = when {
						response.code == 401 -> "Your sign-in has expired. Sign in with Google again."
						response.code in 400..499 && serverMessage != null -> serverMessage
						response.code == 404 -> "That code didn't work. Check it, or ask your care team for a new one."
						else -> OFFLINE
					}
					Result.Failed(message, error.ifBlank { "http_${response.code}" })
				}
			} catch (e: Exception) {
				Result.Failed(OFFLINE, "io:${e.javaClass.simpleName}")
			}
		}

	private fun parse(body: JSONObject, apiBase: String): Result {
		val email = body.optNullableString("email")
		val kits = parseKits(body.optJSONArray("kits"), apiBase)
		val claimed = body.optJSONObject("kit")?.let { parseKit(it, apiBase) }
		val all = if (claimed != null && kits.none { it.enrollment == claimed.enrollment }) kits + claimed else kits
		return if (all.isEmpty()) Result.NoKit(email) else Result.Found(email, all, claimed)
	}

	private fun parseKits(array: JSONArray?, apiBase: String): List<Kit> = buildList {
		if (array != null) for (i in 0 until array.length()) {
			array.optJSONObject(i)?.let { parseKit(it, apiBase) }?.let(::add)
		}
	}

	private fun parseKit(k: JSONObject, apiBase: String): Kit? {
		val org = k.optString("orgId").trim()
		val subject = k.optString("subjectId").trim()
		if (org.isEmpty() || subject.isEmpty()) return null
		return Kit(
			enrollment = Enrollment(
				orgId = org,
				subjectId = subject,
				label = k.optNullableString("label"),
				patchSerial = k.optNullableString("patchSerial"),
				nightsRequired = k.optInt("nights", 5).coerceIn(1, 14),
				apiBase = apiBase.trimEnd('/').takeIf { it != AppGraph.DEFAULT_API_BASE }
			),
			orgName = k.optNullableString("orgName")
		)
	}

	/** org.json returns "null" for JSON null; treat that and blanks as absent. */
	private fun JSONObject.optNullableString(name: String): String? =
		if (!has(name) || isNull(name)) null else optString(name).trim().ifEmpty { null }
}