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
