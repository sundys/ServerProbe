package com.serverprobe.manager.remote

import android.net.Uri
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

    /** 从任意文本中提取 URL（用于“分享到云枢”） */
    fun extractUrl(text: String): String? =
        Regex("https?://\\S+").find(text)?.value

    /**
     * 解析 zcode 远程链接，例如：
     * https://zcode.z.ai/remote/v4?sid=...&hash=...&t=...&mid=...&name=Google&app_version=3.10.2
     * 卡片名称取 name 参数，版本号取 app_version 参数；链接缺 sid/hash 时视为无效。
     */
    fun parse(raw: String, existing: Link? = null): Link? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host ?: return null
        if (!host.contains('.')) return null
        val sid = uri.getQueryParameter("sid")
        if (sid.isNullOrBlank()) return null
        val hash = uri.getQueryParameter("hash")
        if (hash.isNullOrBlank()) return null
        val name = uri.getQueryParameter("name")?.trim()
            ?.takeUnless { it.isEmpty() } ?: DEFAULT_NAME
        val version = uri.getQueryParameter("app_version")?.trim().orEmpty()
        return Link(
            id = existing?.id ?: UUID.randomUUID().toString(),
            url = url,
            name = name,
            version = version,
            host = host,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastOpenedAt = existing?.lastOpenedAt ?: 0L
        )
    }
}
