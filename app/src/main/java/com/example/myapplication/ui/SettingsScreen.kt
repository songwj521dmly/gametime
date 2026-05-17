package com.example.myapplication.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.EmailReporter
import com.example.myapplication.data.TimeBank
import com.example.myapplication.admin.AdminReceiver
import kotlinx.coroutines.launch

data class AppInfo(val packageName: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var blockedPackages by remember {
        mutableStateOf(TimeBank.getBlockedPackages())
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var manualPackageName by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf(loadInstalledApps(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("游戏列表管理") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                Text(
                    "被拦截的游戏应用",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "余额不足时将拦截以下游戏",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(blockedPackages.toList()) { pkg ->
                val label = installedApps.find { it.packageName == pkg }?.label ?: pkg
                ListItem(
                    headlineContent = { Text(label) },
                    supportingContent = { Text(pkg) },
                    trailingContent = {
                        IconButton(onClick = {
                            val newSet = blockedPackages.toMutableSet().apply { remove(pkg) }
                            blockedPackages = newSet
                            TimeBank.setBlockedPackages(newSet)
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                )
            }

            // Daily game limit section
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "每日游戏时长限制",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "游戏仅在 8:00-22:30 间可用，超时强制关闭",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                DailyLimitCard()
            }

            // PIN change section
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "安全设置",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                PinChangeCard()
            }

            item {
                DeviceAdminCard()
            }

            // Email config section
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "每日邮件报告",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                EmailConfigSection()
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    if (showAddDialog) {
        AddGameDialog(
            installedApps = installedApps.filter { it.packageName !in blockedPackages },
            manualPackageName = manualPackageName,
            onManualPackageNameChange = { manualPackageName = it },
            onSelectApp = { pkg ->
                val newSet = blockedPackages.toMutableSet().apply { add(pkg) }
                blockedPackages = newSet
                TimeBank.setBlockedPackages(newSet)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGameDialog(
    installedApps: List<AppInfo>,
    manualPackageName: String,
    onManualPackageNameChange: (String) -> Unit,
    onSelectApp: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加游戏") },
        text = {
            LazyColumn(modifier = Modifier.height(400.dp)) {
                item {
                    OutlinedTextField(
                        value = manualPackageName,
                        onValueChange = onManualPackageNameChange,
                        label = { Text("手动输入包名") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        singleLine = true
                    )
                    if (manualPackageName.isNotBlank()) {
                        TextButton(onClick = {
                            onSelectApp(manualPackageName.trim())
                        }) {
                            Text("添加: $manualPackageName")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        "已安装应用",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(installedApps) { app ->
                    ListItem(
                        headlineContent = { Text(app.label) },
                        supportingContent = { Text(app.packageName) },
                        modifier = Modifier.clickable { onSelectApp(app.packageName) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun loadInstalledApps(context: android.content.Context): List<AppInfo> {
    return try {
        context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != context.packageName }
            .sortedBy { context.packageManager.getApplicationLabel(it).toString() }
            .map {
                AppInfo(
                    packageName = it.packageName,
                    label = context.packageManager.getApplicationLabel(it).toString()
                )
            }
    } catch (e: Exception) {
        emptyList()
    }
}

@Composable
private fun DailyLimitCard() {
    var workdayMinutes by remember { mutableStateOf(TimeBank.getWorkdayLimitMinutes().toString()) }
    var holidayMinutes by remember { mutableStateOf(TimeBank.getHolidayLimitMinutes().toString()) }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("工作日", fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = workdayMinutes,
                        onValueChange = { v ->
                            if (v.isEmpty() || v.all { it.isDigit() }) {
                                workdayMinutes = v
                                v.toLongOrNull()?.let { TimeBank.setWorkdayLimitMinutes(it) }
                            }
                        },
                        label = { Text("分钟") },
                        singleLine = true,
                        modifier = Modifier.width(100.dp)
                    )
                    Text(" 分钟/天", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("节假日", fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = holidayMinutes,
                        onValueChange = { v ->
                            if (v.isEmpty() || v.all { it.isDigit() }) {
                                holidayMinutes = v
                                v.toLongOrNull()?.let { TimeBank.setHolidayLimitMinutes(it) }
                            }
                        },
                        label = { Text("分钟") },
                        singleLine = true,
                        modifier = Modifier.width(100.dp)
                    )
                    Text(" 分钟/天", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PinChangeCard() {
    var showDialog by remember { mutableStateOf(false) }
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("管理密码", fontWeight = FontWeight.Medium)
                Text(
                    "用于访问设置和游戏列表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = { showDialog = true }) {
                Text("修改")
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false; oldPin = ""; newPin = ""; error = "" },
            title = { Text("修改密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = oldPin,
                        onValueChange = { oldPin = it.filter { c -> c.isDigit() }.take(4); error = "" },
                        label = { Text("旧密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(4); error = "" },
                        label = { Text("新密码 (4位)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error.isNotEmpty()) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!TimeBank.verifyPin(oldPin)) {
                        error = "旧密码错误"
                    } else if (newPin.length != 4) {
                        error = "请输入4位新密码"
                    } else {
                        TimeBank.setPin(newPin)
                        showDialog = false
                        oldPin = ""
                        newPin = ""
                        error = ""
                    }
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDialog = false; oldPin = ""; newPin = ""; error = ""
                }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DeviceAdminCard() {
    val context = LocalContext.current
    val dpm = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val adminComponent = ComponentName(context, AdminReceiver::class.java)
    val isActive = dpm.isAdminActive(adminComponent)

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("防卸载保护", fontWeight = FontWeight.Medium)
                Text(
                    if (isActive) "已激活，应用无法被卸载" else "未激活，孩子可以卸载应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                )
            }
            if (!isActive) {
                Button(onClick = {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "激活设备管理器可防止孩子卸载 GameTime 应用"
                        )
                    }
                    context.startActivity(intent)
                }) {
                    Text("激活")
                }
            } else {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "已激活",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun EmailConfigSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(EmailReporter.isEnabled()) }
    var sender by remember { mutableStateOf(EmailReporter.getSender()) }
    var authCode by remember { mutableStateOf(EmailReporter.getAuthCode()) }
    var recipient by remember { mutableStateOf(EmailReporter.getRecipient()) }
    var sendHour by remember { mutableStateOf(EmailReporter.getSendHour().toString()) }
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用每日邮件", fontWeight = FontWeight.Medium)
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    EmailReporter.setEnabled(it)
                })
            }

            if (enabled) {
                OutlinedTextField(
                    value = sender,
                    onValueChange = { sender = it; EmailReporter.setSender(it) },
                    label = { Text("发件人 QQ 邮箱") },
                    placeholder = { Text("123456789@qq.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = authCode,
                    onValueChange = { authCode = it; EmailReporter.setAuthCode(it) },
                    label = { Text("QQ SMTP 授权码") },
                    placeholder = { Text("在 QQ 邮箱设置中获取") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = recipient,
                    onValueChange = { recipient = it; EmailReporter.setRecipient(it) },
                    label = { Text("收件人邮箱") },
                    placeholder = { Text("接收报告的邮箱") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = sendHour,
                    onValueChange = { v ->
                        if (v.isEmpty() || (v.all { it.isDigit() } && v.toIntOrNull() in 0..23)) {
                            sendHour = v
                            v.toIntOrNull()?.let { EmailReporter.setSendHour(it) }
                        }
                    },
                    label = { Text("每日发送时间 (0-23点)") },
                    placeholder = { Text("22") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Test button
                var testResult by remember { mutableStateOf<String?>(null) }
                var testing by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "使用 QQ 邮箱 SMTP 服务。授权码获取：QQ 邮箱 → 设置 → 账户 → POP3/SMTP 服务 → 生成授权码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }

                Button(
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val result = EmailReporter.sendReport()
                            testResult = if (result.isSuccess) "发送成功 ✓" else "发送失败: ${result.exceptionOrNull()?.message}"
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (testing) "发送中..." else "发送测试邮件")
                }

                if (testResult != null) {
                    Text(
                        testResult!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testResult!!.contains("成功"))
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
