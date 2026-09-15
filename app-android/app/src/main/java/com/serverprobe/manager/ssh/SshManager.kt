package com.serverprobe.manager.ssh

import com.serverprobe.manager.data.db.AUTH_KEY
import com.serverprobe.manager.data.db.AUTH_PASSWORD
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier

class SshException(message: String) : Exception(message)

/** 首次连接且未锁定主机指纹时抛出，UI 据此弹窗让用户确认（TOFU）。 */
class HostKeyUnknownException(val fingerprint: String) : Exception("HOST_KEY_UNKNOWN")

/**
 * 已建立的 SSH 交互会话。output 为从服务器收到的字节块流。
 *
 * **所有通道写操作（用户输入、终端应答、window-change）都被串行到一条专用线程上。**
 * sshj 的 channel 写入不是线程安全的：早先版本每次按键都在 `Dispatchers.IO` 上新建
 * 一个协程直接写通道（IO 是 64 线程池），两次快速输入即会并发写同一个 channel，
 * 与传输读线程竞争内部的窗口/锁状态，服务器会以 "Disconnected" 直接断开——表现为
 * "输入/点一下键盘就连接中断"。串行化后所有请求按提交顺序单线程执行。
 */
class SshSession internal constructor(
    private val client: SSHClient,
    private val session: Session,
    private val shell: Session.Shell,
) {
    private val outChannel = Channel<ByteArray>(Channel.UNLIMITED)
    val output: Channel<ByteArray> = outChannel

    @Volatile
    private var closed = false

    /** 会话终止原因（null 表示尚未结束），供界面显示"为什么断开" */
    @Volatile
    private var endReason: String? = null

    /** 写/控制请求失败时的回调（由 ViewModel 转成界面状态） */
    @Volatile
    var onError: ((String) -> Unit)? = null

    /** 唯一的通道操作线程：提交顺序 = 执行顺序 */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-io").apply { isDaemon = true }
    }

    init {
        val input = shell.inputStream
        Thread({
            val buf = ByteArray(8192)
            try {
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) {
                        // 服务器主动关闭（正常退出/EOT），与本地断开要区分开
                        if (endReason == null) endReason = "服务器已关闭会话"
                        break
                    }
                    if (n > 0) outChannel.trySend(buf.copyOf(n))
                }
            } catch (e: Exception) {
                if (endReason == null) {
                    endReason = "读取中断：${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                outChannel.close()
            }
        }, "ssh-output-pump").apply {
            isDaemon = true
            start()
        }
    }

    /** 终止原因，供 UI 展示（比原来的"无理由已断开"更能定位问题） */
    fun reason(): String? = endReason

    /** PTY 窗口尺寸变更（window-change 请求），串行执行 */
    fun resize(cols: Int, rows: Int) {
        if (closed) return
        io.execute {
            if (closed) return@execute
            try {
                shell.changeWindowDimensions(cols, rows, 0, 0)
            } catch (_: Exception) {
                // 尺寸同步失败多为通道已关闭，属于可忽略的瞬时错误，不主动断开会话；
                // 真正致命的是写入失败（见 write），由 onError 上报
            }
        }
    }

    /**
     * 写入通道。只做入队（开销极小，可在任意线程调用），实际写入在 ssh-io 线程上
     * 按顺序执行；失败时终止会话并回调 onError。
     */
    fun write(bytes: ByteArray) {
        if (closed || bytes.isEmpty()) return
        io.execute {
            if (closed) return@execute
            try {
                val out = shell.outputStream
                out.write(bytes)
                out.flush()
            } catch (e: Exception) {
                val msg = "发送失败，连接已断开：${e.message ?: e.javaClass.simpleName}"
                if (endReason == null) endReason = msg
                close()
                onError?.invoke(msg)
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        // disconnect 也走同一条线程，避免与进行中的写入竞争传输状态
        io.execute { runCatching { client.disconnect() } }
        outChannel.close()
    }

    val isConnected: Boolean get() = !closed && client.isConnected
}

/**
 * SSH 连接管理：TOFU 主机指纹校验 + 密码/私钥认证。
 */
object SshManager {

    const val CONNECT_TIMEOUT_MS = 12_000

    data class Params(
        val host: String,
        val port: Int,
        val username: String,
        val authType: Int,
        val password: String? = null,
        val privateKey: String? = null,
        val keyPassphrase: String? = null,
        val storedFingerprint: String? = null,
    )

    fun fingerprintOf(key: PublicKey): String {
        val sum = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return sum.joinToString(":") { "%02X".format(it) }
    }

    suspend fun connect(params: Params, cols: Int, rows: Int): SshSession = withContext(Dispatchers.IO) {
        var presented: PublicKey? = null
        val client = SSHClient()
        // 握手阶段需要 socket 读超时，避免服务端未回包时一直卡住；
        // 连接建立后立即清零，交互式 shell 空闲时不会被 socket 超时掐断。
        client.timeout = 0
        client.connectTimeout = CONNECT_TIMEOUT_MS
        // 不启用 keepalive：sshj 的保活线程与交互写入并发时服务器会以
        // "Disconnected"(reason=0) 断开（实测）。交互式 shell 依赖 TCP 层
        // 存活即可；socket 读超时保持 0，空闲不会被掐断。
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                presented = key
                val stored = params.storedFingerprint ?: return false // TOFU：未知则拒绝，由上层确认后重试
                return fingerprintOf(key).equals(stored.replace(" ", ""), ignoreCase = true)
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> = mutableListOf()
        })

        try {
            try {
                client.connect(params.host, params.port)
                // SSHClient.timeout 会映射到 socket SO_TIMEOUT。只在握手期间启用，
                // 否则终端空闲一段时间后会收到 SocketTimeoutException 并断线。
                client.timeout = 0
                client.socket.soTimeout = 0
            } catch (e: net.schmizz.sshj.transport.TransportException) {
                val key = presented
                if (key != null && params.storedFingerprint == null) {
                    throw HostKeyUnknownException(fingerprintOf(key))
                }
                if (key != null) {
                    throw SshException("主机密钥指纹与已保存的不一致，已拒绝连接（可能存在中间人风险）。如服务器确实更换了密钥，请删除该主机后重新添加。")
                }
                throw SshException("SSH 握手失败: ${e.message}")
            } catch (e: java.net.ConnectException) {
                throw SshException("无法连接 ${params.host}:${params.port}（端口未开放或被拒绝）")
            } catch (e: java.net.SocketTimeoutException) {
                throw SshException("连接超时")
            } catch (e: java.net.UnknownHostException) {
                throw SshException("主机名无法解析: ${params.host}")
            }

            when (params.authType) {
                AUTH_PASSWORD -> {
                    val pw = params.password ?: throw SshException("未设置密码")
                    client.authPassword(params.username, pw)
                }
                AUTH_KEY -> {
                    val keyText = params.privateKey ?: throw SshException("未设置私钥")
                    val provider = SshKeyLoader.load(keyText, params.keyPassphrase)
                        .getOrElse { throw SshException("私钥解析失败: ${it.message}") }
                    client.authPublickey(params.username, provider)
                }
                else -> throw SshException("不支持的认证方式")
            }

            if (!client.isAuthenticated) throw SshException("认证失败：用户名或凭据不正确")

            val session = client.startSession()
            session.allocatePTY(
                "xterm",
                cols.coerceIn(20, 500),
                rows.coerceIn(5, 200),
                0, 0,
                emptyMap<net.schmizz.sshj.connection.channel.direct.PTYMode, Int>(),
            )
            val shell = session.startShell()
            SshSession(client, session, shell)
        } catch (e: SshException) {
            runCatching { client.disconnect() }
            throw e
        } catch (e: HostKeyUnknownException) {
            // TOFU：交由上层弹出指纹确认框，不能被包装成通用错误
            runCatching { client.disconnect() }
            throw e
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw SshException("SSH 错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 仅测试连接（认证成功即断开），返回服务器主机密钥指纹。 */
    suspend fun testConnection(params: Params): String = withContext(Dispatchers.IO) {
        var presented: PublicKey? = null
        val client = SSHClient()
        // 与交互连接一致：限制握手等待时间，但不要让后续认证/读取受 socket 超时影响。
        client.timeout = CONNECT_TIMEOUT_MS
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                presented = key
                return true // 测试模式不校验，仅采集指纹
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> = mutableListOf()
        })
        client.use { c ->
            try {
                c.connect(params.host, params.port)
                c.timeout = 0
                c.socket.soTimeout = 0
            } catch (e: Exception) {
                throw SshException("连接失败: ${e.message ?: e.javaClass.simpleName}")
            }
            when (params.authType) {
                AUTH_PASSWORD -> c.authPassword(params.username, params.password ?: "")
                AUTH_KEY -> {
                    val provider = SshKeyLoader.load(params.privateKey ?: "", params.keyPassphrase)
                        .getOrElse { throw SshException("私钥解析失败: ${it.message}") }
                    c.authPublickey(params.username, provider)
                }
            }
            val fp = presented?.let { fingerprintOf(it) } ?: "-"
            if (!c.isAuthenticated) throw SshException("认证失败：用户名或凭据不正确")
            fp
        }
    }
}
