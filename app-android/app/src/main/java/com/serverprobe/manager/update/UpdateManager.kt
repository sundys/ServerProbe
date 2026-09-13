package com.serverprobe.manager.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 应用内更新：检测 GitHub Release 最新版本并下载 APK。
 * 检测与下载均支持多通道（直连 + 多个加速代理）依次回退，避免单一渠道不可达。
 */
object UpdateManager {

    const val REPO_OWNER = "sundys"
    const val REPO_NAME = "ServerProbe"
    const val REPO_URL = "https://github.com/$REPO_OWNER/$REPO_NAME"

    private const val API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    /** 加速代理前缀（空串表示直连），检测与下载共用；依次尝试直到成功 */
    private val proxies = listOf(
        "",
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://github.moeyy.xyz/",
        "https://ghproxy.net/",
    )

    class UpdateCheckException(message: String) : Exception(message)

    @Serializable
    data class GhAsset(
        val name: String = "",
        @SerialName("browser_download_url") val url: String = "",
        val size: Long = 0,
    )

    @Serializable
    data class GhRelease(
        @SerialName("tag_name") val tagName: String = "",
        val body: String? = null,
        val assets: List<GhAsset> = emptyList(),
    )

    data class ReleaseInfo(
        val tag: String,
        val version: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** APK 大小上限：异常通道返回超大响应时中断，避免写满磁盘 */
    private const val MAX_APK_BYTES = 300L * 1024 * 1024

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 检测最新版本；全部通道失败抛 UpdateCheckException */
    suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        var lastErr: Exception? = null
        for (prefix in proxies) {
            try {
                val request = Request.Builder()
                    .url(prefix + API_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "ServerProbe-App")
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body?.string() ?: throw IOException("empty body")
                    val rel = json.decodeFromString<GhRelease>(body)
                    // 优先 arm64 包，其次任意 APK
                    val apk = rel.assets
                        .filter { it.name.endsWith(".apk", ignoreCase = true) }
                        .sortedByDescending { it.name.contains("arm64") }
                        .firstOrNull()
                        ?: throw UpdateCheckException("最新发布中没有 APK 安装包")
                    return@withContext ReleaseInfo(
                        tag = rel.tagName,
                        version = rel.tagName.removePrefix("v"),
                        notes = rel.body ?: "",
                        apkUrl = apk.url,
                        apkName = apk.name,
                        apkSize = apk.size,
                    )
                }
            } catch (e: UpdateCheckException) {
                throw e // 服务端响应正常但资产缺失，无需再试其它通道
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw UpdateCheckException("检测失败：所有通道均不可达（${lastErr?.message ?: "网络错误"}）")
    }

    /**
     * 下载 APK 到 dest（候选：直连 + 各代理前缀，依次回退）。
     * onProgress(received, total) 回调下载进度。
     */
    suspend fun downloadApk(url: String, dest: File, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            var lastErr: Exception? = null
            val candidates = proxies.map { it + url }.distinct()
            for (candidate in candidates) {
                try {
                    tmp.outputStream().use { out ->
                        http.newCall(Request.Builder().url(candidate).header("User-Agent", "ServerProbe-App").build())
                            .execute().use { resp ->
                                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                                val body = resp.body ?: throw IOException("empty body")
                                val total = body.contentLength()
                                if (total > MAX_APK_BYTES) throw IOException("APK 超过大小上限")
                                var received = 0L
                                var lastNotified = 0L
                                val buf = ByteArray(32 * 1024)
                                body.byteStream().use { input ->
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        received += n
                                        if (received > MAX_APK_BYTES) throw IOException("APK 超过大小上限")
                                        out.write(buf, 0, n)
                                        // 至少 256KB 或完成时回调一次，避免过度刷新 UI
                                        if (received - lastNotified >= 256 * 1024 || received == total) {
                                            lastNotified = received
                                            onProgress(received, total)
                                        }
                                    }
                                }
                                if (total > 0 && received < total) throw IOException("下载不完整")
                            }
                    }
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                    return@withContext dest
                } catch (e: Exception) {
                    lastErr = e
                    runCatching { tmp.delete() }
                }
            }
            throw UpdateCheckException("下载失败：所有通道均不可达（${lastErr?.message ?: "网络错误"}）")
        }

    /**
     * 版本号比较：支持 "v1.2.3"、"1.2.3"、"1.2.3-beta" 等格式。
     * 返回 >0 表示 a 更新，<0 表示 b 更新，0 表示相同。
     */
    fun compareVersion(a: String, b: String): Int {
        fun parts(v: String): List<Int> =
            v.trim().removePrefix("v").removePrefix("V")
                .substringBefore('-')
                .split('.')
                .map { it.filter(Char::isDigit).ifEmpty { "0" }.toInt() }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    fun isNewer(remoteTag: String, currentVersion: String): Boolean =
        compareVersion(remoteTag, currentVersion) > 0

    /** 唤起系统安装器 */
    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
