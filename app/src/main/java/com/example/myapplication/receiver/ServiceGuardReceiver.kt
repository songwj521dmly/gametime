package com.example.myapplication.receiver

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.example.myapplication.MainActivity
import com.example.myapplication.data.EmailReporter

class ServiceGuardReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ServiceGuard"
        private const val ALERT_CHANNEL_ID = "game_time_alert"
        private const val ALERT_NOTIFICATION_ID = 101
        private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L
        private const val PREFS_NAME = "service_guard"
        private const val KEY_ALERT_SENT = "alert_sent"

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ServiceGuardReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + CHECK_INTERVAL_MS,
                CHECK_INTERVAL_MS,
                pendingIntent
            )
            Log.d(TAG, "Guard scheduled every ${CHECK_INTERVAL_MS / 60000} minutes")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Guard check running")
        EmailReporter.init(context)
        checkServiceStatus(context)
    }

    private fun checkServiceStatus(context: Context) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        val serviceRunning = enabledServices.any {
            it.resolveInfo?.serviceInfo?.name?.contains("GameTimeService") == true
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (serviceRunning) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ALERT_NOTIFICATION_ID)
            prefs.edit().putBoolean(KEY_ALERT_SENT, false).apply()
            Log.d(TAG, "Service OK")
        } else {
            Log.w(TAG, "Service STOPPED - showing alert")
            showAlertNotification(context)

            // Send email alert once per stoppage
            val alreadySent = prefs.getBoolean(KEY_ALERT_SENT, false)
            if (!alreadySent) {
                EmailReporter.sendAlert(
                    "无障碍服务已停止",
                    "GameTime 无障碍服务已被关闭，游戏拦截和计时功能失效。请立即检查设备。"
                )
                prefs.edit().putBoolean(KEY_ALERT_SENT, true).apply()
                Log.d(TAG, "Alert email sent for service stop")
            }
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
                .setContentTitle("GameTime 服务异常")
                .setContentText("无障碍服务已停止！游戏拦截功能失效。点击打开应用重新开启。")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setContentTitle("GameTime 服务异常")
                .setContentText("无障碍服务已停止！游戏拦截功能失效。点击打开应用重新开启。")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
        }

        nm.notify(ALERT_NOTIFICATION_ID, notification)
    }
}
