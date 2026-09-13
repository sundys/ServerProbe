package com.serverprobe.manager.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/**
 * 私钥文件选择器（国产 ROM 专项优化）：
 * 注册多条启动通道，依次尝试直到某条成功打开系统选择器。
 *
 * 背景：ColorOS 15 等 ROM 对 ACTION_GET_CONTENT 的通配 MIME 会同步抛出
 * IllegalArgumentException（AndroidX 侧表现为 launch() 即失败），故逐条回退：
 *  1. OpenDocument + 任意类型 —— 标准 SAF，多数 ROM 走独立实现
 *  2. GetContent + application/octet-stream —— 具体类型，避开通配处理的 ROM 缺陷
 *  3. GetContent + text/plain —— 文本类型
 *  4. GetContent + 通配类型 —— 原始通道
 *  5. Chooser 中转 GET_CONTENT —— 经系统 Chooser 解析，绕过 ROM 直接处理
 *
 * 仅当 launch() 同步抛异常才尝试下一条；用户取消选择不会推进回退链。
 * 全部失败时通过 onAllFailed 返回每条通道的详细异常（应写入诊断日志）。
 */
@Composable
fun rememberKeyFilePickerLauncher(
    onPicked: (Uri) -> Unit,
    onAllFailed: (String) -> Unit,
): () -> Unit {

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

        if (!launched) onAllFailed(failures.joinToString("\n"))
    }
}
