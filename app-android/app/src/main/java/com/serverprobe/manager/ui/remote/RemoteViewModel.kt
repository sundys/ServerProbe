package com.serverprobe.manager.ui.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.serverprobe.manager.remote.Link
import com.serverprobe.manager.remote.LinkStore
import kotlinx.coroutines.flow.MutableStateFlow

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val store = LinkStore(app)

    /** 构造时同步预载，首帧即有数据，避免空态闪烁 */
    val links = MutableStateFlow<List<Link>>(store.load())

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
