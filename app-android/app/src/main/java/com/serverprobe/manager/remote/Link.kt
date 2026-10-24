package com.serverprobe.manager.remote

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class Link(
    val id: String,
    val url: String,
    val name: String,
    val version: String,
    val host: String,
    val createdAt: Long,
    val lastOpenedAt: Long
)

object LinkParser {

    const val DEFAULT_NAME = "未命名电脑"

    /**
     * 旧版链接（路径可能不是 /remote）里用来认定“这是 zcode 远程链接”的凭证参数。
     * 任一带值即视为有效——新版 v4 链接只有 `sid` + `mid`，不再带 `hash`。
     */
    private val CREDENTIAL_PARAMS = listOf("sid", "hash")

    /** 粘贴整段文字时，URL 末尾常被带上的标点（中英文句读与右括号） */
    private const val TRAILING_JUNK = ".,;:!?。，；：！？、)）]】>》\"'"

    /** 纯 URL 输入时允许裁掉的尾部字符：只裁右括号/引号/中文句读，不动 `.` `:` 等 URL 合法字符 */
    private const val URL_TAIL_JUNK = "。，；：！？、)）]】>》\"'"

    private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    /** 从任意文本中提取 URL（用于“分享到云枢”、粘贴整段分享消息） */
    fun extractUrl(text: String): String? =
        URL_REGEX.find(text)?.value
            ?.trimEnd { it in TRAILING_JUNK }
            ?.takeIf { it.length > "https://".length }

    /**
     * 解析 zcode 远程链接。
     *
     * 判定规则（与版本号解耦，兼容 v4 及后续所有版本）：
     * 1. scheme 为 http/https，host 含 `.`；
     * 2. 路径为 `/remote` 或 `/remote/...`（版本段任意，v4 / v5 / 无版本均可），
     *    或路径不是 /remote 时，查询串（含 `#` 片段）里带 [CREDENTIAL_PARAMS] 之一。
     *
     * 不再要求 `sid` 与 `hash` 同时存在——新版链接只带 `sid` + `mid` 也能识别。
     * 字段取值：`name` 取 name 参数（缺省「未命名电脑」），`version` 取
     * app_version（兼容 version），`host` 取 URL 主机。参数名大小写不敏感，
     * `?` 查询串与 `#` 片段里的参数都会被读取；粘贴整段文字时自动取出第一条 URL。
     */
    fun parse(raw: String, existing: Link? = null): Link? {
        val cleaned = normalize(raw)
        if (cleaned.isEmpty()) return null

        // 允许直接粘贴“分享消息”：非纯 URL（含空白/前后带说明文字）时从中提取第一条链接
        val url = if (isUrl(cleaned) && cleaned.none { it.isWhitespace() }) {
            cleaned
        } else {
            extractUrl(cleaned) ?: return null
        }

        val parts = UrlParts.parse(url) ?: return null
        if (parts.scheme != "https" && parts.scheme != "http") return null
        if (parts.host.isEmpty() || !parts.host.contains('.')) return null

        val params = parts.params()
        if (!looksLikeRemote(parts.path, params)) return null

        val name = params["name"]?.trim()?.takeUnless { it.isEmpty() } ?: DEFAULT_NAME
        val version = (params["app_version"] ?: params["version"])?.trim().orEmpty()
        return Link(
            id = existing?.id ?: UUID.randomUUID().toString(),
            url = url,
            name = name,
            version = version,
            host = parts.host,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastOpenedAt = existing?.lastOpenedAt ?: 0L
        )
    }

    /** 路径为 /remote 或 /remote/<任意版本段>；路径不符时退化为看凭证参数 */
    private fun looksLikeRemote(path: String, params: Map<String, String>): Boolean {
        val p = path.trim('/').lowercase()
        if (p == "remote" || p.startsWith("remote/")) return true
        return CREDENTIAL_PARAMS.any { !params[it].isNullOrBlank() }
    }

    private fun isUrl(s: String): Boolean =
        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    /** 去掉首尾空白/引号/零宽字符，纠正全角符号与 HTML 转义 */
    private fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            // 零宽空格 / BOM：复制粘贴时经常混进来，肉眼不可见
            if (c.code == 0x200B || c.code == 0xFEFF) continue
            sb.append(
                when (c) {
                    '＆' -> '&'   // 全角 ＆
                    '？' -> '?'   // 全角 ？
                    else -> c
                }
            )
        }
        var s = sb.toString().replace("&amp;", "&").trim()
        while (s.isNotEmpty() && s.first() in "\"'<>《》「」") s = s.substring(1).trimStart()
        while (s.isNotEmpty() && s.last() in "\"'<>《》「」") s = s.dropLast(1).trimEnd()
        // 纯 URL 粘贴时尾部常带中文句号/右括号，裁掉；`.` `:` 等 URL 合法字符不动
        if (isUrl(s) && s.none { it.isWhitespace() }) s = s.trimEnd { it in URL_TAIL_JUNK }
        return s
    }
}

/**
 * 轻量 URL 拆解（不依赖 android.net.Uri，便于在 JVM 单测里直接验证）。
 * 只关心 scheme / host / path / query / fragment，其余部分原样保留。
 */
internal class UrlParts(
    val scheme: String,
    val host: String,
    val path: String,
    val query: String,
    val fragment: String,
) {

    /** 合并 `?` 查询串与 `#` 片段里的参数，键统一小写；同名取先出现的非空值 */
    fun params(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        putParams(query, stripLeadingPath = false, out = out)
        putParams(fragment, stripLeadingPath = true, out = out)
        return out
    }

    companion object {

        fun parse(url: String): UrlParts? {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = url.substring(0, schemeEnd).lowercase()
            if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null

            val rest = url.substring(schemeEnd + 3)
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
                .let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnd).substringAfterLast('@')
            val tail = rest.substring(authorityEnd)

            val host = when {
                authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']') // IPv6
                else -> authority.substringBefore(':')
            }

            val hashIdx = tail.indexOf('#')
            val beforeHash = if (hashIdx >= 0) tail.substring(0, hashIdx) else tail
            val fragment = if (hashIdx >= 0) tail.substring(hashIdx + 1) else ""
            val qIdx = beforeHash.indexOf('?')
            val path = if (qIdx >= 0) beforeHash.substring(0, qIdx) else beforeHash
            val query = if (qIdx >= 0) beforeHash.substring(qIdx + 1) else ""

            return UrlParts(scheme, host, path, query, fragment)
        }

        /**
         * 解析 `k=v&k2=v2`。片段可能是 `#/remote/v4?sid=…&hash=…` 这种路由形式，
         * 此时 [stripLeadingPath] 为 true，先取 `?` 之后的部分。
         */
        private fun putParams(segment: String, stripLeadingPath: Boolean, out: MutableMap<String, String>) {
            if (segment.isEmpty()) return
            val body = if (stripLeadingPath && '?' in segment) segment.substringAfter('?') else segment
            for (pair in body.split('&')) {
                if (pair.isEmpty()) continue
                val key = decode(pair.substringBefore('=')).trim().lowercase()
                if (key.isEmpty()) continue
                val value = decode(pair.substringAfter('=', ""))
                if (out[key].isNullOrBlank()) out[key] = value
            }
        }

        private fun decode(s: String): String = try {
            URLDecoder.decode(s, StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            s
        }
    }
}
