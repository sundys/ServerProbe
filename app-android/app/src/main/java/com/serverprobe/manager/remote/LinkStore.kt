package com.serverprobe.manager.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** SharedPreferences + org.json 持久化，零第三方依赖。 */
class LinkStore(context: Context) {

    private val prefs = context.getSharedPreferences("zrmt_links", Context.MODE_PRIVATE)

    fun load(): MutableList<Link> {
        val json = prefs.getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    val id = o.optString("id")
                    val url = o.optString("url")
                    if (id.isNotEmpty() && url.isNotEmpty()) {
                        Link(
                            id = id,
                            url = url,
                            name = o.optString("name", LinkParser.DEFAULT_NAME),
                            version = o.optString("version"),
                            host = o.optString("host"),
                            createdAt = o.optLong("createdAt"),
                            lastOpenedAt = o.optLong("lastOpenedAt")
                        )
                    } else null
                }
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(links: List<Link>) {
        val arr = JSONArray()
        links.forEach { l ->
            arr.put(JSONObject().apply {
                put("id", l.id)
                put("url", l.url)
                put("name", l.name)
                put("version", l.version)
                put("host", l.host)
                put("createdAt", l.createdAt)
                put("lastOpenedAt", l.lastOpenedAt)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(link: Link) {
        val list = load()
        list.removeAll { it.id == link.id }
        list.add(0, link)
        save(list)
    }

    fun delete(id: String) = save(load().filterNot { it.id == id })

    fun find(id: String): Link? = load().firstOrNull { it.id == id }

    fun markOpened(id: String) {
        val list = load()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(lastOpenedAt = System.currentTimeMillis())
            save(list)
        }
    }

    companion object {
        private const val KEY = "links"
    }
}
