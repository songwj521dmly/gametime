package com.example.myapplication.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.myapplication.data.ActivityLog
import com.example.myapplication.data.EmailReporter
import com.example.myapplication.data.TimeBank
import kotlinx.coroutines.runBlocking
import java.util.Calendar

class EmailReportReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "EmailReportReceiver"

        fun schedule(context: Context) {
            EmailReporter.init(context)
            ActivityLog.init(context)
            TimeBank.init(context)

            if (!EmailReporter.isEnabled() || !EmailReporter.isConfigured()) {
                Log.d(TAG, "Email not enabled or configured, skipping schedule")
                return
            }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, EmailReportReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, EmailReporter.getSendHour())
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }

            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pendingIntent
            )
            Log.d(TAG, "Email report scheduled for ${EmailReporter.getSendHour()}:00 daily")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        EmailReporter.init(context)
        ActivityLog.init(context)
        TimeBank.init(context)

        if (!EmailReporter.isEnabled() || !EmailReporter.isConfigured()) {
            Log.d(TAG, "Email disabled or not configured")
            return
        }

        Log.d(TAG, "Sending daily report...")
        Thread {
            runBlocking {
                val result = EmailReporter.sendReport()
                result.onSuccess {
                    Log.d(TAG, "Daily report sent successfully")
                }.onFailure {
                    Log.e(TAG, "Daily report failed: ${it.message}")
                }
            }
        }.start()
    }
}
