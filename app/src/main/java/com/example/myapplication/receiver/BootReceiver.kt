package com.example.myapplication.receiver

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.example.myapplication.MainActivity

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private const val ALERT_CHANNEL_ID = "game_time_alert"
        private const val ALERT_NOTIFICATION_ID = 100
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Boot completed, re-scheduling tasks")
                EmailReportReceiver.schedule(context)
                ServiceGuardReceiver.schedule(context)
                checkAndAlert(context)
            }
        }
    }

    private fun checkAndAlert(context: Context) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val serviceRunning = enabledServices.any {
            it.resolveInfo?.serviceInfo?.name?.contains("GameTimeService") == true
        }
        if (!serviceRunning) {
            showAlertNotification(context)
        }
    }

    private fun showAlertNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "安全警告",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "GameTime 服务状态警告"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ALERT_CHANNEL_ID)
                .setContentTitle("GameTime 服务已停止")
                .setContentText("无障碍服务未运行，游戏拦截功能已失效。点击打开应用重新开启。")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setContentTitle("GameTime 服务已停止")
                .setContentText("无障碍服务未运行，游戏拦截功能已失效。点击打开应用重新开启。")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
        }

        nm.notify(ALERT_NOTIFICATION_ID, notification)
    }
}
