package com.serverprobe.manager.ui.ssh

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.serverprobe.manager.App
import com.serverprobe.manager.data.crypto.KeystoreCipher
import com.serverprobe.manager.data.db.AUTH_KEY
import com.serverprobe.manager.data.db.AUTH_PASSWORD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SshFormViewModel(app: Application, private val editId: Long) : AndroidViewModel(app) {

    @Serializable
    data class Form(
        val alias: String = "",
        val host: String = "",
        val port: String = "22",
        val username: String = "",
        val authType: Int = AUTH_PASSWORD,
        val password: String = "",
        val privateKey: String? = null,
        val keyPassphrase: String = "",
        val isEdit: Boolean = false,
        val passwordChanged: Boolean = false,
        val privateKeyChanged: Boolean = false,
        val passphraseChanged: Boolean = false,
    )

    val form = MutableStateFlow(Form())
    val testing = MutableStateFlow(false)
    val testResult = MutableStateFlow<String?>(null)
    val saved = MutableStateFlow(false)

    private val mgr = App.get(app)
    private val prefs = app.getSharedPreferences("ssh_form_draft", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        if (editId > 0) load() else restoreDraft()
    }

    fun load() {
        viewModelScope.launch {
            val e = mgr.sshRepo.byId(editId) ?: return@launch
            form.value = Form(
                alias = e.alias,
                host = e.host,
                port = e.port.toString(),
                username = e.username,
                authType = e.authType,
                privateKey = null,
                isEdit = true,
            )
        }
    }

    fun update(transform: (Form) -> Form) {
        form.value = transform(form.value)
        // 新建场景持久化草稿：即使 ROM 在打开系统文件选择器期间查杀进程，回到应用也能恢复
        if (!form.value.isEdit) persistDraft(form.value)
    }

    private fun persistDraft(f: Form) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                prefs.edit()
                    .putString("draft", KeystoreCipher.encrypt(json.encodeToString(f)))
                    .apply()
            }
        }
    }

    private fun restoreDraft() {
        viewModelScope.launch(Dispatchers.IO) {
            val encoded = prefs.getString("draft", null) ?: return@launch
            val draft = runCatching { json.decodeFromString<Form>(KeystoreCipher.decrypt(encoded)) }
                .getOrNull() ?: return@launch
            form.value = draft
        }
    }

    private fun clearDraft() {
        prefs.edit().remove("draft").apply()
    }

    fun test() {
        val f = form.value
        viewModelScope.launch {
            testing.value = true
            try {
                testResult.value = withContext(Dispatchers.IO) {
                    val existing = if (f.isEdit) mgr.sshRepo.byId(editId) else null
                    val params = com.serverprobe.manager.ssh.SshManager.Params(
                        host = f.host.trim(),
                        port = f.port.toIntOrNull() ?: 22,
                        username = f.username.trim(),
                        authType = f.authType,
                        password = if (f.authType == AUTH_PASSWORD) {
                            f.password.ifEmpty { existing?.let { mgr.sshRepo.passwordOf(it) } ?: "" }
                        } else null,
                        privateKey = if (f.authType == AUTH_KEY) {
                            f.privateKey ?: existing?.let { mgr.sshRepo.privateKeyOf(it) }
                        } else null,
                        keyPassphrase = f.keyPassphrase.ifEmpty {
                            existing?.let { mgr.sshRepo.keyPassphraseOf(it) } ?: ""
                        }.ifEmpty { null },
                    )
                    val fp = com.serverprobe.manager.ssh.SshManager.testConnection(params)
                    "连接成功！主机密钥指纹: $fp"
                }
            } catch (e: Exception) {
                testResult.value = "测试失败: ${e.message}"
            } finally {
                testing.value = false
            }
        }
    }

    fun save() {
        val f = form.value
        viewModelScope.launch {
            try {
                val existing = if (f.isEdit) mgr.sshRepo.byId(editId) else null
                mgr.sshRepo.save(
                    id = if (f.isEdit) editId else null,
                    alias = f.alias,
                    host = f.host,
                    port = f.port.toIntOrNull() ?: 22,
                    username = f.username,
                    authType = f.authType,
                    password = if (f.passwordChanged || !f.isEdit) f.password.takeIf { it.isNotEmpty() } else null,
                    privateKey = if (f.privateKeyChanged || !f.isEdit) f.privateKey else null,
                    keyPassphrase = if (f.passphraseChanged || !f.isEdit) f.keyPassphrase.takeIf { it.isNotEmpty() } else null,
                    hostKeyFingerprint = existing?.hostKeyFingerprint,
                )
                clearDraft()
                saved.value = true
            } catch (e: Exception) {
                testResult.value = "保存失败: ${e.message}"
            }
        }
    }

    fun clearTestResult() {
        testResult.value = null
    }
}
