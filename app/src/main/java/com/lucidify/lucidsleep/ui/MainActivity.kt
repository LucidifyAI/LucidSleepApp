package com.lucidify.lucidsleep.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.auth.FirebaseAuth
import com.lucidify.lucidsleep.AppGraph
import com.lucidify.lucidsleep.LucidSleepApp
import com.lucidify.lucidsleep.core.cloudrecording.CloudSyncScheduler
import com.lucidify.lucidsleep.enroll.EnrollmentCodec
import com.lucidify.lucidsleep.enroll.KitLookup
import com.lucidify.lucidsleep.recording.NightRecorder
import com.lucidify.lucidsleep.recording.RecordingService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		val graph = (application as LucidSleepApp).graph
		handleEnrollLink(intent)
		setContent {
			LucidSleepTheme { LucidSleepRoot(graph) }
		}
		// While the patch is lost during a night, the app may show over the lock screen and
		// turn the screen on, so the "Patch disconnected" alert is seen. Otherwise it may not.
		lifecycleScope.launch {
			var shown: Boolean? = null
			while (true) {
				val alert = NightRecorder.patchAlert(graph.recorder.state.value, System.currentTimeMillis())
				if (alert != shown) {
					shown = alert
					setShowWhenLocked(alert)
					setTurnScreenOn(alert)
				}
				delay(2_000)
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleEnrollLink(intent)
	}

	/**
	 * The public setup card's "Open in the LucidSleep app" button opens either
	 *   lucidsleep://claim?k=<kit code>   a one-time kit code, used once the sleeper has signed in
	 *   lucidsleep://enroll?c=...         a staff setup card (org and subject), saved right away
	 * Ignored while a night is being recorded or once a kit is set up.
	 */
	private fun handleEnrollLink(intent: Intent?) {
		val data = intent?.data ?: return
		if (data.scheme != "lucidsleep") return
		val graph = (application as LucidSleepApp).graph
		if (graph.recorder.state.value.recording) return
		if (data.host == "claim") {
			if (graph.setup.enrollment != null) return
			val code = KitLookup.normalizeCode(data.toString()) ?: run {
				graph.log.event("KIT_LINK_INVALID")
				return
			}
			KitLookup.pendingCode.value = code
			graph.log.event("KIT_LINK_OPENED")
			return
		}
		if (data.host != "enroll") return
		val e = EnrollmentCodec.parse(data.toString()) ?: run {
			graph.log.event("ENROLL_LINK_INVALID")
			return
		}
		val current = graph.setup.enrollment
		if (current != null && current.orgId == e.orgId && current.subjectId == e.subjectId &&
			current.patchSerial == e.patchSerial && current.nightsRequired == e.nightsRequired) return
		graph.setup.saveEnrollment(e)
		CloudSyncScheduler.configure(this, e.apiBase ?: AppGraph.DEFAULT_API_BASE)
		graph.log.event("ENROLLED", "via" to "link", "nights" to e.nightsRequired, "serialOnCard" to (e.patchSerial != null))
	}

	override fun onStart() {
		super.onStart()
		// If Android refused the foreground service while the app was in the background,
		// start it now that the app is in front.
		val graph = (application as LucidSleepApp).graph
		if (graph.recorder.state.value.recording) RecordingService.start(this)
	}
}

private enum class HomeStep { HOME, GUIDE, READY, CONTACT, NIGHTS, HELP }

/**
 * Picks the screen from where the sleeper is: one-time setup first, then the nightly loop.
 * Setup steps and an active recording always win; Home and its sub-screens are navigated.
 */
@Composable
fun LucidSleepRoot(graph: AppGraph) {
	val setup by graph.setup.setup.collectAsState()
	val rec by graph.recorder.state.collectAsState()
	val nights by graph.series.nights.collectAsState()
	var signedIn by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser != null) }
	var step by rememberSaveable { mutableStateOf(HomeStep.HOME) }
	// Where the how-to goes when it ends or is skipped: the ready checks, or the screen that opened it.
	var guideNext by rememberSaveable { mutableStateOf(HomeStep.READY) }
	val context = LocalContext.current
	fun openGuide(next: HomeStep) {
		guideNext = next
		step = HomeStep.GUIDE
	}

	DisposableEffect(Unit) {
		val auth = FirebaseAuth.getInstance()
		val listener = FirebaseAuth.AuthStateListener { signedIn = it.currentUser != null }
		auth.addAuthStateListener(listener)
		onDispose { auth.removeAuthStateListener(listener) }
	}

	// Keep the Patch connected only on the screens that need it (or while recording), and
	// let it go when this screen goes away.
	val holdLink = rec.recording || step == HomeStep.READY || step == HomeStep.CONTACT ||
		(step == HomeStep.GUIDE && guideNext == HomeStep.CONTACT)
	DisposableEffect(holdLink) {
		graph.recorder.holdLink(holdLink)
		onDispose {
			if (holdLink && !graph.recorder.state.value.recording) graph.recorder.holdLink(false)
		}
	}
	// Recording started (or a night resumed): come back to Home when it ends.
	LaunchedEffect(rec.recording) { if (rec.recording) step = HomeStep.HOME }

	val unreviewed = remember(nights) { graph.series.unreviewedNight() }

	when {
		// A night in progress always shows, so Stop is never hidden behind another step.
		rec.recording -> RecordingScreen(graph)
		// Sign in first: the kit is then linked to this Google account with the kit card's code.
		!signedIn -> SignInScreen(graph)
		setup.enrollment == null -> FindKitScreen(graph)
		!setup.consentGiven -> ConsentScreen(graph)
		!setup.phoneCheckDone -> PhoneCheckScreen(graph)
		setup.patchAddress == null -> PairScreen(graph)
		unreviewed != null -> GoodMorningScreen(graph, unreviewed)
		else -> {
			BackHandler(enabled = step != HomeStep.HOME) {
				step = if (step == HomeStep.GUIDE && guideNext != HomeStep.READY) guideNext else HomeStep.HOME
			}
			when (step) {
				HomeStep.HOME -> HomeScreen(
					graph,
					// The how-to plays before the checks until it has been watched to the end once.
					onReady = { if (SetupGuide.watched(context)) step = HomeStep.READY else openGuide(HomeStep.READY) },
					onNights = { step = HomeStep.NIGHTS },
					onHelp = { step = HomeStep.HELP }
				)
				HomeStep.GUIDE -> SetupGuideScreen(
					doneLabel = if (guideNext == HomeStep.READY) "Next: check everything" else "Done",
					onDone = { step = guideNext }
				)
				HomeStep.READY -> ReadyCheckScreen(
					graph,
					onNext = { step = HomeStep.CONTACT },
					onBack = { step = HomeStep.HOME },
					onHelp = { step = HomeStep.HELP }
				)
				HomeStep.CONTACT -> ContactCheckScreen(
					graph,
					onStarted = { step = HomeStep.HOME },
					onBack = { step = HomeStep.READY },
					onHelp = { step = HomeStep.HELP },
					onGuide = { openGuide(HomeStep.CONTACT) }
				)
				HomeStep.NIGHTS -> MyNightsScreen(graph, onBack = { step = HomeStep.HOME })
				HomeStep.HELP -> HelpScreen(graph, onBack = { step = HomeStep.HOME }, onGuide = { openGuide(HomeStep.HELP) })
			}
		}
	}
}