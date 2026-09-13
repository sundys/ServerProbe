package com.serverprobe.manager.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.serverprobe.manager.App
import com.serverprobe.manager.data.repo.DisplayMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    val displayMode = MutableStateFlow(DisplayMode.SYSTEM)
    val pollInterval = MutableStateFlow(15)
    val biometricLock = MutableStateFlow(false)

    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<Pair<Boolean, String>?>(null)

    private val mgr = App.get(app)

    init {
        viewModelScope.launch {
            mgr.settings.displayMode.collect { displayMode.value = it }
        }
        viewModelScope.launch {
            mgr.settings.pollIntervalSec.collect { pollInterval.value = it }
        }
        viewModelScope.launch {
            mgr.settings.biometricLock.collect { biometricLock.value = it }
        }
    }

    fun setDisplayMode(mode: DisplayMode) {
        viewModelScope.launch { mgr.settings.setDisplayMode(mode) }
    }

    fun setPollInterval(sec: Int) {
        viewModelScope.launch { mgr.settings.setPollIntervalSec(sec) }
    }

    fun setBiometricLock(enabled: Boolean) {
        viewModelScope.launch { mgr.settings.setBiometricLock(enabled) }
    }

    /** 导出备份到用户选择的 Uri */
    fun exportBackup(uri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            busy.value = true
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        mgr.backupManager.export(it, passphrase)
                    } ?: throw IllegalStateException("无法打开输出文件")
                }
                message.value = true to "备份已导出（口令加密），请妥善保管文件与口令"
            } catch (e: Exception) {
                message.value = false to "导出失败: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    /** 从 Uri 恢复备份 */
    fun importBackup(uri: Uri, passphrase: CharArray, replaceAll: Boolean) {
        viewModelScope.launch {
            busy.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        mgr.backupManager.import(it, passphrase, replaceAll)
                    } ?: throw IllegalStateException("无法打开备份文件")
                }
                message.value = true to "恢复完成：探针主机 ${result.probes} 个，SSH 主机 ${result.sshHosts} 个"
            } catch (e: Exception) {
                message.value = false to "恢复失败: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    fun clearMessage() {
        message.value = null
    }
}
