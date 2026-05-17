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
    private const val KEY_WORKDAY_LIMIT_MINUTES = "workday_limit_minutes"
    private const val KEY_HOLIDAY_LIMIT_MINUTES = "holiday_limit_minutes"
    private const val KEY_PIN = "pin_code"
    private const val KEY_DAILY_GAME_CONSUMED = "daily_game_consumed"
    private const val KEY_HOURLY_STUDY = "hourly_study"      // JSON: {"2026-05-17": {"8": 15, "9": 30}, ...}
    private const val KEY_HOURLY_GAME = "hourly_game"        // JSON: {"2026-05-17": {"14": 5, "15": 10}, ...}
    private const val KEY_LAST_SETTLEMENT_MS = "last_settlement_ms"
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

    fun getStudyMinutesToday(): Long {
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

    // --- Hourly Data ---

    fun recordHourlyStudy(minutes: Long) {
        val hour = java.time.LocalTime.now(zoneId).hour.toString()
        val map = parseHourlyMap(KEY_HOURLY_STUDY).toMutableMap()
        val date = todayKey()
        val hourMap = (map[date] ?: emptyMap()).toMutableMap()
        hourMap[hour] = (hourMap[hour] ?: 0L) + minutes
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
        val studyMinutes: Long,
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

    data class DayStats(
        val date: String,
        val studyMinutes: Long,
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
                studyMinutes = studyMap[date] ?: 0L,
                gameSeconds = gameMap[date] ?: 0L
            )
        }
    }
}
