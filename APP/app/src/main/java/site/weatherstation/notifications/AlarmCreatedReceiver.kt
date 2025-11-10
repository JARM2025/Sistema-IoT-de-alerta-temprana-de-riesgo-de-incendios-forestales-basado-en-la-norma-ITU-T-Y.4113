package site.weatherstation.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import site.weatherstation.MainActivity
import site.weatherstation.R

/**
 * Recibe el broadcast cuando una alarma se dispara y muestra la notificación del sistema.
 * Usa un ID de notificación único por alarma para permitir múltiples notificaciones simultáneas.
 */
class AlarmCreatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Ensure alerts channel exists on O+
        NotificationHelper.ensureChannel(context)

        val message: String = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()

        // ID único por alarma (estable). Si no viene, usa timestamp como fallback (también único).
        val notifId: Int = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
            .let { if (it == -1L) System.currentTimeMillis() else it }
            .let { (it and 0x7FFF_FFFFL).toInt() }

        // Título a partir del label de la app (evita depender de strings.xml)
        val appLabel = try {
            context.applicationInfo.loadLabel(context.packageManager)?.toString() ?: "Weather Station"
        } catch (e: Exception) {
            "Weather Station"
        }

        // Intent para abrir la app en la pestaña de "Notifications"
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_NAV_DESTINATION, "notifications")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Construye la notificación
        val contentText = if (message.isNotBlank()) message else "Alarm triggered"
        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(appLabel)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(notifId, notification)
    }

    companion object {
        const val ACTION = "site.weatherstation.ACTION_ALARM_CREATED"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_NAV_DESTINATION = "extra_nav_destination"
    }
}
