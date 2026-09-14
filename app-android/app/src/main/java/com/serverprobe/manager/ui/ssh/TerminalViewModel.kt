package com.serverprobe.manager.ui.ssh

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.serverprobe.manager.App
import com.serverprobe.manager.data.db.AUTH_KEY
import com.serverprobe.manager.data.db.AUTH_PASSWORD
import com.serverprobe.manager.terminal.TerminalEmulator
import com.serverprobe.manager.ssh.HostKeyUnknownException
import com.serverprobe.manager.ssh.SshManager
import com.serverprobe.manager.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    sealed class TState {
        object Idle : TState()
        data class Connecting(val msg: String = "正在建立 SSH 连接…") : TState()
        object AwaitingHostKeyConfirm : TState()
        object Connected : TState()
        data class Closed(val reason: String?) : TState()
    }

    val state = MutableStateFlow<TState>(TState.Idle)
    val emulator = MutableStateFlow<TerminalEmulator?>(null)
    val dataVersion = MutableStateFlow(0L)
    val title = MutableStateFlow("")
    val pendingFingerprint = MutableStateFlow<String?>(null)

    private var session: SshSession? = null
    private var connectJob: Job? = null
    private var sshId: Long = 0
    private var lastCols = 0
    private var lastRows = 0
    private var started = false
    private val mgr = App.get(app)

    /**
     * 终端尺寸变化。
     * 首次（布局稳定后）以此尺寸发起连接；已连接后键盘弹出导致的行数变化
     * 只调整本地缓冲区渲染，**不改 PTY 尺寸**——避免服务器屏幕模型与
     * 显示错位（光标"消失"在键盘下方），也不触发窗口变更竞争。
     */
    fun onSize(sshId: Long, cols: Int, rows: Int) {
        if (!started) {
            started = true
            start(sshId, cols, rows)
            return
        }
        if (sshId != this.sshId) return
        val emu = emulator.value ?: return
        // 列数变化仍需同步服务器（旋转屏幕）；行数变化多半是键盘，保持 PTY 不变
        if (cols != lastCols) {
            lastCols = cols
            runCatching { emu.resize(cols, rows) }
            runCatching { session?.resize(cols, rows) }
            dataVersion.value++
        }
    }

    fun start(sshId: Long, cols: Int, rows: Int) {
        if (this.sshId == sshId && session?.isConnected == true) return
        this.sshId = sshId
        this.lastCols = cols
        this.lastRows = rows
        connect()
    }

    private fun connect(storedFpOverride: String? = null) {
        connectJob?.cancel()
        session?.close()
        session = null
        connectJob = viewModelScope.launch {
            state.value = TState.Connecting()
            val entity = mgr.sshRepo.byId(sshId)
            if (entity == null) {
                state.value = TState.Closed("SSH 主机不存在")
                return@launch
            }
            title.value = "${entity.username}@${entity.host}:${entity.port}"
            val params = SshManager.Params(
                host = entity.host,
                port = entity.port,
                username = entity.username,
                authType = entity.authType,
                password = if (entity.authType == AUTH_PASSWORD) mgr.sshRepo.passwordOf(entity) else null,
                privateKey = if (entity.authType == AUTH_KEY) mgr.sshRepo.privateKeyOf(entity) else null,
                keyPassphrase = mgr.sshRepo.keyPassphraseOf(entity),
                storedFingerprint = storedFpOverride ?: entity.hostKeyFingerprint,
            )
            try {
                // Read the dimensions after the coroutine starts.  Compose may
                // perform another layout between the first callback and this
                // point (notably while the IME is being shown), so capturing
                // them before launch can allocate a permanently narrow PTY.
                val cols = lastCols.coerceAtLeast(20)
                val rows = lastRows.coerceAtLeast(5)
                val s = SshManager.connect(params, cols, rows)
                session = s

                // The view can be laid out more than once while the connection is
                // being established (for example when the IME appears).  The
                // dimensions captured above are therefore not necessarily the
                // dimensions of the currently visible terminal.  Start the
                // emulator at the latest size and notify the server of any size
                // change that happened during the handshake; otherwise the PTY
                // remains stuck at the transient first-layout width and wraps
                // output after only a few columns.
                val currentCols = lastCols.coerceAtLeast(20)
                val currentRows = lastRows.coerceAtLeast(5)
                val emu = TerminalEmulator(currentCols, currentRows) { resp -> runCatching { s.write(resp) } }
                if (currentCols != cols || currentRows != rows) {
                    s.resize(currentCols, currentRows)
                }
                emulator.value = emu
                state.value = TState.Connected
                // 批量合并输出块：高吞吐场景（如 cat 大文件）下减少解析与重绘次数
                for (first in s.output) {
                    var batch = first
                    var size = first.size
                    while (size < 256 * 1024) {
                        val more = s.output.tryReceive().getOrNull() ?: break
                        batch += more
                        size += more.size
                    }
                    // 喂入异常不应打断会话（缩小/清屏瞬间尺寸竞争等瞬时问题）
                    runCatching { emu.feed(batch) }
                    dataVersion.value++
                }
                // 输出通道关闭 = 连接断开
                if (state.value is TState.Connected) {
                    state.value = TState.Closed(null)
                }
            } catch (e: HostKeyUnknownException) {
                pendingFingerprint.value = e.fingerprint
                state.value = TState.AwaitingHostKeyConfirm
            } catch (e: Exception) {
                state.value = TState.Closed(e.message)
            }
        }
    }

    /** 用户确认并信任主机指纹（TOFU） */
    fun trustHostKey() {
        val fp = pendingFingerprint.value ?: return
        pendingFingerprint.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val entity = mgr.sshRepo.byId(sshId) ?: return@launch
            mgr.sshRepo.save(entity.copy(hostKeyFingerprint = fp))
            connect(storedFpOverride = fp)
        }
    }

    fun rejectHostKey() {
        pendingFingerprint.value = null
        state.value = TState.Closed("已取消：主机指纹未确认")
    }

    fun write(bytes: ByteArray) {
        try {
            session?.write(bytes)
        } catch (e: Exception) {
            state.value = TState.Closed(e.message ?: "发送失败，连接已断开")
        }
    }

    fun retry() {
        connect()
    }

    override fun onCleared() {
        session?.close()
        session = null
        super.onCleared()
    }
}
