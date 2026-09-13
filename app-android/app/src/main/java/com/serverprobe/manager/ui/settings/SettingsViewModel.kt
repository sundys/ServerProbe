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

import java.io.File

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    val displayMode = MutableStateFlow(DisplayMode.SYSTEM)
    val pollInterval = MutableStateFlow(15)
    val biometricLock = MutableStateFlow(false)

    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<Pair<Boolean, String>?>(null)

    // ---- 检测更新 ----
    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        data class Available(val info: com.serverprobe.manager.update.UpdateManager.ReleaseInfo) : UpdateState()
        data class UpToDate(val version: String) : UpdateState()
        data class Failed(val msg: String) : UpdateState()
        data class Downloading(val received: Long, val total: Long) : UpdateState()
        data class Downloaded(val file: File) : UpdateState()
    }

    val currentVersion = MutableStateFlow("1.0.0")
    val updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)

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
        runCatching {
            val pm = getApplication<Application>().packageManager
            currentVersion.value = pm.getPackageInfo(getApplication<Application>().packageName, 0).versionName ?: ""
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

    // ---- 检测更新 ----

    fun checkUpdate() {
        if (updateState.value is UpdateState.Checking || updateState.value is UpdateState.Downloading) return
        updateState.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val info = com.serverprobe.manager.update.UpdateManager.fetchLatest()
                if (com.serverprobe.manager.update.UpdateManager.isNewer(info.tag, currentVersion.value)) {
                    updateState.value = UpdateState.Available(info)
                } else {
                    updateState.value = UpdateState.UpToDate(info.version)
                }
            } catch (e: Exception) {
                updateState.value = UpdateState.Failed(e.message ?: "检测失败")
            }
        }
    }

    /** 下载新版本 APK，完成后自动唤起安装 */
    fun downloadUpdate(info: com.serverprobe.manager.update.UpdateManager.ReleaseInfo) {
        updateState.value = UpdateState.Downloading(0, info.apkSize)
        viewModelScope.launch {
            try {
                val dest = File(
                    getApplication<Application>().cacheDir,
                    "apk/${info.apkName.ifEmpty { "remoto-update.apk" }}",
                )
                val file = com.serverprobe.manager.update.UpdateManager.downloadApk(
                    info.apkUrl, dest,
                ) { received, total ->
                    updateState.value = UpdateState.Downloading(received, total)
                }
                updateState.value = UpdateState.Downloaded(file)
                com.serverprobe.manager.update.UpdateManager.installApk(getApplication(), file)
            } catch (e: Exception) {
                updateState.value = UpdateState.Failed(e.message ?: "下载失败")
            }
        }
    }

    fun dismissUpdate() {
        updateState.value = UpdateState.Idle
    }
}
