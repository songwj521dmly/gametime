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
        val map = parseDailyMap()
        return map[todayKey()] ?: 0L
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
