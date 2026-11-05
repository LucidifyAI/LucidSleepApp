package com.lucidify.lucidsleep.ui

/**
 * Lucidify support details shown under "Contact support" in Help. Fill these in before the
 * app goes to sleepers. Any left blank isn't shown; with all three blank, the section is hidden.
 */
object SupportInfo {
	/** Shown as "Call or text …" and dialed when tapped, e.g. "+1 902 555 0100". */
	const val PHONE = ""
	/** Opens the phone's email app with the diagnostics ticket code in the subject. */
	const val EMAIL = ""
	/** For example "Weekdays 9 am to 5 pm Eastern". */
	const val HOURS = ""

	val available: Boolean get() = PHONE.isNotBlank() || EMAIL.isNotBlank() || HOURS.isNotBlank()
}