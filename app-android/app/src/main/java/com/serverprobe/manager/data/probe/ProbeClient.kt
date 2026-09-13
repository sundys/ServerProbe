package com.serverprobe.manager.data.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/** 探针通信异常分类，UI 据此给出可读提示。 */
sealed class ProbeException(message: String) : Exception(message) {
    class Auth(message: String = "Token 无效或已过期") : ProbeException(message)
    class RateLimited : ProbeException("请求过于频繁（触发探针限流）")
    class Network(message: String) : ProbeException("网络错误: $message")
    class Tls(message: String) : ProbeException("TLS/证书校验失败: $message")
    class Http(val code: Int) : ProbeException("HTTP $code")
    class Parse(message: String) : ProbeException("响应解析失败: $message")
}

/**
 * 证书指纹锁定信任管理器：仅信任 SHA-256 指纹匹配的证书（自签场景）。
 */
class PinnedTrustManager(pinned: String) : X509TrustManager {
    private val pin = pinned.replace(":", "").replace(" ", "").uppercase()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("client auth unsupported")

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isNullOrEmpty()) throw CertificateException("empty certificate chain")
        val fp = fingerprintOf(chain[0])
        if (fp != pin) throw CertificateException("certificate fingerprint mismatch")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun fingerprintOf(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString("") { "%02X".format(it) }
    }
}

/** 仅用于「获取指纹」的显式不校验模式（用户主动触发）。 */
class InsecureTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * 探针 API 客户端。恒定携带 Bearer Token；TLS 场景支持指纹锁定 / 显式不校验。
 */
class ProbeClient(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val useTls: Boolean = true,
    private val fingerprint: String? = null,
    private val allowInsecure: Boolean = false,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val client: OkHttpClient by lazy {
        sharedClient(useTls, fingerprint, allowInsecure)
    }

    companion object {
        private const val MAX_APK_BYTES = 300L * 1024 * 1024

        /**
         * 按信任配置复用 OkHttpClient：轮询期间避免反复创建连接池与线程。
         * Token/主机在请求层携带，不影响客户端复用。
         */
        private val sharedClients = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()

        private fun sharedClient(useTls: Boolean, fingerprint: String?, allowInsecure: Boolean): OkHttpClient =
            sharedClients.getOrPut("$useTls|${fingerprint ?: "-"}|$allowInsecure") { buildClient(useTls, fingerprint, allowInsecure) }

        private fun buildClient(useTls: Boolean, fingerprint: String?, allowInsecure: Boolean): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .protocols(listOf(Protocol.HTTP_1_1))
            if (useTls) {
                val tm: X509TrustManager? = when {
                    !fingerprint.isNullOrBlank() -> PinnedTrustManager(fingerprint)
                    allowInsecure -> InsecureTrustManager()
                    else -> null
                }
                if (tm != null) {
                    val ctx = SSLContext.getInstance("TLS")
                    ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
                    builder.sslSocketFactory(ctx.socketFactory, tm)
                    // 身份由指纹断言（自签证书 CN 不含 IP），显式跳过主机名校验
                    builder.hostnameVerifier { _, _ -> true }
                }
            }
            return builder.build()
        }
    }

    private fun url(path: String): String {
        val scheme = if (useTls) "https" else "http"
        return "$scheme://$host:$port$path"
    }

    private suspend fun call(path: String): Response = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(path))
            .header("Authorization", "Bearer $token")
            .build()
        try {
            client.newCall(request).execute()
        } catch (e: SSLPeerUnverifiedException) {
            throw ProbeException.Tls(e.message ?: "peer unverified")
        } catch (e: SSLException) {
            throw ProbeException.Tls(e.message ?: "ssl error")
        } catch (e: SocketTimeoutException) {
            throw ProbeException.Network("连接超时")
        } catch (e: UnknownHostException) {
            throw ProbeException.Network("主机名无法解析")
        } catch (e: ConnectException) {
            throw ProbeException.Network("无法连接（端口未开放或被防火墙拦截）")
        } catch (e: IOException) {
            throw ProbeException.Network(e.message ?: "io error")
        }
    }

    private inline fun <reified T> parseBody(response: Response, deserializer: (String) -> T): T {
        response.use {
            when {
                it.code == 401 -> throw ProbeException.Auth()
                it.code == 429 -> throw ProbeException.RateLimited()
                !it.isSuccessful -> throw ProbeException.Http(it.code)
            }
            val body = it.body?.string() ?: throw ProbeException.Parse("empty body")
            return try {
                deserializer(body)
            } catch (e: Exception) {
                throw ProbeException.Parse(e.message ?: "bad json")
            }
        }
    }

    suspend fun info(): ProbeInfo = parseBody(call("/api/v1/info")) { json.decodeFromString(it) }

    suspend fun status(): ProbeStatus = parseBody(call("/api/v1/status")) { json.decodeFromString(it) }

    suspend fun services(): List<ProbeService> =
        parseBody(call("/api/v1/services")) { json.decodeFromString<ServicesResponse>(it).services }

    private suspend fun post(path: String, body: String): Response = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(path))
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()
        try {
            client.newCall(request).execute()
        } catch (e: SSLException) {
            throw ProbeException.Tls(e.message ?: "ssl error")
        } catch (e: IOException) {
            throw ProbeException.Network(e.message ?: "io error")
        }
    }

    suspend fun serviceAction(action: String, name: String): ServiceActionResponse {
        val body = """{"name":"$name","action":"$action"}"""
        return parseBody(post("/api/v1/services/action", body)) { json.decodeFromString(it) }
    }

    /**
     * 显式不校验地连接一次，返回服务器证书 SHA-256 指纹（冒号分隔大写），用于「锁定指纹」。
     * 仅在用户主动点击「获取指纹」时调用。
     */
    suspend fun fetchCertificateFingerprint(): String = withContext(Dispatchers.IO) {
        val insecure = ProbeClient(host, port, token, useTls = true, fingerprint = null, allowInsecure = true)
        val request = Request.Builder().url(insecure.url("/api/v1/info")).build()
        val response = try {
            insecure.client.newCall(request).execute()
        } catch (e: SSLException) {
            throw ProbeException.Tls(e.message ?: "ssl error")
        } catch (e: IOException) {
            throw ProbeException.Network(e.message ?: "io error")
        }
        response.use {
            val cert = it.handshake?.peerCertificates?.firstOrNull() as? X509Certificate
                ?: throw ProbeException.Tls("未获取到服务器证书")
            PinnedTrustManager.fingerprintOf(cert)
        }
    }
}
