package com.example.myapplication.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class PermissionItem(
    val name: String,
    val description: String,
    val isGranted: Boolean,
    val action: (Context) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick

    val permissions = listOf(
        PermissionItem(
            name = "使用情况访问权限",
            description = "用于辅助验证多邻国使用时长。必须开启。",
            isGranted = checkUsageStatsPermission(context),
            action = { ctx ->
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        ),
        PermissionItem(
            name = "无障碍服务",
            description = "核心权限。检测多邻国学习和拦截游戏应用。必须开启。",
            isGranted = checkAccessibilityServiceEnabled(context),
            action = { ctx ->
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        ),
        PermissionItem(
            name = "悬浮窗权限",
            description = "用于在游戏上方显示拦截提示。必须开启。",
            isGranted = Settings.canDrawOverlays(context),
            action = { ctx ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
                ctx.startActivity(intent)
            }
        ),
        PermissionItem(
            name = "省电优化豁免",
            description = "防止系统在后台杀死服务。强烈建议开启。",
            isGranted = checkBatteryOptimization(context),
            action = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                    }
                    ctx.startActivity(intent)
                }
            }
        ),
        PermissionItem(
            name = "通知权限",
            description = "用于前台服务通知。Android 13+ 必须开启。",
            isGranted = checkNotificationPermission(context),
            action = { ctx ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    }
                    ctx.startActivity(intent)
                }
            }
        )
    )

    val allGranted = permissions.all { it.isGranted }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限设置") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (allGranted) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "所有权限已就绪",
                        modifier = Modifier.padding(16.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "还有 ${permissions.count { !it.isGranted }} 项权限需要开启",
                        modifier = Modifier.padding(16.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            permissions.forEach { perm ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (perm.isGranted)
                            MaterialTheme.colorScheme.surfaceVariant
                        else
                            MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (perm.isGranted) Icons.Default.Check
                                else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (perm.isGranted)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(perm.name, fontWeight = FontWeight.Medium)
                            Text(
                                perm.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!perm.isGranted) {
                            Button(onClick = { perm.action(context) }) {
                                Text("开启")
                            }
                        }
                    }
                }
            }

            // ROM-specific guidance
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("国产手机额外步骤", fontWeight = FontWeight.Bold)
                    Text(
                        "MIUI/ColorOS等系统请手动完成：\n" +
                        "1. 设置 > 应用 > GameTime > 自启动（开启）\n" +
                        "2. 多任务界面 > 长按GameTime > 锁定\n" +
                        "3. 设置 > 应用 > GameTime > 省电策略 > 无限制",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun checkUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun checkAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(
        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
    )
    return enabledServices.any {
        it.resolveInfo?.serviceInfo?.name?.contains("GameTimeService") == true
    }
}

private fun checkBatteryOptimization(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } else {
        true
    }
}

private fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}
