package com.serverprobe.manager.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
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
    private val debounceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSize: Pair<Int, Int>? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = spToPx(14f)
    }
    private val bgPaint = Paint()
    private val cursorPaint = Paint().apply { style = Paint.Style.FILL }
    private val selectPaint = Paint().apply { style = Paint.Style.FILL }
    private val cursorBounds = RectF()
    private val selectBounds = RectF()

    private var cellW = textPaint.measureText("W")
    private var cellH = textPaint.fontSpacing
    private var textBaseline = 0f

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
        cellH = textPaint.fontMetrics.let { it.descent - it.ascent }
        textBaseline = -textPaint.fontMetrics.ascent
    }

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    fun setTextSizePx(px: Float) {
        textPaint.textSize = px
        cellW = textPaint.measureText("W")
        cellH = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
        textBaseline = -textPaint.fontMetrics.ascent
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (width > 0 && height > 0) {
            val cols = (width / cellW).toInt().coerceIn(20, 300)
            val rows = (height / cellH).toInt().coerceIn(5, 100)
            if (cols != reportedCols || rows != reportedRows) {
                reportedCols = cols
                reportedRows = rows
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
                runBuilder.setLength(0)
                for (i in col until end) runBuilder.append(emu.charAt(row, i))
                if (runBuilder.isNotBlank()) canvas.drawText(runBuilder, 0, runBuilder.length, x, y + textBaseline, textPaint)
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
