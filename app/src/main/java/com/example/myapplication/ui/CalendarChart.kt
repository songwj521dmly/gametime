package com.example.myapplication.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.TimeBank
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val CHART_BAR_WIDTH = 20.dp
private val CHART_BAR_GAP = 10.dp

@Composable
fun CalendarChart(modifier: Modifier = Modifier) {
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(30000)
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick

    val today = remember(tick) { LocalDate.now(TimeBank.zoneId) }
    var selectedDate by remember { mutableStateOf(today) }

    val availableDates = remember(tick) { TimeBank.getAvailableDates(30) }
    val hourlyStats = remember(selectedDate, tick) {
        TimeBank.getHourlyStats(selectedDate.toString())
    }

    // Fixed Y-axis: max 1h, 10-min steps
    val ySteps = 6  // 6 steps of 10 minutes = 60 minutes
    val maxSeconds = 3600f  // 1 hour max

    val studyColor = Color(0xFF34C759)
    val gameColor = Color(0xFFFF6B6B)

    val dateStr = if (selectedDate == today) "今天" else selectedDate.format(DateTimeFormatter.ofPattern("MM/dd"))
    val canGoNext = selectedDate.isBefore(today)
    val canGoPrev = availableDates.any { it < selectedDate.toString() }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Title + Legend + Date nav
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("学习 & 游戏日历", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendItem(color = studyColor, label = "学习")
                    LegendItem(color = gameColor, label = "游戏")
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Date navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { selectedDate = selectedDate.minusDays(1) },
                    enabled = canGoPrev
                ) { Text("◀", fontSize = 14.sp) }

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (selectedDate == today)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        dateStr,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }

                TextButton(
                    onClick = { selectedDate = selectedDate.plusDays(1) },
                    enabled = canGoNext
                ) { Text("▶", fontSize = 14.sp) }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Chart area: fixed Y-axis + scrollable chart
            val chartHeightDp = 200.dp
            val topPaddingPx = 4f
            val bottomPaddingPx = 34f
            val yAxisWidthDp = 52.dp
            val barColumnWidth = CHART_BAR_WIDTH + CHART_BAR_GAP
            val chartContentWidth = barColumnWidth * 24

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Fixed Y-axis
                Canvas(
                    modifier = Modifier
                        .width(yAxisWidthDp)
                        .height(chartHeightDp)
                ) {
                    val chartH = size.height - bottomPaddingPx - topPaddingPx
                    for (i in 0..ySteps) {
                        val y = topPaddingPx + chartH * (1f - i.toFloat() / ySteps)
                        val label = when (i) {
                            6 -> "1h"
                            else -> "${i * 10}m"
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            size.width - 10f, y + 4f,
                            android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#8E8E93")
                                textSize = 28f
                                isAntiAlias = true
                                textAlign = android.graphics.Paint.Align.RIGHT
                            }
                        )
                    }
                }

                // Scrollable chart
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                ) {
                    Canvas(
                        modifier = Modifier
                            .width(chartContentWidth)
                            .height(chartHeightDp)
                    ) {
                        val canvasWidth = size.width
                        val canvasHeight = size.height
                        val chartHeight = canvasHeight - bottomPaddingPx - topPaddingPx

                        val barAreaPx = canvasWidth / 24f
                        val barW = barAreaPx * 0.28f
                        val gap = barAreaPx * 0.08f

                        // Grid lines
                        for (i in 0..ySteps) {
                            val y = topPaddingPx + chartHeight * (1f - i.toFloat() / ySteps)
                            drawLine(
                                color = Color(0xFFE8E8ED),
                                start = Offset(0f, y),
                                end = Offset(canvasWidth, y),
                                strokeWidth = 1f
                            )
                        }

                        // Bars — scaled to 1h max
                        hourlyStats.forEach { stat ->
                            val centerX = barAreaPx * stat.hour + barAreaPx / 2f
                            val studyH = (stat.studySeconds / maxSeconds * chartHeight).coerceAtLeast(0f)
                            val gameH = (stat.gameSeconds / maxSeconds * chartHeight).coerceAtLeast(0f)
                            val baseY = topPaddingPx + chartHeight

                            if (studyH > 1f) {
                                drawRoundRect(
                                    color = studyColor,
                                    topLeft = Offset(centerX - barW - gap, baseY - studyH),
                                    size = Size(barW, studyH),
                                    cornerRadius = CornerRadius(6f, 6f)
                                )
                            }
                            if (gameH > 1f) {
                                drawRoundRect(
                                    color = gameColor,
                                    topLeft = Offset(centerX + gap, baseY - gameH),
                                    size = Size(barW, gameH),
                                    cornerRadius = CornerRadius(6f, 6f)
                                )
                            }
                        }

                        // X-axis labels
                        for (h in 0..23) {
                            val centerX = barAreaPx * h + barAreaPx / 2f
                            drawContext.canvas.nativeCanvas.drawText(
                                "${h}",
                                centerX - 10f,
                                canvasHeight - 2f,
                                android.graphics.Paint().apply {
                                    color = android.graphics.Color.parseColor("#8E8E93")
                                    textSize = 30f
                                    isAntiAlias = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
