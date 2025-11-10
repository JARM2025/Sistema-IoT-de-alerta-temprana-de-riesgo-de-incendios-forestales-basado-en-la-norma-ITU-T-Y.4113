package site.weatherstation.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

private const val PREFS_NAME = "permission_prefs"
private const val KEY_ASKED_POST_NOTIFICATIONS = "asked_post_notifications"

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

fun requestNotificationsIfNeeded(
    context: Context,
    permissionLauncher: ActivityResultLauncher<String>
) {
    // Android 13+ requiere POST_NOTIFICATIONS
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val askedBefore = prefs.getBoolean(KEY_ASKED_POST_NOTIFICATIONS, false)
            val activity = context.findActivity()
            val shouldShow = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(
                    it, Manifest.permission.POST_NOTIFICATIONS
                )
            } ?: false

            when {
                !askedBefore -> {
                    prefs.edit().putBoolean(KEY_ASKED_POST_NOTIFICATIONS, true).apply()
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
                shouldShow -> {
                    // Vuelve a lanzar el permiso mostrando racional
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
                else -> {
                    openAppNotificationSettings(context)
                    return
                }
            }
        }
    }

    val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    if (!enabled) {
        openAppNotificationSettings(context)
    }
}

private fun openAppNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

/** ✅ NUEVO: helper para saber si la app puede mostrar notificaciones ahora mismo. */
fun canPostNotifications(context: Context): Boolean {
    // Android 13+ necesita permiso explícito
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false
    }
    // Y que el usuario no haya bloqueado las notificaciones de la app en sistema
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}
