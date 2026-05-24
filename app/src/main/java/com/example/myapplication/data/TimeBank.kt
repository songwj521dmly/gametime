package com.example.myapplication.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

object TimeBank {
    private const val PREFS_NAME = "time_bank"
    private const val KEY_GAME_BALANCE_SECONDS = "game_balance_seconds"
    private const val KEY_DAILY_STUDY = "daily_study"        // JSON: {"2026-05-17": 600, ...}
    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    private const val KEY_WORKDAY_LIMIT_MINUTES = "workday_limit_minutes"
    private const val KEY_HOLIDAY_LIMIT_MINUTES = "holiday_limit_minutes"
    private const val KEY_PIN = "pin_code"
    private const val KEY_DAILY_GAME_CONSUMED = "daily_game_consumed"
    private const val KEY_HOURLY_STUDY = "hourly_study"      // JSON: {"2026-05-17": {"8": 15, "9": 30}, ...}
    private const val KEY_HOURLY_GAME = "hourly_game"        // JSON: {"2026-05-17": {"14": 5, "15": 10}, ...}
    private const val KEY_LAST_SETTLEMENT_MS = "last_settlement_ms"
    private const val KEY_LAST_HEARTBEAT_MS = "last_heartbeat_ms"
    private const val KEY_LAST_HEARTBEAT_ELAPSED = "last_heartbeat_elapsed"
    private const val KEY_LAST_KILL_TIME_MS = "last_kill_time_ms"
    private const val KEY_KILL_COUNT = "kill_count"
    private const val KEY_KILL_WEEK_START = "kill_week_start"
    private const val KEY_LAST_PENALTY_SECONDS = "last_penalty_seconds"
    private const val KEY_LAST_DEAD_MINUTES = "last_dead_minutes"
    private const val KEY_LAST_KILL_COUNT_FOR_DIALOG = "last_kill_count_dialog"
    private const val HEARTBEAT_GAP_KILL_MS = 90_000L  // 3x 30s heartbeat interval = killed
    private const val ZONE = "+08:00"

    val zoneId: ZoneId = ZoneId.of(ZONE)

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
        val consumed = current - newValue
        prefs.edit()
            .putLong(KEY_GAME_BALANCE_SECONDS, newValue)
            .apply()
        if (consumed > 0) {
            recordDailyGameConsumed(consumed)
        }
    }

    // --- Daily Study Minutes ---

    fun getStudySecondsToday(): Long {
        val map = parseDailyMap()
        return map[todayKey()] ?: 0L
    }

    // --- Daily Game Consumed ---

    fun getGameConsumedSecondsToday(): Long {
        val map = parseDailyGameConsumedMap()
        return map[todayKey()] ?: 0L
    }

    private fun recordDailyGameConsumed(seconds: Long) {
        val map = parseDailyGameConsumedMap().toMutableMap()
        val key = todayKey()
        map[key] = (map[key] ?: 0L) + seconds
        val cutoff = LocalDate.now(zoneId).minusDays(60).toString()
        val trimmed = map.filterKeys { it >= cutoff }
        prefs.edit()
            .putString(KEY_DAILY_GAME_CONSUMED, toDailyJson(trimmed))
            .apply()
    }

    fun recordSettlement(studySeconds: Long, earnedGameSeconds: Long) {
        val map = parseDailyMap().toMutableMap()
        val key = todayKey()
        map[key] = (map[key] ?: 0L) + studySeconds
        // Keep only last 60 days
        val cutoff = LocalDate.now(zoneId).minusDays(60).toString()
        val trimmed = map.filterKeys { it >= cutoff }
        prefs.edit()
            .putString(KEY_DAILY_STUDY, toDailyJson(trimmed))
            .putLong(KEY_GAME_BALANCE_SECONDS, getGameBalance() + earnedGameSeconds)
            .putLong(KEY_LAST_SETTLEMENT_MS, System.currentTimeMillis())
            .apply()
    }

    // --- Hourly Data ---

    fun recordHourlyStudy(seconds: Long) {
        val hour = java.time.LocalTime.now(zoneId).hour.toString()
        val map = parseHourlyMap(KEY_HOURLY_STUDY).toMutableMap()
        val date = todayKey()
        val hourMap = (map[date] ?: emptyMap()).toMutableMap()
        hourMap[hour] = (hourMap[hour] ?: 0L) + seconds
        map[date] = hourMap
        trimAndSaveHourly(KEY_HOURLY_STUDY, map)
    }

    fun recordHourlyGame(seconds: Long) {
        val hour = java.time.LocalTime.now(zoneId).hour.toString()
        val map = parseHourlyMap(KEY_HOURLY_GAME).toMutableMap()
        val date = todayKey()
        val hourMap = (map[date] ?: emptyMap()).toMutableMap()
        hourMap[hour] = (hourMap[hour] ?: 0L) + seconds
        map[date] = hourMap
        trimAndSaveHourly(KEY_HOURLY_GAME, map)
    }

    data class HourlyStats(
        val hour: Int,
        val studySeconds: Long,
        val gameSeconds: Long
    )

    fun getHourlyStats(date: String): List<HourlyStats> {
        val studyMap = parseHourlyMap(KEY_HOURLY_STUDY)[date] ?: emptyMap()
        val gameMap = parseHourlyMap(KEY_HOURLY_GAME)[date] ?: emptyMap()
        return (0..23).map { hour ->
            val h = hour.toString()
            HourlyStats(hour, studyMap[h] ?: 0L, gameMap[h] ?: 0L)
        }
    }

    fun getAvailableDates(days: Int = 14): List<String> {
        val studyMap = parseHourlyMap(KEY_HOURLY_STUDY)
        val gameMap = parseHourlyMap(KEY_HOURLY_GAME)
        val today = LocalDate.now(zoneId)
        return (days - 1 downTo 0).map { offset ->
            today.minusDays(offset.toLong()).toString()
        }.filter { date ->
            (studyMap[date]?.isNotEmpty() == true) || (gameMap[date]?.isNotEmpty() == true) || date == today.toString()
        }
    }

    private fun parseHourlyMap(key: String): Map<String, Map<String, Long>> {
        val json = prefs.getString(key, "{}") ?: "{}"
        return try {
            val outer = json.trim().removeSurrounding("{", "}")
            if (outer.isEmpty()) emptyMap()
            else {
                // Parse: "2026-05-17": {"8": 15, "9": 30}
                val result = mutableMapOf<String, Map<String, Long>>()
                var i = 0
                val s = outer
                while (i < s.length) {
                    // Skip whitespace
                    while (i < s.length && s[i].isWhitespace()) i++
                    if (i >= s.length) break
                    // Read date key
                    if (s[i] != '"') break
                    val dateEnd = s.indexOf('"', i + 1)
                    if (dateEnd < 0) break
                    val dateKey = s.substring(i + 1, dateEnd)
                    i = dateEnd + 1
                    // Skip ": {
                    while (i < s.length && (s[i].isWhitespace() || s[i] == ':')) i++
                    if (i >= s.length || s[i] != '{') break
                    // Find matching }
                    val innerStart = i
                    var depth = 0
                    while (i < s.length) {
                        if (s[i] == '{') depth++
                        else if (s[i] == '}') {
                            depth--
                            if (depth == 0) { i++; break }
                        }
                        i++
                    }
                    val innerJson = s.substring(innerStart, i)
                    val innerMap = parseSimpleJsonMap(innerJson)
                    result[dateKey] = innerMap
                    // Skip comma
                    while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
                }
                result
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun toHourlyJson(map: Map<String, Map<String, Long>>): String {
        val entries = map.entries.joinToString(",") { (date, hours) ->
            val inner = hours.entries.joinToString(",") { (h, v) -> "\"$h\":$v" }
            "\"$date\":{$inner}"
        }
        return "{$entries}"
    }

    private fun trimAndSaveHourly(key: String, map: Map<String, Map<String, Long>>) {
        val cutoff = LocalDate.now(zoneId).minusDays(60).toString()
        val trimmed = map.filterKeys { it >= cutoff }
        prefs.edit().putString(key, toHourlyJson(trimmed)).apply()
    }

    // --- Streak ---

    fun getConsecutiveDays(): Int {
        val map = parseDailyMap()
        val today = LocalDate.now(zoneId)
        var streak = 0
        var cursor = if (map.containsKey(today.toString())) today else today.minusDays(1)
        while (true) {
            val seconds = map[cursor.toString()] ?: 0L
            if (seconds >= 600) {
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
        return map.filter { it.value >= 600 }.keys.maxOrNull()
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
            "com.netease.party",
            "com.netease.sky",
            "com.tgc.sky",
            "com.meta.box"
        )
        val stored = prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()) ?: emptySet()
        return if (stored.isEmpty()) defaults else stored
    }

    fun setBlockedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, packages).apply()
    }

    // --- PIN ---

    fun getPin(): String = prefs.getString(KEY_PIN, "1234") ?: "1234"

    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN, pin).apply()
    }

    fun verifyPin(pin: String): Boolean = pin == getPin()

    // --- Persistence helpers ---

    private fun parseDailyMap(): Map<String, Long> {
        val json = prefs.getString(KEY_DAILY_STUDY, "{}") ?: "{}"
        return parseSimpleJsonMap(json)
    }

    private fun parseDailyGameConsumedMap(): Map<String, Long> {
        val json = prefs.getString(KEY_DAILY_GAME_CONSUMED, "{}") ?: "{}"
        return parseSimpleJsonMap(json)
    }

    private fun parseSimpleJsonMap(json: String): Map<String, Long> {
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

    fun getCurrentStreakBonus(): Double = minOf(getConsecutiveDays() * 0.05, 0.25)

    fun isWeekend(): Boolean {
        val dayOfWeek = LocalDate.now(zoneId).dayOfWeek
        return dayOfWeek == java.time.DayOfWeek.SATURDAY || dayOfWeek == java.time.DayOfWeek.SUNDAY
    }

    // --- Game Time Restrictions ---

    fun getWorkdayLimitMinutes(): Long {
        return prefs.getLong(KEY_WORKDAY_LIMIT_MINUTES, 60)
    }

    fun setWorkdayLimitMinutes(minutes: Long) {
        prefs.edit().putLong(KEY_WORKDAY_LIMIT_MINUTES, minutes).apply()
    }

    fun getHolidayLimitMinutes(): Long {
        return prefs.getLong(KEY_HOLIDAY_LIMIT_MINUTES, 120)
    }

    fun setHolidayLimitMinutes(minutes: Long) {
        prefs.edit().putLong(KEY_HOLIDAY_LIMIT_MINUTES, minutes).apply()
    }

    fun getDailyGameLimitSeconds(): Long {
        val minutes = if (isWeekend()) getHolidayLimitMinutes() else getWorkdayLimitMinutes()
        return minutes * 60
    }

    fun getDailyGameRemainingSeconds(): Long {
        val limit = getDailyGameLimitSeconds()
        val consumed = getGameConsumedSecondsToday()
        return maxOf(0L, limit - consumed)
    }

    fun isWithinGameTimeWindow(): Boolean {
        val now = java.time.LocalTime.now(zoneId)
        val start = java.time.LocalTime.of(8, 0)
        val end = java.time.LocalTime.of(22, 30)
        return !now.isBefore(start) && now.isBefore(end)
    }

    fun getGameWindowDescription(): String {
        return if (isWithinGameTimeWindow()) {
            "游戏时间 8:00-22:30"
        } else {
            "当前不在游戏时间段 (8:00-22:30)"
        }
    }

    // --- Chart Data ---

    // --- Heartbeat, Kill Detection & Penalty ---

    enum class KillType {
        NOT_KILLED, REBOOTED, FIRST_WARNING, PENALTY
    }

    data class KillResult(
        val type: KillType,
        val killTimeMs: Long,
        val killCount: Int,
        val penaltySeconds: Long,
        val deadMinutes: Long
    )

    fun writeHeartbeat() {
        prefs.edit()
            .putLong(KEY_LAST_HEARTBEAT_MS, System.currentTimeMillis())
            .putLong(KEY_LAST_HEARTBEAT_ELAPSED, SystemClock.elapsedRealtime())
            .apply()
    }

    /**
     * Detect if the app was force-killed. Handles reboot detection, weekly kill count,
     * and penalty calculation. Returns detailed result for UI/logging.
     */
    fun detectKill(): KillResult {
        val lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT_MS, 0L)
        if (lastHeartbeat == 0L) return KillResult(KillType.NOT_KILLED, 0L, 0, 0L, 0L)

        val gap = System.currentTimeMillis() - lastHeartbeat
        if (gap <= HEARTBEAT_GAP_KILL_MS) return KillResult(KillType.NOT_KILLED, 0L, 0, 0L, 0L)

        // Check if device rebooted (battery died or manual shutdown)
        val lastElapsed = prefs.getLong(KEY_LAST_HEARTBEAT_ELAPSED, 0L)
        val currentElapsed = SystemClock.elapsedRealtime()
        val elapsedGap = currentElapsed - lastElapsed
        // If elapsed time decreased or went backwards, device rebooted
        if (elapsedGap < 0 || elapsedGap < gap * 0.5) {
            // Device rebooted - reset heartbeat, no penalty
            prefs.edit()
                .putLong(KEY_LAST_HEARTBEAT_MS, 0L)
                .putLong(KEY_LAST_HEARTBEAT_ELAPSED, 0L)
                .apply()
            return KillResult(KillType.REBOOTED, lastHeartbeat, 0, 0L,
                (System.currentTimeMillis() - lastHeartbeat) / 60_000L)
        }

        // Confirmed force-kill (system kept running but app was killed)
        val killTime = lastHeartbeat
        val deadMinutes = gap / 60_000L

        // Weekly kill count
        val today = LocalDate.now(zoneId)
        val weekStart = today.with(java.time.DayOfWeek.MONDAY)
        val weekStartEpoch = weekStart.atStartOfDay(zoneId).toEpochSecond() * 1000L
        val storedWeekStart = prefs.getLong(KEY_KILL_WEEK_START, 0L)

        var killCount = if (weekStartEpoch == storedWeekStart) {
            prefs.getInt(KEY_KILL_COUNT, 0)
        } else {
            0  // New week, reset count
        }
        killCount++

        val dailyLimit = getDailyGameLimitSeconds()

        val type: KillType
        val penaltySeconds: Long

        if (killCount == 1) {
            // First kill this week: warning only
            type = KillType.FIRST_WARNING
            penaltySeconds = 0L
        } else {
            // Second+ kill this week: penalty based on dead time, capped at daily limit
            type = KillType.PENALTY
            penaltySeconds = minOf(deadMinutes * 60L, dailyLimit)
            // Apply penalty (allow negative balance)
            applyKillPenalty(penaltySeconds)
        }

        // Save state
        prefs.edit()
            .putLong(KEY_LAST_KILL_TIME_MS, killTime)
            .putLong(KEY_LAST_HEARTBEAT_MS, 0L)       // Reset heartbeat
            .putLong(KEY_LAST_HEARTBEAT_ELAPSED, 0L)
            .putInt(KEY_KILL_COUNT, killCount)
            .putLong(KEY_KILL_WEEK_START, weekStartEpoch)
            .putLong(KEY_LAST_PENALTY_SECONDS, penaltySeconds)
            .putLong(KEY_LAST_DEAD_MINUTES, deadMinutes)
            .putInt(KEY_LAST_KILL_COUNT_FOR_DIALOG, killCount)
            .apply()

        return KillResult(type, killTime, killCount, penaltySeconds, deadMinutes)
    }

    private fun applyKillPenalty(seconds: Long) {
        val current = getGameBalance()
        // Allow negative balance
        prefs.edit().putLong(KEY_GAME_BALANCE_SECONDS, current - seconds).apply()
        // Also record as daily game consumed
        if (seconds > 0) {
            recordDailyGameConsumed(seconds)
        }
    }

    fun getLastKillTimeMs(): Long = prefs.getLong(KEY_LAST_KILL_TIME_MS, 0L)

    fun getLastPenaltySeconds(): Long = prefs.getLong(KEY_LAST_PENALTY_SECONDS, 0L)

    fun getLastDeadMinutes(): Long = prefs.getLong(KEY_LAST_DEAD_MINUTES, 0L)

    fun getLastKillCountForDialog(): Int = prefs.getInt(KEY_LAST_KILL_COUNT_FOR_DIALOG, 0)

    fun clearKillFlag() {
        prefs.edit()
            .putLong(KEY_LAST_KILL_TIME_MS, 0L)
            .putLong(KEY_LAST_PENALTY_SECONDS, 0L)
            .putLong(KEY_LAST_DEAD_MINUTES, 0L)
            .putInt(KEY_LAST_KILL_COUNT_FOR_DIALOG, 0)
            .apply()
    }

    fun formatKillTime(killTimeMs: Long): String {
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(killTimeMs), zoneId)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH时mm分ss秒")
        return zdt.format(fmt)
    }

    data class DayStats(
        val date: String,
        val studySeconds: Long,
        val gameSeconds: Long
    )

    fun getDayStats(days: Int = 14): List<DayStats> {
        val today = LocalDate.now(zoneId)
        val studyMap = parseDailyMap()
        val gameMap = parseDailyGameConsumedMap()
        return (days - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong()).toString()
            DayStats(
                date = date,
                studySeconds = studyMap[date] ?: 0L,
                gameSeconds = gameMap[date] ?: 0L
            )
        }
    }
}
