package com.serverprobe.manager.ui.ssh

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.TypedValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.serverprobe.manager.terminal.TerminalView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    sshId: Long,
    onClose: () -> Unit,
    vm: TerminalViewModel = viewModel(key = "term_$sshId"),
) {
    val state by vm.state.collectAsState()
    val emu by vm.emulator.collectAsState()
    val dataVersion by vm.dataVersion.collectAsState()
    val title by vm.title.collectAsState()
    val pendingFp by vm.pendingFingerprint.collectAsState()
    val ctx = LocalContext.current
    var fontSizeSp by remember { mutableStateOf(13f) }

    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title.ifEmpty { "SSH 终端" }, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            when (val s = state) {
                                is TerminalViewModel.TState.Connecting -> s.msg
                                is TerminalViewModel.TState.Connected -> "已连接"
                                is TerminalViewModel.TState.Closed -> s.reason ?: "已断开"
                                else -> ""
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(
                    factory = { context ->
                        TerminalView(context).apply {
                            setTextSizePx(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, context.resources.displayMetrics))
                            onData = { vm.write(it) }
                            onSizeChanged = { c, r -> vm.onSize(sshId, c, r) }
                            terminalView = this
                        }
                    },
                    update = { view ->
                        view.emulator = emu
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                when (val s = state) {
                    is TerminalViewModel.TState.Connecting, TerminalViewModel.TState.Idle -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator()
                            Text((s as? TerminalViewModel.TState.Connecting)?.msg ?: "准备中…")
                        }
                    }
                    is TerminalViewModel.TState.Closed -> {
                        // 顶部横幅：保留终端内容可见，便于查看断开前的输出
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(10.dp)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                                    RoundedCornerShape(14.dp),
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "连接已断开${s.reason?.let { "：$it" } ?: ""}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { vm.retry() }) { Text("重试") }
                                FilledTonalButton(onClick = onClose) { Text("返回") }
                            }
                        }
                    }
                    else -> Unit
                }
            }

            // 控制键条
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlKey("ESC") { terminalView?.send("\u001B") }
                ControlKey("TAB") { terminalView?.send("\t") }
                ControlKey("↑") { terminalView?.send("\u001B[A") }
                ControlKey("↓") { terminalView?.send("\u001B[B") }
                ControlKey("←") { terminalView?.send("\u001B[D") }
                ControlKey("→") { terminalView?.send("\u001B[C") }
                ControlKey("PGUP") { terminalView?.send("\u001B[5~") }
                ControlKey("PGDN") { terminalView?.send("\u001B[6~") }
                ControlKey("Ctrl+C") { terminalView?.send(byteArrayOf(0x03)) }
                ControlKey("Ctrl+D") { terminalView?.send(byteArrayOf(0x04)) }
                ControlKey("Ctrl+Z") { terminalView?.send(byteArrayOf(0x1A)) }
                ControlKey("|") { terminalView?.send("|") }
                ControlKey("~") { terminalView?.send("~") }
                ControlKey("-") { terminalView?.send("-") }
                ControlKey("A−") {
                    fontSizeSp = (fontSizeSp - 1f).coerceAtLeast(8f)
                    terminalView?.setTextSizePx(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, ctx.resources.displayMetrics))
                }
                ControlKey("A+") {
                    fontSizeSp = (fontSizeSp + 1f).coerceAtMost(28f)
                    terminalView?.setTextSizePx(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, ctx.resources.displayMetrics))
                }
                ControlKey("粘贴") {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: return@ControlKey
                    val paste = if (emu?.bracketedPaste == true) "\u001B[200~$text\u001B[201~" else text
                    terminalView?.send(paste.replace("\n", "\r"))
                }
            }
        }
    }

    // 首次连接主机指纹确认（TOFU）
    pendingFp?.let { fp ->
        AlertDialog(
            onDismissRequest = { vm.rejectHostKey() },
            title = { Text("确认主机指纹") },
            text = {
                Text(
                    "首次连接该主机。请核对服务器 SSH 主机密钥指纹（SHA-256）：\n\n$fp\n\n" +
                        "信任后 App 将锁定该指纹；若以后指纹变化将拒绝连接，防止中间人攻击。",
                )
            },
            confirmButton = { Button(onClick = { vm.trustHostKey() }) { Text("信任并连接") } },
            dismissButton = { TextButton(onClick = { vm.rejectHostKey() }) { Text("取消") } },
        )
    }

    // 连接建立后聚焦终端并拉起软键盘（仅一次，不随输出重绘重复触发）
    LaunchedEffect(emu) {
        if (emu != null) {
            terminalView?.requestFocus()
            val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, 0)
        }
    }

    // 每批输出到达后重绘一次
    LaunchedEffect(dataVersion) {
        terminalView?.invalidate()
    }
}

@Composable
private fun ControlKey(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .height(38.dp)
            .width(if (label.length <= 2) 44.dp else 62.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(label, fontSize = 12.sp)
    }
}
