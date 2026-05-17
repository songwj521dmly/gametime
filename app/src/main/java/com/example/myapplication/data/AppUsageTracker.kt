package com.example.myapplication.data

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.TimeUnit

object AppUsageTracker {
    private const val TAG = "AppUsageTracker"
    private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L

    private lateinit var context: Context
    private val handler = Handler(Looper.getMainLooper())

    private val checkRunnable = object : Runnable {
        override fun run() {
            validateAgainstUsageStats()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    fun startPeriodicCheck() {
        handler.post(checkRunnable)
    }

    fun stopPeriodicCheck() {
        handler.removeCallbacks(checkRunnable)
    }

    private fun validateAgainstUsageStats() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return
        val endTime = System.currentTimeMillis()
        val beginTime = endTime - TimeUnit.DAYS.toMillis(1)
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            beginTime,
            endTime
        )
        val duolingoFgSeconds = stats
            .filter { it.packageName == "com.duolingo" }
            .sumOf { it.totalTimeInForeground / 1000 }

        val timeBankMinutes = TimeBank.getStudyMinutesToday()
        val timeBankSeconds = timeBankMinutes * 60

        if (Math.abs(duolingoFgSeconds - timeBankSeconds) > 120) {
            Log.w(TAG, "Discrepancy: UsageStats=${duolingoFgSeconds}s, TimeBank=${timeBankSeconds}s")
        } else {
            Log.d(TAG, "OK: UsageStats=${duolingoFgSeconds}s, TimeBank=${timeBankSeconds}s")
        }
    }
}
