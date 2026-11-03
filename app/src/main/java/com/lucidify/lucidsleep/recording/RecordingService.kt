package com.lucidify.lucidsleep.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lucidify.lucidsleep.LucidSleepApp
import com.lucidify.lucidsleep.R
import com.lucidify.lucidsleep.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and the CPU awake while a night is recorded. The recording itself
 * lives in [NightRecorder]; this service only holds the foreground slot and a wake lock, and
 * resumes the night when Android restarts the service after killing the process.
 */
class RecordingService : Service() {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private var wakeLock: PowerManager.WakeLock? = null
	private var alertWatch: Job? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_STOP) {
			cancelPatchAlert()
			releaseWakeLock()
			ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
			stopSelf()
			return START_NOT_STICKY
		}
		// Android 14 refuses a connected-device service without Bluetooth permission.
		if (runCatching { startInForeground() }.onFailure { Log.w(TAG, "startForeground refused", it) }.isFailure) {
			stopSelf()
			return START_NOT_STICKY
		}
		acquireWakeLock()
		val recorder = (application as LucidSleepApp).graph.recorder
		recorder.start()
		if (alertWatch == null) alertWatch = scope.launch { watchPatch() }
		// A null intent means Android restarted the service after the process died.
		if (intent == null || intent.action == ACTION_RESUME) {
			scope.launch {
				recorder.resumeIfInterrupted()
				// Nothing to resume (night too old, already finished): don't hold the phone awake.
				if (!recorder.state.value.recording) stop(this@RecordingService)
			}
		}
		return START_STICKY
	}

	override fun onDestroy() {
		cancelPatchAlert()
		releaseWakeLock()
		scope.cancel()
		super.onDestroy()
	}

	private fun startInForeground() {
		val nm = getSystemService(NotificationManager::class.java)
		if (nm.getNotificationChannel(CHANNEL_ID) == null) {
			nm.createNotificationChannel(
				NotificationChannel(CHANNEL_ID, "Sleep recording", NotificationManager.IMPORTANCE_LOW).apply {
					description = "Shown while LucidSleep records your night"
					setShowBadge(false)
				}
			)
		}
		val open = PendingIntent.getActivity(
			this, 0, Intent(this, MainActivity::class.java),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
		)
		val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_stat_moon)
			.setContentTitle("Recording your sleep")
			.setContentText("Keep your phone on its charger. Sleep well.")
			.setOngoing(true)
			.setContentIntent(open)
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			.build()
		val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
		} else 0
		ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
	}

	/**
	 * Raises the "Patch disconnected" alert once per outage when a night has had no patch data
	 * for a minute (the app reconnects on its own before then), and clears it when data is back.
	 * The alert opens the app over the lock screen, where the recording screen flashes red.
	 */
	private suspend fun watchPatch() {
		val graph = (application as LucidSleepApp).graph
		var alertedFor = 0L
		while (scope.isActive) {
			val s = graph.recorder.state.value
			val now = System.currentTimeMillis()
			if (NightRecorder.patchAlert(s, now)) {
				if (alertedFor != s.outageSinceEpochMs) {
					alertedFor = s.outageSinceEpochMs
					postPatchAlert()
					graph.log.event("PATCH_ALERT", "silentSec" to (now - s.outageSinceEpochMs) / 1000L,
						"battery" to s.batteryPercent?.toInt())
				}
			} else if (alertedFor != 0L) {
				alertedFor = 0L
				cancelPatchAlert()
			}
			delay(5_000)
		}
	}

	private fun postPatchAlert() {
		val nm = getSystemService(NotificationManager::class.java)
		if (nm.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
			nm.createNotificationChannel(
				NotificationChannel(ALERT_CHANNEL_ID, "Patch disconnected", NotificationManager.IMPORTANCE_HIGH).apply {
					description = "Alerts you when the patch stops recording during the night"
					enableVibration(true)
				}
			)
		}
		val open = PendingIntent.getActivity(
			this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
		)
		val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_stat_moon)
			.setColor(0xFFE53935.toInt())
			.setContentTitle("Patch disconnected")
			.setContentText("Your night isn't being recorded. Turn the patch off and on again.")
			.setPriority(NotificationCompat.PRIORITY_MAX)
			.setCategory(NotificationCompat.CATEGORY_ALARM)
			.setFullScreenIntent(open, true)
			.setContentIntent(open)
			.setAutoCancel(true)
			.build()
		runCatching { nm.notify(ALERT_NOTIFICATION_ID, notification) }
			.onFailure { Log.w(TAG, "Patch alert refused", it) }
	}

	private fun cancelPatchAlert() {
		runCatching { getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIFICATION_ID) }
	}

	private fun acquireWakeLock() {
		if (wakeLock?.isHeld == true) return
		val pm = getSystemService(PowerManager::class.java)
		wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LucidSleep:Recording").apply {
			setReferenceCounted(false)
			acquire()
		}
	}

	private fun releaseWakeLock() {
		runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
		wakeLock = null
	}

	companion object {
		private const val TAG = "RecordingService"
		private const val CHANNEL_ID = "sleep_recording"
		private const val NOTIFICATION_ID = 41
		private const val ALERT_CHANNEL_ID = "patch_alert"
		private const val ALERT_NOTIFICATION_ID = 42
		const val ACTION_RESUME = "com.lucidify.lucidsleep.RESUME"
		const val ACTION_STOP = "com.lucidify.lucidsleep.STOP"

		/**
		 * Returns false when Android refuses to start a foreground service now (for example
		 * from the background on Android 12+). The night keeps recording; the service starts
		 * the next time the app comes to the foreground.
		 */
		fun start(context: Context): Boolean = runCatching {
			ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
		}.onFailure { Log.w(TAG, "Foreground service start refused", it) }.isSuccess

		fun resume(context: Context): Boolean = runCatching {
			ContextCompat.startForegroundService(
				context, Intent(context, RecordingService::class.java).setAction(ACTION_RESUME)
			)
		}.onFailure { Log.w(TAG, "Foreground service resume refused", it) }.isSuccess

		fun stop(context: Context) {
			runCatching {
				context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
			}
		}
	}
}

/** After a reboot or an app update, picks up a night that was being recorded. */
class ResumeReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		when (intent.action) {
			Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
				val app = context.applicationContext as LucidSleepApp
				if (app.graph.recorder.hasInterruptedNight()) RecordingService.resume(context)
			}
		}
	}
}