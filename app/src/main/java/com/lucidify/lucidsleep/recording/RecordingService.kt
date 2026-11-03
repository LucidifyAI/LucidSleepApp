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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and the CPU awake while a night is recorded. The recording itself
 * lives in [NightRecorder]; this service only holds the foreground slot and a wake lock, and
 * resumes the night when Android restarts the service after killing the process.
 */
class RecordingService : Service() {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private var wakeLock: PowerManager.WakeLock? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_STOP) {
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