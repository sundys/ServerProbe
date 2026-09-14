package com.serverprobe.manager.terminal

import android.graphics.Paint
import android.graphics.Typeface
import java.io.File

/**
 * 终端单元格字体度量。
 *
 * 终端要求**严格等宽**字体：单元格宽度必须等于每一个字符的绘制步长，否则
 * 「一屏能放多少列」与「服务器按多少列折行」就会互相不一致。
 *
 * 此前实现直接使用 `Typeface.MONOSPACE`，并用 `measureText("W")` 作为单元格
 * 宽度。在不带 DroidSansMono 的 ROM 上，"monospace" 家族会**静默回退到默认
 * （中文/比例）字体**，此时 'W' 宽约 0.75em，而正文（小写字母/数字/空格）
 * 平均字宽只有约 0.4em —— 单元格被高估近 2 倍：
 *  - 上报给 PTY 的列数只有屏幕实际可容纳量的一半，服务器据此提前折行，
 *    文字只占屏幕左半边、右侧大片空白；
 *  - 光标/背景/选区按过宽的格子绘制，光标停在提示符右侧很远处。
 *
 * 这里改为：显式挑选一个**真正等宽**的字体（候选系统字体文件 + 家族名，
 * 逐个用字宽一致性校验），并按该字体实际字宽计算单元格；若系统确实没有
 * 任何可用等宽字体，则退回用正文探针的**平均字宽**，保证列数与屏幕一致，
 * 同时由渲染侧把每个字符压进各自的格子。
 */
internal object TerminalFont {

    /** 单元格度量结果 */
    class Metrics(
        /** 单元格宽度（像素），保证「屏幕上能容纳的列数」与渲染一致 */
        val cellW: Float,
        /** 是否为真正等宽字体（决定绘制路径） */
        val fixedPitch: Boolean,
    )

    /** 选中结果 */
    private class Resolved(val typeface: Typeface, val name: String, val fixedPitch: Boolean)

    /** 常见等宽系统字体文件（不存在则跳过） */
    private val FONT_FILES = arrayOf(
        "/system/fonts/DroidSansMono.ttf",
        "/system/fonts/RobotoMono-Regular.ttf",
        "/system/fonts/NotoSansMono-Regular.ttf",
        "/system/fonts/NotoSansMono[wdth,wght].ttf",
        "/system/fonts/CutiveMono.ttf",
        "/system/fonts/DejaVuSansMono.ttf",
        "/system/fonts/CourierNew.ttf",
        "/system/fonts/Courier.ttf",
    )

    /** 常见等宽字体家族名（部分 ROM 需按名创建才能拿到真等宽字体） */
    private val FONT_FAMILIES = arrayOf(
        "monospace",
        "Roboto Mono",
        "Droid Sans Mono",
        "Noto Sans Mono",
        "DejaVu Sans Mono",
        "Cutive Mono",
        "Courier New",
    )

    /**
     * 校验用探针：含最宽（M/W/@）与最窄（i/l/./:）字符。
     * 等宽字体下这些字宽必须一致；比例字体下差异极大。
     */
    private const val PROBE = "MWilm0O.:/|#@ "

    /**
     * 兜底估算平均字宽用的探针：贴近终端正文（数字、小写字母、常见符号），
     * 使「一屏能放多少列」接近真实正文的容纳量。
     */
    private const val WIDTH_PROBE = "0123456789abcdefghijklmnopqrstuvwxyz .,:;/-_()[]"

    /** 校验字号：取足够大以降低取整误差 */
    private const val VALIDATE_SIZE = 100f

    /** 视为等宽的字宽相对误差上限 */
    private const val TOLERANCE = 0.02f

    private val resolved: Resolved by lazy { resolve() }

    /** 全局选定的终端字体（进程内只解析一次） */
    val typeface: Typeface get() = resolved.typeface

    /** 选中的字体是否严格等宽 */
    val fixedPitch: Boolean get() = resolved.fixedPitch

    /** 诊断用描述（写入诊断日志，便于反馈定位字体问题） */
    val description: String
        get() = "${resolved.name} / ${if (resolved.fixedPitch) "fixed-pitch" else "proportional"}"

    /**
     * 按当前字号计算单元格度量。
     * @param paint 度量用画笔（方法内会设置其 typeface/textSize）
     * @param textSizePx 当前字号（像素）
     */
    fun metrics(paint: Paint, textSizePx: Float): Metrics {
        paint.typeface = resolved.typeface
        paint.textSize = textSizePx
        if (resolved.fixedPitch) {
            // 真等宽：任一字符宽度即单元格宽度（用探针平均消除单个字形的取整误差）
            return Metrics(cellW = probeAverages(paint, PROBE).coerceAtLeast(1f), fixedPitch = true)
        }
        // 无可用等宽字体：用正文探针的平均字宽，使列数贴近屏幕实际可容纳量
        val avg = probeAverages(paint, WIDTH_PROBE)
        val fallback = avg.takeIf { it > 0f } ?: paint.measureText("W")
        return Metrics(cellW = fallback.coerceAtLeast(1f), fixedPitch = false)
    }

    /** 逐字符测量并取平均（忽略零宽字符） */
    private fun probeAverages(paint: Paint, probe: String): Float {
        var sum = 0f
        var n = 0
        for (c in probe) {
            val w = paint.measureText(c.toString())
            if (w > 0f) {
                sum += w
                n++
            }
        }
        return if (n == 0) 0f else sum / n
    }

    /** 字宽一致性校验：探针字符宽度全部接近才算等宽 */
    private fun isFixedPitch(tf: Typeface): Boolean {
        val p = Paint()
        p.typeface = tf
        p.textSize = VALIDATE_SIZE
        var min = Float.MAX_VALUE
        var max = 0f
        for (c in PROBE) {
            val w = p.measureText(c.toString())
            if (w <= 0f) continue
            if (w < min) min = w
            if (w > max) max = w
        }
        if (max <= 0f) return false
        return (max - min) <= max * TOLERANCE
    }

    /** 依次尝试系统字体文件与家族名，返回首个通过等宽校验的字体 */
    private fun resolve(): Resolved {
        for (path in FONT_FILES) {
            val tf = runCatching {
                if (File(path).exists()) Typeface.createFromFile(path) else null
            }.getOrNull() ?: continue
            if (isFixedPitch(tf)) return Resolved(tf, "file:$path", true)
        }
        var best: Pair<Typeface, String>? = null
        for (name in FONT_FAMILIES) {
            val tf = runCatching { Typeface.create(name, Typeface.NORMAL) }.getOrNull() ?: continue
            if (tf === Typeface.DEFAULT) continue
            if (isFixedPitch(tf)) return Resolved(tf, "family:$name", true)
            if (best == null) best = tf to name
        }
        // 系统确实没有等宽字体：交给调用方按平均字宽兜底（fixedPitch=false）
        val fb = best ?: (Typeface.MONOSPACE to "MONOSPACE")
        return Resolved(fb.first, "fallback:${fb.second}", false)
    }
}
