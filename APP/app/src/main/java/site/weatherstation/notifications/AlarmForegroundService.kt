package site.weatherstation.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import site.weatherstation.MainActivity
import site.weatherstation.R
import site.weatherstation.ui.notifications.AlarmEvaluator
import site.weatherstation.util.alignedTicker

class AlarmForegroundService : Service() {

    companion object {
        const val ACTION_START = "site.weatherstation.notifications.ACTION_START"
        const val ACTION_STOP  = "site.weatherstation.notifications.ACTION_STOP"
        const val CHANNEL_ID   = "alarm_service"
        const val NOTIF_ID     = 2025
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runningJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLoop()
            ACTION_STOP  -> stopSelf()
            else         -> startLoop()
        }
        return START_STICKY
    }

    private fun startLoop() {
        if (runningJob?.isActive == true) return
        ensureChannel()
        startForeground(NOTIF_ID, buildOngoingNotification())
        runningJob = serviceScope.launch {
            // 10s alineados a :05, :15, :25, ...
            alignedTicker(periodMs = 10_000L, offsetMs = 5_000L).collect {
                AlarmEvaluator.evaluateOnce(applicationContext)
            }
        }
    }

    private fun buildOngoingNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher) // usa launcher para evitar faltantes
            .setContentTitle("Weather Station – Alarms running")
            .setContentText("Monitoring thresholds every 10s")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Alarm checker",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
    }

    override fun onDestroy() {
        super.onDestroy()
        runningJob?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
