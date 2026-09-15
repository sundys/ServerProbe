package com.serverprobe.manager.ui.ssh

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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
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
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.serverprobe.manager.App
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var showSelectionActions by remember { mutableStateOf(false) }
    val savedFontSize by App.instance.settings.terminalFontSize.collectAsState(initial = 10f)
    var fontSizeSp by remember { mutableStateOf(10f) }
    var appliedSavedFont by remember { mutableStateOf(false) }
    LaunchedEffect(savedFontSize) {
        if (!appliedSavedFont && savedFontSize > 0f) {
            appliedSavedFont = true
            fontSizeSp = savedFontSize
            terminalView?.setTextSizePx(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, ctx.resources.displayMetrics),
            )
        }
    }
    // 主题（日/夜）注入终端配色
    val displayMode by App.instance.settings.displayMode.collectAsState(initial = com.serverprobe.manager.data.repo.DisplayMode.SYSTEM)
    val isLight = when (displayMode) {
        com.serverprobe.manager.data.repo.DisplayMode.LIGHT -> true
        com.serverprobe.manager.data.repo.DisplayMode.DARK -> false
        else -> !androidx.compose.foundation.isSystemInDarkTheme()
    }
    LaunchedEffect(isLight) { terminalView?.lightTheme = isLight }

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
        // 底部系统栏的让位交由按键条自己处理（见下方 Row），避免与
        // systemGestures 让位叠加成双份空白
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
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
                            onUserInput = { vm.scrollToBottomLocal() }
                            onSelectionChanged = { showSelectionActions = it }
                            terminalView = this
                        }
                    },
                    update = { view ->
                        view.emulator = emu
                        // 字号重组后保持一致（factory 初值可能已被 savedFontSize 更新）
                        view.setTextSizePx(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, ctx.resources.displayMetrics))
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
                // 选择复制：长按拖选后浮出按钮。状态由 View 回调驱动——旧实现只在
                // 收到新输出时刷新一次，长按后若没有输出则按钮永不出现，用户会卡在
                // 选择模式里（点按不再聚焦、键盘也拉不起来）。
                if (showSelectionActions) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = {
                                terminalView?.clearSelection()
                                showSelectionActions = false
                                // 焦点交回终端并重新拉起输入法，用户可以立刻继续输入
                                terminalView?.focusAndShowKeyboard()
                            },
                        ) { Text("取消") }
                        androidx.compose.material3.ExtendedFloatingActionButton(
                            onClick = {
                                val text = terminalView?.selectedText()
                                if (text != null) {
                                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("term", text))
                                    android.widget.Toast.makeText(ctx, "已复制选中内容", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                terminalView?.clearSelection()
                                showSelectionActions = false
                                terminalView?.focusAndShowKeyboard()
                            },
                        ) { Text("复制选中") }
                    }
                }
            }

            // 控制键条。注意：这一条位于屏幕最底部，正处在系统"手势带"内——
            // 全屏/导航栏隐藏时 WindowInsets.systemBars 为 0，但 systemGestures
            // 依然存在，起手于该区域的横向拖动会被系统截走，表现为"划不动"。
            // 因此显式让开手势带，并申请手势排除。
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.systemGestures
                            .union(WindowInsets.navigationBars)
                            .only(WindowInsetsSides.Bottom),
                    )
                    .systemGestureExclusion()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlKey("键盘") { terminalView?.toggleKeyboard() }
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
                    scope.launch { App.instance.settings.setTerminalFontSize(fontSizeSp) }
                }
                ControlKey("A+") {
                    fontSizeSp = (fontSizeSp + 1f).coerceAtMost(28f)
                    terminalView?.setTextSizePx(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, ctx.resources.displayMetrics))
                    scope.launch { App.instance.settings.setTerminalFontSize(fontSizeSp) }
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
            // 关键校准：连接过程中上报的网格尺寸发生在 emulator 就绪之前，
            // 不能作为 PTY 的最终尺寸；此处按当前屏幕真实列数再上报一次，
            // 确保服务器折行宽度与屏幕一致（否则输出只占屏幕左半边）。
            terminalView?.reportSizeNow()
            // focusAndShowKeyboard 内部延后一帧调用，避免输入法被窗口焦点问题吞掉
            terminalView?.focusAndShowKeyboard()
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
