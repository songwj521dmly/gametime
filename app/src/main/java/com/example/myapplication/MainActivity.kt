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
