package com.example.myzuoye.transfer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 传输记录的持久化。
 *
 * 聊天历史只在内存里（重启全丢），文件系统里只有收到的文件本体。
 * 这里把「谁在什么时候传过什么」落盘：文件名、大小、方向、时间、MD5。
 * 价值：
 * - 重启后传输历史还在，配合已收文件页能看到每个文件的来龙去脉；
 * - 后续做统计（共传了多少、最常传的文件）有数据源。
 *
 * 实现照抄 [SendHistory] 的 SharedPreferences + JSON 模式：
 * 数据量上限内（[MAX] 条）读写都是毫秒级，不值得为它建一张 SQLite 表。
 */
object TransferLog {

    private const val PREFS = "transfer_log"
    private const val KEY_ITEMS = "items"

    private const val MAX = 200

    /** dir 用单字符存，JSON 体积和可读性折中 */
    const val DIR_SENT = "S"
    const val DIR_RECEIVED = "R"

    data class Entry(
        val name: String,
        val size: Long,
        val dir: String,
        val at: Long,
        val md5: String,
        /** 传输用时（毫秒）。老记录没存这个字段，读出来是 0 */
        val ms: Long = 0L,
        /** 对端设备名（用于传输卡片）。老记录为空 */
        val peer: String = ""
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                if (name.isEmpty()) continue
                add(
                    Entry(
                        name = name,
                        size = obj.optLong("size", -1L),
                        dir = obj.optString("dir", DIR_RECEIVED),
                        at = obj.optLong("at", 0L),
                        md5 = obj.optString("md5"),
                        ms = obj.optLong("ms", 0L),
                        peer = obj.optString("peer", "")
                    )
                )
            }
        }
    }

    /** 记一条成功传输；最新在最前，超上限从尾部截断 */
    fun record(
        context: Context,
        name: String,
        size: Long,
        dir: String,
        md5: String = "",
        /** 传输用时（毫秒），用于卡片上的「用时 / 平均速度」 */
        ms: Long = 0L,
        /** 对端设备名，用于卡片上的人物标签 */
        peer: String = ""
    ) {
        val updated = buildList {
            add(Entry(name, size, dir, System.currentTimeMillis(), md5, ms, peer))
            addAll(all(context))
        }.take(MAX)

        val array = JSONArray()
        updated.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("size", it.size)
                    .put("dir", it.dir)
                    .put("at", it.at)
                    .put("md5", it.md5)
                    .put("ms", it.ms)
                    .put("peer", it.peer)
            )
        }
        prefs(context).edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    /** 按文件名找最近的记录（用于已收文件页补显传输时间） */
    fun latestByName(context: Context, name: String): Entry? =
        all(context).firstOrNull { it.name == name }

    /**
     * 只取最新一条，不构建整个列表。
     *
     * 桌面小组件的状态行每次刷新都会问一次「最近传了什么」，
     * 而记录上限是 200 条——这里只读数组第 0 个元素，不为主屏上一行字解析全量。
     */
    fun latest(context: Context): Entry? {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return null
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        val obj = array.optJSONObject(0) ?: return null
        val name = obj.optString("name")
        if (name.isEmpty()) return null
        return Entry(
            name = name,
            size = obj.optLong("size", -1L),
            dir = obj.optString("dir", DIR_RECEIVED),
            at = obj.optLong("at", 0L),
            md5 = obj.optString("md5"),
            ms = obj.optLong("ms", 0L),
            peer = obj.optString("peer", "")
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ITEMS).apply()
    }
}
