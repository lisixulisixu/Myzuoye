package com.example.myzuoye

import android.net.Uri
import com.example.myzuoye.transfer.SendItem
import com.example.myzuoye.transfer.SendQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送队列「断线保留 + 重连续传」的仪器测试。
 *
 * 真机/模拟器上跑才有真的 android.net.Uri；连通性与发送器都是注入的假件，
 * 整个队列的推进完全由测试驱动，不依赖网络。
 */
class SendQueueResumeTest {

    private fun item(name: String) = SendItem(Uri.parse("content://test/$name"), name, 100L)

    /** 记录型队列：自动记录回调，方便断言 */
    private class RecordingQueue(connected: Boolean) {
        var connected = connected
        val started = mutableListOf<String>()
        val doneOk = mutableListOf<String>()
        val doneFail = mutableListOf<String>()
        var allDoneOk = -1
        var allDoneFail = -1
        var interruptedAt = -1

        fun build(): SendQueue = SendQueue(
            opener = { null },
            isConnected = { connected },
            sender = { true },
            onItemStart = { item, _, _ -> started.add(item.name) },
            onItemDone = { item, ok ->
                if (ok) doneOk.add(item.name) else doneFail.add(item.name)
            },
            onAllDone = { ok, fail ->
                allDoneOk = ok
                allDoneFail = fail
            },
            onInterrupted = { remaining -> interruptedAt = remaining }
        )
    }

    @Test
    fun normalFlowSendsAllAndReports() {
        val rec = RecordingQueue(connected = true)
        val queue = rec.build()

        assertEquals(2, queue.enqueue(listOf(item("a"), item("b"))))
        // a 正在发（onItemStart 记录）
        assertEquals(listOf("a"), rec.started)
        queue.onTransferFinished(true)
        // b 自动接上
        assertTrue(rec.started.contains("b"))
        queue.onTransferFinished(true)
        assertEquals(2, rec.allDoneOk)
        assertEquals(0, rec.allDoneFail)
    }

    @Test
    fun disconnectKeepsQueueAndRequeuesCurrent() {
        val rec = RecordingQueue(connected = true)
        val queue = rec.build()
        queue.enqueue(listOf(item("a"), item("b")))

        // 通道断开，正在发的 a 失败
        rec.connected = false
        queue.onTransferFinished(false)

        // 不算失败、不报 all-done：现场保留等重连
        assertEquals(-1, rec.allDoneOk)
        assertTrue(rec.doneFail.isEmpty())
        // a 塞回队首 + b 还在，共 2 项
        assertEquals(2, rec.interruptedAt)
        assertEquals(2, queue.pendingCount)
    }

    @Test
    fun resumeAfterReconnectSendsFromBreakpoint() {
        val rec = RecordingQueue(connected = true)
        val queue = rec.build()
        queue.enqueue(listOf(item("a"), item("b")))
        rec.connected = false
        queue.onTransferFinished(false)

        // 重连成功
        rec.connected = true
        assertTrue(queue.resumeAfterReconnect())

        // 队首的 a 被重新发出（走续传握手从断点继续是 TransferManager 的事）
        val lastStarted = rec.started.last()
        assertEquals("a", lastStarted)
        // a 发完接着发 b，全部完成
        queue.onTransferFinished(true)
        queue.onTransferFinished(true)
        assertEquals(2, rec.allDoneOk)
        assertEquals(0, rec.allDoneFail)
    }

    @Test
    fun resumeDoesNothingWhenStillDisconnected() {
        val rec = RecordingQueue(connected = true)
        val queue = rec.build()
        queue.enqueue(listOf(item("a")))
        rec.connected = false
        queue.onTransferFinished(false)

        // 还没重连上：恢复调用不该误启动发送
        assertFalse(queue.resumeAfterReconnect())
        assertEquals(listOf("a"), rec.started)
    }

    @Test
    fun successfulItemThenDisconnectKeepsRest() {
        val rec = RecordingQueue(connected = true)
        val queue = rec.build()
        queue.enqueue(listOf(item("a"), item("b"), item("c")))

        // a 刚发完成功，通道断了：b、c 保留等续传
        rec.connected = false
        queue.onTransferFinished(true)
        // 有剩余项在等待，不报 all-done
        assertEquals(-1, rec.allDoneOk)
        assertEquals(2, rec.interruptedAt)

        rec.connected = true
        assertTrue(queue.resumeAfterReconnect())
        queue.onTransferFinished(true)
        queue.onTransferFinished(true)
        assertEquals(3, rec.allDoneOk)
    }

    @Test
    fun repeatedDisconnectsGiveUpAfterMaxRounds() {
        val rec = RecordingQueue(connected = true)
        val queue = rec.build()
        queue.enqueue(listOf(item("a")))

        repeat(SendQueue.RESUME_MAX_ROUNDS) {
            rec.connected = false
            queue.onTransferFinished(false)
            rec.connected = true
            assertTrue(queue.resumeAfterReconnect())
        }

        // 额度用完后再次断线失败：如实报失败，不再无限重试
        rec.connected = false
        queue.onTransferFinished(false)
        assertEquals(0, rec.allDoneOk)
        assertEquals(listOf("a"), rec.doneFail)
        assertEquals(1, rec.allDoneFail)
    }

    @Test
    fun clearResetsInterruptedState() {
        val rec = RecordingQueue(connected = true)
        val queue = rec.build()
        queue.enqueue(listOf(item("a"), item("b")))
        rec.connected = false
        queue.onTransferFinished(false)
        assertEquals(2, queue.pendingCount)

        queue.clear()
        assertEquals(0, queue.pendingCount)
        // 清空后即使通道恢复也没有东西可续
        rec.connected = true
        assertFalse(queue.resumeAfterReconnect())
    }
}
