package com.example.myapplication.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    val maxMinutes = hourlyStats.maxOfOrNull {
        (it.studyMinutes + it.gameSeconds / 60).toInt()
    }?.coerceAtLeast(5) ?: 5

    // Round up to nearest 5
    val yMax = ((maxMinutes + 4) / 5) * 5

    val studyColor = Color(0xFF4CAF50)
    val gameColor = Color(0xFFFF7043)

    val dateStr = if (selectedDate == today) "今天" else selectedDate.format(DateTimeFormatter.ofPattern("MM/dd"))
    val canGoNext = selectedDate.isBefore(today)
    val canGoPrev = availableDates.any { it < selectedDate.toString() }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Title + Legend + Date nav
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("学习 & 游戏日历", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LegendItem(color = studyColor, label = "学习")
                    LegendItem(color = gameColor, label = "游戏")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Date navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { selectedDate = selectedDate.minusDays(1) },
                    enabled = canGoPrev
                ) { Text("◀") }

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (selectedDate == today)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        dateStr,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                TextButton(
                    onClick = { selectedDate = selectedDate.plusDays(1) },
                    enabled = canGoNext
                ) { Text("▶") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Chart
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val bottomPadding = 34f
                val topPadding = 6f
                val chartHeight = canvasHeight - bottomPadding - topPadding
                val barAreaWidth = canvasWidth / 24
                val barWidth = (barAreaWidth * 0.38f).coerceAtMost(14f)
                val gap = barWidth * 0.3f

                // Y-axis grid lines
                val gridSteps = 4
                for (i in 0..gridSteps) {
                    val y = topPadding + chartHeight * (1f - i.toFloat() / gridSteps)
                    val value = yMax * i / gridSteps
                    drawLine(
                        color = Color.LightGray.copy(alpha = 0.4f),
                        start = Offset(0f, y),
                        end = Offset(canvasWidth, y),
                        strokeWidth = 1f
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        "${value}m",
                        2f, y + 3f,
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.GRAY
                            textSize = 20f
                            isAntiAlias = true
                        }
                    )
                }

                // Bars for each hour
                hourlyStats.forEach { stat ->
                    val centerX = barAreaWidth * stat.hour + barAreaWidth / 2
                    val studyHeight = (stat.studyMinutes.toFloat() / yMax * chartHeight).coerceAtLeast(0f)
                    val gameHeight = (stat.gameSeconds / 60f / yMax * chartHeight).coerceAtLeast(0f)
                    val baseY = topPadding + chartHeight

                    // Study bar (left)
                    if (studyHeight > 0.5f) {
                        drawRect(
                            color = studyColor,
                            topLeft = Offset(centerX - barWidth - gap / 2, baseY - studyHeight),
                            size = Size(barWidth, studyHeight)
                        )
                    }

                    // Game bar (right)
                    if (gameHeight > 0.5f) {
                        drawRect(
                            color = gameColor,
                            topLeft = Offset(centerX + gap / 2, baseY - gameHeight),
                            size = Size(barWidth, gameHeight)
                        )
                    }
                }

                // X-axis hour labels (every 3 hours)
                for (h in 0..23 step 3) {
                    val centerX = barAreaWidth * h + barAreaWidth / 2
                    drawContext.canvas.nativeCanvas.drawText(
                        "${h}h",
                        centerX - 12f,
                        canvasHeight - 2f,
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.GRAY
                            textSize = 18f
                            isAntiAlias = true
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
