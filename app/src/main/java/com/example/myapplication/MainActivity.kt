package com.example.myapplication

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.AppUsageTracker
import com.example.myapplication.data.ActivityLog
import com.example.myapplication.data.EmailReporter
import com.example.myapplication.data.TimeBank
import com.example.myapplication.receiver.EmailReportReceiver
import com.example.myapplication.receiver.ServiceGuardReceiver
import com.example.myapplication.ui.DashboardScreen
import com.example.myapplication.ui.LogScreen
import com.example.myapplication.ui.PermissionGuideScreen
import com.example.myapplication.ui.SettingsScreen
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TimeBank.init(this)
        ActivityLog.init(this)
        EmailReporter.init(this)
        AppUsageTracker.init(this)
        AppUsageTracker.startPeriodicCheck()
        EmailReportReceiver.schedule(this)
        ServiceGuardReceiver.schedule(this)

        // Detect if app was force-killed (service may not have started yet)
        val killResult = TimeBank.detectKill()
        when (killResult.type) {
            TimeBank.KillType.FIRST_WARNING -> {
                val formattedTime = TimeBank.formatKillTime(killResult.killTimeMs)
                EmailReporter.sendAlert(
                    "应用进程被手动关闭(第1次警告)",
                    "GameTime 进程被手动杀死\n\n杀死时间: $formattedTime\n关闭时长: ${killResult.deadMinutes}分钟\n\n本周第1次，仅警告。再次关闭将扣除游戏时间。"
                )
            }
            TimeBank.KillType.PENALTY -> {
                val formattedTime = TimeBank.formatKillTime(killResult.killTimeMs)
                EmailReporter.sendAlert(
                    "应用进程被手动关闭(扣除惩罚)",
                    "GameTime 进程被手动杀死\n\n杀死时间: $formattedTime\n关闭时长: ${killResult.deadMinutes}分钟\n本周第${killResult.killCount}次关闭\n惩罚: 扣除${killResult.penaltySeconds / 60}分钟游戏时间"
                )
            }
            else -> {}
        }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TimeBank.init(this)
    }
}

enum class Screen { DASHBOARD, SETTINGS, PERMISSIONS, LOGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavHost() {
    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }
    var pendingScreen by remember { mutableStateOf<Screen?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    // Kill detection dialog
    val killTimeMs = TimeBank.getLastKillTimeMs()
    val penaltySeconds = TimeBank.getLastPenaltySeconds()
    val deadMinutes = TimeBank.getLastDeadMinutes()
    val killCount = TimeBank.getLastKillCountForDialog()
    var showKillDialog by remember { mutableStateOf(killTimeMs > 0L) }

    if (showKillDialog && killTimeMs > 0L) {
        val formattedTime = TimeBank.formatKillTime(killTimeMs)
        val isFirstKill = penaltySeconds <= 0L
        AlertDialog(
            onDismissRequest = {
                showKillDialog = false
                TimeBank.clearKillFlag()
            },
            title = {
                Text(
                    if (isFirstKill) "⚠️ 警告" else "⛔ 惩罚通知",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                val msg = buildString {
                    append("检测到应用在上次被手动关闭！\n\n")
                    append("关闭时间: $formattedTime\n")
                    append("关闭时长: ${deadMinutes}分钟\n")
                    if (isFirstKill) {
                        append("\n这是本周第1次关闭，仅作警告。\n")
                        append("如再次关闭将被扣除游戏时间！")
                    } else {
                        val penaltyMin = penaltySeconds / 60
                        append("\n本周第${killCount}次关闭！\n")
                        append("惩罚: 扣除 ${penaltyMin}分钟 游戏时间\n")
                        append("(已从余额扣除，可扣至负值)")
                    }
                    append("\n\n此行为已记录并通知家长。")
                    append("\n请不要手动关闭此应用。")
                }
                Text(msg)
            },
            confirmButton = {
                TextButton(onClick = {
                    showKillDialog = false
                    TimeBank.clearKillFlag()
                }) {
                    Text("我知道了")
                }
            }
        )
    }

    // PIN dialog
    if (pendingScreen != null) {
        AlertDialog(
            onDismissRequest = {
                pendingScreen = null
                pinInput = ""
                pinError = false
            },
            title = { Text("输入密码") },
            text = {
                Column {
                    Text("请输入4位数字密码以访问设置")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                                pinInput = it
                                pinError = false
                                if (it.length == 4) {
                                    if (TimeBank.verifyPin(it)) {
                                        currentScreen = pendingScreen!!
                                        pendingScreen = null
                                        pinInput = ""
                                    } else {
                                        pinError = true
                                        pinInput = ""
                                    }
                                }
                            }
                        },
                        label = { Text("PIN") },
                        isError = pinError,
                        supportingText = if (pinError) {{ Text("密码错误") }} else null,
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (TimeBank.verifyPin(pinInput)) {
                        currentScreen = pendingScreen!!
                        pendingScreen = null
                        pinInput = ""
                        pinError = false
                    } else {
                        pinError = true
                        pinInput = ""
                    }
                }) { Text("确认") }
            }
        )
    }

    when (currentScreen) {
        Screen.DASHBOARD -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            val serviceEnabled = remember { checkServiceEnabled(context) }
            var showWarning by remember { mutableStateOf(!serviceEnabled) }

            Column(modifier = Modifier.fillMaxSize()) {
                if (showWarning) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "无障碍服务未开启，应用无法工作",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = {
                                showWarning = false
                                currentScreen = Screen.PERMISSIONS
                            }) {
                                Text("去开启")
                            }
                        }
                    }
                }

                DashboardScreen(
                    onNavigateToSettings = { pendingScreen = Screen.SETTINGS },
                    onNavigateToPermissions = { currentScreen = Screen.PERMISSIONS },
                    onNavigateToLogs = { currentScreen = Screen.LOGS }
                )
            }
        }
        Screen.SETTINGS -> {
            SettingsScreen(onBack = { currentScreen = Screen.DASHBOARD })
        }
        Screen.PERMISSIONS -> {
            PermissionGuideScreen(onBack = { currentScreen = Screen.DASHBOARD })
        }
        Screen.LOGS -> {
            LogScreen(onBack = { currentScreen = Screen.DASHBOARD })
        }
    }
}

private fun checkServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(
        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
    )
    return enabledServices.any {
        it.resolveInfo?.serviceInfo?.name?.contains("GameTimeService") == true
    }
}
