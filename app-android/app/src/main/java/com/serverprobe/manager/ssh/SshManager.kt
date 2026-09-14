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
 */
class SshSession internal constructor(
    private val client: SSHClient,
    private val session: Session,
    private val shell: Session.Shell,
) {
    private val outChannel = Channel<ByteArray>(Channel.UNLIMITED)
    val output: Channel<ByteArray> = outChannel
    private var closed = false

    init {
        val input = shell.inputStream
        Thread({
            val buf = ByteArray(8192)
            try {
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) outChannel.trySend(buf.copyOf(n))
                }
            } catch (_: Exception) {
            } finally {
                outChannel.close()
            }
        }, "ssh-output-pump").apply {
            isDaemon = true
            start()
        }
    }

    val stdin: java.io.OutputStream get() = shell.outputStream

    /** PTY 窗口尺寸变更（window-change 请求） */
    fun resize(cols: Int, rows: Int) {
        runCatching { shell.changeWindowDimensions(cols, rows, 0, 0) }
    }

    fun write(bytes: ByteArray) {
        try {
            stdin.write(bytes)
            stdin.flush()
        } catch (e: Exception) {
            close()
            throw SshException("连接已断开: ${e.message}")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { client.disconnect() }
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
