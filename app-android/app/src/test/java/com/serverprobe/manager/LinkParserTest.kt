package com.serverprobe.manager

import com.serverprobe.manager.remote.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LinkParserTest {

    @Test
    fun `v4 link with sid and mid only is accepted`() {
        val link = LinkParser.parse(
            "https://zcode.z.ai/remote/v4?sid=abc123&mid=xyz789&name=Google&app_version=3.16.3"
        )
        assertNotNull(link)
        assertEquals("Google", link!!.name)
        assertEquals("3.16.3", link.version)
        assertEquals("zcode.z.ai", link.host)
    }

    @Test
    fun `future version path and unknown params still accepted`() {
        // 版本段不参与判定，v9 / 无版本 / 多级路径都应通过
        assertNotNull(LinkParser.parse("https://zcode.z.ai/remote/v9?sid=s&hash=h"))
        assertNotNull(LinkParser.parse("https://zcode.z.ai/remote?sid=s"))
        assertNotNull(LinkParser.parse("https://zcode.z.ai/remote/v12/session?sid=s&mid=m"))
        // 参数改名/精简不影响识别：带 sid 即可
        assertNotNull(LinkParser.parse("https://zcode.z.ai/remote/v5?sid=s"))
        // 其他域名同理（旧版规则也未限制域名）
        assertNotNull(LinkParser.parse("https://example.com/remote/v4?sid=t"))
        // 只有 /remote 路径、暂无凭证参数也先认下来：凭证可能被后续版本放进路径段或运行时注入
        assertNotNull(LinkParser.parse("https://zcode.z.ai/remote/v4"))
    }

    @Test
    fun `params in fragment are read`() {
        val link = LinkParser.parse("https://zcode.z.ai/remote/v4#sid=s1&hash=h1&name=Desk&app_version=3.17.0")
        assertNotNull(link)
        assertEquals("Desk", link!!.name)
        assertEquals("3.17.0", link.version)
    }

    @Test
    fun `param names are case insensitive and version falls back to version key`() {
        val link = LinkParser.parse("https://zcode.z.ai/remote/v4?SID=s&Name=MyPC&Version=9.9")
        assertNotNull(link)
        assertEquals("MyPC", link!!.name)
        assertEquals("9.9", link.version)
    }

    @Test
    fun `name defaults when missing`() {
        val link = LinkParser.parse("https://zcode.z.ai/remote/v4?sid=s")
        assertEquals(LinkParser.DEFAULT_NAME, link!!.name)
        assertEquals("", link.version)
    }

    @Test
    fun `pasted share text is unwrapped`() {
        val link = LinkParser.parse(
            "在 ZCode 中远程控制这台电脑：https://zcode.z.ai/remote/v4?sid=s&name=PC&app_version=3.16.3 。"
        )
        assertNotNull(link)
        assertEquals("PC", link!!.name)
    }

    @Test
    fun `dirty paste is cleaned`() {
        // 全角符号、HTML 转义、零宽空格、包裹引号
        val raw = "“https://zcode.z.ai/remote/v4?sid=s＆name=PC&app_version=3.16.3​”"
        val link = LinkParser.parse(raw)
        assertNotNull(link)
        assertEquals("PC", link!!.name)
    }

    @Test
    fun `non remote urls are rejected`() {
        assertNull(LinkParser.parse(""))
        assertNull(LinkParser.parse("   "))
        assertNull(LinkParser.parse("zcode.z.ai/remote/v4?sid=s"))
        assertNull(LinkParser.parse("https://zcode.z.ai/dashboard?x=1"))          // 路径与参数都不像远程
        assertNull(LinkParser.parse("ftp://zcode.z.ai/remote/v4?sid=s"))
        assertNull(LinkParser.parse("https://localhost/remote?sid=s"))
    }

    @Test
    fun `editing keeps identity and timestamps`() {
        val first = LinkParser.parse("https://zcode.z.ai/remote/v4?sid=s&name=A")!!
        val edited = LinkParser.parse("https://zcode.z.ai/remote/v4?sid=t&name=B", first)!!
        assertEquals(first.id, edited.id)
        assertEquals(first.createdAt, edited.createdAt)
        assertEquals("B", edited.name)
    }
}
