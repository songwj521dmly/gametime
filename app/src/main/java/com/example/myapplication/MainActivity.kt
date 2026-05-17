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
import com.example.myapplication.data.TimeBank
import com.example.myapplication.ui.DashboardScreen
import com.example.myapplication.ui.PermissionGuideScreen
import com.example.myapplication.ui.SettingsScreen
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TimeBank.init(this)
        AppUsageTracker.init(this)
        AppUsageTracker.startPeriodicCheck()
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

enum class Screen { DASHBOARD, SETTINGS, PERMISSIONS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavHost() {
    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }

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
                    onNavigateToSettings = { currentScreen = Screen.SETTINGS },
                    onNavigateToPermissions = { currentScreen = Screen.PERMISSIONS }
                )
            }
        }
        Screen.SETTINGS -> {
            SettingsScreen(onBack = { currentScreen = Screen.DASHBOARD })
        }
        Screen.PERMISSIONS -> {
            PermissionGuideScreen(onBack = { currentScreen = Screen.DASHBOARD })
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
