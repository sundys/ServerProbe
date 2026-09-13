package com.serverprobe.manager.ui.addprobe

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.serverprobe.manager.App
import com.serverprobe.manager.data.db.AUTH_KEY
import com.serverprobe.manager.data.db.AUTH_PASSWORD
import com.serverprobe.manager.data.probe.ProbeClient
import com.serverprobe.manager.data.probe.ProbeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddProbeViewModel(app: Application, private val editId: Long) : AndroidViewModel(app) {

    data class Form(
        val name: String = "",
        val host: String = "",
        val port: String = "9822",
        val token: String = "",
        val useTls: Boolean = true,
        val fingerprint: String = "",
        val allowInsecureTls: Boolean = false,
        val sshMode: Int = SSH_NONE, // 0 不绑定 1 绑定已有 2 新建
        val sshExistingId: Long? = null,
        val sshUsername: String = "",
        val sshAuthType: Int = AUTH_PASSWORD,
        val sshPassword: String = "",
        val sshPrivateKey: String? = null,
        val sshKeyPassphrase: String = "",
        val isEdit: Boolean = false,
        val tokenChanged: Boolean = false,
    )

    val form = MutableStateFlow(Form())
    val sshHosts = MutableStateFlow<List<com.serverprobe.manager.data.db.SshHostEntity>>(emptyList())
    val testing = MutableStateFlow(false)
    val testResult = MutableStateFlow<Pair<Boolean, String>?>(null)
    val fetchedFingerprint = MutableStateFlow<String?>(null)
    val saving = MutableStateFlow(false)
    val saved = MutableStateFlow(false)

    private val mgr = App.get(app)

    companion object {
        const val SSH_NONE = 0
        const val SSH_EXISTING = 1
        const val SSH_NEW = 2
    }

    init {
        viewModelScope.launch {
            mgr.sshRepo.hosts.collect { sshHosts.value = it }
        }
        if (editId > 0) {
            viewModelScope.launch {
                val e = mgr.probeRepo.byId(editId) ?: return@launch
                form.value = Form(
                    name = e.name,
                    host = e.host,
                    port = e.port.toString(),
                    useTls = e.useTls,
                    fingerprint = e.fingerprint ?: "",
                    allowInsecureTls = e.allowInsecureTls,
                    sshMode = if (e.sshHostId != null) SSH_EXISTING else SSH_NONE,
                    sshExistingId = e.sshHostId,
                    isEdit = true,
                )
            }
        }
    }

    fun update(transform: (Form) -> Form) {
        form.value = transform(form.value)
    }

    /** 测试连接：校验地址/Token/TLS，成功返回主机信息 */
    fun test() {
        val f = form.value
        viewModelScope.launch {
            testing.value = true
            testResult.value = null
            try {
                val info = withContext(Dispatchers.IO) {
                    ProbeClient(
                        host = f.host.trim(),
                        port = f.port.toIntOrNull() ?: 9822,
                        token = f.token.trim(),
                        useTls = f.useTls,
                        fingerprint = f.fingerprint.takeIf { it.isNotBlank() },
                        allowInsecure = f.allowInsecureTls,
                    ).info()
                }
                testResult.value = true to "连接成功：${info.hostname} · ${info.os} · 探针 v${info.probeVersion}"
            } catch (e: Exception) {
                testResult.value = false to (e.message ?: "未知错误")
            } finally {
                testing.value = false
            }
        }
    }

    /** 显式不校验地获取服务器证书指纹，用于锁定（防中间人） */
    fun fetchFingerprint() {
        val f = form.value
        viewModelScope.launch {
            try {
                val fp = withContext(Dispatchers.IO) {
                    ProbeClient(
                        host = f.host.trim(),
                        port = f.port.toIntOrNull() ?: 9822,
                        token = "-",
                        useTls = true,
                    ).fetchCertificateFingerprint()
                }
                fetchedFingerprint.value = fp
            } catch (e: Exception) {
                testResult.value = false to "获取指纹失败: ${e.message}"
            }
        }
    }

    fun useFingerprint(fp: String) {
        update { it.copy(fingerprint = fp) }
        fetchedFingerprint.value = null
    }

    fun dismissFingerprint() {
        fetchedFingerprint.value = null
    }

    fun clearTestResult() {
        testResult.value = null
    }

    fun save() {
        val f = form.value
        viewModelScope.launch {
            saving.value = true
            try {
                // 先创建新建的 SSH 身份
                var sshId: Long? = null
                if (f.sshMode == SSH_EXISTING) {
                    sshId = f.sshExistingId
                } else if (f.sshMode == SSH_NEW) {
                    sshId = mgr.sshRepo.save(
                        id = null,
                        alias = f.name.ifEmpty { f.host },
                        host = f.host,
                        port = 22,
                        username = f.sshUsername,
                        authType = f.sshAuthType,
                        password = f.sshPassword.takeIf { f.sshAuthType == AUTH_PASSWORD && it.isNotEmpty() },
                        privateKey = f.sshPrivateKey.takeIf { f.sshAuthType == AUTH_KEY },
                        keyPassphrase = f.sshKeyPassphrase.takeIf { it.isNotEmpty() },
                    )
                }
                mgr.probeRepo.save(
                    id = if (f.isEdit) editId else null,
                    name = f.name,
                    host = f.host,
                    port = f.port.toIntOrNull() ?: 9822,
                    token = f.token.takeIf { it.isNotBlank() },
                    useTls = f.useTls,
                    fingerprint = f.fingerprint.takeIf { it.isNotBlank() },
                    allowInsecureTls = f.allowInsecureTls,
                    sshHostId = sshId,
                )
                saved.value = true
            } catch (e: Exception) {
                testResult.value = false to "保存失败: ${e.message}"
            } finally {
                saving.value = false
            }
        }
    }
}
