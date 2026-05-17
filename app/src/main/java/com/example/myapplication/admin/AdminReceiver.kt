package com.example.myapplication.admin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.myapplication.MainActivity
import com.example.myapplication.data.EmailReporter

class AdminReceiver : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "AdminReceiver"
        private const val ALERT_CHANNEL_ID = "game_time_admin_alert"
        private const val ALERT_NOTIFICATION_ID = 200
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.d(TAG, "Device admin enabled")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ALERT_NOTIFICATION_ID)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "Device admin disabled - locking device")
        EmailReporter.init(context)
        showDisableAlert(context)
        EmailReporter.sendAlert(
            "设备管理权限被移除",
            "GameTime 设备管理权限已被移除，应用可能被卸载。请立即检查设备。"
        )

        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = android.content.ComponentName(context, AdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock device: ${e.message}")
        }
    }

    override fun onPasswordChanged(context: Context, intent: Intent) {
        Log.d(TAG, "Password changed")
    }

    private fun showDisableAlert(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "设备管理警告",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "设备管理权限变更警告"
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
                .setContentTitle("GameTime 安全警告")
                .setContentText("设备管理权限已被移除！应用可能被卸载。请立即打开应用重新激活保护。")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setContentTitle("GameTime 安全警告")
                .setContentText("设备管理权限已被移除！应用可能被卸载。请立即打开应用重新激活保护。")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
        }

        nm.notify(ALERT_NOTIFICATION_ID, notification)
    }
}
