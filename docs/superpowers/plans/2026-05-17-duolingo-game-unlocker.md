# Duolingo Game Unlocker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android app that monitors Duolingo learning via AccessibilityService and unlocks game app usage time proportionally.

**Architecture:** Single Activity (Compose) + single AccessibilityService doing double duty (Duolingo detection + game blocking). Data stored in SharedPreferences with epoch-millis timestamps converted to UTC+8 for date logic. ExchangeEngine computes earned game time using a multiplication formula with streak/weekend bonuses.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Android SDK 34+, AccessibilityService API, UsageStatsManager

---

### Task 1: TimeBank Data Layer

**Files:**
- Create: `app/src/main/java/com/example/myapplication/data/TimeBank.kt`

- [ ] **Step 1: Write TimeBank.kt**

```kotlin
package com.example.myapplication.data

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

object TimeBank {
    private const val PREFS_NAME = "time_bank"
    private const val KEY_GAME_BALANCE_SECONDS = "game_balance_seconds"
    private const val KEY_DAILY_STUDY = "daily_study"        // JSON: {"2026-05-17": 240, ...}
    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    private const val KEY_LAST_SETTLEMENT_MS = "last_settlement_ms"
    private const val ZONE = "+08:00"

    private val zoneId: ZoneId = ZoneId.of(ZONE)

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun todayKey(): String = LocalDate.now(zoneId).toString()

    // --- Game Balance ---

    fun getGameBalance(): Long = prefs.getLong(KEY_GAME_BALANCE_SECONDS, 0)

    fun addGameSeconds(seconds: Long) {
        prefs.edit().putLong(KEY_GAME_BALANCE_SECONDS, getGameBalance() + seconds).apply()
    }

    fun consumeGameSeconds(seconds: Long) {
        val current = getGameBalance()
        val newValue = maxOf(0L, current - seconds)
        prefs.edit().putLong(KEY_GAME_BALANCE_SECONDS, newValue).apply()
    }

    // --- Daily Study Minutes ---

    fun getStudyMinutesToday(): Long {
        val json = prefs.getString(KEY_DAILY_STUDY, "{}") ?: "{}"
        return parseDailyMap()[todayKey()] ?: 0L
    }

    /**
     * Called when STUDYING state settles (at each 60-second tick and on state exit).
     * Adds earned game seconds to balance and records study minutes for streak tracking.
     */
    fun recordSettlement(studySeconds: Long, earnedGameSeconds: Long) {
        val map = parseDailyMap().toMutableMap()
        val key = todayKey()
        map[key] = (map[key] ?: 0L) + (studySeconds / 60)
        // Keep only last 60 days
        val cutoff = LocalDate.now(zoneId).minusDays(60).toString()
        val trimmed = map.filterKeys { it >= cutoff }
        prefs.edit()
            .putString(KEY_DAILY_STUDY, toDailyJson(trimmed))
            .putLong(KEY_GAME_BALANCE_SECONDS, getGameBalance() + earnedGameSeconds)
            .putLong(KEY_LAST_SETTLEMENT_MS, System.currentTimeMillis())
            .apply()
    }

    // --- Streak ---

    fun getConsecutiveDays(): Int {
        val map = parseDailyMap()
        val today = LocalDate.now(zoneId)
        var streak = 0
        var cursor = if (map.containsKey(today.toString())) today else today.minusDays(1)
        while (true) {
            val minutes = map[cursor.toString()] ?: 0L
            if (minutes >= 4) {
                streak++
                cursor = cursor.minusDays(1)
            } else {
                break
            }
            if (streak > 365) break
        }
        return streak
    }

    fun getLastStudyDate(): String? {
        val map = parseDailyMap()
        return map.filter { it.value >= 4 }.keys.maxOrNull()
    }

    // --- Blocked Packages ---

    fun getBlockedPackages(): Set<String> {
        val defaults = setOf(
            "com.tencent.tmgp.sgame",
            "com.tencent.tmgp.pubgmhd",
            "com.miHoYo.GenshinImpact",
            "com.HoYoverse.hkrpgoversea",
            "com.hypergryph.arknights",
            "com.tencent.jkchess",
            "com.netease.party"
        )
        val stored = prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()) ?: emptySet()
        return if (stored.isEmpty()) defaults else stored
    }

    fun setBlockedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, packages).apply()
    }

    // --- Persistence helpers ---

    private fun parseDailyMap(): Map<String, Long> {
        val json = prefs.getString(KEY_DAILY_STUDY, "{}") ?: "{}"
        return try {
            val cleaned = json.trim().removeSurrounding("{", "}")
            if (cleaned.isEmpty()) emptyMap()
            else cleaned.split(",").mapNotNull { part ->
                val kv = part.split(":").map { it.trim().removeSurrounding("\"") }
                if (kv.size == 2) kv[0] to kv[1].toLong() else null
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun toDailyJson(map: Map<String, Long>): String {
        val entries = map.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
        return "{$entries}"
    }

    fun getCurrentStreakBonus(): Double = minOf(getConsecutiveDays() * 0.10, 0.50)

    fun isWeekend(): Boolean {
        val dayOfWeek = LocalDate.now(zoneId).dayOfWeek
        return dayOfWeek == java.time.DayOfWeek.SATURDAY || dayOfWeek == java.time.DayOfWeek.SUNDAY
    }
}
```

- [ ] **Step 2: Build to verify compilation**

```bash
cd /f/phone && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

---

### Task 2: ExchangeEngine Model

**Files:**
- Create: `app/src/main/java/com/example/myapplication/model/ExchangeEngine.kt`

- [ ] **Step 1: Write ExchangeEngine.kt**

```kotlin
package com.example.myapplication.model

object ExchangeEngine {
    /**
     * Calculate earned game seconds from raw study seconds.
     * Multiplication model: studySeconds * (1.0 + streakBonus) * weekendMultiplier
     */
    fun calculateEarnedSeconds(studySeconds: Long, consecutiveDays: Int, isWeekend: Boolean): Long {
        val streakBonus = minOf(consecutiveDays * 0.10, 0.50)
        val weekendMultiplier = if (isWeekend) 2.0 else 1.0
        val multiplier = (1.0 + streakBonus) * weekendMultiplier
        return (studySeconds * multiplier).toLong()
    }

    fun getMultiplierDescription(consecutiveDays: Int, isWeekend: Boolean): String {
        val streakBonus = minOf(consecutiveDays * 0.10, 0.50)
        val weekendMultiplier = if (isWeekend) 2.0 else 1.0
        val sb = StringBuilder("x${String.format("%.1f", (1.0 + streakBonus) * weekendMultiplier)}")
        if (streakBonus > 0) sb.append(" (连续${consecutiveDays}天+${(streakBonus * 100).toInt()}%)")
        if (isWeekend) sb.append(" (周末双倍)")
        return sb.toString()
    }
}
```

- [ ] **Step 2: Build to verify compilation**

```bash
cd /f/phone && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

---

### Task 3: Accessibility Service Config XML

**Files:**
- Create: `app/src/main/res/xml/accessibility_service_config.xml`

- [ ] **Step 1: Write accessibility_service_config.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/accessibility_service_description"
    android:accessibilityEventTypes="typeWindowStateChanged|typeViewClicked|typeViewTextChanged|typeViewScrolled"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="true"
    android:accessibilityFlags="flagDefault|flagIncludeNotImportantViews|flagReportViewIds" />
```

- [ ] **Step 2: Build to verify resource processing**

```bash
cd /f/phone && ./gradlew :app:processDebugResources 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

---

### Task 4: GameTimeService (AccessibilityService)

**Files:**
- Create: `app/src/main/java/com/example/myapplication/service/GameTimeService.kt`

This is the core. Build in sub-steps: skeleton → study detection → game blocking → overlay.

**Step 1: Service skeleton with state machine, Handler, and notification**

```kotlin
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
        private const val STUDY_TICK_MS = 60_000L   // 60 seconds
        private const val CONSUME_TICK_MS = 1_000L  // 1 second
        private const val IDLE_TIMEOUT_MS = 30_000L // 30s no interaction → IDLE
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
                    // Give 60s grace then go home
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
        // Interaction events indicate active engagement
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
                // Duolingo may still be foreground — check timeout
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
        // Settle remaining accumulated seconds
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

    // --- Overlay (simplified: uses a full-screen Intent-based approach) ---

    private fun showBlockOverlay() {
        isGameForeground = false
        handler.removeCallbacks(consumeTick)
        // Broadcast a message to MainActivity to show block UI
        // As AccessibilityService, we send a notification + try to go home
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

    // --- Notification ---

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
```

**Step 2: Build to verify compilation**

```bash
cd /f/phone && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

---

### Task 5: AppUsageTracker (Validation-Only)

**Files:**
- Create: `app/src/main/java/com/example/myapplication/data/AppUsageTracker.kt`

- [ ] **Step 1: Write AppUsageTracker.kt**

```kotlin
package com.example.myapplication.data

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.TimeUnit

object AppUsageTracker {
    private const val TAG = "AppUsageTracker"
    private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

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

    /**
     * Pulls UsageStats for Duolingo and compares against TimeBank.
     * Only logs discrepancies — does NOT override AccessibilityService data.
     */
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
```

- [ ] **Step 2: Build to verify compilation**

```bash
cd /f/phone && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

---

### Task 6: Update AndroidManifest with Permissions and Service

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Read current manifest and replace**

Rewrite `app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Permissions -->
    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
        tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.VIBRATE" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.MyApplication">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.MyApplication">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.GameTimeService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
            android:foregroundServiceType="specialUse">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

    </application>
</manifest>
```

- [ ] **Step 2: Build to verify manifest processing**

```bash
cd /f/phone && ./gradlew :app:processDebugManifest 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

---

### Task 7: DashboardScreen UI

**Files:**
- Create: `app/src/main/java/com/example/myapplication/ui/DashboardScreen.kt`

- [ ] **Step 1: Write DashboardScreen.kt**

```kotlin
package com.example.myapplication.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.TimeBank

@Composable
fun DashboardScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToPermissions: () -> Unit
) {
    // Refresh every second while visible
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    // Force recomposition on tick change
    @Suppress("UNUSED_EXPRESSION")
    tick

    val studyMinutes = TimeBank.getStudyMinutesToday()
    val gameBalanceSeconds = TimeBank.getGameBalance()
    val streakDays = TimeBank.getConsecutiveDays()
    val isWeekend = TimeBank.isWeekend()
    val streakBonus = TimeBank.getCurrentStreakBonus()
    val weekendMultiplier = if (isWeekend) 2.0 else 1.0
    val totalMultiplier = (1.0 + streakBonus) * weekendMultiplier

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GameTime") },
                actions = {
                    TextButton(onClick = onNavigateToPermissions) {
                        Text("权限")
                    }
                    TextButton(onClick = onNavigateToSettings) {
                        Text("设置")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Study time card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("今日学习", fontSize = 14.sp)
                    Text(
                        text = "${studyMinutes}分钟",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Game balance card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("可用游戏时间", fontSize = 14.sp)
                    Text(
                        text = formatSeconds(gameBalanceSeconds),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Status info
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InfoRow("连续打卡", "${streakDays}天")
                    InfoRow("打卡加成", "+${(streakBonus * 100).toInt()}%")
                    InfoRow("周末状态", if (isWeekend) "双倍中 🎉" else "平日")
                    InfoRow("综合倍率", "x${String.format("%.1f", totalMultiplier)}")
                }
            }

            // Quick tip
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Text(
                    text = when {
                        studyMinutes < 4 -> "今天学满4分钟打卡，解锁游戏时间！"
                        gameBalanceSeconds > 0 -> "尽情游戏吧，注意时间哦～"
                        else -> "多学多玩，加油！"
                    },
                    modifier = Modifier.padding(16.dp),
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun formatSeconds(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "${hours}h ${minutes}m"
    } else {
        "${minutes}m ${seconds}s"
    }
}
```

- [ ] **Step 2: Note — DashboardScreen uses `kotlinx.coroutines.delay` which requires the kotlinx-coroutines dependency. The `lifecycle-runtime-ktx` dependency already includes it transitively. Verify build:**

```bash
cd /f/phone && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

---

### Task 8: SettingsScreen UI

**Files:**
- Create: `app/src/main/java/com/example/myapplication/ui/SettingsScreen.kt`

- [ ] **Step 1: Write SettingsScreen.kt**

```kotlin
package com.example.myapplication.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.TimeBank

data class AppInfo(val packageName: String, val label: String)

@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var blockedPackages by remember {
        mutableStateOf(TimeBank.getBlockedPackages())
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var manualPackageName by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf(loadInstalledApps(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("游戏列表管理") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                Text(
                    "被拦截的游戏应用",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "余额不足时将拦截以下游戏",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(blockedPackages.toList()) { pkg ->
                val label = installedApps.find { it.packageName == pkg }?.label ?: pkg
                ListItem(
                    headlineContent = { Text(label) },
                    supportingContent = { Text(pkg) },
                    trailingContent = {
                        IconButton(onClick = {
                            val newSet = blockedPackages.toMutableSet().apply { remove(pkg) }
                            blockedPackages = newSet
                            TimeBank.setBlockedPackages(newSet)
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                )
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    if (showAddDialog) {
        AddGameDialog(
            installedApps = installedApps.filter { it.packageName !in blockedPackages },
            manualPackageName = manualPackageName,
            onManualPackageNameChange = { manualPackageName = it },
            onSelectApp = { pkg ->
                val newSet = blockedPackages.toMutableSet().apply { add(pkg) }
                blockedPackages = newSet
                TimeBank.setBlockedPackages(newSet)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
private fun AddGameDialog(
    installedApps: List<AppInfo>,
    manualPackageName: String,
    onManualPackageNameChange: (String) -> Unit,
    onSelectApp: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加游戏") },
        text = {
            LazyColumn(modifier = Modifier.height(400.dp)) {
                item {
                    OutlinedTextField(
                        value = manualPackageName,
                        onValueChange = onManualPackageNameChange,
                        label = { Text("手动输入包名") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        singleLine = true
                    )
                    if (manualPackageName.isNotBlank()) {
                        TextButton(onClick = {
                            onSelectApp(manualPackageName.trim())
                        }) {
                            Text("添加: $manualPackageName")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        "已安装应用",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(installedApps) { app ->
                    ListItem(
                        headlineContent = { Text(app.label) },
                        supportingContent = { Text(app.packageName) },
                        modifier = Modifier.clickable { onSelectApp(app.packageName) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun loadInstalledApps(context: android.content.Context): List<AppInfo> {
    return try {
        context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != context.packageName }
            .sortedBy { context.packageManager.getApplicationLabel(it).toString() }
            .map {
                AppInfo(
                    packageName = it.packageName,
                    label = context.packageManager.getApplicationLabel(it).toString()
                )
            }
    } catch (e: Exception) {
        emptyList()
    }
}
```

- [ ] **Step 2: Build to verify compilation**

```bash
cd /f/phone && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

---

### Task 9: PermissionGuideScreen UI

**Files:**
- Create: `app/src/main/java/com/example/myapplication/ui/PermissionGuideScreen.kt`

- [ ] **Step 1: Write PermissionGuideScreen.kt**

```kotlin
package com.example.myapplication.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class PermissionItem(
    val name: String,
    val description: String,
    val isGranted: Boolean,
    val action: (Context) -> Unit
)

@Composable
fun PermissionGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick

    val permissions = listOf(
        PermissionItem(
            name = "使用情况访问权限",
            description = "用于辅助验证多邻国使用时长。必须开启。",
            isGranted = checkUsageStatsPermission(context),
            action = { ctx ->
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        ),
        PermissionItem(
            name = "无障碍服务",
            description = "核心权限。检测多邻国学习和拦截游戏应用。必须开启。",
            isGranted = checkAccessibilityServiceEnabled(context),
            action = { ctx ->
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        ),
        PermissionItem(
            name = "悬浮窗权限",
            description = "用于在游戏上方显示拦截提示。必须开启。",
            isGranted = Settings.canDrawOverlays(context),
            action = { ctx ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
                ctx.startActivity(intent)
            }
        ),
        PermissionItem(
            name = "省电优化豁免",
            description = "防止系统在后台杀死服务。强烈建议开启。",
            isGranted = checkBatteryOptimization(context),
            action = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                    }
                    ctx.startActivity(intent)
                }
            }
        ),
        PermissionItem(
            name = "通知权限",
            description = "用于前台服务通知。Android 13+ 必须开启。",
            isGranted = checkNotificationPermission(context),
            action = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    }
                    ctx.startActivity(intent)
                }
            }
        )
    )

    val allGranted = permissions.all { it.isGranted }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限设置") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (allGranted) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "所有权限已就绪 ✅",
                        modifier = Modifier.padding(16.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "还有 ${permissions.count { !it.isGranted }} 项权限需要开启",
                        modifier = Modifier.padding(16.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            permissions.forEach { perm ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (perm.isGranted)
                            MaterialTheme.colorScheme.surfaceVariant
                        else
                            MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (perm.isGranted) Icons.Default.Check
                                else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (perm.isGranted)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(perm.name, fontWeight = FontWeight.Medium)
                            Text(
                                perm.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!perm.isGranted) {
                            Button(onClick = { perm.action(context) }) {
                                Text("开启")
                            }
                        }
                    }
                }
            }

            // ROM-specific guidance
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("国产手机额外步骤", fontWeight = FontWeight.Bold)
                    Text(
                        "MIUI/ColorOS等系统请手动完成：\n" +
                        "1. 设置 > 应用 > GameTime > 自启动（开启）\n" +
                        "2. 多任务界面 > 长按GameTime > 锁定\n" +
                        "3. 设置 > 应用 > GameTime > 省电策略 > 无限制",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun checkUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun checkAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(
        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
    )
    return enabledServices.any {
        it.resolveInfo?.serviceInfo?.name?.contains("GameTimeService") == true
    }
}

private fun checkBatteryOptimization(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } else {
        true
    }
}

private fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}
```

- [ ] **Step 2: Build to verify compilation**

```bash
cd /f/phone && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

---

### Task 10: Update MainActivity with Navigation and Service Check

**Files:**
- Modify: `app/src/main/java/com/example/myapplication/MainActivity.kt`

- [ ] **Step 1: Rewrite MainActivity.kt with navigation and service status check**

```kotlin
package com.example.myapplication

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.AppUsageTracker
import com.example.myapplication.data.TimeBank
import com.example.myapplication.ui.DashboardScreen
import com.example.myapplication.ui.PermissionGuideScreen
import com.example.myapplication.ui.SettingsScreen
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TimeBank.init(this)
        AppUsageTracker.init(this)
        AppUsageTracker.startPeriodicCheck()
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TimeBank.init(this)
    }
}

enum class Screen { DASHBOARD, SETTINGS, PERMISSIONS }

@Composable
fun MainNavHost() {
    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }

    when (currentScreen) {
        Screen.DASHBOARD -> {
            // Show service warning banner if AccessibilityService is not enabled
            val context = androidx.compose.ui.platform.LocalContext.current
            val serviceEnabled = remember { checkServiceEnabled(context) }
            var showWarning by remember { mutableStateOf(!serviceEnabled) }

            Column(modifier = Modifier.fillMaxSize()) {
                if (showWarning) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "⚠ 无障碍服务未开启，应用无法工作",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = {
                                showWarning = false
                                currentScreen = Screen.PERMISSIONS
                            }) {
                                Text("去开启")
                            }
                        }
                    }
                }

                DashboardScreen(
                    onNavigateToSettings = { currentScreen = Screen.SETTINGS },
                    onNavigateToPermissions = { currentScreen = Screen.PERMISSIONS }
                )
            }
        }
        Screen.SETTINGS -> {
            SettingsScreen(onBack = { currentScreen = Screen.DASHBOARD })
        }
        Screen.PERMISSIONS -> {
            PermissionGuideScreen(onBack = { currentScreen = Screen.DASHBOARD })
        }
    }
}

private fun checkServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(
        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
    )
    return enabledServices.any {
        it.resolveInfo?.serviceInfo?.name?.contains("GameTimeService") == true
    }
}
```

- [ ] **Step 2: Build to verify compilation**

```bash
cd /f/phone && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

---

### Task 11: Update strings.xml

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Rewrite strings.xml**

```xml
<resources>
    <string name="app_name">GameTime</string>
    <string name="accessibility_service_description">用于检测多邻国学习状态和管理游戏应用使用时间。开启后，多邻国学习时间将自动转换为游戏时间。</string>
</resources>
```

- [ ] **Step 2: Build to verify resources**

```bash
cd /f/phone && ./gradlew :app:processDebugResources 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

---

### Task 12: Full Build Verification

- [ ] **Step 1: Run full debug build**

```bash
cd /f/phone && ./gradlew :app:assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

---

### Task 13: Pre-Code Prerequisite — Duolingo Accessibility Node Tree

**Note:** Before deploying, run this ADB command while using Duolingo to capture the accessibility tree:

```bash
adb shell uiautomator dump /sdcard/duolingo_tree.xml
adb pull /sdcard/duolingo_tree.xml /tmp/duolingo_tree.xml
```

Review the XML to verify which event types and node classNames appear during active lesson answering. Update `GameTimeService.handleDuolingoEvent()` if needed based on findings.

---

## Self-Review

**1. Spec coverage:**
- TimeBank (SharedPreferences CRUD) → Task 1
- ExchangeEngine (multiplication formula) → Task 2
- GameTimeService (AccessibilityService, state machine, Handler ticks) → Task 4
- AppUsageTracker (validation-only) → Task 5
- AndroidManifest (permissions, service declaration) → Task 6
- DashboardScreen → Task 7
- SettingsScreen (game list management) → Task 8
- PermissionGuideScreen → Task 9
- MainActivity (navigation, service check) → Task 10
- strings.xml → Task 11

**2. Placeholder scan:** No TBDs, TODOs, or vague instructions found. All steps contain complete code.

**3. Type consistency:**
- TimeBank API methods match what GameTimeService calls: `getGameBalance()`, `consumeGameSeconds()`, `recordSettlement()`, `getConsecutiveDays()`, `isWeekend()`
- ExchangeEngine API: `calculateEarnedSeconds(studySeconds, consecutiveDays, isWeekend)` — called from GameTimeService with matching types
- BlockedPackages: stored as `Set<String>`, accessed via `getBlockedPackages()` / `setBlockedPackages()`
