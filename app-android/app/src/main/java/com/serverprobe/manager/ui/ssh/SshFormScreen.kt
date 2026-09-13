package com.serverprobe.manager.ui.ssh

import android.widget.Toast
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.serverprobe.manager.data.db.AUTH_KEY
import com.serverprobe.manager.data.db.AUTH_PASSWORD
import com.serverprobe.manager.ssh.SshKeyLoader
import com.serverprobe.manager.ui.components.PickerFailureDialog
import com.serverprobe.manager.ui.components.rememberKeyFilePickerLauncher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshFormScreen(
    editId: Long = 0L,
    onDone: () -> Unit,
    vm: SshFormViewModel = viewModel(key = "sshform_$editId", factory = sshFormFactory(editId)),
) {
    val form by vm.form.collectAsState()
    val testing by vm.testing.collectAsState()
    val testResult by vm.testResult.collectAsState()
    val saved by vm.saved.collectAsState()
    val ctx = LocalContext.current
    var pickerFail by remember { mutableStateOf<String?>(null) }

    val launchKeyPicker = rememberKeyFilePickerLauncher(
        onPicked = { uri ->
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()?.let { vm.update { f -> f.copy(privateKey = it, privateKeyChanged = true) } }
                ?: Toast.makeText(ctx, "无法读取所选文件", Toast.LENGTH_SHORT).show()
        },
        onAllFailed = { detail ->
            pickerFail = detail
            com.serverprobe.manager.Diagnostics.log(ctx, "key picker fail:\n$detail")
        },
    )

    LaunchedEffect(Unit) { if (editId > 0) vm.load() }
    LaunchedEffect(saved) { if (saved) onDone() }
    LaunchedEffect(testResult) {
        testResult?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.clearTestResult()
        }
    }

    pickerFail?.let { PickerFailureDialog(detail = it, onDismiss = { pickerFail = null }) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editId > 0) "编辑 SSH 主机" else "添加 SSH 主机", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
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
                value = form.alias,
                onValueChange = { v -> vm.update { f -> f.copy(alias = v) } },
                label = { Text("别名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.host,
                onValueChange = { v -> vm.update { f -> f.copy(host = v) } },
                label = { Text("主机地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.port,
                onValueChange = { v -> vm.update { f -> f.copy(port = v.filter { it.isDigit() }.take(5)) } },
                label = { Text("端口 (默认 22)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.username,
                onValueChange = { v -> vm.update { f -> f.copy(username = v) } },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = form.authType == AUTH_PASSWORD,
                    onClick = { vm.update { f -> f.copy(authType = AUTH_PASSWORD) } },
                    label = { Text("密码认证") },
                )
                FilterChip(
                    selected = form.authType == AUTH_KEY,
                    onClick = { vm.update { f -> f.copy(authType = AUTH_KEY) } },
                    label = { Text("私钥认证") },
                )
            }

            if (form.authType == AUTH_PASSWORD) {
                OutlinedTextField(
                    value = form.password,
                    onValueChange = { v -> vm.update { f -> f.copy(password = v, passwordChanged = true) } },
                    label = { Text(if (form.isEdit && !form.passwordChanged) "密码（未输入则保留原值）" else "密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedButton(
                    onClick = launchKeyPicker,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("从文件选择私钥（OpenSSH / PEM PKCS#1 / PKCS#8 / PuTTY PPK）") }
                val pk = form.privateKey
                OutlinedTextField(
                    value = pk ?: "",
                    onValueChange = { v ->
                        vm.update { f -> f.copy(privateKey = v.takeIf { it.isNotBlank() }, privateKeyChanged = true) }
                    },
                    label = { Text("或直接粘贴私钥内容（无需选择文件）") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----", style = MaterialTheme.typography.bodySmall) },
                    supportingText = {
                        Text(
                            if (pk != null) "已识别格式：${SshKeyLoader.detectFormat(pk)} · ${pk.length} 字符"
                            else "支持 OpenSSH 新格式 / PEM PKCS#1 / PKCS#8 / PuTTY PPK，粘贴后自动识别",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    minLines = 3,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pk == null && form.isEdit) {
                    Text(
                        "已保存私钥（未重新输入则保留原值）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = form.keyPassphrase,
                    onValueChange = { v -> vm.update { f -> f.copy(keyPassphrase = v, passphraseChanged = true) } },
                    label = { Text(if (form.isEdit && !form.passphraseChanged) "私钥口令（可选，未输入则保留）" else "私钥口令（可选）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { vm.test() },
                    enabled = !testing && form.host.isNotBlank() && form.username.isNotBlank(),
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
                    enabled = form.host.isNotBlank() && form.username.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun sshFormFactory(editId: Long): androidx.lifecycle.ViewModelProvider.Factory =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            SshFormViewModel(com.serverprobe.manager.App.instance, editId) as T
    }
