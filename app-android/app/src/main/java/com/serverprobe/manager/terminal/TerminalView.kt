package com.serverprobe.manager.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * 终端渲染 View：等宽字体绘制 + 光标闪烁 + 软/硬键盘输入 → onData 回调发给 SSH。
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var emulator: TerminalEmulator? = null
    var onData: (ByteArray) -> Unit = {}
    /** 终端网格尺寸变化回调（含首次布局与键盘/旋转导致的尺寸变化） */
    var onSizeChanged: ((cols: Int, rows: Int) -> Unit)? = null
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
    private val cursorBounds = RectF()

    private var cellW = textPaint.measureText("W")
    private var cellH = textPaint.fontSpacing
    private var textBaseline = 0f

    private val blinkHandler = object : android.os.Handler(android.os.Looper.getMainLooper()) {}
    private var cursorOn = true
    private val runBuilder = StringBuilder(128)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        cellH = textPaint.fontMetrics.let { it.descent - it.ascent }
        textBaseline = -textPaint.fontMetrics.ascent
        setOnClickListener { showKeyboard() }
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
                // 防抖 200ms：布局连续变化（键盘动画等）只上报最终尺寸
                pendingSize = cols to rows
                debounceHandler.removeCallbacksAndMessages(null)
                debounceHandler.postDelayed({
                    pendingSize?.let { onSizeChanged?.invoke(it.first, it.second) }
                    pendingSize = null
                }, 200)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val emu = emulator ?: return
        val cols = emu.cols
        val rows = emu.rows
        val colorDefaultFg = 0xFFE8EAED.toInt()
        val colorDefaultBg = 0xFF12161F.toInt()

        var y = 0f
        for (row in 0 until rows) {
            var x = 0f
            var col = 0
            while (col < cols) {
                // 同样式游程合并绘制
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

        // 光标
        if (emu.cursorVisible && cursorOn) {
            cursorPaint.color = 0xFF4FD8C4.toInt()
            cursorBounds.set(emu.cx * cellW, emu.cy * cellH, (emu.cx + 1) * cellW, (emu.cy + 1) * cellH)
            cursorPaint.alpha = 110
            canvas.drawRect(cursorBounds, cursorPaint)
        }
        blinkHandler.removeCallbacksAndMessages(null)
        blinkHandler.postDelayed({
            cursorOn = !cursorOn
            invalidate()
        }, 500)
    }

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun send(bytes: ByteArray) = onData(bytes)
    fun send(text: String) = onData(text.toByteArray(Charsets.UTF_8))

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
        // Ctrl+字母
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
                if (text != null) {
                    send(text.toString().replace("\n", "\r"))
                }
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
