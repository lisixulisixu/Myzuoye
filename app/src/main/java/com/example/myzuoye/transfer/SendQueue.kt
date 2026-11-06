package com.example.myzuoye.transfer

import android.net.Uri
import java.io.InputStream

/** 待发送的一项 */
data class SendItem(
    val uri: Uri,
    val name: String,
    /** 拿不到大小时为 0 或负数，发送端会自己扫一遍 */
    val size: Long
)

/**
 * 发送队列。
 *
 * 一次选十个文件，现在只有一个一个点、点一次等一次。这里把「选」和「发」解耦：
 * 选完就排进队列，当前这个发完自动接下一个，中途断连就把队列清掉并如实回报。
 *
 * 之所以不塞进 [TransferManager]：传输管理器只该管一条 TCP 连接上的收发，
 * 排队属于交互层的事，混在一起以后想改成「并行多连接」就得重构。
 */
class SendQueue(
    private val opener: (Uri) -> InputStream?,
    /** 某一项开始发送 */
    private val onItemStart: (item: SendItem, index: Int, total: Int) -> Unit,
    /** 某一项结束（成功与否） */
    private val onItemDone: (item: SendItem, ok: Boolean) -> Unit,
    /** 队列清空：成功数 / 失败数 */
    private val onAllDone: (okCount: Int, failCount: Int) -> Unit
) {

    private val pending = ArrayDeque<SendItem>()

    /** 正在发的那一项 */
    var current: SendItem? = null
        private set

    private var okCount = 0
    private var failCount = 0
    private var totalCount = 0

    /** 队列里还有几项（不含正在发的） */
    val pendingCount: Int get() = pending.size

    val isIdle: Boolean get() = current == null && pending.isEmpty()

    /**
     * 追加若干项并开始发送。
     * @return 实际入队的数量（0 表示当前没有可用的通道）
     */
    fun enqueue(items: List<SendItem>): Int {
        if (items.isEmpty()) return 0
        if (!TransferManager.isConnected) return 0

        // 第一批进来时重置统计；后续追加不动
        if (isIdle) {
            okCount = 0
            failCount = 0
            totalCount = 0
        }
        pending.addAll(items)
        totalCount += items.size
        if (current == null) startNext()
        return items.size
    }

    /** 丢弃队列（断连、用户取消） */
    fun clear() {
        pending.clear()
        current = null
        okCount = 0
        failCount = 0
        totalCount = 0
    }

    /**
     * 交给界面在 `TransferManager.onSendDone` 里调用。
     *
     * 必须由外部驱动而不是自己监听回调：TransferManager 的回调是单槽位的，
     * 界面已经挂了一个，再挂就会互相覆盖。
     */
    fun onTransferFinished(ok: Boolean) {
        val done = current ?: return
        current = null
        if (ok) okCount++ else failCount++
        onItemDone(done, ok)

        if (pending.isEmpty()) {
            onAllDone(okCount, failCount)
            okCount = 0
            failCount = 0
            totalCount = 0
            return
        }
        // 通道没了还接着发只会连续失败，直接清空并如实回报
        if (!TransferManager.isConnected) {
            failCount += pending.size
            pending.clear()
            onAllDone(okCount, failCount)
            okCount = 0
            failCount = 0
            totalCount = 0
            return
        }
        startNext()
    }

    private fun startNext() {
        val next = pending.removeFirstOrNull() ?: return
        current = next
        val index = totalCount - pending.size
        onItemStart(next, index, totalCount)

        val started = TransferManager.sendFile(
            openStream = { opener(next.uri) },
            fileName = next.name,
            fileSize = next.size
        )
        if (!started) {
            // 起不来就当这一项失败，避免队列卡死在「当前项」
            current = null
            failCount++
            onItemDone(next, false)
            if (pending.isEmpty()) {
                onAllDone(okCount, failCount)
                okCount = 0
                failCount = 0
                totalCount = 0
            } else {
                startNext()
            }
        }
    }
}
