package site.weatherstation.notifications

import android.os.Build

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import site.weatherstation.ui.notifications.AlarmDataStore

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.intent.action.BOOT_COMPLETED") return
        runBlocking {
            val hasEnabled = AlarmDataStore.getAlarms(context).any { it.enabled }
            if (hasEnabled) {
                val start = Intent(context, AlarmForegroundService::class.java)
                    .setAction(AlarmForegroundService.ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(start)
            } else {
                context.startService(start)
            }
            }
        }
    }
}