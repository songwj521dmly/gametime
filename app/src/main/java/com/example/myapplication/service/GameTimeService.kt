package com.example.myapplication.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.app.ActivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.myapplication.MainActivity
import com.example.myapplication.data.ActivityLog
import com.example.myapplication.data.EmailReporter
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
    private var lastStudyTickTime = 0L
    private var lastInteractionTime = 0L
    private var isGameForeground = false
    private var currentGamePkg: String? = null
    private var blockedPackages: Set<String> = emptySet()
    private var blockOverlay: View? = null
    private var windowManager: WindowManager? = null

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
                TimeBank.recordHourlyStudy(STUDY_TICK_MS / 1000 / 60)
                lastStudyTickTime = System.currentTimeMillis()
                Log.d(TAG, "Study tick: earned ${earned}s, balance=${TimeBank.getGameBalance()}s")
                handler.postDelayed(this, STUDY_TICK_MS)
            }
        }
    }

    private var consumeTickCount = 0

    private val consumeTick = object : Runnable {
        override fun run() {
            if (!isGameForeground) return

            // Every 3 seconds (starting from 3rd tick), verify game process is still running
            if (consumeTickCount > 0 && consumeTickCount % 3 == 0 && !isGameProcessStillForeground()) {
                Log.d(TAG, "Game process no longer foreground, stopping")
                stopConsuming()
                return
            }

            TimeBank.consumeGameSeconds(1)
            consumeTickCount++
            // Record hourly every 60 ticks (60 seconds)
            if (consumeTickCount >= 60) {
                TimeBank.recordHourlyGame(consumeTickCount.toLong())
                consumeTickCount = 0
            }
            val balance = TimeBank.getGameBalance()
            val dailyRemaining = TimeBank.getDailyGameRemainingSeconds()

            updateOverlay(balance)
            when {
                !TimeBank.isWithinGameTimeWindow() -> {
                    ActivityLog.record("GAME_BLOCK", "", "游戏时段结束，强制关闭")
                    showBlockOverlay()
                }
                dailyRemaining <= 0 -> {
                    ActivityLog.record("GAME_BLOCK", "", "今日游戏时长已达上限")
                    showBlockOverlay()
                }
                balance <= 0 -> {
                    showBlockOverlay()
                }
                else -> {
                    handler.postDelayed(this, CONSUME_TICK_MS)
                }
            }
            Log.d(TAG, "Consume tick: balance=${balance}s, dailyRemaining=${dailyRemaining}s")
        }
    }

    private fun isGameProcessStillForeground(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val processes = am.runningAppProcesses ?: return true // can't check, assume still running
        val result = processes.any { proc ->
            proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
            blockedPackages.any { proc.processName.startsWith(it) }
        }
        if (!result) {
            Log.d(TAG, "No blocked game process found in foreground")
        }
        return result
    }

    override fun onCreate() {
        super.onCreate()
        TimeBank.init(this)
        ActivityLog.init(this)
        EmailReporter.init(this)
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
        val eventType = event.eventType
        val typeName = eventTypeToString(eventType)

        Log.d(TAG, "Event: pkg=$pkg type=$typeName($eventType)")

        when {
            pkg == DUOLINGO_PACKAGE -> handleDuolingoEvent(event)
            blockedPackages.contains(pkg) -> handleGameEvent(event, pkg)
        }
    }

    private fun eventTypeToString(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TEXT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "VIEW_LONG_CLICKED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION"
        else -> "OTHER($type)"
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
        lastStudyTickTime = System.currentTimeMillis()
        ActivityLog.record("STUDY", DUOLINGO_PACKAGE, "开始学习多邻国")
        Log.d(TAG, "Enter STUDYING")
        handler.postDelayed(studyTick, STUDY_TICK_MS)
        updateNotification("学习中…")
    }

    private fun exitStudying() {
        if (state != State.STUDYING) return
        handler.removeCallbacks(studyTick)
        val elapsedSeconds = (System.currentTimeMillis() - lastStudyTickTime) / 1000
        if (elapsedSeconds > 0) {
            val earned = ExchangeEngine.calculateEarnedSeconds(
                elapsedSeconds,
                TimeBank.getConsecutiveDays(),
                TimeBank.isWeekend()
            )
            TimeBank.recordSettlement(elapsedSeconds, earned)
            TimeBank.recordHourlyStudy(elapsedSeconds / 60)
        }
        state = State.IDLE
        ActivityLog.record("STUDY_END", DUOLINGO_PACKAGE, "结束学习 ${elapsedSeconds / 60}分${elapsedSeconds % 60}秒")
        Log.d(TAG, "Exit STUDYING, elapsed=${elapsedSeconds}s, balance=${TimeBank.getGameBalance()}s")
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

        // If switching from one game to another, close the previous one first
        if (isGameForeground && currentGamePkg != pkg) {
            Log.d(TAG, "Switching game: $currentGamePkg -> $pkg")
            stopConsuming()
        }

        val balance = TimeBank.getGameBalance()
        val dailyRemaining = TimeBank.getDailyGameRemainingSeconds()

        when {
            // Check time window first
            !TimeBank.isWithinGameTimeWindow() -> {
                ActivityLog.record("GAME_BLOCK", pkg, "非游戏时段，拦截")
                showBlockOverlay()
            }
            // Check daily limit
            dailyRemaining <= 0 -> {
                ActivityLog.record("GAME_BLOCK", pkg, "今日游戏时长已达上限，拦截")
                showBlockOverlay()
            }
            // Check balance
            balance <= 0 -> {
                ActivityLog.record("GAME_BLOCK", pkg, "余额不足，拦截")
                showBlockOverlay()
            }
            // All clear - start game
            !isGameForeground -> {
                isGameForeground = true
                currentGamePkg = pkg
                ActivityLog.record("GAME_OPEN", pkg, "游戏启动: $pkg, 余额: ${balance}s, 今日剩余: ${dailyRemaining}s")
                handler.post(consumeTick)
            }
        }
    }

    private fun stopConsuming() {
        val balance = TimeBank.getGameBalance()
        if (consumeTickCount > 0) {
            TimeBank.recordHourlyGame(consumeTickCount.toLong())
            consumeTickCount = 0
        }
        ActivityLog.record("GAME_CLOSE", currentGamePkg ?: "", "游戏关闭，剩余余额: ${balance}秒")
        isGameForeground = false
        currentGamePkg = null
        handler.removeCallbacks(consumeTick)
        hideBlockScreen()
        Log.d(TAG, "Stopped consuming, balance=${balance}s")
    }

    private fun showBlockOverlay() {
        stopConsuming()
        showBlockScreen()
        updateNotification("游戏时间已用完")
    }

    private fun updateOverlay(balance: Long) {
        if (balance in 1..60) {
            updateNotification("剩余游戏时间: ${balance}秒")
        }
    }

    private fun showBlockScreen() {
        if (blockOverlay != null) return
        val wm = windowManager ?: run {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager!!
        }
        val ctx = this
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 0, 0, 0))
            gravity = Gravity.CENTER
        }
        val text = TextView(ctx).apply {
            text = "⏰ 游戏时间已用完\n\n去多邻国学习赚取时间吧！"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }
        layout.addView(text)
        val btn = Button(ctx).apply {
            setText("返回桌面")
            setOnClickListener {
                hideBlockScreen()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
        layout.addView(btn)
        blockOverlay = layout

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
        }
        wm.addView(layout, params)
        Log.d(TAG, "Block screen shown")
    }

    private fun hideBlockScreen() {
        val overlay = blockOverlay ?: return
        try {
            windowManager?.removeView(overlay)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove overlay: ${e.message}")
        }
        blockOverlay = null
        Log.d(TAG, "Block screen hidden")
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
