package com.lucidify.lucidsleep.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.google.firebase.auth.FirebaseAuth
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.lucidify.lucidsleep.AppGraph
import com.lucidify.lucidsleep.core.cloudrecording.CloudSyncScheduler
import com.lucidify.lucidsleep.enroll.Enrollment
import com.lucidify.lucidsleep.enroll.EnrollmentCodec
import com.lucidify.lucidsleep.enroll.GoogleSignIn
import com.lucidify.lucidsleep.enroll.KitLookup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// ---------------------------------------------------------------------------------------
// 1. Welcome / Sign in with Google
// ---------------------------------------------------------------------------------------

@Composable
fun SignInScreen(graph: AppGraph) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var showEmail by remember { mutableStateOf(false) }
	var email by remember { mutableStateOf("") }
	var password by remember { mutableStateOf("") }
	var busy by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }

	fun signedIn(how: String) {
		graph.log.event("SIGNED_IN", "method" to how)
		graph.recorder.wakeUploads()
	}

	ScreenFrame(
		title = "Welcome to LucidSleep™",
		step = "Step 1 of 5",
		subtitle = "Let's set up your kit. This takes about 10 minutes.",
		bottom = {
			BigButton(if (busy && !showEmail) "Signing in…" else "Sign in with Google", enabled = !busy) {
				busy = true
				error = null
				scope.launch {
					when (val r = GoogleSignIn.signIn(context)) {
						GoogleSignIn.Outcome.Success -> signedIn("google")
						GoogleSignIn.Outcome.Cancelled -> Unit
						is GoogleSignIn.Outcome.Failed -> {
							error = r.message
							graph.log.event("SIGN_IN_FAILED", "method" to "google", "error" to r.code)
						}
					}
					busy = false
				}
			}
			if (!showEmail) {
				QuietButton("Use an email and password instead", enabled = !busy) { showEmail = true; error = null }
			}
		}
	) {
		Note("First, sign in with your Google account. You'll use it for this kit from now on, so pick the one you check most. You only do this once.")
		if (showEmail) {
			Note("Only if Lucidify gave you an email and password for this kit.")
			OutlinedTextField(
				value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true,
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth()
			)
			OutlinedTextField(
				value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
				visualTransformation = PasswordVisualTransformation(),
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()
			)
			QuietButton(if (busy) "Signing in…" else "Sign in with email", enabled = !busy && email.isNotBlank() && password.isNotBlank()) {
				busy = true
				error = null
				scope.launch {
					runCatching {
						FirebaseAuth.getInstance().signInWithEmailAndPassword(email.trim(), password).await()
					}.onSuccess {
						signedIn("password")
					}.onFailure {
						error = "That didn't work. Check the email and password, and that you're online."
						graph.log.event("SIGN_IN_FAILED", "method" to "password", "error" to it.javaClass.simpleName)
					}
					busy = false
				}
			}
		}
		error?.let { Callout(it, Palette.Bad) }
	}
}

// ---------------------------------------------------------------------------------------
// 1a. Your kit: already claimed by this account, or claimed now with the kit card's code
// ---------------------------------------------------------------------------------------

@Composable
fun FindKitScreen(graph: AppGraph) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val pending by KitLookup.pendingCode.collectAsState()
	var checking by remember { mutableStateOf(true) }
	var busy by remember { mutableStateOf(false) }
	var kits by remember { mutableStateOf<List<KitLookup.Kit>>(emptyList()) }
	var code by remember { mutableStateOf("") }
	var error by remember { mutableStateOf<String?>(null) }
	var attempt by remember { mutableIntStateOf(0) }
	val email = remember { FirebaseAuth.getInstance().currentUser?.email }

	fun enroll(e: Enrollment, via: String) {
		graph.setup.saveEnrollment(e)
		CloudSyncScheduler.configure(context, e.apiBase ?: AppGraph.DEFAULT_API_BASE)
		KitLookup.pendingCode.value = null
		graph.log.event("ENROLLED", "via" to via, "nights" to e.nightsRequired, "serialOnKit" to (e.patchSerial != null))
	}

	/** A scanned or typed kit code (or, for a staff-run kit, a staff setup card's code). */
	fun submit(raw: String?, via: String) {
		error = null
		val text = raw?.trim().orEmpty()
		if (text.isEmpty() || busy) return
		val kitCode = KitLookup.normalizeCode(text)
		if (kitCode == null) {
			val staffCard = EnrollmentCodec.parse(text)
			if (staffCard != null) enroll(staffCard, "staff_card_$via")
			else error = "That isn't a LucidSleep™ kit code. Check the card in your kit and try again."
			return
		}
		busy = true
		scope.launch {
			when (val r = KitLookup.claim(graph.httpClient, kitCode)) {
				is KitLookup.Result.Found -> {
					graph.log.event("KIT_CLAIMED", "via" to via)
					enroll((r.claimed ?: r.kits.first()).enrollment, "kit_code_$via")
				}
				is KitLookup.Result.NoKit -> error = "That code didn't work. Check it, or ask your care team for a new one."
				is KitLookup.Result.Failed -> {
					error = r.message
					graph.log.event("KIT_CLAIM_FAILED", "via" to via, "error" to r.code)
				}
			}
			busy = false
		}
	}

	// Kits this account already has (for example after reinstalling the app).
	LaunchedEffect(attempt) {
		checking = true
		error = null
		when (val r = KitLookup.find(graph.httpClient)) {
			is KitLookup.Result.Found -> {
				graph.log.event("KIT_LOOKUP", "kits" to r.kits.size)
				if (r.kits.size == 1) enroll(r.kits.first().enrollment, "account") else kits = r.kits
			}
			is KitLookup.Result.NoKit -> graph.log.event("KIT_LOOKUP", "kits" to 0)
			is KitLookup.Result.Failed -> {
				error = r.message
				graph.log.event("KIT_LOOKUP_FAILED", "error" to r.code)
			}
		}
		checking = false
	}

	// Opened from the setup card's "Open in the LucidSleep app" link: use its code.
	LaunchedEffect(pending, checking) {
		val p = pending
		if (p != null && !checking && kits.isEmpty()) {
			KitLookup.pendingCode.value = null
			submit(p, "link")
		}
	}

	fun switchAccount() {
		scope.launch {
			graph.log.event("SIGN_OUT", "reason" to "switch_account")
			GoogleSignIn.signOut(context)
		}
	}

	when {
		checking -> ScreenFrame(title = "Finding your kit…", subtitle = email?.let { "Signed in as $it" }) {
			CircularProgressIndicator(color = Palette.Amber)
		}
		kits.size > 1 -> ScreenFrame(
			title = "Choose your kit",
			subtitle = "This account has more than one kit. Pick the one you have with you."
		) {
			for (kit in kits) {
				val name = listOfNotNull(kit.enrollment.label?.let { "Kit $it" }, kit.orgName)
					.joinToString(" · ").ifEmpty { "LucidSleep™ kit" }
				QuietButton("$name — ${kit.enrollment.nightsRequired} nights") { enroll(kit.enrollment, "account") }
			}
		}
		else -> ScreenFrame(
			title = "Scan your kit card",
			step = "Step 2 of 5",
			subtitle = email?.let { "Signed in as $it" },
			bottom = {
				BigButton(if (busy) "Checking the code…" else "Scan the kit card", enabled = !busy) {
					error = null
					val options = GmsBarcodeScannerOptions.Builder()
						.setBarcodeFormats(Barcode.FORMAT_QR_CODE)
						.build()
					GmsBarcodeScanning.getClient(context, options).startScan()
						.addOnSuccessListener { barcode -> submit(barcode.rawValue, "scan") }
						.addOnFailureListener { error = "The scanner couldn't start. You can type the kit code instead." }
				}
				QuietButton("Use a different Google account", enabled = !busy) { switchAccount() }
			}
		) {
			Note("Find the card with the square code in your kit. Tap the button below and point your phone's camera at it.")
			Note("No camera, or the scan won't work? Type the kit code printed under the square code:")
			OutlinedTextField(
				value = code,
				onValueChange = { code = it; error = null },
				label = { Text("Kit code") },
				placeholder = { Text("XXXX-XXXX-XXXX") },
				singleLine = true,
				enabled = !busy,
				modifier = Modifier.fillMaxWidth()
			)
			QuietButton(if (busy) "Checking…" else "Use this code", enabled = !busy && code.isNotBlank()) { submit(code, "typed") }
			if (busy) CircularProgressIndicator(color = Palette.Amber)
			error?.let {
				Callout(it, Palette.Bad)
				if (!busy) QuietButton("Check my account again") { attempt++ }
			}
			Note("The first Google account that uses a kit code is linked to that kit, and the code stops working. Don't have a card? Ask your care team.")
		}
	}
}

// ---------------------------------------------------------------------------------------
// 2. Consent
// ---------------------------------------------------------------------------------------

@Composable
fun ConsentScreen(graph: AppGraph) {
	var share by remember { mutableStateOf(true) }
	var research by remember { mutableStateOf(false) }
	ScreenFrame(
		title = "Before we start",
		step = "Step 3 of 5",
		bottom = {
			BigButton("I understand and agree") {
				graph.setup.saveConsent(shareWithDoctor = share, researchOptIn = research)
				graph.log.event("CONSENT", "share" to share, "research" to research)
			}
		}
	) {
		Note("LucidSleep™ measures your sleep with a small patch on your forehead. It is a wellness service. It does not diagnose or treat any condition. If something in your report concerns you, talk to your doctor.")
		Note("Your recordings are encrypted on your phone and sent securely to Lucidify.")
		ConsentToggle("Share my sleep reports with my doctor", share) { share = it }
		ConsentToggle("Lucidify may use my de-identified recordings for research (optional)", research) { research = it }
	}
}

@Composable
private fun ConsentToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
		Checkbox(
			checked = checked, onCheckedChange = onChange,
			colors = CheckboxDefaults.colors(checkedColor = Palette.Amber, checkmarkColor = Palette.Navy)
		)
		Text(label, style = MaterialTheme.typography.bodyLarge)
	}
}

// ---------------------------------------------------------------------------------------
// 3. Phone check
// ---------------------------------------------------------------------------------------

fun blePermissions(): Array<String> =
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
		arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
	} else {
		arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
	}

/** After a permission was refused twice, Android stops asking: send the sleeper to app settings. */
fun appSettingsIntent(context: Context): Intent =
	Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

fun hasPermissions(context: Context, perms: Array<String>): Boolean =
	perms.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

fun notificationsAllowed(context: Context): Boolean =
	Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
		context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

fun batteryExempt(context: Context): Boolean =
	context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

fun freeStorageBytes(context: Context): Long = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)

const val MIN_FREE_BYTES = 1_000_000_000L

@Composable
fun PhoneCheckScreen(graph: AppGraph) {
	val context = LocalContext.current
	var tick by remember { mutableIntStateOf(0) }
	var asked by remember { mutableStateOf(setOf<String>()) }
	val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
	val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
	LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }

	// Re-read every second and after each settings screen returns.
	val bt = remember(tick) { hasPermissions(context, blePermissions()) }
	val notif = remember(tick) { notificationsAllowed(context) }
	val btOn = remember(tick) { graph.ble.isBluetoothReady() }
	val battery = remember(tick) { batteryExempt(context) }
	val storage = remember(tick) { freeStorageBytes(context) >= MIN_FREE_BYTES }
	val ready = bt && notif && btOn && storage

	ScreenFrame(
		title = "Phone check",
		step = "Step 4 of 5",
		subtitle = "Your phone needs a few settings so it can record all night without you.",
		bottom = {
			BigButton("Continue", enabled = ready) {
				graph.setup.markPhoneCheckDone()
				graph.log.event(
					"PHONE_CHECK", "model" to "${Build.MANUFACTURER} ${Build.MODEL}", "android" to Build.VERSION.SDK_INT,
					"batteryExempt" to battery
				)
			}
		}
	) {
		CheckRow("Allow Bluetooth", if (bt) CheckState.OK else CheckState.PROBLEM,
			"Lets the app talk to your patch.", if ("bt" in asked) "Open settings" else "Allow") {
			if ("bt" in asked) settingsLauncher.launch(appSettingsIntent(context))
			else { asked = asked + "bt"; permLauncher.launch(blePermissions()) }
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			CheckRow("Allow notifications", if (notif) CheckState.OK else CheckState.PROBLEM,
				"Shows that a recording is running.", if ("notif" in asked) "Open settings" else "Allow") {
				if ("notif" in asked) settingsLauncher.launch(appSettingsIntent(context))
				else { asked = asked + "notif"; permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
			}
		}
		CheckRow("Bluetooth is on", if (btOn) CheckState.OK else CheckState.PROBLEM,
			null, "Turn on") { settingsLauncher.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
		CheckRow("Let LucidSleep™ run overnight", if (battery) CheckState.OK else CheckState.PROBLEM,
			"Stops the phone from pausing LucidSleep™ to save battery.", "Allow") {
			settingsLauncher.launch(
				Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
			)
		}
		CheckRow("Space on your phone", if (storage) CheckState.OK else CheckState.PROBLEM,
			if (storage) null else "Free up at least 1 GB so a full night fits.")
		if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
			Callout("On Samsung phones, also open Settings › Battery › Background usage limits, and make sure LucidSleep™ is not listed under Sleeping apps or Deep sleeping apps.")
		}
	}
}

// ---------------------------------------------------------------------------------------
// 4–5. Find the patch
// ---------------------------------------------------------------------------------------

@Composable
fun PairScreen(graph: AppGraph) {
	val scope = rememberCoroutineScope()
	val expected = graph.setup.enrollment?.patchSerial
	var busy by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }

	ScreenFrame(
		title = "Find your patch",
		step = "Step 5 of 5",
		subtitle = "Press the round button with the Lucidify logo to turn on your patch, and keep it near your phone.",
		bottom = {
			BigButton(if (busy) "Looking for your patch…" else "Find my patch", enabled = !busy) {
				busy = true
				error = null
				scope.launch {
					graph.recorder.pairPatch(expected)
						.onFailure { error = pairErrorText(it.message) }
					busy = false
				}
			}
		}
	) {
		Note("The white light comes on and the patch checks itself for about 30 seconds. You don't need to put it on yet.")
		Note("If no light comes on, plug the patch into its charger. A flashing white light means it's charging.")
		if (busy) CircularProgressIndicator(color = Palette.Amber)
		error?.let { Callout(it, Palette.Bad) }
	}
}

private fun pairErrorText(message: String?): String = when (message) {
	"Bluetooth is off" -> "Bluetooth is off. Turn it on and try again."
	"No patch found" -> "We couldn't find a patch. Make sure its white light is on and it's within arm's reach."
	"No patch matched the kit" -> "We found a patch, but not the one from your kit. Turn off any other patches nearby and try again."
	else -> "Something went wrong. Try again, and if it keeps happening, contact Lucidify."
}