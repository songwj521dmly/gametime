package com.example.myapplication.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.myapplication.MainActivity
import com.example.myapplication.data.TimeBank
import com.example.myapplication.model.ExchangeEngine

class GameTimeService : AccessibilityService() {

    companion object {
        private const val TAG = "GameTimeService"
        private const val NOTIFICATION_CHANNEL_ID = "game_time_channel"
        private const val NOTIFICATION_ID = 1
        const val DUOLINGO_PACKAGE = "com.duolingo"
        private const val STUDY_TICK_MS = 60_000L
        private const val CONSUME_TICK_MS = 1_000L
        private const val IDLE_TIMEOUT_MS = 30_000L
    }

    enum class State { IDLE, STUDYING }

    private var state = State.IDLE
    private var studyAccumulatorSeconds = 0L
    private var lastInteractionTime = 0L
    private var isGameForeground = false
    private var blockedPackages: Set<String> = emptySet()

    private val handler = Handler(Looper.getMainLooper())

    private val studyTick = object : Runnable {
        override fun run() {
            if (state == State.STUDYING) {
                val earned = ExchangeEngine.calculateEarnedSeconds(
                    STUDY_TICK_MS / 1000,
                    TimeBank.getConsecutiveDays(),
                    TimeBank.isWeekend()
                )
                TimeBank.recordSettlement(STUDY_TICK_MS / 1000, earned)
                studyAccumulatorSeconds = 0L
                Log.d(TAG, "Study tick: earned ${earned}s, balance=${TimeBank.getGameBalance()}s")
                handler.postDelayed(this, STUDY_TICK_MS)
            }
        }
    }

    private val consumeTick = object : Runnable {
        override fun run() {
            if (isGameForeground) {
                TimeBank.consumeGameSeconds(1)
                val balance = TimeBank.getGameBalance()
                updateOverlay(balance)
                if (balance <= 0) {
                    showBlockOverlay()
                    handler.postDelayed({
                        if (TimeBank.getGameBalance() <= 0) {
                            goHome()
                        }
                    }, 60_000L)
                } else {
                    handler.postDelayed(this, CONSUME_TICK_MS)
                }
                Log.d(TAG, "Consume tick: balance=${balance}s")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        TimeBank.init(this)
        createNotificationChannel()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        blockedPackages = TimeBank.getBlockedPackages()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info
        startForeground(NOTIFICATION_ID, buildNotification("就绪"))
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        when {
            pkg == DUOLINGO_PACKAGE -> handleDuolingoEvent(event)
            blockedPackages.contains(pkg) -> handleGameEvent(event, pkg)
        }
    }

    private fun handleDuolingoEvent(event: AccessibilityEvent) {
        val eventType = event.eventType
        val isInteraction = eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED

        if (isInteraction) {
            lastInteractionTime = System.currentTimeMillis()
            if (state == State.IDLE) {
                enterStudying()
            }
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (state == State.STUDYING) {
                scheduleIdleCheck()
            }
        }
    }

    private fun enterStudying() {
        state = State.STUDYING
        studyAccumulatorSeconds = 0L
        Log.d(TAG, "Enter STUDYING")
        handler.postDelayed(studyTick, STUDY_TICK_MS)
        updateNotification("学习中…")
    }

    private fun exitStudying() {
        if (state != State.STUDYING) return
        handler.removeCallbacks(studyTick)
        if (studyAccumulatorSeconds > 0) {
            val earned = ExchangeEngine.calculateEarnedSeconds(
                studyAccumulatorSeconds,
                TimeBank.getConsecutiveDays(),
                TimeBank.isWeekend()
            )
            TimeBank.recordSettlement(studyAccumulatorSeconds, earned)
        }
        state = State.IDLE
        studyAccumulatorSeconds = 0L
        Log.d(TAG, "Exit STUDYING, balance=${TimeBank.getGameBalance()}s")
        updateNotification("就绪")
    }

    private fun scheduleIdleCheck() {
        handler.postDelayed({
            if (state == State.STUDYING &&
                System.currentTimeMillis() - lastInteractionTime >= IDLE_TIMEOUT_MS) {
                exitStudying()
            }
        }, IDLE_TIMEOUT_MS + 1000)
    }

    private fun handleGameEvent(event: AccessibilityEvent, pkg: String) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val balance = TimeBank.getGameBalance()
        if (balance <= 0) {
            showBlockOverlay()
        } else if (!isGameForeground) {
            isGameForeground = true
            handler.post(consumeTick)
        }
    }

    private fun showBlockOverlay() {
        isGameForeground = false
        handler.removeCallbacks(consumeTick)
        updateNotification("游戏时间已用完")
        goHome()
    }

    private fun updateOverlay(balance: Long) {
        if (balance in 1..60) {
            updateNotification("剩余游戏时间: ${balance}秒")
        }
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "游戏时间管理",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "多邻国学习解锁游戏"
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("GameTime")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("GameTime")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onInterrupt() {
        exitStudying()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
