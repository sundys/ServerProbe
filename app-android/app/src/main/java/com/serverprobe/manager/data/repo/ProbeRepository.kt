package com.serverprobe.manager.data.repo

import com.serverprobe.manager.data.crypto.KeystoreCipher
import com.serverprobe.manager.data.db.ProbeHostDao
import com.serverprobe.manager.data.db.ProbeHostEntity
import com.serverprobe.manager.data.probe.ProbeClient
import com.serverprobe.manager.data.probe.ProbeException
import com.serverprobe.manager.data.probe.ProbeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 探针主机仓库：CRUD + 全局轮询循环。
 * 轮询在 App 作用域常驻，Home/详情页共享同一份运行时状态。
 */
class ProbeRepository(
    private val dao: ProbeHostDao,
    private val sshRepo: SshRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {

    enum class ConnState { IDLE, CHECKING, ONLINE, OFFLINE }

    data class HostRuntime(
        val state: ConnState = ConnState.IDLE,
        val status: ProbeStatus? = null,
        val error: String? = null,
        val cpuHistory: List<Double> = emptyList(),
        val lastUpdated: Long = 0,
    )

    val hosts: Flow<List<ProbeHostEntity>> = dao.observeAll()

    suspend fun byId(id: Long): ProbeHostEntity? = dao.byId(id)

    suspend fun all(): List<ProbeHostEntity> = dao.all()

    private val _runtime = MutableStateFlow<Map<Long, HostRuntime>>(emptyMap())
    val runtime: StateFlow<Map<Long, HostRuntime>> = _runtime

    private val pollJobs = mutableMapOf<Long, Job>()
    private val loopStarted = MutableStateFlow(false)

    /** 启动全局轮询：随主机列表增删自动调整任务 */
    fun start() {
        if (!loopStarted.compareAndSet(false, true)) return
        scope.launch {
            hosts.collect { list ->
                val ids = list.map { it.id }.toSet()
                synchronized(pollJobs) {
                    pollJobs.keys.filterNot { it in ids }.forEach { id ->
                        pollJobs.remove(id)?.cancel()
                        _runtime.value = _runtime.value - id
                    }
                    list.forEach { host ->
                        if (host.id !in pollJobs) {
                            pollJobs[host.id] = launchPollLoop(host.id)
                        }
                    }
                }
            }
        }
    }

    private fun launchPollLoop(hostId: Long): Job = scope.launch {
        while (true) {
            val host = dao.byId(hostId) ?: break
            refresh(host)
            delay(settings.pollIntervalSec.first() * 1000L)
        }
    }

    suspend fun refresh(host: ProbeHostEntity) {
        update(host.id) { it.copy(state = ConnState.CHECKING) }
        val client = clientFor(host)
        try {
            val st = client.status()
            update(host.id) { prev ->
                prev.copy(
                    state = ConnState.ONLINE,
                    status = st,
                    error = null,
                    lastUpdated = System.currentTimeMillis(),
                    cpuHistory = (prev.cpuHistory + st.cpuPercent).takeLast(60),
                )
            }
        } catch (e: ProbeException) {
            update(host.id) {
                it.copy(state = ConnState.OFFLINE, error = e.message, lastUpdated = System.currentTimeMillis())
            }
        }
    }

    fun refreshAsync(host: ProbeHostEntity) {
        scope.launch { refresh(host) }
    }

    private fun update(id: Long, transform: (HostRuntime) -> HostRuntime) {
        _runtime.value = _runtime.value.toMutableMap().apply {
            put(id, transform(getOrDefault(id, HostRuntime())))
        }
    }

    fun clientFor(host: ProbeHostEntity): ProbeClient {
        val token = try {
            KeystoreCipher.decrypt(host.tokenEnc)
        } catch (e: Exception) {
            throw ProbeException.Auth("本地凭据解密失败（可能是系统安全配置变更）")
        }
        return ProbeClient(
            host = host.host,
            port = host.port,
            token = token,
            useTls = host.useTls,
            fingerprint = host.fingerprint,
            allowInsecure = host.allowInsecureTls,
        )
    }

    suspend fun save(
        id: Long?,
        name: String,
        host: String,
        port: Int,
        token: String?,
        useTls: Boolean,
        fingerprint: String?,
        allowInsecureTls: Boolean,
        sshHostId: Long?,
    ): Long {
        val old = id?.let { dao.byId(it) }
        val tokenEnc = when {
            !token.isNullOrEmpty() -> KeystoreCipher.encrypt(token)
            else -> old?.tokenEnc ?: throw IllegalArgumentException("token required")
        }
        val entity = ProbeHostEntity(
            id = id ?: 0,
            name = name.trim().ifEmpty { host },
            host = host.trim(),
            port = port,
            tokenEnc = tokenEnc,
            useTls = useTls,
            fingerprint = fingerprint?.takeIf { it.isNotBlank() } ?: old?.fingerprint,
            allowInsecureTls = allowInsecureTls,
            sshHostId = sshHostId ?: old?.sshHostId,
            sortOrder = old?.sortOrder ?: 0,
        )
        return dao.upsert(entity)
    }

    suspend fun delete(host: ProbeHostEntity) {
        dao.delete(host)
    }
}
