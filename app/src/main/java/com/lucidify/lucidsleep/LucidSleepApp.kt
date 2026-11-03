package com.lucidify.lucidsleep

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.lucidify.lucidsleep.core.CgxBleClient
import com.lucidify.lucidsleep.core.cloud.FirebaseAuthInterceptor
import com.lucidify.lucidsleep.core.cloud.OrgApi
import com.lucidify.lucidsleep.core.cloudrecording.CloudSyncScheduler
import com.lucidify.lucidsleep.core.cloudrecording.DurableCloudSessionStore
import com.lucidify.lucidsleep.core.cloudrecording.DurableCloudSyncEngine
import com.lucidify.lucidsleep.enroll.EnrollmentStore
import com.lucidify.lucidsleep.recording.NightLog
import com.lucidify.lucidsleep.recording.NightRecorder
import com.lucidify.lucidsleep.series.SeriesStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LucidSleepApp : Application() {
	lateinit var graph: AppGraph
		private set

	override fun onCreate() {
		super.onCreate()
		FirebaseApp.initializeApp(this)
		graph = AppGraph(this)
		CloudSyncScheduler.configure(this, graph.apiBase)
		graph.recorder.start()
	}
}

/** The single instance of each long-lived object in the process. */
class AppGraph(context: Context) {
	private val appContext = context.applicationContext

	val setup = EnrollmentStore(appContext)
	val series = SeriesStore(appContext)
	val log = NightLog(appContext)

	/** org-service base URL: the kit's QR card can override the default. */
	val apiBase: String
		get() = setup.enrollment?.apiBase ?: DEFAULT_API_BASE

	val httpClient: OkHttpClient = OkHttpClient.Builder()
		.addInterceptor(FirebaseAuthInterceptor())
		.connectTimeout(20, TimeUnit.SECONDS)
		.readTimeout(60, TimeUnit.SECONDS)
		.writeTimeout(60, TimeUnit.SECONDS)
		// Bound every whole call so one stuck request cannot stall the upload loop.
		.callTimeout(120, TimeUnit.SECONDS)
		.build()

	val store = DurableCloudSessionStore(appContext)
	val orgApi = OrgApi(apiBase, httpClient)
	val ble = CgxBleClient(appContext)

	val recorder = NightRecorder(
		context = appContext,
		ble = ble,
		store = store,
		engine = DurableCloudSyncEngine(store, orgApi, httpClient),
		setupStore = setup,
		series = series,
		log = log
	)

	companion object {
		/** Same org-service the demo app uses. */
		const val DEFAULT_API_BASE = "https://lucidify-org-245566811250.us-central1.run.app"
	}
}