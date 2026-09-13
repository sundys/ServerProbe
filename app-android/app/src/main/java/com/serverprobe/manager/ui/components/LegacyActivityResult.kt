package com.serverprobe.manager.ui.components

import android.app.Activity
import android.content.Intent

/**
 * 传统 ActivityResult 通道：直接调用 Activity.startActivityForResult(intent, code)，
 * 不经过 androidx ActivityResultRegistry（后者会附带 ActivityOptions Bundle）。
 * 若 ROM 对 options 空包处理有缺陷（如 ColorOS 15 抛 IllegalArgumentException），
 * 该通道可绕开。结果经 MainActivity.onActivityResult → deliver 转发。
 */
object LegacyActivityResult {

    private const val CODE = 40001

    @Volatile
    private var callback: ((Int, Intent?) -> Unit)? = null

    /** 发起启动；失败时原样抛出异常（由调用方 runCatching 记录并回退）。 */
    fun start(activity: Activity, intent: Intent, onResult: (Int, Intent?) -> Unit) {
        callback = onResult
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, CODE)
        } catch (e: Exception) {
            callback = null
            throw e
        }
    }

    /** 由 MainActivity.onActivityResult 调用转发结果。 */
    fun deliver(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != CODE) return
        val cb = callback
        callback = null
        cb?.invoke(resultCode, data)
    }
}
