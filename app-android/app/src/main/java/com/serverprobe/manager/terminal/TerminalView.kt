package com.serverprobe.manager.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.util.TypedValue
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

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

    private val blinkHandler = object : android.os.Handler(android.os.Looper.getMainLooper()) {}
    private var cursorOn = true
    private val runBuilder = StringBuilder(128)

    // ---- 触控滚动 / 选择 ----
    private var scrollRow = 0 // 0=贴底（跟随输出），>0=向上回看的行数
    private var selecting = false
    private var selectStart: Pair<Int, Int>? = null // (row,col)
    private var selectEnd: Pair<Int, Int>? = null
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val emu = emulator ?: return true
                if (selecting) return true
                val maxScroll = (emu.rows - visibleRows()).coerceAtLeast(0)
                scrollRow = (scrollRow + (dy / cellH).toInt()).coerceIn(0, maxScroll)
                invalidate()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                selecting = true
                selectStart = hitCell(e.x, e.y)
                selectEnd = selectStart
                invalidate()
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (selecting) { selecting = false; selectStart = null; selectEnd = null; invalidate() }
                showKeyboard()
                return true
            }
        },
    )

    private fun visibleRows(): Int = (height / cellH).toInt().coerceAtLeast(1)

    private fun hitCell(x: Float, y: Float): Pair<Int, Int> {
        val emu = emulator ?: return 0 to 0
        val col = (x / cellW).toInt().coerceIn(0, emu.cols - 1)
        val row = (y / cellH).toInt().coerceIn(0, visibleRows() - 1)
        // 屏幕行 → 缓冲区行
        return (row + topRow(emu)) to col
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

    /** 单字符宽度（非等宽兜底绘制用，避免逐帧重复测量） */
    private fun widthOf(c: Char): Float = charWidths.getOrPut(c) {
        measurePaint.textSize = textPaint.textSize
        measurePaint.measureText(c.toString()).coerceAtLeast(textPaint.textSize * 0.05f)
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
                val bgColor = when {
                    bg == TerminalEmulator.DEFAULT_BG -> colorDefaultBg
                    else -> TerminalEmulator.PALETTE[bg.coerceIn(0, 255)]
                }
                if (bgColor != colorDefaultBg) {
                    bgPaint.color = bgColor
                    canvas.drawRect(x, y, x + (end - col) * cellW, y + cellH, bgPaint)
                }
                textPaint.color = when {
                    fg == TerminalEmulator.DEFAULT_FG -> colorDefaultFg
                    else -> TerminalEmulator.PALETTE[fg.coerceIn(0, 255)]
                }
                textPaint.isFakeBoldText = (attr and 1) != 0
                textPaint.isUnderlineText = (attr and 2) != 0
                if (fixedPitch) {
                    textPaint.textScaleX = 1f
                    runBuilder.setLength(0)
                    for (i in col until end) runBuilder.append(emu.charAt(row, i))
                    if (runBuilder.isNotBlank()) canvas.drawText(runBuilder, 0, runBuilder.length, x, y + textBaseline, textPaint)
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
        blinkHandler.removeCallbacksAndMessages(null)
        blinkHandler.postDelayed({
            cursorOn = !cursorOn
            invalidate()
        }, 500)
    }

    /** 非等宽字体兜底：把字符压进各自的单元格，列位置固定为 col * cellW */
    private fun drawInCells(canvas: Canvas, emu: TerminalEmulator, row: Int, from: Int, to: Int, baseline: Float) {
        for (col in from until to) {
            val ch = emu.charAt(row, col)
            if (ch == ' ' || ch == '\u0000') continue
            val adv = widthOf(ch)
            textPaint.textScaleX = (cellW / adv).coerceIn(0.35f, 1.6f)
            singleChar[0] = ch
            canvas.drawText(singleChar, 0, 1, col * cellW, baseline, textPaint)
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
            for (c in colA..colB) sb.append(emu.charAt(row, c))
            sb.append('\n')
        }
        val text = sb.toString().trimEnd('\n')
        return text.ifBlank { null }
    }

    fun clearSelection() {
        selecting = false
        selectStart = null
        selectEnd = null
        invalidate()
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (selecting && event.actionMasked == MotionEvent.ACTION_MOVE) {
            selectEnd = hitCell(event.x, event.y)
            invalidate()
            return true
        }
        if (selecting && event.actionMasked == MotionEvent.ACTION_UP) {
            selectEnd = hitCell(event.x, event.y)
            invalidate()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && !selecting) {
            // 点按：聚焦并拉起输入法（重写后丢失的路径）
            requestFocus()
            showKeyboard()
            // 若正在回看历史，点按回到底部
            if (scrollRow > 0) scrollRow = 0
            invalidate()
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
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
                repeat(beforeLength) { send("\u007F") }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                send("\r")
                return true
            }
        }
    }
}
