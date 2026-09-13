package com.serverprobe.manager.terminal

/**
 * 精简 VT100/xterm 终端模拟器：UTF-8 输入、ANSI/CSI、SGR(16/256色)、滚动区域、备用屏、
 * 光标控制、响应 DA/DSR。配合 TerminalView 渲染。
 */
class TerminalEmulator(
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val responder: (ByteArray) -> Unit = {},
) {

    companion object {
        const val DEFAULT_FG = -1
        const val DEFAULT_BG = -2

        /** xterm 256 色板 */
        val PALETTE: IntArray = buildPalette()

        private fun buildPalette(): IntArray {
            val base = intArrayOf(
                0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
                0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
            )
            val p = IntArray(256) { if (it < 16) base[it] else 0 }
            val levels = intArrayOf(0, 95, 135, 175, 215, 255)
            var idx = 16
            for (r in 0..5) for (g in 0..5) for (b in 0..5) {
                p[idx++] = (levels[r] shl 16) or (levels[g] shl 8) or levels[b]
            }
            for (i in 0..23) {
                val v = 8 + 10 * i
                p[idx++] = (v shl 16) or (v shl 8) or v
            }
            return p
        }
    }

    private class Buffer(val cols: Int, val rows: Int) {
        val chars = CharArray(cols * rows) { ' ' }
        val fg = IntArray(cols * rows) { DEFAULT_FG }
        val bg = IntArray(cols * rows) { DEFAULT_BG }
        val attr = ByteArray(cols * rows) // bit0 bold bit1 underline bit2 reverse

        fun clearLine(y: Int, fromX: Int = 0, toX: Int = cols - 1) {
            if (y < 0 || y >= rows) return
            for (x in fromX..toX) {
                if (x < 0 || x >= cols) continue
                chars[y * cols + x] = ' '
                fg[y * cols + x] = DEFAULT_FG
                bg[y * cols + x] = DEFAULT_BG
                attr[y * cols + x] = 0
            }
        }

        fun clearAll() {
            for (y in 0 until rows) clearLine(y)
        }
    }

    var cols: Int = initialCols
        private set
    var rows: Int = initialRows
        private set

    private var main = Buffer(cols, rows)
    private var alt = Buffer(cols, rows)
    private var useAlt = false
    private val buf: Buffer get() = if (useAlt) alt else main

    var cx = 0
        private set
    var cy = 0
        private set
    var cursorVisible = true
        private set
    var autoWrap = true
    var bracketedPaste = false
        private set

    private var scrollTop = 0
    private var scrollBottom = rows - 1

    // SGR 当前属性
    private var curFg = DEFAULT_FG
    private var curBg = DEFAULT_BG
    private var curAttr = 0

    private var savedMainCx = 0
    private var savedMainCy = 0
    private var savedAltCx = 0
    private var savedAltCy = 0

    private var wrapPending = false

    // 转义序列状态
    private enum class State { NONE, ESC, CSI, OSC, CHARSET }
    private var state = State.NONE
    private val params = StringBuilder()
    private var privatePrefix = false
    private var intermediates = StringBuilder()

    // 增量 UTF-8 解码状态（多字节序列可跨数据块）
    private var utf8Accum = 0
    private var utf8Need = 0

    private fun resetUtf8() {
        utf8Accum = 0
        utf8Need = 0
    }

    private fun feedByte(b: Int) {
        if (utf8Need > 0) {
            if (b and 0xC0 == 0x80) {
                utf8Accum = (utf8Accum shl 6) or (b and 0x3F)
                utf8Need--
                if (utf8Need == 0) {
                    val cp = utf8Accum
                    resetUtf8()
                    if (cp > 0xFFFF) {
                        // 代理对
                        val v = cp - 0x10000
                        process(((0xD800 + (v shr 10)) and 0xFFFF).toChar())
                        process(((0xDC00 + (v and 0x3FF)) and 0xFFFF).toChar())
                    } else {
                        process(cp.toChar())
                    }
                }
            } else {
                // 残缺序列后紧跟非法字节：替换并重新解析当前字节
                resetUtf8()
                process('\uFFFD')
                feedByte(b)
            }
        } else when {
            b < 0x80 -> process(b.toChar())
            b and 0xE0 == 0xC0 -> { utf8Accum = b and 0x1F; utf8Need = 1 }
            b and 0xF0 == 0xE0 -> { utf8Accum = b and 0x0F; utf8Need = 2 }
            b and 0xF8 == 0xF0 -> { utf8Accum = b and 0x07; utf8Need = 3 }
            else -> process('\uFFFD')
        }
    }

    var dirty = true
        private set

    fun markClean() {
        dirty = false
    }

    /** 喂入服务器字节流（增量，UTF-8 多字节可跨块） */
    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        for (b in bytes) feedByte(b.toInt() and 0xFF)
        dirty = true
    }

    private fun process(c: Char) {
        when (state) {
            State.NONE -> when (c) {
                '\u001B' -> {
                    state = State.ESC
                    params.setLength(0)
                    privatePrefix = false
                    intermediates.setLength(0)
                }
                '\r' -> {
                    cx = 0
                    wrapPending = false
                }
                '\n', '\u000B', '\u000C' -> lineFeed()
                '\u0008' -> if (cx > 0) cx--
                '\t' -> cx = ((cx / 8) + 1) * 8
                '\u0007' -> Unit // BEL 忽略
                '\u0000', '\u0001', '\u0002', '\u0003', '\u0004', '\u0005', '\u0006' -> Unit
                else -> if (c >= ' ') putChar(c)
            }
            State.ESC -> processEsc(c)
            State.CSI -> processCsi(c)
            State.CHARSET -> state = State.NONE // 字符集选择符后的一个字节，忽略
            State.OSC -> {
                if (c == '\u0007') state = State.NONE
                else if (c == '\u001B') {
                    // ESC \ (ST) 的 '\' 由下一次 process 消费
                    state = State.ESC
                }
            }
        }
    }

    private fun processEsc(c: Char) {
        when (c) {
            '[' -> state = State.CSI
            ']' -> state = State.OSC
            '7' -> {
                if (useAlt) { savedAltCx = cx; savedAltCy = cy } else { savedMainCx = cx; savedMainCy = cy }
                state = State.NONE
            }
            '8' -> {
                if (useAlt) { cx = savedAltCx; cy = savedAltCy } else { cx = savedMainCx; cy = savedMainCy }
                clampCursor()
                state = State.NONE
            }
            'D' -> { lineFeed(); state = State.NONE }
            'M' -> { reverseLineFeed(); state = State.NONE }
            'E' -> { lineFeed(); cx = 0; state = State.NONE }
            'c' -> { resetAll(); state = State.NONE }
            '(' , ')', '*', '+' -> state = State.CHARSET // 吞掉下一个字符
            '=', '>' -> state = State.NONE
            '\\' -> state = State.NONE // ST
            else -> state = State.NONE
        }
    }

    private fun processCsi(c: Char) {
        when (c) {
            in '0'..'9', ';', ':' -> params.append(c)
            '?' -> privatePrefix = true
            in ' '..'/' -> intermediates.append(c)
            in '@'..'~' -> {
                dispatch(c)
                state = State.NONE
            }
            else -> state = State.NONE
        }
    }

    private fun p(i: Int, def: Int): Int {
        val parts = params.toString().split(';')
        if (i >= parts.size) return def
        val v = parts[i].toIntOrNull() ?: return def
        return if (v == 0) def else v
    }

    private fun allParams(): List<Int> =
        params.toString().split(';').map { it.toIntOrNull() ?: 0 }.filter { true }.ifEmpty { listOf(0) }

    private fun dispatch(final: Char) {
        if (privatePrefix) {
            when (final) {
                'h' -> setPrivateModes(true)
                'l' -> setPrivateModes(false)
            }
            params.setLength(0)
            return
        }
        when (final) {
            'A' -> cy = (cy - p(0, 1)).coerceAtLeast(0)
            'B', 'e' -> cy = (cy + p(0, 1)).coerceAtMost(rows - 1)
            'C', 'a' -> cx = (cx + p(0, 1)).coerceAtMost(cols - 1)
            'D' -> cx = (cx - p(0, 1)).coerceAtLeast(0)
            'E' -> { cy = (cy + p(0, 1)).coerceAtMost(rows - 1); cx = 0 }
            'F' -> { cy = (cy - p(0, 1)).coerceAtLeast(0); cx = 0 }
            'G', '`' -> cx = (p(0, 1) - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                cy = (p(0, 1) - 1).coerceIn(0, rows - 1)
                cx = (p(1, 1) - 1).coerceIn(0, cols - 1)
                wrapPending = false
            }
            'd' -> cy = (p(0, 1) - 1).coerceIn(0, rows - 1)
            'J' -> eraseDisplay(p(0, 0))
            'K' -> eraseLine(p(0, 0))
            'L' -> insertLines(p(0, 1))
            'M' -> deleteLines(p(0, 1))
            '@' -> insertChars(p(0, 1))
            'P' -> deleteChars(p(0, 1))
            'X' -> {
                val n = p(0, 1)
                buf.clearLine(cy, cx, (cx + n - 1).coerceAtMost(cols - 1))
            }
            'm' -> sgr()
            'r' -> {
                scrollTop = (p(0, 1) - 1).coerceIn(0, rows - 1)
                scrollBottom = (p(1, rows) - 1).coerceIn(scrollTop, rows - 1)
                cy = scrollTop
                cx = 0
            }
            'S' -> repeat(p(0, 1)) { scrollUp() }
            'T' -> repeat(p(0, 1)) { scrollDown() }
            's' -> { if (useAlt) { savedAltCx = cx; savedAltCy = cy } else { savedMainCx = cx; savedMainCy = cy } }
            'u' -> { if (useAlt) { cx = savedAltCx; cy = savedAltCy } else { cx = savedMainCx; cy = savedMainCy }; clampCursor() }
            'c' -> responder("\u001B[?62;1;2;6;8;9;15;c".toByteArray()) // DA：报告为 vt102 级
            'n' -> when (p(0, 0)) {
                6 -> responder("\u001B[${cy + 1};${cx + 1}R".toByteArray())
                5 -> responder("\u001B[0n".toByteArray())
            }
            'Z' -> cx = (((cx - 1) / 8) * 8).coerceAtLeast(0)
            'h', 'l' -> Unit // 非 private 模式忽略
        }
        params.setLength(0)
    }

    private fun setPrivateModes(set: Boolean) {
        val list = params.toString().split(';').map { it.toIntOrNull() ?: 0 }
        for (m in list) when (m) {
            25 -> cursorVisible = set
            7 -> autoWrap = set
            2004 -> bracketedPaste = set
            1049 -> {
                if (set) {
                    if (!useAlt) {
                        savedMainCx = cx; savedMainCy = cy
                        useAlt = true
                        alt.clearAll()
                        cx = 0; cy = 0
                        scrollTop = 0; scrollBottom = rows - 1
                    }
                } else {
                    if (useAlt) {
                        useAlt = false
                        cx = savedMainCx; cy = savedMainCy
                        clampCursor()
                        scrollTop = 0; scrollBottom = rows - 1
                    }
                }
            }
            47 -> {
                if (set && !useAlt) { useAlt = true; alt.clearAll() }
                if (!set && useAlt) { useAlt = false }
            }
        }
        params.setLength(0)
    }

    private fun sgr() {
        val codes = params.toString().split(';').map { it.toIntOrNull() ?: 0 }.ifEmpty { listOf(0) }
        var i = 0
        while (i < codes.size) {
            when (val c = codes[i]) {
                0 -> { curFg = DEFAULT_FG; curBg = DEFAULT_BG; curAttr = 0 }
                1 -> curAttr = curAttr or 1
                4 -> curAttr = curAttr or 2
                7 -> curAttr = curAttr or 4
                21, 22 -> curAttr = curAttr and 1.inv()
                24 -> curAttr = curAttr and 2.inv()
                27 -> curAttr = curAttr and 4.inv()
                30, 31, 32, 33, 34, 35, 36, 37 -> curFg = c - 30
                39 -> curFg = DEFAULT_FG
                40, 41, 42, 43, 44, 45, 46, 47 -> curBg = c - 40
                49 -> curBg = DEFAULT_BG
                90, 91, 92, 93, 94, 95, 96, 97 -> curFg = (c - 90) + 8
                100, 101, 102, 103, 104, 105, 106, 107 -> curBg = (c - 100) + 8
                38, 48 -> {
                    var color: Int? = null
                    if (i + 1 < codes.size && codes[i + 1] == 5 && i + 2 < codes.size) {
                        color = codes[i + 2].coerceIn(0, 255)
                        i += 2
                    } else if (i + 1 < codes.size && codes[i + 1] == 2 && i + 4 < codes.size) {
                        val r = codes[i + 2].coerceIn(0, 255)
                        val g = codes[i + 3].coerceIn(0, 255)
                        val b = codes[i + 4].coerceIn(0, 255)
                        color = nearest256(r, g, b)
                        i += 4
                    }
                    if (color != null) {
                        if (c == 38) curFg = color else curBg = color
                    }
                }
            }
            i++
        }
    }

    private fun nearest256(r: Int, g: Int, b: Int): Int {
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        fun nearest(v: Int): Int {
            var best = 0
            var bd = Int.MAX_VALUE
            for (i in levels.indices) {
                val d = Math.abs(levels[i] - v)
                if (d < bd) { bd = d; best = i }
            }
            return best
        }
        return 16 + nearest(r) * 36 + nearest(g) * 6 + nearest(b)
    }

    private fun putChar(c: Char) {
        if (wrapPending) {
            wrapPending = false
            cx = 0
            lineFeed()
        }
        val b = buf
        val idx = cy * cols + cx
        b.chars[idx] = c
        b.fg[idx] = curFg
        b.bg[idx] = curBg
        b.attr[idx] = curAttr.toByte()
        if (cx == cols - 1) {
            if (autoWrap) wrapPending = true
        } else {
            cx++
        }
    }

    private fun lineFeed() {
        if (cy == scrollBottom) scrollUp() else cy = (cy + 1).coerceAtMost(rows - 1)
        wrapPending = false
    }

    private fun reverseLineFeed() {
        if (cy == scrollTop) scrollDown() else cy = (cy - 1).coerceAtLeast(0)
    }

    private fun scrollUp() {
        val b = buf
        for (y in scrollTop until scrollBottom) {
            System.arraycopy(b.chars, (y + 1) * cols, b.chars, y * cols, cols)
            System.arraycopy(b.fg, (y + 1) * cols, b.fg, y * cols, cols)
            System.arraycopy(b.bg, (y + 1) * cols, b.bg, y * cols, cols)
            System.arraycopy(b.attr, (y + 1) * cols, b.attr, y * cols, cols)
        }
        b.clearLine(scrollBottom)
    }

    private fun scrollDown() {
        val b = buf
        for (y in scrollBottom downTo scrollTop + 1) {
            System.arraycopy(b.chars, (y - 1) * cols, b.chars, y * cols, cols)
            System.arraycopy(b.fg, (y - 1) * cols, b.fg, y * cols, cols)
            System.arraycopy(b.bg, (y - 1) * cols, b.bg, y * cols, cols)
            System.arraycopy(b.attr, (y - 1) * cols, b.attr, y * cols, cols)
        }
        b.clearLine(scrollTop)
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                buf.clearLine(cy, cx, cols - 1)
                for (y in cy + 1 until rows) buf.clearLine(y)
            }
            1 -> {
                for (y in 0 until cy) buf.clearLine(y)
                buf.clearLine(cy, 0, cx)
            }
            2, 3 -> buf.clearAll()
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> buf.clearLine(cy, cx, cols - 1)
            1 -> buf.clearLine(cy, 0, cx)
            2 -> buf.clearLine(cy)
        }
    }

    private fun insertLines(n: Int) {
        if (cy < scrollTop || cy > scrollBottom) return
        val b = buf
        var count = n.coerceAtMost(scrollBottom - cy + 1)
        for (y in scrollBottom downTo cy + count) {
            System.arraycopy(b.chars, (y - count) * cols, b.chars, y * cols, cols)
            System.arraycopy(b.fg, (y - count) * cols, b.fg, y * cols, cols)
            System.arraycopy(b.bg, (y - count) * cols, b.bg, y * cols, cols)
            System.arraycopy(b.attr, (y - count) * cols, b.attr, y * cols, cols)
        }
        for (y in cy until cy + count) b.clearLine(y)
        cx = 0
    }

    private fun deleteLines(n: Int) {
        if (cy < scrollTop || cy > scrollBottom) return
        val b = buf
        val count = n.coerceAtMost(scrollBottom - cy + 1)
        for (y in cy..scrollBottom - count) {
            System.arraycopy(b.chars, (y + count) * cols, b.chars, y * cols, cols)
            System.arraycopy(b.fg, (y + count) * cols, b.fg, y * cols, cols)
            System.arraycopy(b.bg, (y + count) * cols, b.bg, y * cols, cols)
            System.arraycopy(b.attr, (y + count) * cols, b.attr, y * cols, cols)
        }
        for (y in scrollBottom - count + 1..scrollBottom) b.clearLine(y)
        cx = 0
    }

    private fun insertChars(n: Int) {
        val b = buf
        val count = n.coerceAtMost(cols - cx)
        for (x in cols - 1 downTo cx + count) {
            val src = cy * cols + x - count
            val dst = cy * cols + x
            b.chars[dst] = b.chars[src]
            b.fg[dst] = b.fg[src]
            b.bg[dst] = b.bg[src]
            b.attr[dst] = b.attr[src]
        }
        buf.clearLine(cy, cx, cx + count - 1)
    }

    private fun deleteChars(n: Int) {
        val b = buf
        val count = n.coerceAtMost(cols - cx)
        for (x in cx until cols - count) {
            val src = cy * cols + x + count
            val dst = cy * cols + x
            b.chars[dst] = b.chars[src]
            b.fg[dst] = b.fg[src]
            b.bg[dst] = b.bg[src]
            b.attr[dst] = b.attr[src]
        }
        buf.clearLine(cy, cols - count, cols - 1)
    }

    private fun resetAll() {
        main.clearAll(); alt.clearAll()
        cx = 0; cy = 0
        curFg = DEFAULT_FG; curBg = DEFAULT_BG; curAttr = 0
        scrollTop = 0; scrollBottom = rows - 1
        useAlt = false
        cursorVisible = true
        autoWrap = true
        bracketedPaste = false
    }

    private fun clampCursor() {
        cx = cx.coerceIn(0, cols - 1)
        cy = cy.coerceIn(0, rows - 1)
    }

    /** 简单缩放：保留左上角内容 */
    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceIn(20, 500)
        val nr = newRows.coerceIn(5, 200)
        if (nc == cols && nr == rows) return
        val newMain = Buffer(nc, nr)
        copyInto(main, newMain)
        val newAlt = Buffer(nc, nr)
        copyInto(alt, newAlt)
        cols = nc; rows = nr
        main = newMain; alt = newAlt
        scrollTop = 0; scrollBottom = nr - 1
        clampCursor()
        dirty = true
    }

    private fun copyInto(src: Buffer, dst: Buffer) {
        val h = minOf(src.rows, dst.rows)
        val w = minOf(src.cols, dst.cols)
        for (y in 0 until h) {
            System.arraycopy(src.chars, y * src.cols, dst.chars, y * dst.cols, w)
            System.arraycopy(src.fg, y * src.cols, dst.fg, y * dst.cols, w)
            System.arraycopy(src.bg, y * src.cols, dst.bg, y * dst.cols, w)
            System.arraycopy(src.attr, y * src.cols, dst.attr, y * dst.cols, w)
        }
    }

    // ---- 渲染读取接口 ----

    fun charAt(row: Int, col: Int): Char = buf.chars[row * cols + col]
    fun fgAt(row: Int, col: Int): Int = buf.fg[row * cols + col]
    fun bgAt(row: Int, col: Int): Int = buf.bg[row * cols + col]
    fun attrAt(row: Int, col: Int): Int = buf.attr[row * cols + col].toInt()

    fun isReverse(row: Int, col: Int): Boolean = (attrAt(row, col) and 4) != 0
    fun isBold(row: Int, col: Int): Boolean = (attrAt(row, col) and 1) != 0

    /** 用于剪贴板：整屏文本 */
    fun dumpScreen(): String {
        val sb = StringBuilder()
        for (y in 0 until rows) {
            var lineEnd = cols
            while (lineEnd > 0 && charAt(y, lineEnd - 1) == ' ') lineEnd--
            for (x in 0 until lineEnd) sb.append(charAt(y, x))
            sb.append('\n')
        }
        return sb.toString()
    }
}
