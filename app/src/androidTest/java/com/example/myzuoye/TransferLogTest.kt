package com.example.myzuoye.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 传输记录持久化测试（仪器测试：SharedPreferences 需要 Android 运行时）。
 */
@RunWith(AndroidJUnit4::class)
class TransferLogTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        TransferLog.clear(context)
    }

    @Test
    fun recordAndReadBack() {
        TransferLog.record(context, "photo.jpg", 12345L, TransferLog.DIR_RECEIVED, "AB12")
        val all = TransferLog.all(context)
        assertEquals(1, all.size)
        val e = all[0]
        assertEquals("photo.jpg", e.name)
        assertEquals(12345L, e.size)
        assertEquals(TransferLog.DIR_RECEIVED, e.dir)
        assertEquals("AB12", e.md5)
        assertTrue(e.at > 0)
    }

    @Test
    fun newestFirst() {
        TransferLog.record(context, "a.txt", 1L, TransferLog.DIR_SENT)
        Thread.sleep(5)
        TransferLog.record(context, "b.txt", 2L, TransferLog.DIR_RECEIVED)
        val names = TransferLog.all(context).map { it.name }
        assertEquals(listOf("b.txt", "a.txt"), names)
    }

    @Test
    fun latestByNameFindsMostRecent() {
        TransferLog.record(context, "x.bin", 10L, TransferLog.DIR_SENT, "MD1")
        TransferLog.record(context, "y.bin", 20L, TransferLog.DIR_RECEIVED)
        assertEquals("x.bin", TransferLog.latestByName(context, "x.bin")?.name)
        assertEquals(null, TransferLog.latestByName(context, "missing.txt"))
    }

    @Test
    fun persistsAcrossClearOnly() {
        // 重启不丢：SharedPreferences 天然持久，这里验证 clear 后确实清空
        TransferLog.record(context, "c.txt", 3L, TransferLog.DIR_SENT)
        TransferLog.clear(context)
        assertEquals(0, TransferLog.all(context).size)
    }

    @Test
    fun recordsDurationAndPeerForCard() {
        TransferLog.record(
            context, "movie.mp4", 88L, TransferLog.DIR_SENT, "MD9",
            ms = 2_400L, peer = "P60 Pro"
        )
        val e = TransferLog.all(context).first()
        assertEquals(2_400L, e.ms)
        assertEquals("P60 Pro", e.peer)
        // latest() 也要带上，工具菜单的「最近一次卡片」走这条路
        assertEquals(2_400L, TransferLog.latest(context)?.ms)
        assertEquals("P60 Pro", TransferLog.latest(context)?.peer)
    }

    @Test
    fun oldRecordsWithoutNewFieldsStillReadable() {
        // 老版本写下的 JSON 没有 ms / peer：读出来应是 0 与空串，不能让解析炸掉
        context.getSharedPreferences("transfer_log", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "items",
                """[{"name":"legacy.bin","size":42,"dir":"R","at":1700000000000,"md5":"AB"}]"""
            )
            .commit()
        val e = TransferLog.all(context).first()
        assertEquals("legacy.bin", e.name)
        assertEquals(0L, e.ms)
        assertEquals("", e.peer)
    }
}
