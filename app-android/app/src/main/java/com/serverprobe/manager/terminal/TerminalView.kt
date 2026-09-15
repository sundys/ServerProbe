package com.serverprobe.manager.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.util.TypedValue
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 终端渲染 View：
 *  - 配色跟随 App 日夜主题（light=true 白底黑字）
 *  - 光标跟随滚动：可视窗口自动平移，保证光标（提示符）始终可见
 *  - 触控上滑回看历史，回看时右下角出现"回到底部 ↓"按钮
 *  - 长按进入选择模式，点选起点/终点后复制到剪贴板
 *  - 双指捏合或快捷键调字号（字号由外部持久化）
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var emulator: TerminalEmulator? = null
    var onData: (ByteArray) -> Unit = {}
    /** 终端网格尺寸变化回调（含首次布局与旋转导致的尺寸变化） */
    var onSizeChanged: ((cols: Int, rows: Int) -> Unit)? = null
    /** 键入任意内容时回调（用于自动回到底部/清选择） */
    var onUserInput: (() -> Unit)? = null
    /** 选区状态变化回调（界面据此显示/隐藏"复制选中"按钮） */
    var onSelectionChanged: ((Boolean) -> Unit)? = null
    /** 主题变化时由外部设置 */
    var lightTheme: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    /** 字号变化回调（外部持久化） */
    var onFontSizeChanged: ((Float) -> Unit)? = null

    private var reportedCols = 0
    private var reportedRows = 0
    private var diagnized = false
    private val debounceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSize: Pair<Int, Int>? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = TerminalFont.typeface
        textSize = spToPx(14f)
    }

    /** 度量专用画笔：绘制路径会临时改 textScaleX，不能污染字宽测量 */
    private val measurePaint = Paint().apply { typeface = textPaint.typeface }
    private val bgPaint = Paint()
    private val cursorPaint = Paint().apply { style = Paint.Style.FILL }
    private val selectPaint = Paint().apply { style = Paint.Style.FILL }
    private val cursorBounds = RectF()
    private val selectBounds = RectF()

    /** 是否选到了真正等宽的字体（决定绘制路径） */
    private var fixedPitch = TerminalFont.fixedPitch

    /** 单元格宽度：必须等于每个字符的绘制步长（否则列数与屏宽对不上） */
    private var cellW = 1f
    private var cellH = 1f
    private var textBaseline = 0f

    /** 单字符宽度缓存（非等宽字体兜底绘制用） */
    private val charWidths = HashMap<Char, Float>(128)
    private val singleChar = CharArray(1)

    private val blinkHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var cursorOn = true
    /** 光标闪烁：独立定时器，只在 View 附着窗口期间运行 */
    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            invalidate()
            blinkHandler.postDelayed(this, BLINK_MS)
        }
    }
    private val runBuilder = StringBuilder(128)

    // ---- 触控滚动 / 选择 ----
    private var scrollRow = 0 // 0=贴底（跟随输出），>0=向上回看的行数
    private var selecting = false
    private var selectStart: Pair<Int, Int>? = null // (row,col)
    private var selectEnd: Pair<Int, Int>? = null

    // 手势状态：长按选择自己计时，不再用 GestureDetector。
    // 原因：SimpleOnGestureListener.onDown() 默认返回 false，而本 View 不可点击时
    // super.onTouchEvent() 也返回 false，于是 ACTION_DOWN 被判定为"未消费"——
    // View 再也收不到 MOVE/UP，GestureDetector 内部的长按定时器永不被取消，
    // 结果每次轻点都会在超时后触发 onLongPress 进入选择模式，且 selecting 一旦为真
    // 就永不退出："轻点=编辑（拉起输入法）"的路径被彻底堵死。
    private var downX = 0f
    private var downY = 0f
    private var lastMoveY = 0f
    private var scrollAccum = 0f
    private var dragged = false        // 本次手势位移已超过 slop
    private var longPressFired = false // 本次手势已进入长按（=选择模式）
    private var showRetries = 0        // 输入法拉起重试计数
    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!dragged && emulator != null) {
            longPressFired = true
            selectStart = hitCell(downX, downY)
            selectEnd = selectStart
            setSelecting(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    private fun visibleRows(): Int = (height / cellH).toInt().coerceAtLeast(1)

    private fun hitCell(x: Float, y: Float): Pair<Int, Int> {
        val emu = emulator ?: return 0 to 0
        val row = (y / cellH).toInt().coerceIn(0, visibleRows() - 1)
        // 屏幕行 → 缓冲区行
        val bufRow = row + topRow(emu)
        var col = (x / cellW).toInt().coerceIn(0, emu.cols - 1)
        // 落在宽字符右半格时吸附回左半格，选区/定位不会切在汉字中间
        if (col > 0 && bufRow in 0 until emu.rows &&
            emu.charAt(bufRow, col) == TerminalEmulator.WIDE_TAIL
        ) {
            col--
        }
        return bufRow to col
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        updateCellMetrics()
    }

    /**
     * 重算单元格度量。字体/字号变化后必须调用，否则「列数 ↔ 屏宽」会失配。
     */
    private fun updateCellMetrics() {
        val m = TerminalFont.metrics(measurePaint, textPaint.textSize)
        fixedPitch = m.fixedPitch
        cellW = m.cellW
        charWidths.clear()
        val fm = textPaint.fontMetrics
        cellH = fm.descent - fm.ascent
        textBaseline = -fm.ascent
    }

    /** 单字符/整字宽度（逐帧复用缓存，避免重复测量；s 用于代理对） */
    private fun widthOf(c: Char, s: String = c.toString()): Float = charWidths.getOrPut(c) {
        measurePaint.textSize = textPaint.textSize
        measurePaint.measureText(s).coerceAtLeast(textPaint.textSize * 0.05f)
    }

    /** 取出一个格子对应的完整字形：代理对（emoji）需两级一起绘制 */
    private fun glyphAt(emu: TerminalEmulator, row: Int, col: Int): String {
        val c = emu.charAt(row, col)
        if (c.isHighSurrogate() && col + 1 < emu.cols) {
            val low = emu.charAt(row, col + 1)
            if (low.isLowSurrogate()) return charArrayOf(c, low).concatToString()
        }
        return c.toString()
    }

    /** 当前 View 能容纳的网格尺寸 */
    private fun currentGrid(): Pair<Int, Int> {
        val cols = (width / cellW).toInt().coerceIn(20, 300)
        val rows = (height / cellH).toInt().coerceIn(5, 200)
        return cols to rows
    }

    /**
     * 立即上报当前网格尺寸（不等防抖）。
     * 连接建立后由 UI 调用一次，确保 PTY 最终一定等于屏幕真实列数——
     * 握手期间的尺寸上报发生在 emulator 就绪之前，不能作为最终依据。
     */
    fun reportSizeNow() {
        if (width <= 0 || height <= 0) return
        val (cols, rows) = currentGrid()
        reportedCols = cols
        reportedRows = rows
        pendingSize = null
        debounceHandler.removeCallbacksAndMessages(null)
        onSizeChanged?.invoke(cols, rows)
    }

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    fun setTextSizePx(px: Float) {
        // 幂等：Compose 每次重组都会调用，字号未变时直接返回，
        // 避免反复 requestLayout 造成无谓的布局与尺寸上报
        if (px > 0f && kotlin.math.abs(px - textPaint.textSize) < 0.01f) return
        textPaint.textSize = px
        updateCellMetrics()
        // 字号变化会改变每行列数，重新上报网格尺寸
        if (width > 0 && height > 0) {
            reportedCols = 0 // 强制下轮 onLayout 重新上报
            requestLayout()
        }
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (width > 0 && height > 0) {
            val (cols, rows) = currentGrid()
            if (cols != reportedCols || rows != reportedRows) {
                reportedCols = cols
                reportedRows = rows
                if (!diagnized) {
                    // 字号 / 字体 / 列数是"折行错乱、只占半边屏"类问题的关键取证信息
                    diagnized = true
                    com.serverprobe.manager.Diagnostics.log(
                        context,
                        "terminal grid ${cols}x$rows, view ${width}x$height, " +
                            "font=${TerminalFont.description}, cell=${cellW}x$cellH, sp=${textPaint.textSize}",
                    )
                }
                // 防抖 200ms：布局连续变化只上报最终尺寸
                pendingSize = cols to rows
                debounceHandler.removeCallbacksAndMessages(null)
                debounceHandler.postDelayed({
                    pendingSize?.let { onSizeChanged?.invoke(it.first, it.second) }
                    pendingSize = null
                }, 200)
            }
        }
    }

    /** 可视窗口的第一行（缓冲区行号）。光标在可视区外时自动平移使其可见。 */
    private fun topRow(emu: TerminalEmulator): Int {
        val visRows = visibleRows()
        var top = emu.rows - visRows - scrollRow
        // 光标跟随：光标行不在可视窗口内时，平移窗口使其贴底/贴顶可见
        if (emu.cy < top || emu.cy >= top + visRows) {
            top = (emu.cy - visRows + 1).coerceAtLeast(0)
        }
        return top.coerceIn(0, (emu.rows - visRows).coerceAtLeast(0))
    }

    override fun onDraw(canvas: Canvas) {
        val emu = emulator ?: return
        val cols = emu.cols
        val visRows = visibleRows()
        val top = topRow(emu)
        val colorDefaultFg = if (lightTheme) 0xFF1A1C22.toInt() else 0xFFE8EAED.toInt()
        val colorDefaultBg = if (lightTheme) 0xFFF7F8FA.toInt() else 0xFF12161F.toInt()

        canvas.drawColor(colorDefaultBg)

        var y = 0f
        for (visRow in 0 until visRows) {
            val row = top + visRow
            if (row >= emu.rows) break
            var x = 0f
            var col = 0
            while (col < cols) {
                val fg = emu.fgAt(row, col)
                val bg = emu.bgAt(row, col)
                val attr = emu.attrAt(row, col)
                var end = col + 1
                while (end < cols && emu.fgAt(row, end) == fg && emu.bgAt(row, end) == bg && emu.attrAt(row, end) == attr) {
                    end++
                }
                // 取色一律走 colorOf()：调色板是裸 RGB，直接赋给 Paint.color 会被
                // 按 ARGB 解释（alpha=0）→ 全透明，于是脚本里用颜色包起来的文字
                // （菜单序号、状态取值）与所有 ANSI 背景色块都画不出来。
                var fgColor = when {
                    fg == TerminalEmulator.DEFAULT_FG -> colorDefaultFg
                    else -> TerminalEmulator.colorOf(fg)
                }
                val bgColor = when {
                    bg == TerminalEmulator.DEFAULT_BG -> colorDefaultBg
                    else -> TerminalEmulator.colorOf(bg)
                }
                val bold = (attr and 1) != 0
                // SGR 1（粗体）：真实终端会把 0-7 号基础前景色提升为 8-15 号亮色。
                // 少了这一步，`\e[1;30m`（脚本常用作"暗色标签"）就成了纯黑，
                // 在深色底上几乎看不见。
                if (bold && fg in 0..7) fgColor = TerminalEmulator.colorOf(fg + 8)
                // SGR 7（反显）：前景与背景互换。此前解析了却从未参与渲染，
                // 于是用反显做的"选中高亮"看不到任何高亮。
                val reverse = (attr and 4) != 0
                val cellFg = if (reverse) bgColor else fgColor
                val cellBg = if (reverse) fgColor else bgColor
                if (cellBg != colorDefaultBg) {
                    bgPaint.color = cellBg
                    canvas.drawRect(x, y, x + (end - col) * cellW, y + cellH, bgPaint)
                }
                textPaint.color = cellFg
                textPaint.isFakeBoldText = bold
                textPaint.isUnderlineText = (attr and 2) != 0
                if (fixedPitch) {
                    textPaint.textScaleX = 1f
                    drawRun(canvas, emu, row, col, end, y + textBaseline)
                } else {
                    // 系统没有可用的等宽字体：逐字符画进自己的格子（横向压缩到格宽），
                    // 保证列对齐且不越格——否则同一行的字符步长各不相同，整屏错列
                    drawInCells(canvas, emu, row, col, end, y + textBaseline)
                }
                x += (end - col) * cellW
                col = end
            }
            y += cellH
        }

        // 选择高亮
        if (selecting && selectStart != null && selectEnd != null) {
            selectPaint.color = 0x664FD8C4
            val (r1, c1) = selectStart!!
            val (r2, c2) = selectEnd!!
            val (rowA, rowB) = if (r1 <= r2) r1 to r2 else r2 to r1
            for (row in rowA..rowB) {
                val visRow = row - top
                if (visRow < 0 || visRow >= visRows) continue
                val colA: Int
                val colB: Int
                if (row == rowA && row == rowB) { colA = minOf(c1, c2); colB = maxOf(c1, c2) }
                else if (row == rowA) { colA = c1; colB = cols - 1 }
                else if (row == rowB) { colA = 0; colB = c2 }
                else { colA = 0; colB = cols - 1 }
                selectBounds.set(
                    colA * cellW, visRow * cellH,
                    (colB + 1) * cellW, (visRow + 1) * cellH,
                )
                canvas.drawRect(selectBounds, selectPaint)
            }
        }

        // 光标（只在光标行可见时绘制，跟随滚动已保证其在窗口内）
        val cursorVisRow = emu.cy - top
        if (emu.cursorVisible && cursorVisRow in 0 until visRows && cursorOn) {
            cursorPaint.color = if (lightTheme) 0xFF17B8A6.toInt() else 0xFF4FD8C4.toInt()
            cursorBounds.set(emu.cx * cellW, cursorVisRow * cellH, (emu.cx + 1) * cellW, (cursorVisRow + 1) * cellH)
            cursorPaint.alpha = 110
            canvas.drawRect(cursorBounds, cursorPaint)
        }
        // 光标闪烁由独立的 blinkRunnable 驱动，这里不再投递重绘消息——
        // 旧实现每次绘制都 removeCallbacks + postDelayed，等于让持续刷新的
        // 终端永远无法结束重绘循环，白白占用主线程。
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blinkHandler.removeCallbacks(blinkRunnable)
        blinkHandler.postDelayed(blinkRunnable, BLINK_MS)
    }

    override fun onDetachedFromWindow() {
        blinkHandler.removeCallbacks(blinkRunnable)
        debounceHandler.removeCallbacksAndMessages(null)
        longPressHandler.removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    /**
     * 绘制同属性的一段。
     *
     * **窄字符**才合并成一次 drawText（性能）；**宽字符**（汉字/假名/全角/emoji）
     * 必须逐个绘制并横向缩放到正好两格——否则其后字符的起点由字体自然步长决定，
     * 与网格（列 × cellW）错开，光标与选区就都对不上字形。
     */
    private fun drawRun(canvas: Canvas, emu: TerminalEmulator, row: Int, from: Int, to: Int, baseline: Float) {
        var i = from
        while (i < to) {
            val ch = emu.charAt(row, i)
            when (TerminalEmulator.cellWidth(ch)) {
                0 -> i++ // 宽字符右半格占位符 / 零宽字符：由左半格整字覆盖
                2 -> {
                    drawWide(canvas, emu, row, i, baseline)
                    val hasTail = i + 1 < emu.cols && emu.charAt(row, i + 1) == TerminalEmulator.WIDE_TAIL
                    i += if (hasTail) 2 else 1
                }
                else -> {
                    var j = i + 1
                    while (j < to && TerminalEmulator.cellWidth(emu.charAt(row, j)) == 1) j++
                    runBuilder.setLength(0)
                    for (k in i until j) runBuilder.append(emu.charAt(row, k))
                    if (runBuilder.isNotBlank()) {
                        canvas.drawText(runBuilder, 0, runBuilder.length, i * cellW, baseline, textPaint)
                    }
                    i = j
                }
            }
        }
    }

    /** 宽字符：横向缩放到正好两个单元格宽，与网格严格对齐 */
    private fun drawWide(canvas: Canvas, emu: TerminalEmulator, row: Int, col: Int, baseline: Float) {
        val s = glyphAt(emu, row, col)
        val adv = widthOf(s[0], s)
        val target = if (col + 1 < emu.cols) cellW * 2f else cellW // 末列没有右半格
        textPaint.textScaleX = (target / adv).coerceIn(0.4f, 2.5f)
        canvas.drawText(s, 0, s.length, col * cellW, baseline, textPaint)
        textPaint.textScaleX = 1f
    }

    /** 非等宽字体兜底：把字符压进各自的单元格，列位置固定为 col * cellW */
    private fun drawInCells(canvas: Canvas, emu: TerminalEmulator, row: Int, from: Int, to: Int, baseline: Float) {
        var col = from
        while (col < to) {
            val ch = emu.charAt(row, col)
            when (TerminalEmulator.cellWidth(ch)) {
                0 -> col++ // 占位符：由左半格覆盖
                2 -> {
                    drawWide(canvas, emu, row, col, baseline)
                    col += 2
                }
                else -> {
                    if (ch != ' ') {
                        val adv = widthOf(ch)
                        textPaint.textScaleX = (cellW / adv).coerceIn(0.35f, 1.6f)
                        singleChar[0] = ch
                        canvas.drawText(singleChar, 0, 1, col * cellW, baseline, textPaint)
                    }
                    col++
                }
            }
        }
        textPaint.textScaleX = 1f
    }

    /** 选区文本（供复制） */
    fun selectedText(): String? {
        if (!selecting || selectStart == null || selectEnd == null) return null
        val emu = emulator ?: return null
        val (r1, c1) = selectStart!!
        val (r2, c2) = selectEnd!!
        val (rowA, rowB) = if (r1 <= r2) r1 to r2 else r2 to r1
        val sb = StringBuilder()
        for (row in rowA..rowB) {
            if (row >= emu.rows) break
            val colA: Int
            val colB: Int
            if (row == rowA && row == rowB) { colA = minOf(c1, c2); colB = maxOf(c1, c2) }
            else if (row == rowA) { colA = c1; colB = emu.cols - 1 }
            else if (row == rowB) { colA = 0; colB = c2 }
            else { colA = 0; colB = emu.cols - 1 }
            for (c in colA..colB) {
                val ch = emu.charAt(row, c)
                if (ch != TerminalEmulator.WIDE_TAIL) sb.append(ch) // 跳过宽字符右半格占位符
            }
            sb.append('\n')
        }
        val text = sb.toString().trimEnd('\n')
        return text.ifBlank { null }
    }

    fun clearSelection() {
        setSelecting(false)
        invalidate()
    }

    private fun setSelecting(value: Boolean) {
        if (selecting == value) return
        selecting = value
        if (!value) {
            selectStart = null
            selectEnd = null
        }
        onSelectionChanged?.invoke(selecting)
    }

    val isSelecting: Boolean get() = selecting

    fun scrollToBottom() {
        scrollRow = 0
        invalidate()
    }

    fun send(bytes: ByteArray) = onData(bytes)
    fun send(text: String) {
        clearSelection()
        scrollRow = 0
        onUserInput?.invoke()
        onData(text.toByteArray(Charsets.UTF_8))
    }

    // ---- 触控 ----
    //
    // 交互约定（与界面上的菜单一一对应）：
    //   轻点        → 编辑：聚焦 + 拉起输入法（若在回看历史则先回到底部）
    //   长按        → 进入选择模式（浮出「复制选中 / 取消」，抬手后保持）
    //   按住拖动    → 选择模式下扩展选区；否则上下滚动回看历史
    //
    // 必须返回 true 消费 ACTION_DOWN：View 只有在 DOWN 时"吃掉"事件才会被父容器
    // 记为本次手势的触摸目标，否则收不到后续 MOVE/UP——长按定时器也就永远无法
    // 被取消（详见字段区注释）。

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastMoveY = event.y
                scrollAccum = 0f
                dragged = false
                longPressFired = false
                longPressHandler.removeCallbacks(longPressRunnable)
                longPressHandler.postDelayed(
                    longPressRunnable,
                    ViewConfiguration.getLongPressTimeout().toLong(),
                )
                // 终端自己处理纵向拖动（回看历史 / 拖选），不让外层容器抢走手势
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (!dragged &&
                    (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop)
                ) {
                    dragged = true
                    // 已开始拖动 = 不是长按，撤销计时（否则拖到一半会中途弹出选择菜单）
                    longPressHandler.removeCallbacks(longPressRunnable)
                }
                if (dragged) {
                    if (selecting) {
                        selectEnd = hitCell(event.x, event.y)
                    } else {
                        val emu = emulator
                        if (emu != null) {
                            // 累加浮点位移再取整，避免每个 MOVE 的小位移都被截断丢掉
                            scrollAccum += event.y - lastMoveY
                            val rows = (scrollAccum / cellH).toInt()
                            if (rows != 0) {
                                val maxScroll = (emu.rows - visibleRows()).coerceAtLeast(0)
                                val next = (scrollRow + rows).coerceIn(0, maxScroll)
                                if (next != scrollRow) {
                                    scrollRow = next
                                    scrollAccum -= rows * cellH
                                } else {
                                    scrollAccum = 0f
                                }
                            }
                        }
                    }
                    invalidate()
                }
                lastMoveY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                when {
                    // 长按进入的选择模式：抬手后**保留**选区与菜单，等用户明确点
                    // 「复制选中」或「取消」——旧的"抬手即清空"会让长按白按一场。
                    longPressFired -> {
                        selectEnd = hitCell(event.x, event.y)
                        invalidate()
                    }
                    // 拖动结束（滚动回看 / 拖选扩展）：保持现状
                    dragged -> {
                        if (selecting) {
                            selectEnd = hitCell(event.x, event.y)
                            invalidate()
                        }
                    }
                    // 轻点 = 编辑
                    else -> {
                        if (selecting) setSelecting(false)
                        if (scrollRow > 0) scrollRow = 0
                        invalidate()
                        focusAndShowKeyboard()
                    }
                }
                dragged = false
                longPressFired = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                dragged = false
                longPressFired = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    /**
     * 聚焦并拉起输入法。触控回调里同步调用 `showSoftInput` 在部分 ROM 上会被丢弃
     * （IMM 服务对"当前输入目标视图"的登记是异步的），因此统一延后一帧。
     */
    fun focusAndShowKeyboard() {
        requestFocus()
        post { showKeyboard() }
    }

    /**
     * 拉起输入法。三重保障，缺一不可：
     *  1. `restartInput` —— 本 View 的 `inputType` 是 `TYPE_NULL`，没有可编辑文本，
     *     部分输入法据此认为"无需弹出"；该调用强制其重新读取一次输入连接。
     *  2. `WindowInsetsControllerCompat.show(ime())` —— 官方推荐通道，由系统在窗口
     *     获得焦点后调度显示。用户按返回键收起键盘后，这是唯一可靠的再次拉起方式。
     *  3. `showSoftInput(view, 0)` + 有限重试 —— 兼容通道。**不使用 SHOW_FORCED**：
     *     官方明确不建议（键盘可能在 App 退出后残留），且新版本已被忽略。
     *     IMM 对目标视图的登记是异步的，过早调用会返回 false 并被静默丢弃，故重试。
     */
    fun showKeyboard() {
        if (!isAttachedToWindow) return
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        runCatching { imm.restartInput(this) }
        hostWindow()?.let { win ->
            runCatching {
                WindowCompat.getInsetsController(win, this)
                    .show(WindowInsetsCompat.Type.ime())
            }
        }
        val shown = runCatching { imm.showSoftInput(this, 0) }.getOrDefault(false)
        if (!shown && showRetries < MAX_SHOW_RETRIES) {
            showRetries++
            postDelayed({ showKeyboard() }, 80L * showRetries)
        } else {
            showRetries = 0
        }
    }

    fun hideKeyboard() {
        // 阻断尚未执行完的延迟重试，否则键盘会被刚隐藏又立刻拉起
        showRetries = MAX_SHOW_RETRIES
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        runCatching { imm.hideSoftInputFromWindow(windowToken, 0) }
        hostWindow()?.let { win ->
            runCatching {
                WindowCompat.getInsetsController(win, this).hide(WindowInsetsCompat.Type.ime())
            }
        }
    }

    private fun imeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /** 供控制键条上的「键盘」键使用：输入法可见则收起，否则拉起 */
    fun toggleKeyboard() {
        if (imeVisible()) hideKeyboard() else focusAndShowKeyboard()
    }

    /** 从 View 的 Context 链上找出宿主 Window（WindowInsetsController 需要它） */
    private fun hostWindow(): android.view.Window? {
        var c: Context? = context
        var guard = 0
        while (c is android.content.ContextWrapper && guard++ < 10) {
            if (c is android.app.Activity) return c.window
            val base = c.baseContext
            if (base === c) return null
            c = base
        }
        return null
    }

    // ---- 输入 ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val seq = when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007F"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001B[3~"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001B[H"
            KeyEvent.KEYCODE_MOVE_END -> "\u001B[F"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~"
            KeyEvent.KEYCODE_DPAD_UP -> "\u001B[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B[D"
            else -> null
        }
        if (seq != null) {
            send(seq)
            return true
        }
        if (event != null && event.isCtrlPressed && event.isPrintingKey) {
            val ch = event.unicodeChar
            if (ch in 0x61..0x7A || ch in 0x41..0x5A) {
                send(byteArrayOf((ch and 0x1F).toByte()))
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text != null) send(text.toString().replace("\n", "\r"))
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // 部分输入法在获得/失去焦点时会用极大的 beforeLength 调用来"清空编辑区"，
                // 旧实现逐个 send 会产生成千上万次写入：合并成一次写入并设上限，
                // 避免瞬间冲垮 SSH 通道（也会连带把连接写崩）。
                val n = beforeLength.coerceIn(0, MAX_IME_BACKSPACES)
                if (n > 0) send(ByteArray(n) { 0x7F })
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                send("\r")
                return true
            }
        }
    }

    private companion object {
        /** 单次删除请求最多回退的字符数（防御输入法异常大值） */
        const val MAX_IME_BACKSPACES = 64

        /** 光标闪烁周期 */
        const val BLINK_MS = 500L

        /** 拉起输入法失败后的最大重试次数 */
        const val MAX_SHOW_RETRIES = 3
    }
}
