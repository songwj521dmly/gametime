package com.example.myapplication.data

import android.content.Context
import android.content.SharedPreferences
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object ActivityLog {
    private const val PREFS_NAME = "activity_log"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 500
    private const val ZONE = "+08:00"

    private val zoneId: ZoneId = ZoneId.of(ZONE)
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    data class Event(
        val timestamp: Long,
        val type: String,
        val packageName: String,
        val detail: String
    ) {
        fun formattedTime(): String {
            val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), zoneId)
            return zdt.format(formatter)
        }

        fun appName(): String = packageNameToApp(packageName)

        fun toJson(): String {
            val escapedDetail = detail.replace("\\", "\\\\").replace("\"", "\\\"")
            return "{\"t\":$timestamp,\"type\":\"$type\",\"pkg\":\"$packageName\",\"d\":\"$escapedDetail\"}"
        }

        companion object {
            fun fromJson(json: String): Event? {
                return try {
                    val t = json.substringAfter("\"t\":").substringBefore(",").toLong()
                    val type = json.substringAfter("\"type\":\"").substringBefore("\"")
                    val pkg = json.substringAfter("\"pkg\":\"").substringBefore("\"")
                    val d = json.substringAfter("\"d\":\"").substringBeforeLast("\"")
                        .replace("\\\"", "\"").replace("\\\\", "\\")
                    Event(t, type, pkg, d)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    fun packageNameToApp(pkg: String): String = when (pkg) {
        "" -> ""
        "com.duolingo" -> "多邻国"
        "com.tencent.tmgp.sgame" -> "王者荣耀"
        "com.tencent.tmgp.pubgmhd" -> "和平精英"
        "com.miHoYo.GenshinImpact" -> "原神"
        "com.HoYoverse.hkrpgoversea" -> "崩坏：星穹铁道"
        "com.hypergryph.arknights" -> "明日方舟"
        "com.tencent.jkchess" -> "金铲铲之战"
        "com.netease.party" -> "蛋仔派对"
        "com.netease.sky" -> "光遇"
        "com.tgc.sky" -> "光遇"
        "com.meta.box" -> "233乐园"
        else -> pkg.substringAfterLast(".")
    }

    fun record(type: String, packageName: String, detail: String) {
        val event = Event(System.currentTimeMillis(), type, packageName, detail)
        val events = getEvents().toMutableList()
        events.add(0, event)
        if (events.size > MAX_EVENTS) {
            events.subList(MAX_EVENTS, events.size).clear()
        }
        val json = "[" + events.joinToString(",") { it.toJson() } + "]"
        prefs.edit().putString(KEY_EVENTS, json).apply()
    }

    fun getEvents(): List<Event> {
        val json = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
        if (json == "[]") return emptyList()
        return try {
            val items = mutableListOf<Event>()
            var depth = 0
            var start = -1
            for (i in json.indices) {
                when (json[i]) {
                    '{' -> { depth++; if (depth == 1) start = i }
                    '}' -> {
                        depth--
                        if (depth == 0 && start >= 0) {
                            Event.fromJson(json.substring(start, i + 1))?.let { items.add(it) }
                            start = -1
                        }
                    }
                }
            }
            items
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getEventsForDate(dateStr: String): List<Event> {
        return getEvents().filter {
            val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.timestamp), zoneId)
            zdt.toLocalDate().toString() == dateStr
        }
    }

    fun getTodaySummary(): String {
        val today = java.time.LocalDate.now(zoneId)
        val todayStr = today.toString()
        val dayOfWeek = today.dayOfWeek
        val dayNames = mapOf(
            java.time.DayOfWeek.MONDAY to "周一",
            java.time.DayOfWeek.TUESDAY to "周二",
            java.time.DayOfWeek.WEDNESDAY to "周三",
            java.time.DayOfWeek.THURSDAY to "周四",
            java.time.DayOfWeek.FRIDAY to "周五",
            java.time.DayOfWeek.SATURDAY to "周六",
            java.time.DayOfWeek.SUNDAY to "周日"
        )
        val todayEvents = getEventsForDate(todayStr)

        val studySessions = todayEvents.filter { it.type == "STUDY_END" }
        val studySeconds = studySessions.sumOf {
            it.detail.substringBefore("秒").replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L
        }
        val gameOpenCount = todayEvents.count { it.type == "GAME_OPEN" }
        val gameCloseEvents = todayEvents.filter { it.type == "GAME_CLOSE" }
        val gameBlockCount = todayEvents.count { it.type == "GAME_BLOCK" }
        val gameConsumed = TimeBank.getGameConsumedSecondsToday()
        val balance = TimeBank.getGameBalance()
        val streak = TimeBank.getConsecutiveDays()
        val bonus = TimeBank.getCurrentStreakBonus()
        val dailyLimit = TimeBank.getDailyGameLimitSeconds()
        val remaining = TimeBank.getDailyGameRemainingSeconds()
        val isWeekend = TimeBank.isWeekend()
        val timeBankStudySeconds = TimeBank.getStudySecondsToday()

        fun fmt(sec: Long) = if (sec >= 3600) "${sec / 3600}h${(sec % 3600) / 60}m" else "${sec / 60}m${sec % 60}s"

        val line = "─".repeat(32)

        return buildString {
            appendLine("═══ GameTime 每日报告 ═══")
            appendLine("")
            appendLine("📅 ${todayStr} ${dayNames[dayOfWeek] ?: ""} (${if (isWeekend) "节假日" else "工作日"})")
            appendLine(line)
            appendLine("")
            appendLine("📖 学习")
            appendLine("   今日学习: ${fmt(timeBankStudySeconds)}")
            appendLine("   连续打卡: ${streak}天 (+${(bonus * 100).toInt()}%)")
            appendLine("   可用余额: ${fmt(balance)}")
            appendLine("")
            appendLine("🎮 游戏")
            appendLine("   已用时长: ${fmt(gameConsumed)}")
            appendLine("   剩余配额: ${fmt(remaining)} / ${fmt(dailyLimit)}")
            appendLine("   打开次数: ${gameOpenCount}")
            appendLine("   拦截次数: ${gameBlockCount}")
            appendLine("")
            appendLine(line)
            appendLine("")

            val detailEvents = todayEvents.filter {
                it.type in listOf("STUDY_END", "GAME_OPEN", "GAME_CLOSE", "GAME_BLOCK")
            }.take(30)

            if (detailEvents.isNotEmpty()) {
                appendLine("📋 活动明细")
                appendLine("")
                detailEvents.forEach { event ->
                    val icon = when (event.type) {
                        "STUDY_END" -> "📕"
                        "GAME_OPEN" -> "▶️"
                        "GAME_CLOSE" -> "⏹️"
                        "GAME_BLOCK" -> "🚫"
                        else -> "•"
                    }
                    val label = when (event.type) {
                        "STUDY_END" -> "结束学习"
                        "GAME_OPEN" -> "游戏启动"
                        "GAME_CLOSE" -> "游戏关闭"
                        "GAME_BLOCK" -> "拦截"
                        else -> event.type
                    }
                    appendLine("   ${event.formattedTime()}  $icon $label  ${event.detail}")
                }
                appendLine("")
            } else {
                appendLine("   今日暂无活动记录")
                appendLine("")
            }

            appendLine(line)
            appendLine("GameTime · 多邻国学习解锁游戏")
            appendLine("报告生成: ${java.time.LocalTime.now(zoneId).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}")
        }
    }

    fun getTodayHtmlReport(): String {
        val today = LocalDate.now(zoneId)
        val todayStr = today.toString()
        val dayOfWeek = today.dayOfWeek
        val dayNames = mapOf(
            DayOfWeek.MONDAY to "周一",
            DayOfWeek.TUESDAY to "周二",
            DayOfWeek.WEDNESDAY to "周三",
            DayOfWeek.THURSDAY to "周四",
            DayOfWeek.FRIDAY to "周五",
            DayOfWeek.SATURDAY to "周六",
            DayOfWeek.SUNDAY to "周日"
        )
        val todayEvents = getEventsForDate(todayStr)
        val hourlyStats = TimeBank.getHourlyStats(todayStr)
        val studySeconds = TimeBank.getStudySecondsToday()
        val gameConsumed = TimeBank.getGameConsumedSecondsToday()
        val balance = TimeBank.getGameBalance()
        val streak = TimeBank.getConsecutiveDays()
        val bonus = TimeBank.getCurrentStreakBonus()
        val isWeekend = TimeBank.isWeekend()
        val gameRemaining = TimeBank.getDailyGameRemainingSeconds()
        val gameLimit = TimeBank.getDailyGameLimitSeconds()

        fun fmt(sec: Long) = if (sec >= 3600) "${sec / 3600}h${(sec % 3600) / 60}m" else "${sec / 60}m${sec % 60}s"

        val maxMinutes = hourlyStats.maxOfOrNull {
            (it.studySeconds / 60 + it.gameSeconds / 60).toInt()
        }?.coerceAtLeast(1) ?: 1

        val chartBars = StringBuilder()
        hourlyStats.forEach { stat ->
            val studyPct = (stat.studySeconds.toFloat() / 60f / maxMinutes * 100).toInt().coerceAtMost(100)
            val gamePct = (stat.gameSeconds.toFloat() / 60f / maxMinutes * 100).toInt().coerceAtMost(100)
            chartBars.append("""
                <tr>
                    <td style="color:#8e8e93;font-size:11px;text-align:right;padding-right:6px;width:24px">${stat.hour}</td>
                    <td style="vertical-align:bottom;height:60px;padding:1px 0">
                        <div style="display:flex;align-items:flex-end;height:100%;gap:2px">
                            ${if (stat.studySeconds > 0) "<div style=\"width:14px;height:${studyPct}%;background:#34C759;border-radius:3px 3px 0 0;min-height:2px\" title=\"学习 ${fmt(stat.studySeconds)}\"></div>" else ""}
                            ${if (stat.gameSeconds > 0) "<div style=\"width:14px;height:${gamePct}%;background:#FF6B6B;border-radius:3px 3px 0 0;min-height:2px\" title=\"游戏 ${fmt(stat.gameSeconds)}\"></div>" else ""}
                        </div>
                    </td>
                </tr>
            """.trimIndent())
        }

        val detailEvents = todayEvents.filter {
            it.type in listOf("STUDY_END", "GAME_OPEN", "GAME_CLOSE", "GAME_BLOCK")
        }.take(10)

        val eventRows = if (detailEvents.isEmpty()) {
            "<tr><td colspan=\"2\" style=\"color:#8e8e93;text-align:center;padding:20px;font-size:13px\">今日暂无活动记录</td></tr>"
        } else {
            detailEvents.joinToString("") { event ->
                val icon = when (event.type) {
                    "STUDY_END" -> "📕"
                    "GAME_OPEN" -> "🎮"
                    "GAME_CLOSE" -> "🚪"
                    "GAME_BLOCK" -> "🚫"
                    else -> "•"
                }
                val label = when (event.type) {
                    "STUDY_END" -> "结束学习"
                    "GAME_OPEN" -> "游戏启动"
                    "GAME_CLOSE" -> "游戏关闭"
                    "GAME_BLOCK" -> "拦截"
                    else -> event.type
                }
                "<tr><td style=\"padding:6px 8px;border-bottom:1px solid #f0f0f0;font-size:13px\">$icon $label · ${event.appName()}</td><td style=\"padding:6px 8px;border-bottom:1px solid #f0f0f0;font-size:11px;color:#8e8e93;text-align:right\">${event.formattedTime()}</td></tr>"
            }
        }

        return """
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width"></head>
<body style="margin:0;padding:0;background:#f2f2f7;font-family:-apple-system,BlinkMacSystemFont,sans-serif">
<table width="100%" cellpadding="0" cellspacing="0" style="background:#f2f2f7;padding:20px 0">
<tr><td align="center">
<table width="100%" cellpadding="0" cellspacing="0" style="max-width:480px;background:#fff;border-radius:16px;overflow:hidden">

<!-- Header -->
<tr><td style="padding:24px 20px 16px;text-align:center">
    <div style="font-size:22px;font-weight:700;color:#1c1c1e">📊 GameTime</div>
    <div style="font-size:13px;color:#8e8e93;margin-top:4px">${todayStr} ${dayNames[dayOfWeek] ?: ""} · ${if (isWeekend) "节假日" else "工作日"}</div>
</td></tr>

<!-- Stats Row -->
<tr><td style="padding:8px 20px">
    <table width="100%" cellpadding="0" cellspacing="0">
        <tr>
            <td width="50%" style="padding:12px;background:#F0FFF4;border-radius:12px;vertical-align:top">
                <div style="font-size:12px;color:#34C759;font-weight:600">📖 今日学习</div>
                <div style="font-size:28px;font-weight:700;color:#1c1c1e;margin-top:2px">${fmt(studySeconds)}</div>
                <div style="font-size:11px;color:#8e8e93;margin-top:2px">🔥 ${streak}天 +${(bonus * 100).toInt()}%</div>
            </td>
            <td width="8px"></td>
            <td width="50%" style="padding:12px;background:#FFF0F0;border-radius:12px;vertical-align:top">
                <div style="font-size:12px;color:#FF6B6B;font-weight:600">🎮 今日游戏</div>
                <div style="font-size:28px;font-weight:700;color:#1c1c1e;margin-top:2px">${fmt(gameConsumed)}</div>
                <div style="font-size:11px;color:#8e8e93;margin-top:2px">剩余 ${fmt(gameRemaining)} / ${fmt(gameLimit)}</div>
            </td>
        </tr>
    </table>
</td></tr>

<!-- Hourly Chart -->
<tr><td style="padding:20px 16px">
    <div style="font-size:14px;font-weight:600;color:#1c1c1e;margin-bottom:10px">📈 每小时分布 (0h-23h)</div>
    <div style="display:flex;align-items:center;gap:6px;margin-bottom:12px;font-size:10px;color:#8e8e93">
        <span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#34C759"></span>学习
        <span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#FF6B6B"></span>游戏
        <span style="margin-left:auto">每格 = ${maxMinutes}分钟</span>
    </div>
    <table width="100%" cellpadding="0" cellspacing="0" style="border-bottom:1px solid #e8e8ed">
        $chartBars
    </table>
</td></tr>

<!-- Recent Activity -->
<tr><td style="padding:16px 20px">
    <div style="font-size:14px;font-weight:600;color:#1c1c1e;margin-bottom:8px">📋 最近活动</div>
    <table width="100%" cellpadding="0" cellspacing="0">
        $eventRows
    </table>
</td></tr>

<!-- Footer -->
<tr><td style="padding:16px 20px 24px;text-align:center">
    <div style="font-size:11px;color:#c7c7cc">
        ⏰ 可用余额 ${fmt(balance)} · GameTime
    </div>
</td></tr>

</table>
</td></tr>
</table>
</body>
</html>
        """.trimIndent()
    }
}
