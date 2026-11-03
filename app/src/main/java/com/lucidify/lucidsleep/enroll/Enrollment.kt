package com.lucidify.lucidsleep.enroll

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Which org and subject this phone records for, and how many nights.
 *
 * Usually comes from org-service (see [KitLookup]): the sleeper signs in with Google and
 * scans the one-time kit code the care team printed for the kit. A staff setup card,
 * for kits run by staff accounts, carries the enrollment itself in the format below.
 *
 * Prototype format, version 1: a JSON object
 *   {"v":1,"org":"<orgId>","subject":"<subjectId>","label":"<display name>",
 *    "serial":"<patch serial, optional>","nights":5,"api":"<org-service base URL, optional>"}
 * carried as any of:
 *   - the JSON itself
 *   - "LS1:" + base64url(JSON)
 *   - lucidsleep://enroll?c=<base64url(JSON)>
 *
 * It holds no secret: uploads still need a signed-in account that org-service lets record
 * for this subject (a staff member of the org, or a patient who claimed a kit for it).
 */
data class Enrollment(
	val orgId: String,
	val subjectId: String,
	val label: String?,
	val patchSerial: String?,
	val nightsRequired: Int,
	val apiBase: String?
)

object EnrollmentCodec {
	fun parse(raw: String): Enrollment? {
		val text = raw.trim()
		val json = when {
			text.startsWith("{") -> text
			text.startsWith("LS1:", ignoreCase = true) -> decode(text.substring(4))
			text.startsWith("lucidsleep://", ignoreCase = true) ->
				Uri.parse(text).getQueryParameter("c")?.let(::decode)
			else -> decode(text)
		} ?: return null
		return runCatching {
			val o = JSONObject(json)
			val org = o.optString("org").trim()
			val subject = o.optString("subject").trim()
			if (org.isEmpty() || subject.isEmpty()) return null
			Enrollment(
				orgId = org,
				subjectId = subject,
				label = o.optString("label").trim().ifEmpty { null },
				patchSerial = o.optString("serial").trim().ifEmpty { null },
				nightsRequired = o.optInt("nights", 5).coerceIn(1, 14),
				apiBase = o.optString("api").trim().trimEnd('/').ifEmpty { null }
			)
		}.getOrNull()
	}

	fun encode(e: Enrollment): String {
		val o = JSONObject()
			.put("v", 1)
			.put("org", e.orgId)
			.put("subject", e.subjectId)
			.put("nights", e.nightsRequired)
		e.label?.let { o.put("label", it) }
		e.patchSerial?.let { o.put("serial", it) }
		e.apiBase?.let { o.put("api", it) }
		val b64 = Base64.encodeToString(o.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
		return "LS1:$b64"
	}

	private fun decode(b64: String): String? = runCatching {
		String(Base64.decode(b64.trim(), Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
	}.getOrNull()?.takeIf { it.trim().startsWith("{") }
}

/** Everything set up once per sleeper: enrollment, consent, phone check and the paired patch. */
class EnrollmentStore(context: Context) {

	data class Setup(
		val enrollment: Enrollment?,
		val consentGiven: Boolean,
		val shareWithDoctor: Boolean,
		val researchOptIn: Boolean,
		val phoneCheckDone: Boolean,
		val patchAddress: String?,
		val patchSerialRead: String?
	)

	private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
	private val _setup = MutableStateFlow(load())
	val setup: StateFlow<Setup> = _setup.asStateFlow()

	val enrollment: Enrollment? get() = _setup.value.enrollment

	@Synchronized
	fun saveEnrollment(e: Enrollment) {
		prefs.edit().putString(KEY_CODE, EnrollmentCodec.encode(e))
			// A new enrollment means a new kit: forget the old patch.
			.remove(KEY_PATCH_ADDRESS).remove(KEY_PATCH_SERIAL)
			.apply()
		_setup.value = load()
	}

	@Synchronized
	fun saveConsent(shareWithDoctor: Boolean, researchOptIn: Boolean) {
		prefs.edit()
			.putBoolean(KEY_CONSENT, true)
			.putBoolean(KEY_SHARE, shareWithDoctor)
			.putBoolean(KEY_RESEARCH, researchOptIn)
			.putLong(KEY_CONSENT_AT, System.currentTimeMillis())
			.putInt(KEY_CONSENT_VERSION, CONSENT_VERSION)
			.apply()
		_setup.value = load()
	}

	@Synchronized
	fun markPhoneCheckDone() {
		prefs.edit().putBoolean(KEY_PHONE_CHECK, true).apply()
		_setup.value = load()
	}

	@Synchronized
	fun savePatch(address: String, serial: String?) {
		prefs.edit().putString(KEY_PATCH_ADDRESS, address).putString(KEY_PATCH_SERIAL, serial).apply()
		_setup.value = load()
	}

	@Synchronized
	fun forgetPatch() {
		prefs.edit().remove(KEY_PATCH_ADDRESS).remove(KEY_PATCH_SERIAL).apply()
		_setup.value = load()
	}

	@Synchronized
	fun clearAll() {
		prefs.edit().clear().apply()
		_setup.value = load()
	}

	private fun load(): Setup = Setup(
		enrollment = prefs.getString(KEY_CODE, null)?.let(EnrollmentCodec::parse),
		consentGiven = prefs.getBoolean(KEY_CONSENT, false) && prefs.getInt(KEY_CONSENT_VERSION, 0) == CONSENT_VERSION,
		shareWithDoctor = prefs.getBoolean(KEY_SHARE, false),
		researchOptIn = prefs.getBoolean(KEY_RESEARCH, false),
		phoneCheckDone = prefs.getBoolean(KEY_PHONE_CHECK, false),
		patchAddress = prefs.getString(KEY_PATCH_ADDRESS, null),
		patchSerialRead = prefs.getString(KEY_PATCH_SERIAL, null)
	)

	companion object {
		private const val PREFS = "lucidsleep_setup"
		private const val KEY_CODE = "enrollment_code"
		private const val KEY_CONSENT = "consent_given"
		private const val KEY_CONSENT_AT = "consent_at"
		private const val KEY_CONSENT_VERSION = "consent_version"
		private const val KEY_SHARE = "share_with_doctor"
		private const val KEY_RESEARCH = "research_opt_in"
		private const val KEY_PHONE_CHECK = "phone_check_done"
		private const val KEY_PATCH_ADDRESS = "patch_address"
		private const val KEY_PATCH_SERIAL = "patch_serial"
		const val CONSENT_VERSION = 1
	}
}