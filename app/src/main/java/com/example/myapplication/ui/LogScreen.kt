package com.example.myapplication.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.ActivityLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(2000)
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick

    val events = remember(tick) { ActivityLog.getEvents() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("活动日志") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        }
    ) { padding ->
        if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text("暂无活动记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(events) { event ->
                    val color = when (event.type) {
                        "STUDY" -> MaterialTheme.colorScheme.primary
                        "STUDY_END" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        "GAME_OPEN" -> MaterialTheme.colorScheme.error
                        "GAME_CLOSE" -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        "GAME_BLOCK" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val icon = when (event.type) {
                        "STUDY" -> "📖"
                        "STUDY_END" -> "📕"
                        "GAME_OPEN" -> "🎮"
                        "GAME_CLOSE" -> "🚪"
                        "GAME_BLOCK" -> "🚫"
                        else -> "📌"
                    }
                    val label = when (event.type) {
                        "STUDY" -> "开始学习"
                        "STUDY_END" -> "结束学习"
                        "GAME_OPEN" -> "游戏启动"
                        "GAME_CLOSE" -> "游戏关闭"
                        "GAME_BLOCK" -> "拦截游戏"
                        else -> event.type
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = color.copy(alpha = 0.08f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(icon, fontSize = 20.sp)
                            Column(modifier = Modifier.weight(1f)) {
                                val name = event.appName()
                                val title = if (name.isNotEmpty()) "$label · $name" else label
                                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(
                                    event.detail,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                event.formattedTime(),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
