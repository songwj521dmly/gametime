package com.example.myapplication.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.TimeBank

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    @Suppress("UNUSED_EXPRESSION")
    tick

    val studySeconds = TimeBank.getStudySecondsToday()
    val gameBalanceSeconds = TimeBank.getGameBalance()
    val gameConsumedSeconds = TimeBank.getGameConsumedSecondsToday()
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
                    TextButton(onClick = onNavigateToLogs) {
                        Text("日志")
                    }
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Study + Streak card
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
                        text = "${formatSeconds(studySeconds)}",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val baseRate = if (isWeekend) "节假日 ×2.0" else "日常 ×1.0"
                        Text(
                            "🔥 ${streakDays}天 · +${(streakBonus * 100).toInt()}%",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "  |  ",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f)
                        )
                        Text(
                            baseRate,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isWeekend)
                                Color(0xFFFF6D00)
                            else
                                MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    StreakMilestones(
                        currentStreak = streakDays,
                        modifier = Modifier.fillMaxWidth()
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
                    if (gameConsumedSeconds > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "今日已用 ${formatSeconds(gameConsumedSeconds)}",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            val gameLimit = TimeBank.getDailyGameLimitSeconds()
            val gameRemaining = TimeBank.getDailyGameRemainingSeconds()
            val inWindow = TimeBank.isWithinGameTimeWindow()

            // Restrictions card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (inWindow && gameRemaining > 0)
                        MaterialTheme.colorScheme.surface
                    else
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (isWeekend) "节假日模式 (2h/天)" else "工作日模式 (1h/天)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (inWindow) "8:00-22:30 ✓" else "⛔ 非游戏时段",
                            fontSize = 13.sp,
                            color = if (inWindow) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        "今日游戏剩余 ${formatSeconds(gameRemaining)} / ${formatSeconds(gameLimit)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Calendar chart
            CalendarChart()

            // Quick tip
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Text(
                    text = when {
                        studySeconds < 600 -> "今天学满10分钟打卡，解锁游戏时间！"
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
private fun StreakMilestones(currentStreak: Int, modifier: Modifier = Modifier) {
    val milestones = listOf(
        1 to "+5%",
        2 to "+10%",
        3 to "+15%",
        4 to "+20%",
        5 to "+25%封顶"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        milestones.forEach { (day, bonus) ->
            val achieved = currentStreak >= day
            val isCurrent = currentStreak + 1 == day
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(56.dp)
            ) {
                // Circle indicator
                Surface(
                    shape = CircleShape,
                    color = if (achieved) Color.White
                            else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(2.dp, if (achieved || isCurrent) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (achieved) {
                            Text("🥢", fontSize = 18.sp)
                        } else if (isCurrent) {
                            Text("💩", fontSize = 18.sp)
                        } else {
                            Text("💩", fontSize = 18.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    bonus,
                    fontSize = 10.sp,
                    fontWeight = if (achieved) FontWeight.Bold else FontWeight.Normal,
                    color = if (achieved)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.outline
                )
            }
        }
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
