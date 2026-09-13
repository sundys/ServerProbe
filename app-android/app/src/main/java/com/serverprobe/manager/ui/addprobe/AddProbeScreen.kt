package com.serverprobe.manager.ui.addprobe

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.serverprobe.manager.data.db.AUTH_KEY
import com.serverprobe.manager.data.db.AUTH_PASSWORD

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProbeScreen(
    editId: Long = 0L,
    onDone: () -> Unit,
    vm: AddProbeViewModel = viewModel(key = "addprobe_$editId", factory = addProbeFactory(editId)),
) {
    val form by vm.form.collectAsState()
    val sshHosts by vm.sshHosts.collectAsState()
    val testing by vm.testing.collectAsState()
    val testResult by vm.testResult.collectAsState()
    val fetchedFp by vm.fetchedFingerprint.collectAsState()
    val saved by vm.saved.collectAsState()
    val ctx = LocalContext.current

    var showInstallGuide by remember { mutableStateOf(false) }
    var sshKeyMenu by remember { mutableStateOf(false) }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()?.let { text ->
                vm.update { f -> f.copy(sshPrivateKey = text, sshAuthType = AUTH_KEY) }
            } ?: Toast.makeText(ctx, "无法读取所选文件", Toast.LENGTH_SHORT).show()
        }
    }
    // 选择器启动失败（无组件/ROM限制）时提示改用粘贴输入
    val launchKeyPicker = {
        runCatching { keyPicker.launch("*/*") }
            .onFailure {
                Toast.makeText(
                    ctx,
                    "无法打开系统文件选择器（${it.javaClass.simpleName}），请改用下方“粘贴私钥内容”",
                    Toast.LENGTH_LONG,
                ).show()
            }
        Unit
    }

    LaunchedEffect(saved) { if (saved) onDone() }
    LaunchedEffect(testResult) {
        testResult?.let { (ok, msg) ->
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            if (!ok) vm.clearTestResult() else vm.clearTestResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (form.isEdit) "编辑探针主机" else "添加探针主机", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { showInstallGuide = true }) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, "安装指引")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> vm.update { f -> f.copy(name = v) } },
                label = { Text("名称（如：生产 Web 服务器）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.host,
                onValueChange = { v -> vm.update { f -> f.copy(host = v) } },
                label = { Text("主机地址 (IP 或域名)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.port,
                onValueChange = { v -> vm.update { f -> f.copy(port = v.filter { it.isDigit() }.take(5)) } },
                label = { Text("探针端口 (默认 9822)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.token,
                onValueChange = { v -> vm.update { f -> f.copy(token = v, tokenChanged = true) } },
                label = { Text(if (form.isEdit && !form.tokenChanged) "Token（未输入则保留原值）" else "Token（serverprobe init 输出）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { vm.update { f -> f.copy(token = java.util.UUID.randomUUID().toString().replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", "")) } }) {
                        Text("生成", style = MaterialTheme.typography.labelMedium)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = form.useTls,
                    onClick = { vm.update { f -> f.copy(useTls = true) } },
                    label = { Text("TLS 加密") },
                )
                FilterChip(
                    selected = !form.useTls,
                    onClick = { vm.update { f -> f.copy(useTls = false) } },
                    label = { Text("不加密(不推荐)") },
                )
            }

            if (form.useTls) {
                OutlinedTextField(
                    value = form.fingerprint,
                    onValueChange = { v -> vm.update { f -> f.copy(fingerprint = v) } },
                    label = { Text("证书 SHA-256 指纹（强烈建议填写）") },
                    placeholder = { Text("AA:BB:CC:…", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { vm.fetchFingerprint() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("从服务器获取证书指纹（临时不校验，仅本次）") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = form.allowInsecureTls,
                        onClick = { vm.update { f -> f.copy(allowInsecureTls = !form.allowInsecureTls) } },
                        label = { Text("跳过证书校验(不推荐)") },
                    )
                }
            }

            // ---- SSH 设置项 ----
            Text("SSH 设置项", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "绑定 SSH 身份后，可在主机详情页一键打开远程终端。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = form.sshMode == AddProbeViewModel.SSH_NONE,
                    onClick = { vm.update { f -> f.copy(sshMode = AddProbeViewModel.SSH_NONE) } },
                    label = { Text("不绑定") },
                )
                FilterChip(
                    selected = form.sshMode == AddProbeViewModel.SSH_EXISTING,
                    onClick = { vm.update { f -> f.copy(sshMode = AddProbeViewModel.SSH_EXISTING) } },
                    label = { Text("绑定已有") },
                )
                FilterChip(
                    selected = form.sshMode == AddProbeViewModel.SSH_NEW,
                    onClick = { vm.update { f -> f.copy(sshMode = AddProbeViewModel.SSH_NEW) } },
                    label = { Text("新建身份") },
                )
            }

            when (form.sshMode) {
                AddProbeViewModel.SSH_EXISTING -> {
                    ExposedDropdownMenuBox(
                        expanded = sshKeyMenu,
                        onExpandedChange = { sshKeyMenu = it },
                    ) {
                        val selected = sshHosts.firstOrNull { it.id == form.sshExistingId }
                        OutlinedTextField(
                            value = selected?.alias ?: "请选择 SSH 身份",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("SSH 身份") },
                            trailingIcon = { Text("▾") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                        )
                        ExposedDropdownMenu(expanded = sshKeyMenu, onDismissRequest = { sshKeyMenu = false }) {
                            sshHosts.forEach { ssh ->
                                DropdownMenuItem(
                                    text = { Text("${ssh.alias} (${ssh.username}@${ssh.host})") },
                                    onClick = {
                                        vm.update { f -> f.copy(sshExistingId = ssh.id) }
                                        sshKeyMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                AddProbeViewModel.SSH_NEW -> {
                    OutlinedTextField(
                        value = form.sshUsername,
                        onValueChange = { v -> vm.update { f -> f.copy(sshUsername = v) } },
                        label = { Text("SSH 用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = form.sshAuthType == AUTH_PASSWORD,
                            onClick = { vm.update { f -> f.copy(sshAuthType = AUTH_PASSWORD) } },
                            label = { Text("密码") },
                        )
                        FilterChip(
                            selected = form.sshAuthType == AUTH_KEY,
                            onClick = { vm.update { f -> f.copy(sshAuthType = AUTH_KEY) } },
                            label = { Text("私钥") },
                        )
                    }
                    if (form.sshAuthType == AUTH_PASSWORD) {
                        OutlinedTextField(
                            value = form.sshPassword,
                            onValueChange = { v -> vm.update { f -> f.copy(sshPassword = v) } },
                            label = { Text("SSH 密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OutlinedButton(onClick = launchKeyPicker, modifier = Modifier.fillMaxWidth()) {
                            Text(form.sshPrivateKey?.let { "已选择私钥（点击更换）" } ?: "选择私钥文件")
                        }
                        OutlinedTextField(
                            value = form.sshPrivateKey ?: "",
                            onValueChange = { v ->
                                vm.update { f -> f.copy(sshPrivateKey = v.takeIf { it.isNotBlank() }, sshAuthType = AUTH_KEY) }
                            },
                            label = { Text("或直接粘贴私钥内容（无需选择文件）") },
                            minLines = 3,
                            maxLines = 8,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                capitalization = KeyboardCapitalization.None,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = form.sshKeyPassphrase,
                        onValueChange = { v -> vm.update { f -> f.copy(sshKeyPassphrase = v) } },
                        label = { Text("私钥口令（可选）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { vm.test() },
                    enabled = !testing && form.host.isNotBlank() && form.token.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("测试连接")
                }
                Button(
                    onClick = { vm.save() },
                    enabled = form.host.isNotBlank() && (form.isEdit || form.token.isNotBlank()),
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // 指纹确认弹窗
    fetchedFp?.let { fp ->
        AlertDialog(
            onDismissRequest = { vm.update { f -> f }; vm.dismissFingerprint() },
            title = { Text("获取到服务器证书指纹") },
            text = {
                Text(
                    "SHA-256:\n$fp\n\n请通过可信渠道（服务器控制台执行 serverprobe show）核对后锁定。锁定后 App 仅信任该证书，可防中间人攻击。",
                )
            },
            confirmButton = { Button(onClick = { vm.useFingerprint(fp) }) { Text("填入并锁定") } },
            dismissButton = { TextButton(onClick = { vm.dismissFingerprint() }) { Text("取消") } },
        )
    }

    // 安装指引
    if (showInstallGuide) {
        AlertDialog(
            onDismissRequest = { showInstallGuide = false },
            title = { Text("探针安装指引") },
            text = {
                Text(
                    "1. 在服务器（Linux, systemd）上执行安装：\n\n" +
                        "   # 上传 install.sh 与二进制后：\n" +
                        "   sudo bash install.sh --token <你的Token>\n\n" +
                        "   或使用预编译二进制：\n" +
                        "   sudo bash install.sh --token <Token> --download <二进制URL>\n\n" +
                        "2. 记录安装输出的 Token 与证书指纹。\n" +
                        "3. 回到本页填写地址、端口(默认9822)、Token 与指纹。\n\n" +
                        "安全提示：Token 请走 HTTPS 传输；指纹务必核对后填写，防止中间人。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton(onClick = { showInstallGuide = false }) { Text("知道了") } },
        )
    }
}

private fun addProbeFactory(editId: Long): androidx.lifecycle.ViewModelProvider.Factory =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            AddProbeViewModel(com.serverprobe.manager.App.instance, editId) as T
    }
