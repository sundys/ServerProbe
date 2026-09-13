package com.serverprobe.manager.ui.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.serverprobe.manager.App
import com.serverprobe.manager.remote.Link
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val store = App.get(app).let { com.serverprobe.manager.remote.LinkStore(it) }

    val links = MutableStateFlow<List<Link>>(emptyList())

    fun refresh() {
        links.value = store.load()
    }

    fun upsert(link: Link) {
        store.upsert(link)
        refresh()
    }

    fun delete(id: String) {
        store.delete(id)
        refresh()
    }

    fun markOpened(id: String) {
        store.markOpened(id)
        refresh()
    }
}
