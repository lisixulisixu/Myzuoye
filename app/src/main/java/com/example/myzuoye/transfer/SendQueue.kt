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
 * 选完就排进队列，当前这个发完自动接下一个。
 *
 * 断线不再等于「全军覆没」：正在发的一项塞回队首、剩余项原样保留，
 * 等通道自动重连成功后由 [resumeAfterReconnect] 接着发——
 * 接收端的半成品还在，大文件会走续传握手从断点继续。
 * 连环断线最多自动续 [RESUME_MAX_ROUNDS] 轮，超过就如实报失败，
 * 不做无限重试把用户困在转圈里。
 *
 * 之所以不塞进 [TransferManager]：传输管理器只该管一条 TCP 连接上的收发，
 * 排队属于交互层的事，混在一起以后想改成「并行多连接」就得重构。
 *
 * [isConnected] 与 [sender] 可注入：单测里用假的连通性与发送器驱动整个队列。
 */
class SendQueue(
    private val opener: (Uri) -> InputStream?,
    private val isConnected: () -> Boolean = { TransferManager.isConnected },
    private val sender: (SendItem) -> Boolean = { item ->
        TransferManager.sendFile({ opener(item.uri) }, item.name, item.size)
    },
    /** 某一项开始发送 */
    private val onItemStart: (item: SendItem, index: Int, total: Int) -> Unit,
    /** 某一项结束（成功与否） */
    private val onItemDone: (item: SendItem, ok: Boolean) -> Unit,
    /** 队列清空：成功数 / 失败数 */
    private val onAllDone: (okCount: Int, failCount: Int) -> Unit,
    /** 断线中断（队列保留、等待重连后续传），参数是保留的文件数 */
    private val onInterrupted: (remaining: Int) -> Unit = {}
) {

    private val pending = ArrayDeque<SendItem>()

    /** 正在发的那一项 */
    var current: SendItem? = null
        private set

    private var okCount = 0
    private var failCount = 0
    private var totalCount = 0

    /** 队列是否处于「断线中断、等待恢复」状态 */
    private var interrupted = false

    /** 已自动续传的轮数：断线 → 重连 → 接着发算一轮 */
    private var resumeRounds = 0

    /** 断线后最多自动续传的轮数，超过就放弃并如实报失败 */
    private val resumeMaxRounds = RESUME_MAX_ROUNDS

    /** 队列里还有几项（不含正在发的） */
    val pendingCount: Int get() = pending.size

    val isIdle: Boolean get() = current == null && pending.isEmpty()

    /**
     * 追加若干项并开始发送。
     * @return 实际入队的数量（0 表示当前没有可用的通道）
     */
    fun enqueue(items: List<SendItem>): Int {
        if (items.isEmpty()) return 0
        if (!isConnected()) return 0

        // 第一批进来时重置统计；后续追加不动
        if (isIdle) {
            okCount = 0
            failCount = 0
            totalCount = 0
            resumeRounds = 0
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
        interrupted = false
        resumeRounds = 0
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
        advanceAfterDone(done, ok)
    }

    /**
     * 通道恢复后接着发（界面在「变成 CONNECTED」时调用）。
     *
     * @return true 表示确实恢复了发送（之前有被断线中断的文件）
     */
    fun resumeAfterReconnect(): Boolean {
        if (!interrupted) return false
        if (!isConnected()) return false
        interrupted = false
        if (current == null) startNext()
        return true
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 一项有了结果之后的公共收尾。
     *
     * 失败要分两种情况：
     * - 通道还在：真失败（读不了文件之类），计数、报失败、接下一项；
     * - 通道没了：不算失败，塞回队首等重连后续传（轮次超限才放弃）。
     */
    private fun advanceAfterDone(done: SendItem, ok: Boolean) {
        if (ok) {
            okCount++
            onItemDone(done, true)
        } else if (isConnected()) {
            failCount++
            onItemDone(done, false)
        } else if (resumeRounds < resumeMaxRounds) {
            // 断线中断：保留现场，等 resumeAfterReconnect
            resumeRounds++
            interrupted = true
            pending.addFirst(done)
            onInterrupted(pending.size)
            return
        } else {
            // 反复断线仍发不出去：放弃，别把用户困在无限等待里
            failCount++
            onItemDone(done, false)
        }

        if (pending.isEmpty()) {
            reportAllDone()
            return
        }
        if (!isConnected()) {
            // 这一项刚结束（哪怕成功）通道就断了：剩余项保留等续传
            interrupted = true
            onInterrupted(pending.size)
            return
        }
        startNext()
    }

    private fun startNext() {
        val next = pending.removeFirstOrNull() ?: return
        current = next
        val index = totalCount - pending.size
        onItemStart(next, index, totalCount)

        val started = sender(next)
        if (!started) {
            // 起不来：通道还在就当这一项失败（避免队列卡死）；
            // 通道没了就按断线中断处理，塞回去等恢复
            current = null
            if (!isConnected() && resumeRounds < resumeMaxRounds) {
                resumeRounds++
                interrupted = true
                pending.addFirst(next)
                onInterrupted(pending.size)
                return
            }
            failCount++
            onItemDone(next, false)
            if (pending.isEmpty()) {
                reportAllDone()
            } else {
                startNext()
            }
        }
    }

    private fun reportAllDone() {
        onAllDone(okCount, failCount)
        okCount = 0
        failCount = 0
        totalCount = 0
        interrupted = false
        resumeRounds = 0
    }

    companion object {
        /** 断线后最多自动续传的轮数 */
        const val RESUME_MAX_ROUNDS = 3
    }
}
