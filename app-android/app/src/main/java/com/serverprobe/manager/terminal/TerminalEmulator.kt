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

        /**
         * 宽字符右半格的占位符。
         *
         * 缓冲区的每一格固定对应屏幕的一列，而宽字符（汉字/假名/全角标点/emoji）
         * 占**两列**：左格存字符本身，右格存本占位符，绘制时由左格整字覆盖右格。
         */
        const val WIDE_TAIL: Char = '\u0000'

        /**
         * 一个字符占用的列数（East Asian Width）。
         * 0 = 不单独占格（宽字符右半格、组合记号、代理对低位），1 = 半角，2 = 全角。
         *
         * 汉字、假名、全角标点在终端里一律占两列。此前模拟器对每个字符都只前进
         * 一列，于是：
         *  - 光标位置 `cx * cellW` 比实际字形结束位置**左移一半** —— 提示符
         *    「请输入数字：」后面光标停在字中间；
         *  - 自动折行判定偏移，脚本用全角符号画的菜单/表格整体错列；
         *  - 服务端按 `ESC[r;cH` 定位（其 wcwidth 认为汉字占两列）时同样错位。
         */
        fun cellWidth(c: Char): Int {
            if (c == WIDE_TAIL) return 0
            if (c.isLowSurrogate()) return 0 // 代理对低位：并入前一格绘制
            if (c.isHighSurrogate()) return 2 // 补充平面字符（emoji 等）
            val u = c.code
            if (u < 0x0300) return 1 // ASCII 与拉丁扩展
            if (isZeroWidth(u)) return 0
            return if (isWide(u)) 2 else 1
        }

        /** 零宽字符：组合记号、零宽连接符、变体选择符 */
        private fun isZeroWidth(u: Int): Boolean =
            u in 0x0300..0x036F ||
                u in 0x0483..0x0489 ||
                u in 0x1AB0..0x1AFF ||
                u in 0x200B..0x200F ||
                u in 0x2060..0x2064 ||
                u in 0x20D0..0x20FF ||
                u in 0xFE00..0xFE0F ||
                u in 0xFE20..0xFE2F

        /** 东亚宽字符与全角形式 */
        private fun isWide(u: Int): Boolean =
            u in 0x1100..0x115F || // 谚文字母
                u in 0x2E80..0x303E || // 康熙部首 / CJK 部首 / CJK 符号与标点
                u in 0x3041..0x33FF || // 平假名 ~ CJK 兼容
                u in 0x3400..0x4DBF || // CJK 扩展 A
                u in 0x4E00..0x9FFF || // CJK 统一表意
                u in 0xA000..0xA4CF || // 彝文
                u in 0xA960..0xA97F || // 谚文字母扩展 A
                u in 0xAC00..0xD7A3 || // 谚文音节
                u in 0xF900..0xFAFF || // CJK 兼容表意
                u in 0xFE10..0xFE19 || // 竖排标点
                u in 0xFE30..0xFE6F || // CJK 兼容形式 / 小写变体
                u in 0xFF00..0xFF60 || // 全角形式
                u in 0xFFE0..0xFFE6 || // 全角符号
                u in 0x1F300..0x1F64F || // 表情符号
                u in 0x1F680..0x1F6FF ||
                u in 0x1F900..0x1F9FF

        /** xterm 256 色板（**裸 RGB**，不含 alpha；见 [colorOf]） */
        val PALETTE: IntArray = buildPalette()

        /**
         * 调色板取色，返回**不透明 ARGB**。
         *
         * 必须统一走这里，不要直接把 `PALETTE[i]` 赋给 `Paint.color`：
         * `Paint.color` 是 **ARGB**，而调色板里存的是 `0xRRGGBB`，高 8 位的 alpha
         * 恒为 0 —— 即**完全透明**。这会让所有显式着色的内容一律画不出来：
         * 脚本里用 `\033[32m` 包起来的菜单序号、状态取值，以及所有 ANSI 背景色块，
         * 都会整段"消失"，而只有用默认色的文字正常显示。
         */
        fun colorOf(index: Int): Int = 0xFF000000.toInt() or PALETTE[index.coerceIn(0, 255)]

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
            var a = fromX.coerceAtLeast(0)
            var b = toX.coerceAtMost(cols - 1)
            if (a > b) return
            // 边界落在宽字符中间时连同另半格一起清，否则会留下半个汉字
            if (a > 0 && chars[y * cols + a] == WIDE_TAIL) a--
            if (b + 1 < cols && chars[y * cols + b + 1] == WIDE_TAIL) b++
            for (x in a..b) {
                chars[y * cols + x] = ' '
                fg[y * cols + x] = DEFAULT_FG
                bg[y * cols + x] = DEFAULT_BG
                attr[y * cols + x] = 0
            }
        }

        /** 修正整体搬移后被切断的宽字符：孤立的右半格清成空格，避免画出半个汉字 */
        fun repairRow(y: Int) {
            val base = y * cols
            for (x in 0 until cols) {
                if (chars[base + x] == WIDE_TAIL && (x == 0 || cellWidth(chars[base + x - 1]) != 2)) {
                    chars[base + x] = ' '
                }
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
                        // 补充平面字符（emoji 等）：整体作为**一个**宽字符落格，
                        // 高位代理占左格、低位代理占右格，绘制时再合并成整字
                        val v = cp - 0x10000
                        processSupplementary(
                            ((0xD800 + (v shr 10)) and 0xFFFF).toChar(),
                            ((0xDC00 + (v and 0x3FF)) and 0xFFFF).toChar(),
                        )
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

    private val ioLock = Any()

    /** 喂入服务器字节流（增量，UTF-8 多字节可跨块）；与 resize 互斥避免并发损坏缓冲区 */
    fun feed(bytes: ByteArray) = synchronized(ioLock) {
        if (bytes.isEmpty()) return
        for (b in bytes) feedByte(b.toInt() and 0xFF)
    }

    /**
     * 补充平面字符（emoji 等）。在正文中整体作为一个宽字符落格；
     * 若处于转义序列（如 OSC 标题）中则原样交给状态机吞掉。
     */
    private fun processSupplementary(high: Char, low: Char) {
        if (state == State.NONE) putCell(high, low) else { process(high); process(low) }
    }

    /**
     * 光标吸附：落在宽字符右半格时移回其左半格。
     * 否则后续写入会覆盖右半格，把汉字画成半个。
     */
    private fun snapToHead(x: Int): Int {
        var c = x.coerceIn(0, cols - 1)
        val b = buf
        if (c > 0 && b.chars[cy * cols + c] == WIDE_TAIL) c--
        return c
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
                '\u0008' -> cx = snapToHead(cx - 1)
                '\t' -> cx = (((cx / 8) + 1) * 8).coerceAtMost(cols - 1)
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
            'C', 'a' -> cx = snapToHead(cx + p(0, 1))
            'D' -> cx = snapToHead(cx - p(0, 1))
            'E' -> { cy = (cy + p(0, 1)).coerceAtMost(rows - 1); cx = 0 }
            'F' -> { cy = (cy - p(0, 1)).coerceAtLeast(0); cx = 0 }
            'G', '`' -> cx = snapToHead(p(0, 1) - 1)
            'H', 'f' -> {
                cy = (p(0, 1) - 1).coerceIn(0, rows - 1)
                cx = snapToHead(p(1, 1) - 1)
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

    private fun putChar(c: Char) = putCell(c, WIDE_TAIL)

    /**
     * 写入一个字符到网格。宽字符占两格：左格存字符，右格存 [tail]
     * （普通宽字符用 [WIDE_TAIL]；补充平面字符用代理对低位，绘制时合并成整字）。
     */
    private fun putCell(c: Char, tail: Char) {
        var w = cellWidth(c)
        if (w == 0) return // 零宽字符不单独占格
        if (wrapPending) {
            wrapPending = false
            cx = 0
            lineFeed()
        }
        // 防御：光标一旦越界，idx 会落到相邻行上（写坏下一行）
        if (cx < 0) cx = 0
        if (cx > cols - 1) cx = cols - 1
        if (cy < 0) cy = 0
        if (cy > rows - 1) cy = rows - 1
        // 宽字符在最后一列放不下：标准行为是整字折到下一行
        if (w == 2 && cx == cols - 1) {
            if (autoWrap) {
                cx = 0
                lineFeed()
            } else {
                w = 1 // 关闭自动换行时退化为单格，避免越界写到相邻行
            }
        }
        val b = buf
        val idx = cy * cols + cx
        b.chars[idx] = c
        b.fg[idx] = curFg
        b.bg[idx] = curBg
        b.attr[idx] = curAttr.toByte()
        if (w == 2 && cx + 1 < cols) {
            val t = idx + 1
            b.chars[t] = tail
            b.fg[t] = curFg
            b.bg[t] = curBg
            b.attr[t] = curAttr.toByte()
        }
        if (cx + w >= cols) {
            cx = cols - 1
            if (autoWrap) wrapPending = true
        } else {
            cx += w
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
        b.repairRow(cy)
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
        b.repairRow(cy)
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
        // 宽字符右半格不是合法落点，吸附回左半格
        val b = buf
        if (cx > 0 && b.chars[cy * cols + cx] == WIDE_TAIL) cx--
    }

    /** 缩放缓冲区（与 feed 互斥） */
    fun resize(newCols: Int, newRows: Int) = synchronized(ioLock) {
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
    }

    private fun copyInto(src: Buffer, dst: Buffer) {
        val h = minOf(src.rows, dst.rows)
        val w = minOf(src.cols, dst.cols)
        for (y in 0 until h) {
            System.arraycopy(src.chars, y * src.cols, dst.chars, y * dst.cols, w)
            System.arraycopy(src.fg, y * src.cols, dst.fg, y * dst.cols, w)
            System.arraycopy(src.bg, y * src.cols, dst.bg, y * dst.cols, w)
            System.arraycopy(src.attr, y * src.cols, dst.attr, y * dst.cols, w)
            // 列宽变化可能在右边界切断宽字符，修掉残留的半格
            dst.repairRow(y)
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
            for (x in 0 until lineEnd) {
                val ch = charAt(y, x)
                if (ch != WIDE_TAIL) sb.append(ch) // 宽字符右半格占位符不导出
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
