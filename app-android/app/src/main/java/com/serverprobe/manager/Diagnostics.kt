package com.serverprobe.manager

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断信息落盘（crash-report.txt）：崩溃堆栈 + 关键失败事件，
 * 供设置 → 关于 → 「复制诊断日志」取证反馈。文件超过 200KB 自动清空重记。
 */
object Diagnostics {

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

    fun log(context: Context, text: String) {
        runCatching {
            val f = File(context.filesDir, "crash-report.txt")
            if (f.length() > 200_000) f.writeText("")
            f.appendText("---- DIAG ${stamp()} ----\n$text\n")
        }
    }

    fun crash(context: Context, threadName: String, stack: String) {
        runCatching {
            val f = File(context.filesDir, "crash-report.txt")
            if (f.length() > 200_000) f.writeText("")
            f.appendText("==== CRASH ${stamp()} ====\nthread: $threadName\n$stack\n")
        }
    }
}
