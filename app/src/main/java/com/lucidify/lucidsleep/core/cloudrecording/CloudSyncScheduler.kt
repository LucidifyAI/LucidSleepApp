package com.lucidify.lucidsleep.core.cloudrecording

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object CloudSyncScheduler {
	private const val PREFS = "cloud_recording_sync"
	private const val API_BASE = "api_base"
	private const val PERIODIC_WORK = "eeg-cloud-session-sync-periodic"
	private const val IMMEDIATE_WORK = "eeg-cloud-session-sync-immediate"
	private const val FINALIZATION_WORK_PREFIX = "eeg-cloud-session-finalize-"

	fun configure(context: Context, apiBase: String) {
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
			.edit()
			.putString(API_BASE, apiBase.trimEnd('/'))
			.apply()
		schedulePeriodic(context)
	}

	fun apiBase(context: Context): String? =
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
			.getString(API_BASE, null)
			?.takeIf { it.isNotBlank() }

	fun enqueueImmediate(context: Context) {
		val request = oneTimeRequest()
		WorkManager.getInstance(context).enqueueUniqueWork(
			IMMEDIATE_WORK,
			ExistingWorkPolicy.KEEP,
			request
		)
	}

	/**
	 * Stop/finalization gets its own unique durable work chain. A prior generic
	 * immediate sync may still be ENQUEUED/RUNNING when Stop is pressed; using a
	 * separate per-session name prevents ExistingWorkPolicy.KEEP from swallowing
	 * the only post-stop wake-up. REPLACE is safe because all upload/finalize state
	 * is durably persisted and the protocol is idempotent.
	 */
	fun enqueueFinalization(context: Context, localSessionId: String) {
		if (localSessionId.isBlank()) return
		WorkManager.getInstance(context).enqueueUniqueWork(
			FINALIZATION_WORK_PREFIX + localSessionId,
			ExistingWorkPolicy.REPLACE,
			oneTimeRequest()
		)
	}

	private fun oneTimeRequest() = OneTimeWorkRequestBuilder<CloudSessionSyncWorker>()
		.setConstraints(networkConstraints())
		.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
		.build()

	private fun schedulePeriodic(context: Context) {
		val request = PeriodicWorkRequestBuilder<CloudSessionSyncWorker>(15, TimeUnit.MINUTES)
			.setConstraints(networkConstraints())
			.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
			.build()
		WorkManager.getInstance(context).enqueueUniquePeriodicWork(
			PERIODIC_WORK,
			ExistingPeriodicWorkPolicy.KEEP,
			request
		)
	}

	private fun networkConstraints(): Constraints = Constraints.Builder()
		.setRequiredNetworkType(NetworkType.CONNECTED)
		.build()
}