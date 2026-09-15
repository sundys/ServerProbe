package com.serverprobe.manager

import com.serverprobe.manager.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {

    @Test
    fun `plain text is placed on grid`() {
        val emu = TerminalEmulator(10, 3)
        emu.feed("hello".toByteArray())
        assertEquals('h', emu.charAt(0, 0))
        assertEquals('o', emu.charAt(0, 4))
        assertEquals(' ', emu.charAt(0, 5))
        assertEquals(5, emu.cx)
    }

    @Test
    fun `newline moves cursor and cr resets column`() {
        val emu = TerminalEmulator(10, 5)
        emu.feed("abc\r\ndef".toByteArray())
        assertEquals('a', emu.charAt(0, 0))
        assertEquals('d', emu.charAt(1, 0))
    }

    @Test
    fun `cup cursor position`() {
        val emu = TerminalEmulator(20, 10)
        emu.feed("\u001B[3;5HX".toByteArray())
        assertEquals('X', emu.charAt(2, 4))
    }

    @Test
    fun `erase display`() {
        val emu = TerminalEmulator(10, 4)
        emu.feed("aaaa\r\nbbbb\r\ncccc".toByteArray())
        emu.feed("\u001B[2J".toByteArray())
        assertEquals(' ', emu.charAt(0, 0))
        assertEquals(' ', emu.charAt(2, 0))
    }

    @Test
    fun `sgr colors applied`() {
        val emu = TerminalEmulator(10, 2)
        emu.feed("\u001B[31mR".toByteArray())
        assertEquals(1, emu.fgAt(0, 0)) // 31 → palette index 1
        emu.feed("\u001B[0mN".toByteArray())
        assertEquals(TerminalEmulator.DEFAULT_FG, emu.fgAt(0, 1))
    }

    @Test
    fun `256 color extended sgr`() {
        val emu = TerminalEmulator(10, 2)
        emu.feed("\u001B[38;5;196mX".toByteArray())
        assertEquals(196, emu.fgAt(0, 0))
    }

    @Test
    fun `scrolls at bottom`() {
        val emu = TerminalEmulator(5, 2)
        emu.feed("aaaa\r\nbbbb\r\ncccc\r\ndddd".toByteArray())
        assertEquals('c', emu.charAt(0, 0))
        assertEquals('d', emu.charAt(1, 0))
    }

    @Test
    fun `alt screen switch clears and restores`() {
        val emu = TerminalEmulator(10, 4)
        emu.feed("main".toByteArray())
        emu.feed("\u001B[?1049h".toByteArray())
        assertEquals(' ', emu.charAt(0, 0))
        emu.feed("alt-screen".toByteArray())
        assertTrue(emu.dumpScreen().contains("alt-screen"))
        emu.feed("\u001B[?1049l".toByteArray())
        assertEquals('m', emu.charAt(0, 0))
    }

    @Test
    fun `utf8 multibyte across chunk boundary`() {
        val emu = TerminalEmulator(10, 2)
        val bytes = "服务器".toByteArray(Charsets.UTF_8)
        emu.feed(bytes.copyOfRange(0, 4)) // 切断多字节
        emu.feed(bytes.copyOfRange(4, bytes.size))
        // 汉字各占两列，右半格为占位符
        assertEquals('服', emu.charAt(0, 0))
        assertEquals(TerminalEmulator.WIDE_TAIL, emu.charAt(0, 1))
        assertEquals('务', emu.charAt(0, 2))
        assertEquals('器', emu.charAt(0, 4))
        assertEquals(6, emu.cx)
    }

    @Test
    fun `dsr cursor report`() {
        var response: ByteArray? = null
        val emu = TerminalEmulator(10, 5) { response = it }
        emu.feed("\u001B[2;3H\u001B[6n".toByteArray())
        assertEquals("\u001B[2;3R".toByteArray().decodeToString(), response?.decodeToString())
    }

    @Test
    fun `palette has 256 colors`() {
        assertEquals(256, TerminalEmulator.PALETTE.size)
        assertEquals(0x000000, TerminalEmulator.PALETTE[0])
        assertEquals(0xFFFFFF, TerminalEmulator.PALETTE[15])
        assertEquals((238 shl 16) or (238 shl 8) or 238, TerminalEmulator.PALETTE[255])
    }

    @Test
    fun `tab stops clamp to last column`() {
        // 制表符必须停在最后一列：越界会把后续字符写到下一行（idx 落到相邻行）
        val emu = TerminalEmulator(10, 3)
        emu.feed("\t\tX".toByteArray())
        assertEquals('X', emu.charAt(0, 9))
        assertEquals(' ', emu.charAt(1, 0))
        assertEquals(9, emu.cx)
    }

    @Test
    fun `resize keeps content and clamps cursor`() {
        val emu = TerminalEmulator(30, 8)
        emu.feed("hello".toByteArray())
        assertEquals(5, emu.cx)
        emu.resize(40, 10)
        assertEquals('h', emu.charAt(0, 0))
        assertEquals(5, emu.cx)
        // 光标在最后一列时缩窄缓冲区，必须被钳制回界内
        emu.feed("\u001B[1;30H".toByteArray())
        emu.resize(20, 5)
        assertEquals(19, emu.cx)
    }

    // ---- 宽字符（East Asian Width）----
    //
    // 汉字/假名/全角标点在终端里占**两列**。若按一列记账，光标位置（cx * cellW）
    // 会比字形实际结束位置左移一半——表现就是"提示符后面光标停在字中间"，
    // 以及服务端用 ESC[r;cH 定位时整体错位。

    @Test
    fun `cell width follows east asian width`() {
        assertEquals(1, TerminalEmulator.cellWidth('a'))
        assertEquals(1, TerminalEmulator.cellWidth(' '))
        assertEquals(2, TerminalEmulator.cellWidth('中'))
        assertEquals(2, TerminalEmulator.cellWidth('あ'))
        assertEquals(2, TerminalEmulator.cellWidth('：')) // 全角冒号
        assertEquals(2, TerminalEmulator.cellWidth('Ａ'))
        assertEquals(0, TerminalEmulator.cellWidth(TerminalEmulator.WIDE_TAIL))
    }

    @Test
    fun `cjk occupies two columns`() {
        val emu = TerminalEmulator(20, 3)
        emu.feed("服务器 ok".toByteArray())
        assertEquals('服', emu.charAt(0, 0))
        assertEquals(TerminalEmulator.WIDE_TAIL, emu.charAt(0, 1))
        assertEquals('务', emu.charAt(0, 2))
        assertEquals('器', emu.charAt(0, 4))
        assertEquals(' ', emu.charAt(0, 6))
        assertEquals('o', emu.charAt(0, 7))
        assertEquals('k', emu.charAt(0, 8))
        assertEquals(9, emu.cx) // 3 汉字(6) + 空格 + 2 字母
    }

    @Test
    fun `fullwidth prompt counts twelve columns`() {
        // 脚本提示「请输入数字：」= 5 汉字 + 1 全角冒号 = 12 列；光标必须停在行尾
        val emu = TerminalEmulator(40, 3)
        emu.feed("请输入数字：".toByteArray())
        assertEquals(12, emu.cx)
        assertEquals(TerminalEmulator.WIDE_TAIL, emu.charAt(0, 11))
    }

    @Test
    fun `wide char wraps as a whole`() {
        // 最后一列放不下时必须整字折行，不能只写一半
        val emu = TerminalEmulator(5, 3)
        emu.feed("abcd中".toByteArray())
        assertEquals('d', emu.charAt(0, 3))
        assertEquals(' ', emu.charAt(0, 4))
        assertEquals('中', emu.charAt(1, 0))
        assertEquals(TerminalEmulator.WIDE_TAIL, emu.charAt(1, 1))
    }

    @Test
    fun `cursor snaps off wide char tail`() {
        val emu = TerminalEmulator(20, 3)
        emu.feed("中文".toByteArray())
        assertEquals(4, emu.cx)
        emu.feed("\u0008".toByteArray()) // 退格不能停在汉字右半格
        assertEquals(2, emu.cx)
        emu.feed("\u001B[4G".toByteArray()) // 定位到右半格时同样吸附回左半格
        assertEquals(2, emu.cx)
    }

    @Test
    fun `erase does not split wide char`() {
        val emu = TerminalEmulator(20, 3)
        emu.feed("中文测试".toByteArray())
        // 只擦到「中」的右半格：左半格必须一并清掉，否则会留下半个汉字
        emu.feed("\u001B[1;2H\u001B[1K".toByteArray())
        assertEquals(' ', emu.charAt(0, 0))
        assertEquals(' ', emu.charAt(0, 1))
        assertEquals('文', emu.charAt(0, 2))
    }

    @Test
    fun `dump screen skips wide tail`() {
        val emu = TerminalEmulator(20, 1)
        emu.feed("中文 abc".toByteArray())
        assertEquals("中文 abc", emu.dumpScreen().trimEnd('\n'))
    }

    @Test
    fun `emoji surrogate pair is one wide char`() {
        val emu = TerminalEmulator(10, 2)
        emu.feed("😀x".toByteArray())
        assertTrue(emu.charAt(0, 0).isHighSurrogate())
        assertTrue(emu.charAt(0, 1).isLowSurrogate())
        assertEquals('x', emu.charAt(0, 2))
        assertEquals(3, emu.cx)
    }

    @Test
    fun `palette lookups are opaque`() {
        // 回归：PALETTE 里是裸 RGB，直接赋给 Paint.color 会被按 ARGB 解析，
        // alpha=0 即全透明——脚本里用 ANSI 颜色包住的文字（菜单序号、状态取值）
        // 与所有 ANSI 背景色块都会整段消失。取色必须补齐不透明 alpha。
        for (i in 0 until 256) {
            assertEquals("index $i", 0xFF000000.toInt(), TerminalEmulator.colorOf(i) and 0xFF000000.toInt())
        }
        assertEquals(0x00CD00, TerminalEmulator.colorOf(2) and 0xFFFFFF) // 绿：脚本菜单序号用的就是它
        assertEquals(0xFFFFFF, TerminalEmulator.colorOf(15) and 0xFFFFFF)
        // 越界钳制，不允许抛异常或取到别的颜色
        assertEquals(TerminalEmulator.colorOf(255), TerminalEmulator.colorOf(999))
        assertEquals(TerminalEmulator.colorOf(0), TerminalEmulator.colorOf(-5))
    }

    @Test
    fun `bbr script menu numbers keep color and columns`() {
        // 真实脚本（ylx2016/Linux-NetSpeed tcp.sh）的菜单行：
        // 序号被 \033[32m 包住，列之间用 \t 分隔。序号必须落在自己的列上，
        // 且带绿色（调色板 2）；标签用默认色。
        val emu = TerminalEmulator(40, 3)
        emu.feed(" \u001B[32m17.\u001B[0m 开启ECN\t \t\t\u001B[32m18.\u001B[0m 关闭ECN".toByteArray())
        assertEquals('1', emu.charAt(0, 1))
        assertEquals('7', emu.charAt(0, 2))
        assertEquals('.', emu.charAt(0, 3))
        assertEquals(2, emu.fgAt(0, 1)) // 绿色
        assertEquals('开', emu.charAt(0, 5)) // 标签从第 5 列开始（序号占了 1-3 列）
        assertEquals(TerminalEmulator.DEFAULT_FG, emu.fgAt(0, 5))
        assertEquals('1', emu.charAt(0, 32))
        assertEquals('8', emu.charAt(0, 33))
        assertEquals('关', emu.charAt(0, 36))
    }

    @Test
    fun `sgr attributes and colors are tracked per cell`() {
        val emu = TerminalEmulator(20, 2)
        // 粗体 + 亮绿前景 + 青背景 + 下划线，再反显
        emu.feed("a\u001B[1;92;46;4mb\u001B[7mc\u001B[0md".toByteArray())
        assertEquals(1, emu.attrAt(0, 1) and 1) // bold
        assertEquals(2, emu.attrAt(0, 1) and 2) // underline
        assertEquals(10, emu.fgAt(0, 1)) // \e[92m → 8+2
        assertEquals(6, emu.bgAt(0, 1)) // \e[46m → 6
        assertEquals(4, emu.attrAt(0, 2) and 4) // \e[7m 反显
        assertEquals(TerminalEmulator.DEFAULT_FG, emu.fgAt(0, 3)) // \e[0m 复位
        assertEquals(TerminalEmulator.DEFAULT_BG, emu.bgAt(0, 3))
        assertEquals(0, emu.attrAt(0, 3))
    }
}
