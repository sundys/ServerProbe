package com.serverprobe.manager.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.serverprobe.manager.ui.components.GithubIcon
import com.serverprobe.manager.update.UpdateManager

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
    val currentVersion by vm.currentVersion.collectAsState()
    val updateState by vm.updateState.collectAsState()
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
                            "仅在应用启动时验证一次指纹/PIN，其余场景不再打扰",
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

            // 检测更新
            Section("检测更新") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("当前版本 v${currentVersion}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "多通道检测 GitHub 最新版本（直连 + 加速代理）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { vm.checkUpdate() },
                        enabled = updateState !is SettingsViewModel.UpdateState.Checking &&
                            updateState !is SettingsViewModel.UpdateState.Downloading,
                    ) {
                        if (updateState is SettingsViewModel.UpdateState.Checking) {
                            CircularProgressIndicator(Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("检测更新")
                    }
                }
            }

            // 关于
            Section("关于") {
                Text(
                    "云枢 Remoto（服务探针管理系统）v${currentVersion}\n管理多台服务器运行状态：探针监控 + SSH 终端，凭据全程加密存储。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 开源主页（GitHub 图标 + 超链接）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openUrl(ctx, UpdateManager.REPO_URL) },
                ) {
                    Icon(
                        GithubIcon,
                        contentDescription = "GitHub",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("开源主页：", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Github主页",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )
                }
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

    // 检测更新相关弹窗（新版本/下载进度/结果）
    UpdateDialogs(vm)
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

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}

/** 检测更新状态弹窗：新版本 / 下载进度 / 结果提示 */
@Composable
private fun UpdateDialogs(vm: SettingsViewModel) {
    val updateState by vm.updateState.collectAsState()
    when (val s = updateState) {
        is SettingsViewModel.UpdateState.Available -> AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("发现新版本 v${s.info.version}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        s.info.notes.ifBlank { "暂无更新说明" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "安装包：${s.info.apkName}（${formatMb(s.info.apkSize)}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { Button(onClick = { vm.downloadUpdate(s.info) }) { Text("下载并安装") } },
            dismissButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("以后再说") } },
        )
        is SettingsViewModel.UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载更新") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val total = if (s.total > 0) s.total else 1L
                    LinearProgressIndicator(
                        progress = { (s.received.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatMb(s.received)} / ${formatMb(s.total)}（${(s.received * 100 / total)}%）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "下载完成后将自动唤起系统安装器",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
        )
        is SettingsViewModel.UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("已是最新版本") },
            text = { Text("当前 v${vm.currentVersion.value} 已是最新（远端 v${s.version}）。") },
            confirmButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("好的") } },
        )
        is SettingsViewModel.UpdateState.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("操作失败") },
            text = { Text(s.msg) },
            confirmButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("关闭") } },
        )
        else -> Unit
    }
}

private fun formatMb(bytes: Long): String =
    if (bytes <= 0) "0 MB" else "%.1f MB".format(bytes / 1024.0 / 1024.0)
