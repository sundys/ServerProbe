package com.serverprobe.manager.ui.components

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 私钥文件选择器（国产 ROM 专项优化）：
 * 注册 6 条启动通道，依次尝试直到某条成功打开系统选择器。
 *
 *  1. OpenDocument + 任意类型 —— 标准 SAF
 *  2. GetContent + application/octet-stream —— 具体类型
 *  3. GetContent + text/plain —— 文本类型
 *  4. GetContent + 通配类型 —— 原始通道
 *  5. Chooser 中转 GET_CONTENT —— 经系统 Chooser 解析
 *  6. 传统 startActivityForResult —— 绕开 androidx 与 ActivityOptions，直接走框架老路径
 *
 * 仅当 launch() 同步抛异常才尝试下一条；用户取消选择不会推进回退链。
 * 全部失败时通过 onAllFailed 返回每条通道的详细异常（应写入诊断日志并弹窗展示）。
 */
@Composable
fun rememberKeyFilePickerLauncher(
    onPicked: (Uri) -> Unit,
    onAllFailed: (String) -> Unit,
): () -> Unit {

    val context = LocalContext.current
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPicked(uri)
    }
    val startForResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r: ActivityResult ->
        r.data?.data?.let { onPicked(it) }
    }

    return {
        val failures = mutableListOf<String>()
        var launched = false

        fun attempt(name: String, go: () -> Unit) {
            if (launched) return
            runCatching { go() }
                .onSuccess { launched = true }
                .onFailure { e ->
                    failures.add("$name → ${e.javaClass.name}: ${e.message}")
                }
        }

        attempt("OpenDocument(all)") { openDoc.launch(arrayOf("*/*")) }
        attempt("GetContent(octet-stream)") { getContent.launch("application/octet-stream") }
        attempt("GetContent(text/plain)") { getContent.launch("text/plain") }
        attempt("GetContent(any)") { getContent.launch("*/*") }
        attempt("Chooser(GET_CONTENT)") {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
            startForResult.launch(Intent.createChooser(intent, "选择私钥文件"))
        }
        // 第 6 通道：完全绕开 androidx 注册器与 ActivityOptions（ColorOS 15 等全通道失败时的兜底）
        if (!launched && context is Activity) {
            attempt("Legacy(startActivityForResult)") {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                LegacyActivityResult.start(context, intent) { _, data ->
                    data?.data?.let(onPicked)
                }
            }
        }

        if (!launched) onAllFailed(failures.joinToString("\n"))
    }
}

/** 全通道失败弹窗：提示粘贴保底 + 展示详情 + 一键复制（便于反馈定位 ROM 问题）。 */
@Composable
fun PickerFailureDialog(detail: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("无法打开文件选择器") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "可直接使用「粘贴私钥内容」完成添加（效果相同）。若希望继续排查文件选择问题，请复制详情反馈：",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("picker-fail", detail))
                    Toast.makeText(ctx, "详情已复制", Toast.LENGTH_SHORT).show()
                }
                onDismiss()
            }) { Text("复制详情") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
