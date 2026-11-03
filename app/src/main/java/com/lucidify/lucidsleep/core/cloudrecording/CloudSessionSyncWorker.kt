package com.lucidify.lucidsleep.core.cloudrecording

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lucidify.lucidsleep.core.cloud.FirebaseAuthInterceptor
import com.lucidify.lucidsleep.core.cloud.OrgApi
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CloudSessionSyncWorker(
	appContext: Context,
	params: WorkerParameters
) : CoroutineWorker(appContext, params) {

	override suspend fun doWork(): Result {
		val apiBase = CloudSyncScheduler.apiBase(applicationContext) ?: return Result.failure()
		val http = OkHttpClient.Builder()
			.addInterceptor(FirebaseAuthInterceptor())
			.connectTimeout(20, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			.writeTimeout(60, TimeUnit.SECONDS)
			// Bound every whole call so one stuck request cannot stall the sync pass.
			.callTimeout(120, TimeUnit.SECONDS)
			.build()
		val engine = DurableCloudSyncEngine(
			store = DurableCloudSessionStore(applicationContext),
			orgApi = OrgApi(apiBase, http),
			httpClient = http
		)
		return try {
			val outcome = engine.synchronizeRecoverableSessions()
			when {
				outcome.hasPermanentFailure -> Result.failure()
				outcome.authRequired -> Result.retry()
				outcome.hasRetryableWork -> Result.retry()
				else -> Result.success()
			}
		} catch (_: Throwable) {
			Result.retry()
		}
	}
}