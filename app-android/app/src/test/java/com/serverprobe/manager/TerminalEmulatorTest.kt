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
        assertEquals('服', emu.charAt(0, 0))
        assertEquals('务', emu.charAt(0, 1))
        assertEquals('器', emu.charAt(0, 2))
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
}
