package com.example.myzuoye.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 最近发送过的文件。
 *
 * 目的很实际：从相册/文件管理器里翻出一个文件发过去，过十分钟想再发一次，
 * 就得重新翻一遍目录。这里把发过的记下来，主界面「更多」里点一下就能重发。
 *
 * 两个实现细节：
 * - **持久化 URI 权限**：SAF 返回的 `content://` 只在本次授权内可读，
 *   重发时会读不到。所以记录时顺手 `takePersistableUriPermission`（允许失败，
 *   某些来源不授予持久权限，那就退化成「重发时提示需要重新选择」）。
 * - **有上限的列表**：只留最近 [MAX] 条，同一个文件重复发送会提到最前面而不是重复记录。
 */
object SendHistory {

    private const val PREFS = "send_history"
    private const val KEY_ITEMS = "items"

    /** 留 8 条：再多菜单里就点不过来了 */
    private const val MAX = 8

    data class Entry(val uri: String, val name: String, val size: Long, val at: Long)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recent(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val uri = obj.optString("uri")
                if (uri.isEmpty()) continue
                add(
                    Entry(
                        uri = uri,
                        name = obj.optString("name"),
                        size = obj.optLong("size", -1L),
                        at = obj.optLong("at", 0L)
                    )
                )
            }
        }
    }

    /** 记一次发送；同一个 URI 只保留最新一条 */
    fun record(context: Context, uri: Uri, name: String, size: Long) {
        val uriString = uri.toString()
        val updated = buildList {
            add(Entry(uriString, name, size, System.currentTimeMillis()))
            addAll(recent(context).filterNot { it.uri == uriString })
        }.take(MAX)

        val array = JSONArray()
        updated.forEach {
            array.put(
                JSONObject()
                    .put("uri", it.uri)
                    .put("name", it.name)
                    .put("size", it.size)
                    .put("at", it.at)
            )
        }
        prefs(context).edit().putString(KEY_ITEMS, array.toString()).apply()

        // 让下次重发还能读：失败就算了，说明这个来源没给持久权限
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ITEMS).apply()
    }
}
