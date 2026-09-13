package com.serverprobe.manager.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.serverprobe.manager.App
import com.serverprobe.manager.data.db.ProbeHostEntity
import com.serverprobe.manager.data.db.SshHostEntity
import com.serverprobe.manager.data.repo.ProbeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val mgr = App.get(app)

    val hosts: StateFlow<List<ProbeHostEntity>> =
        mgr.probeRepo.hosts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val runtime: StateFlow<Map<Long, ProbeRepository.HostRuntime>> = mgr.probeRepo.runtime

    val sshHosts: StateFlow<List<SshHostEntity>> =
        mgr.sshRepo.hosts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteProbe(host: ProbeHostEntity) = viewModelScope.launch { mgr.probeRepo.delete(host) }

    fun deleteSsh(host: SshHostEntity) = viewModelScope.launch { mgr.sshRepo.delete(host) }

    fun refreshNow(host: ProbeHostEntity) = mgr.probeRepo.refreshAsync(host)
}
