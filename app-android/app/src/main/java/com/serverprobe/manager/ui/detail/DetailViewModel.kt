package com.serverprobe.manager.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.serverprobe.manager.App
import com.serverprobe.manager.data.db.ProbeHostEntity
import com.serverprobe.manager.data.db.SshHostEntity
import com.serverprobe.manager.data.probe.ProbeService
import com.serverprobe.manager.data.repo.ProbeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetailViewModel(app: Application, private val hostId: Long) : AndroidViewModel(app) {

    private val mgr = App.get(app)

    private val _host = MutableStateFlow<ProbeHostEntity?>(null)
    val host: StateFlow<ProbeHostEntity?> = _host

    init {
        viewModelScope.launch { _host.value = mgr.probeRepo.byId(hostId) }
    }

    val runtime: StateFlow<Map<Long, ProbeRepository.HostRuntime>> = mgr.probeRepo.runtime

    val sshHosts: StateFlow<List<SshHostEntity>> =
        mgr.sshRepo.hosts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val services = MutableStateFlow<List<ProbeService>?>(null)
    val servicesLoading = MutableStateFlow(false)
    val servicesError = MutableStateFlow<String?>(null)
    val actionResult = MutableStateFlow<String?>(null)

    fun loadServices() {
        val h = host.value ?: return
        viewModelScope.launch {
            servicesLoading.value = true
            servicesError.value = null
            try {
                services.value = mgr.probeRepo.clientFor(h).services()
            } catch (e: Exception) {
                servicesError.value = e.message
                services.value = emptyList()
            } finally {
                servicesLoading.value = false
            }
        }
    }

    fun serviceAction(name: String, action: String) {
        val h = host.value ?: return
        viewModelScope.launch {
            try {
                val resp = mgr.probeRepo.clientFor(h).serviceAction(action, name)
                actionResult.value = if (resp.ok) "已对 $name 执行 $action" else "执行失败: ${resp.error ?: resp.output}"
            } catch (e: Exception) {
                actionResult.value = "执行失败: ${e.message}"
            }
            loadServices()
        }
    }

    fun clearActionResult() {
        actionResult.value = null
    }

    fun refresh() {
        val h = host.value ?: return
        mgr.probeRepo.refreshAsync(h)
    }

}
