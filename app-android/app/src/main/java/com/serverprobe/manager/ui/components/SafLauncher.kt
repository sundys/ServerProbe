package com.serverprobe.manager.ui.components

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 通用 SAF 启动器（国产 ROM 专项）：
 * 与私钥选择器（rememberKeyFilePickerLauncher）同一套 6 通道回退机制，
 * 用于备份导出（CreateDocument）与恢复（OpenDocument）等任意 SAF 场景。
 *
 * ColorOS 15 对 androidx ActivityResultRegistry 附带 ActivityOptions 的启动
 * 会同步抛 IllegalArgumentException，导致 App 被杀（表现为"自动退出前台"）。
 * 逐通道尝试，launch() 同步异常才推进下一条；用户取消不推进。
 */
@Composable
fun rememberSafLauncher(
    create: Boolean,
    suggestName: String,
    onResult: (Uri?) -> Unit,
    onAllFailed: (String) -> Unit,
): (String) -> Unit {
    val context = LocalContext.current

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        onResult(uri)
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onResult(uri)
    }
    val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        onResult(uri)
    }
    val startForResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r: ActivityResult ->
        onResult(r.data?.data)
    }

    return { mime ->
        val failures = mutableListOf<String>()
        var launched = false

        fun attempt(name: String, go: () -> Unit) {
            if (launched) return
            runCatching { go() }
                .onSuccess { launched = true }
                .onFailure { e -> failures.add("$name → ${e.javaClass.name}: ${e.message}") }
        }

        if (create) {
            // 导出：CreateDocument 各通道
            attempt("CreateDocument") { createDoc.launch(suggestName) }
            if (!launched && context is Activity) {
                attempt("LegacyCreate") {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(mime.ifBlank { "application/json" })
                        .putExtra(Intent.EXTRA_TITLE, suggestName)
                    LegacyActivityResult.start(context, intent) { _, data -> onResult(data?.data) }
                }
            }
        } else {
            // 导入：OpenDocument / GetContent / Chooser / Legacy 各通道
            attempt("OpenDocument") { openDoc.launch(arrayOf(mime.ifBlank { "*/*" })) }
            attempt("GetContent") { getContent.launch(mime.ifBlank { "*/*" }) }
            attempt("Chooser") {
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime.ifBlank { "*/*" })
                startForResult.launch(Intent.createChooser(intent, null))
            }
            if (!launched && context is Activity) {
                attempt("LegacyOpen") {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(mime.ifBlank { "*/*" })
                    LegacyActivityResult.start(context, intent) { _, data -> onResult(data?.data) }
                }
            }
        }

        if (!launched) onAllFailed(failures.joinToString("\n"))
    }
}
