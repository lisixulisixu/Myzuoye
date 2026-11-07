package com.example.myzuoye

import com.example.myzuoye.transfer.ChatEntry
import com.example.myzuoye.util.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天条目与时间标签的纯 JVM 测试。
 *
 * 气泡的左右对齐、颜色这些是仪器渲染测试的事；这里钉住的是
 * 「传输层写历史时方向标记是否正确」——这是自动复制、长按重发
 * 这些功能的地基，标错了方向功能就会跟着错。
 */
class ChatEntryTest {

    @Test
    fun `时间戳格式化为 HH-mm`() {
        // 2026-11-07 14:08:30 (UTC+8) = epoch 1791797310000
        val at = 1_791_797_310_000L
        assertTrue("格式应为 HH:mm 形态", Formatters.clockTime(at).matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun `同一分钟的时间戳产出相同标签`() {
        // 整分钟起点（秒数为 0），+59s 仍在同一分钟内
        val base = 1_791_797_280_000L
        val a = Formatters.clockTime(base)
        val b = Formatters.clockTime(base + 59_000L)
        assertEquals(a, b)
        // 一分钟之后标签要变，否则气泡时间全是同一个数
        val c = Formatters.clockTime(base + 61_000L)
        assertNotEquals(a, c)
    }

    @Test
    fun `默认条目为系统类型且不带文件`() {
        val entry = ChatEntry(ChatEntry.Kind.SYSTEM, "通道已建立")
        assertEquals(ChatEntry.Kind.SYSTEM, entry.kind)
        assertNull(entry.fileUri)
        assertTrue(entry.at > 0)
    }

    @Test
    fun `四种形态互不混淆`() {
        val kinds = ChatEntry.Kind.entries
        assertEquals(4, kinds.size)
        assertEquals(
            kinds.toSet(),
            setOf(ChatEntry.Kind.MINE, ChatEntry.Kind.PEER, ChatEntry.Kind.SYSTEM, ChatEntry.Kind.FILE_IN)
        )
    }

    @Test
    fun `文件卡条目带 Uri`() {
        val entry = ChatEntry(
            ChatEntry.Kind.FILE_IN,
            "文件接收完成：photo.jpg，已保存到 下载/WiFi直传/photo.jpg",
            fileUri = "content://downloads/photo"
        )
        assertEquals("content://downloads/photo", entry.fileUri)
        assertTrue(entry.text.contains("photo.jpg"))
    }
}
