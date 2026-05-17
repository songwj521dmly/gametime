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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToPermissions: () -> Unit
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
                    InfoRow("周末状态", if (isWeekend) "双倍中" else "平日")
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
