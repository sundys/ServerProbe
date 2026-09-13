package com.serverprobe.manager.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val displayMode by vm.displayMode.collectAsState()
    val pollInterval by vm.pollInterval.collectAsState()
    val biometricLock by vm.biometricLock.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val ctx = LocalContext.current

    var pendingExport by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var replaceAll by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            vm.exportBackup(uri, passphrase.toCharArray())
            passphrase = ""
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pendingImportUri = uri
    }

    LaunchedEffect(message) {
        message?.let { (ok, msg) ->
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 显示模式
            Section("显示模式") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = displayMode == com.serverprobe.manager.data.repo.DisplayMode.LIGHT,
                        onClick = { vm.setDisplayMode(com.serverprobe.manager.data.repo.DisplayMode.LIGHT) },
                        label = { Text("☀ 日间") },
                    )
                    FilterChip(
                        selected = displayMode == com.serverprobe.manager.data.repo.DisplayMode.DARK,
                        onClick = { vm.setDisplayMode(com.serverprobe.manager.data.repo.DisplayMode.DARK) },
                        label = { Text("🌙 夜间") },
                    )
                    FilterChip(
                        selected = displayMode == com.serverprobe.manager.data.repo.DisplayMode.SYSTEM,
                        onClick = { vm.setDisplayMode(com.serverprobe.manager.data.repo.DisplayMode.SYSTEM) },
                        label = { Text("跟随系统") },
                    )
                }
            }

            // 刷新间隔
            Section("状态刷新间隔") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30, 60).forEach { sec ->
                        FilterChip(
                            selected = pollInterval == sec,
                            onClick = { vm.setPollInterval(sec) },
                            label = { Text("${sec}秒") },
                        )
                    }
                }
            }

            // 安全
            Section("安全") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("生物识别锁", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "切换到其他应用后需验证指纹/PIN 才能进入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = biometricLock, onCheckedChange = { vm.setBiometricLock(it) })
                }
            }

            // 备份与恢复
            Section("备份与恢复") {
                Text(
                    "备份包含全部探针主机与 SSH 主机（含凭据），使用口令加密（PBKDF2 + AES-256-GCM）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { pendingExport = true },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("导出备份") }
                    Button(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("恢复备份") }
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                        Text("处理中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // 关于
            Section("关于") {
                Text(
                    "ServerProbe 管理端 v1.0.0\n探针 Agent：Go 编译单二进制，systemd 常驻；\n通信：HTTPS + Bearer Token + 证书指纹锁定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    // 导出：输入口令
    if (pendingExport) {
        AlertDialog(
            onDismissRequest = { pendingExport = false },
            title = { Text("设置备份口令") },
            text = {
                Column {
                    Text("口令至少 6 位，用于加密备份文件；忘记口令将无法恢复。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("备份口令") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.length >= 6,
                    onClick = {
                        pendingExport = false
                        exportLauncher.launch("serverprobe-backup-${System.currentTimeMillis()}.json")
                    },
                ) { Text("导出") }
            },
            dismissButton = { TextButton(onClick = { pendingExport = false }) { Text("取消") } },
        )
    }

    // 导入：输入口令 + 合并策略
    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("恢复备份") },
            text = {
                Column {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("备份口令") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = replaceAll, onCheckedChange = { replaceAll = it })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (replaceAll) "替换模式（清空现有后导入）" else "合并模式（同地址更新，其余保留）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.length >= 6,
                    onClick = {
                        vm.importBackup(uri, passphrase.toCharArray(), replaceAll)
                        pendingImportUri = null
                        passphrase = ""
                    },
                ) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { pendingImportUri = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
