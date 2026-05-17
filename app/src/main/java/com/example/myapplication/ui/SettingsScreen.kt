package com.example.myapplication.ui

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
import com.example.myapplication.data.TimeBank

data class AppInfo(val packageName: String, val label: String)

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
