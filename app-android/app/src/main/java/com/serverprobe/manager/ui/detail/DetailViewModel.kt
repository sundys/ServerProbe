package com.serverprobe.manager.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.serverprobe.manager.App
import com.serverprobe.manager.data.db.ProbeHostEntity
import com.serverprobe.manager.data.db.SshHostEntity
import com.serverprobe.manager.data.probe.ProbeService
import com.serverprobe.manager.data.repo.ProbeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetailViewModel(app: Application, private val hostId: Long) : AndroidViewModel(app) {

    private val mgr = App.get(app)

    private val _host = MutableStateFlow<ProbeHostEntity?>(null)
    val host: StateFlow<ProbeHostEntity?> = _host

    init {
        viewModelScope.launch {
            _host.value = mgr.probeRepo.byId(hostId)
            loadServices()
        }
    }

    val runtime: StateFlow<Map<Long, ProbeRepository.HostRuntime>> = mgr.probeRepo.runtime

    val sshHosts: StateFlow<List<SshHostEntity>> =
        mgr.sshRepo.hosts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val services = MutableStateFlow<List<ProbeService>?>(null)
    val servicesLoading = MutableStateFlow(false)
    val servicesError = MutableStateFlow<String?>(null)
    val actionResult = MutableStateFlow<String?>(null)

    private var servicesJob: Job? = null

    /**
     * 读取 systemd 服务列表。
     *
     * host 由数据库异步载入，这里先等它出现再发请求：旧实现由界面
     * `LaunchedEffect(host?.id)` 触发，若首次调用时 host 尚未就绪会被静默
     * 跳过，表现为“只有搜索框、没有列表也没有任何提示”。
     * 同一个请求进行中时重复调用会被忽略（刷新按钮/重试按钮防抖）。
     */
    fun loadServices() {
        if (servicesJob?.isActive == true) return
        servicesJob = viewModelScope.launch {
            val h = host.filterNotNull().first()
            servicesLoading.value = true
            servicesError.value = null
            try {
                services.value = mgr.probeRepo.clientFor(h).services().distinctBy { it.unit }
            } catch (e: Exception) {
                servicesError.value = e.message ?: e.javaClass.simpleName
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
        // 顶部刷新按钮同时刷新服务列表（旧实现只刷新状态，服务列表要退出页面再进来）
        loadServices()
    }

}
