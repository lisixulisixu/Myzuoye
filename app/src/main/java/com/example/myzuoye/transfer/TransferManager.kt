package com.example.myzuoye.transfer

import android.content.Context
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.myzuoye.service.TransferService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * TCP 文本通道。
 *
 * 角色约定（Wi-Fi Direct 的固定拓扑）：
 * - 组主（GO）地址固定为 192.168.49.1，只有它能被连接，所以组主做服务端，监听 [TransferProtocol.PORT]；
 * - 客户端连到组主地址。
 *
 * 线程模型：所有 socket 读写都在 IO 协程里，状态与消息通过 [onStateChanged] / [onMessage]
 * 回主线程。使用 application 级的协程作用域，所以界面旋转不会把连接打断
 * （M4 会再包一层前台 Service）。
 */
object TransferManager {

    /** 应用上下文，用于控制前台服务（保活）。由 MainActivity 初始化 */
    private var appContext: Context? = null

    /** 前台服务是否已启动（跟踪状态，避免重复 start） */
    @Volatile
    private var serviceStarted = false

    /** 上一次更新前台服务通知的时刻，用于节流（见 [NOTIFY_THROTTLE_MS]） */
    private val notifyLimiter = RateLimiter(NOTIFY_THROTTLE_MS)

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** 文件接收进度回调（主线程）：比例 0..1 + 瞬时速度（字节/秒） */
    var onReceiveProgress: ((Float, Long) -> Unit)? = null

    /** 文件接收完成回调（主线程）。成功时为最终文件路径，失败时为 null */
    var onReceiveDone: ((String?) -> Unit)? = null

    /** 文件发送进度回调（主线程）：比例 0..1 + 瞬时速度（字节/秒） */
    var onSendProgress: ((Float, Long) -> Unit)? = null

    /** 文件发送完成回调（主线程）。成功时为 true */
    var onSendDone: ((Boolean) -> Unit)? = null

    enum class State {
        /** 没有任何通道 */
        IDLE,

        /** 组主已开始监听，等待对方接入 */
        LISTENING,

        /** 客户端正在尝试连接 */
        CONNECTING,

        /** 双向通道可用 */
        CONNECTED,

        /** 出错，具体原因见附带文案 */
        ERROR
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var listenJob: Job? = null
    private var connectJob: Job? = null

    private var serverSocket: ServerSocket? = null

    /** 原子标志：是否处于监听状态，防止 startServer 被并发/重复触发时的 bind 竞态 */
    @Volatile
    private var listening = false

    /**
     * 要绑定的 P2P 网络。
     *
     * 进程级 bindProcessToNetwork 在部分 ROM 上会返回 true 却不生效，
     * 所以建立 socket 时再显式 bindSocket 一次，确保流量真的从 P2P 网卡走。
     */
    @Volatile
    private var p2pNetwork: Network? = null

    fun setP2pNetwork(network: Network?) {
        p2pNetwork = network
    }

    /**
     * 设置本机作为组主时的配对码；传 null / 空串表示不校验。
     *
     * 只影响「以后接入的设备」，当前已连上的那条连接不会被踢掉。
     */
    fun setPairCode(code: String?) {
        pairCode = code?.trim()?.takeIf { it.isNotEmpty() }
        // 正在监听时立刻把新状态写出去，用户切完开关就能在界面上看到码
        if (listening) {
            val c = pairCode
            emit(
                State.LISTENING,
                if (c == null) "已监听端口 $port，配对码校验已关闭"
                else "已监听端口 $port，配对码 $c，等待对方接入"
            )
        }
    }

    /** 本机当前的配对码（没有则为 null），界面用来回显 */
    fun currentPairCode(): String? = pairCode

    /**
     * 用新的配对码重连上一次的地址。
     *
     * 场景：组主开了校验，我们连上去被回了一个拒绝帧，
     * 界面弹框让用户输入 4 位码，输完走这里。
     */
    fun retryWithPairCode(code: String): Boolean {
        val host = lastHost ?: return false
        clientPairCode = code.trim()
        connect(host, lastPort)
        return true
    }

    @Volatile
    private var socket: Socket? = null

    /**
     * 组主开启的配对码；null / 空串表示不校验。
     *
     * 由界面在启动与切换开关时写入：开了之后，接入方必须在报到帧里带对码，
     * 否则直接拒绝（连文本通道都不给）。
     */
    @Volatile
    private var pairCode: String? = null

    /** 客户端自己记着的配对码：连不上被打回时，界面输入正确码后由 [retryWithPairCode] 更新 */
    @Volatile
    private var clientPairCode: String = ""

    /** 上一次连接的地址，用于「配对码输错后重试」与「意外断连自动重连一次」 */
    @Volatile
    private var lastHost: String? = null

    @Volatile
    private var lastPort: Int = TransferProtocol.PORT

    /** 断连自动重连只用一次，避免来回刷 */
    private var autoReconnectUsed = false

    /** 对端因配对码拒绝时回调（主线程），参数是对方给的原因 */
    var onPairRejected: ((String) -> Unit)? = null

    /** 最近一次真的有字节流动的时刻（毫秒），断连看门狗用它判断链路是否还活着 */
    @Volatile
    private var lastIoAt = 0L

    private var watchdogJob: Job? = null

    @Volatile
    private var output: DataOutputStream? = null
    private val writeLock = Any()

    /** 续传握手：发送端在这里等接收端告知「我已经有多少字节」 */
    private val resumeQueue = LinkedBlockingQueue<Long>()

    private val messageBuffer = mutableListOf<ChatEntry>()

    /**
     * 正在接收的文件落盘目标；同一时刻只允许一个接收任务。
     * 具体写公共下载目录还是应用目录，由 [ReceivedFileStore] 按系统版本决定。
     */
    @Volatile
    private var receiveTarget: ReceiveTarget? = null

    /** 边收边算 MD5，避免收完再整读一遍（MediaStore 的 Uri 也不方便当 File 读） */
    private var receiveDigest: MessageDigest? = null

    private var receiveRemaining: Long = 0
    private var receiveTotal: Long = 0
    private var receiveMd5: String = ""

    /** 已写字节数（进度与测速都基于它，比「总数 - 剩余」可靠） */
    private var receiveWritten: Long = 0

    /** 速度滑动平均（字节/秒）与上一次采样点 */
    private var receiveSpeed: Long = 0
    private var receiveSampleAt: Long = 0
    private var receiveSampleBytes: Long = 0

    /** 进度回调节流时间戳：不必每个分片都往主线程抛一次 */
    private var receivePostAt: Long = 0

    /** 本次文件因故（头解析失败 / 建文件失败 / 写盘失败）已放弃，后续分片直接丢弃 */
    private var receiveDiscarding = false

    /** 当前正在接收的文件名，供界面显示进度文案 */
    @Volatile
    var receivingFileName: String = ""
        private set

    /** 当前正在接收的文件总字节数（未知时为 0），界面据此估算剩余时间 */
    @Volatile
    var receivingTotalBytes: Long = 0
        private set

    /** 当前正在发送的文件总字节数（未知时为 0） */
    @Volatile
    var sendingTotalBytes: Long = 0
        private set

    /** 是否正在发送文件 */
    @Volatile
    private var sending = false

    /** 发送协程，取消发送时用它中断循环 */
    private var sendJob: Job? = null

    @Volatile
    var state: State = State.IDLE
        private set

    var onStateChanged: ((State, String) -> Unit)? = null
    /** 每条聊天消息的结构化回调（方向 + 时间），界面据此摆气泡 */
    var onMessage: ((ChatEntry) -> Unit)? = null

    val isConnected: Boolean get() = state == State.CONNECTED

    /** 当前监听 / 连接使用的端口（默认 [TransferProtocol.PORT]） */
    var port: Int = TransferProtocol.PORT
        private set

    /** 历史消息（最多保留 [MAX_HISTORY] 条），界面重建时用它恢复聊天区 */
    fun history(): List<ChatEntry> = synchronized(messageBuffer) { messageBuffer.toList() }

    // ------------------------------------------------------------------ 服务端

    /**
     * 以组主身份开始监听。重复调用是安全的（已在监听则直接返回）。
     */
    fun startServer(port: Int = TransferProtocol.PORT) {
        if (listening) return

        this.port = port
        listening = true
        listenJob = scope.launch {
            var ss: ServerSocket? = null
            try {
                ss = bindWithRetry(port)
                serverSocket = ss
                // 开了校验就把码打在状态里，用户能直接念给对面听
                val code = pairCode
                emit(
                    State.LISTENING,
                    if (code == null) "已监听端口 $port，等待对方接入"
                    else "已监听端口 $port，配对码 $code，等待对方接入"
                )

                while (currentCoroutineContext().isActive) {
                    val client = ss.accept()

                    // 同一时刻只服务一个对端。
                    // 组主模式下第二台设备其实连得上 TCP，如果什么都不做，
                    // 它会一直排队、双方都以为在传——所以要明确回一句再断开。
                    if (socket != null) {
                        runCatching {
                            val busyOut = DataOutputStream(client.getOutputStream())
                            TransferProtocol.writeText(busyOut, BUSY_MESSAGE)
                            busyOut.flush()
                        }
                        runCatching { client.close() }
                        appendAndNotify("有设备尝试接入，但当前已有连接，已拒绝")
                        continue
                    }

                    // 开了配对码校验就先验码，验不过直接关门，
                    // 连「已接入」状态都不发——不给未授权设备留任何通道
                    if (!verifyPair(client)) continue

                    emit(State.CONNECTED, "对方已接入：${client.inetAddress.hostAddress}")
                    autoReconnectUsed = false
                    readLoop(client)
                    emit(State.LISTENING, "连接已断开，继续等待")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(State.ERROR, "监听失败：${e.message}")
            } finally {
                serverSocket = null
                listening = false
                runCatching { ss?.close() }
            }
        }
    }

    /**
     * 绑定端口。刚 stop 后端口可能处于 TIME_WAIT，所以失败时重试几次。
     */
    private fun bindWithRetry(port: Int): ServerSocket {
        var lastError: Exception? = null
        repeat(BIND_RETRIES) { attempt ->
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                return ss
            } catch (e: Exception) {
                lastError = e
                runCatching { ss?.close() }
                Thread.sleep(BIND_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("bind 失败")
    }

    /** 把客户端 socket 显式绑到 P2P 网络，保证流量从 P2P 网卡出去 */
    private fun bindToP2pNetwork(socket: Socket) {
        val net = p2pNetwork ?: return
        runCatching { net.bindSocket(socket) }
            .onFailure { emit(State.CONNECTING, "绑定 P2P 网络失败：${it.message}") }
    }

    /**
     * 组主侧：校验接入方带来的配对码。
     *
     * 这里刻意用**不缓冲**的输入流。如果套一层 `BufferedInputStream`，
     * 它可能把报到帧后面的字节一起读进自己的缓冲区，而 [readLoop] 又会
     * 另起一个流去读同一个 socket——那部分字节就永久丢了。
     * `readFrame` 本身按长度 `readFully`，多读不了一个字节。
     */
    private fun verifyPair(client: Socket): Boolean {
        val required = pairCode?.trim().orEmpty()
        if (required.isEmpty()) return true

        return try {
            // 报到帧在连上之后立刻发出，正常几十毫秒就到；等不到就说明
            // 对端是没带码的老客户端，或者干脆不是我们的客户端
            client.soTimeout = PAIR_WAIT_MS
            val input = DataInputStream(client.getInputStream())
            val code = TransferProtocol.readFrame(input)?.asPairCode()
            client.soTimeout = 0

            if (code == required) {
                appendAndNotify("配对码校验通过：${client.inetAddress.hostAddress}")
                true
            } else {
                runCatching {
                    val out = DataOutputStream(client.getOutputStream())
                    TransferProtocol.writePairReject(
                        out,
                        if (code.isNullOrBlank()) "对方没提供配对码" else "配对码不正确"
                    )
                }
                appendAndNotify(
                    if (code.isNullOrBlank()) "有设备未提供配对码，已拒绝接入"
                    else "有设备配对码不正确，已拒绝接入"
                )
                runCatching { client.close() }
                false
            }
        } catch (e: Exception) {
            // SocketTimeoutException 会走到这里：对端连上却一声不吭，按拒绝处理
            appendAndNotify("接入方没有完成配对，已断开：${e.javaClass.simpleName}")
            runCatching { client.close() }
            false
        }
    }

    // ------------------------------------------------------------------ 客户端

    /**
     * 以客户端身份连接组主。
     *
     * 重试策略针对「P2P 组刚形成、本机还没拿到 192.168.49.x 地址」这个窗口做了优化：
     * - 单次超时压到 1 秒（P2P 链路 RTT 是毫秒级，1 秒连不上就说明还没就绪）；
     * - 重试间隔从 200ms 起步逐步拉长，地址一分配好就能立刻连上，
     *   不像原来每次固定等 1 秒、失败还要 4 秒超时。
     */
    fun connect(
        host: String,
        port: Int = TransferProtocol.PORT,
        attempts: Int = 16,
        auto: Boolean = false
    ) {
        if (state == State.CONNECTED) return
        if (connectJob?.isActive == true) return

        // 用户自己发起的连接重新给一次自动重连额度；
        // 自动重连自身不能再重置，否则「连上就被踢」会变成无限重连
        if (!auto) autoReconnectUsed = false

        this.port = port
        connectJob = scope.launch {
            var lastError: Exception? = null
            repeat(attempts) { index ->
                // 每次重试都刷状态太吵，只在首次和每 4 次时更新
                if (index == 0 || index % 4 == 0) {
                    emit(State.CONNECTING, "正在连接 $host:$port（第 ${index + 1} 次）")
                }
                try {
                    val client = Socket()
                    bindToP2pNetwork(client)
                    client.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

                    // 报到：把本机记得的配对码带上。组主开了校验而码不对，
                    // 它会立刻回一个拒绝帧并把连接关掉，走 [readLoop] 里的处理。
                    runCatching {
                        val hello = DataOutputStream(client.getOutputStream())
                        TransferProtocol.writeHello(hello, clientPairCode)
                        hello.flush()
                    }

                    lastHost = host
                    lastPort = port
                    // 注意：这里不能重置 autoReconnectUsed。连上就被踢的场景（比如
                    // 配对码不对）会让「成功 → 断开 → 自动重连」自己转圈，
                    // 额度只在用户主动发起连接时恢复。
                    emit(State.CONNECTED, "已连接到 $host:$port")
                    readLoop(client)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    delay(retryDelayMs(index))
                }
            }
            emit(
                State.ERROR,
                "连接 $host:$port 失败：${lastError?.message ?: "未知原因"}"
            )
        }
    }

    /** 重试间隔：前几次密一点，后面逐步拉长，兼顾速度与空转开销 */
    private fun retryDelayMs(attempt: Int): Long = when {
        attempt < 5 -> 200L
        attempt < 10 -> 400L
        else -> 700L
    }

    /**
     * 等接收端的续传应答（已经有多少字节）。
     *
     * 超时给得短：P2P 链路 RTT 是个位数毫秒，正常应答几十毫秒就回来了。
     * 对端如果是不支持续传的老版本，它根本不会回这个帧——宁可等一小会儿再从头传，
     * 也不能不确认就发数据，否则会把对方已有的半截文件拼坏。
     */
    private fun awaitResumeOffset(totalBytes: Long): Long {
        val offset = runCatching {
            resumeQueue.poll(RESUME_WAIT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return 0L
        return offset.coerceIn(0L, totalBytes)
    }

    // ------------------------------------------------------------------ 收发

    /**
     * 发送一条文本。
     *
     * @return false 表示通道当前不可用（还没连上）
     */
    fun send(text: String): Boolean {
        val out = output ?: return false
        scope.launch {
            try {
                synchronized(writeLock) { TransferProtocol.writeText(out, text) }
                appendAndNotify(text, ChatEntry.Kind.MINE)
            } catch (e: Exception) {
                emit(State.ERROR, "发送失败：${e.message}")
            }
        }
        return true
    }

    /** 关闭通道，取消所有任务 */
    fun stop() {
        stopWatchdog()
        listenJob?.cancel()
        listenJob = null
        connectJob?.cancel()
        connectJob = null
        listening = false

        val ss = serverSocket
        serverSocket = null
        runCatching { ss?.close() }

        val s = socket
        socket = null
        runCatching { s?.close() }
        output = null

        abortReceive()
        sending = false
        resumeQueue.clear()

        if (state != State.IDLE) emit(State.IDLE, "通道已关闭")
    }

    /** 清空聊天记录（界面上的“清空”用） */
    fun clearHistory() {
        synchronized(messageBuffer) { messageBuffer.clear() }
    }

    /** 清空文件相关回调（界面卸载时调用，避免 Activity 泄漏） */
    fun clearFileCallbacks() {
        onReceiveProgress = null
        onReceiveDone = null
        onSendProgress = null
        onSendDone = null
    }

    // ------------------------------------------------------------------ 内部

    // ------------------------------------------------------------------ 断连看门狗

    /** 记一次「链路上真的走过字节」，收发循环里每个分片都调 */
    private fun touchIo() {
        lastIoAt = SystemClock.elapsedRealtime()
    }

    /**
     * 断连看门狗：处理「对端悄悄消失」这种最难查的卡死。
     *
     * 对方拔掉 Wi-Fi、杀进程或锁屏休眠时，本机的 `read()` / `write()` 会一直阻塞，
     * 界面就永远停在「传输中」，取消按钮也可能点不动。这里每 2 秒看一次，
     * 传输中一旦连续 [LINK_IDLE_TIMEOUT_MS] 没有任何字节流动，直接把 socket 关掉，
     * 让阻塞中的读写抛异常、走正常收尾流程（半成品保留，重连后能续传）。
     *
     * 只在「真的在传文件」时判定：空闲挂着的连接可以一直连着聊天，那是正常状态，
     * 所以不能用 socket 读超时一刀切。
     */
    private fun startWatchdog() {
        stopWatchdog()
        touchIo()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(LINK_WATCHDOG_INTERVAL_MS)
                if (!sending && receiveTarget == null) {
                    // 没在传：把时间戳往前推，免得下一次传大文件被这段空闲时间误判
                    touchIo()
                    continue
                }
                val stalled = linkStalled(
                    nowMs = SystemClock.elapsedRealtime(),
                    lastIoAt = lastIoAt,
                    transferring = true,
                    idleTimeoutMs = LINK_IDLE_TIMEOUT_MS
                )
                if (!stalled) continue
                killSocket("超过 ${LINK_IDLE_TIMEOUT_MS / 1000} 秒没有任何数据，链路已断开")
                break
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /** 主动关掉 socket，让阻塞中的读写立刻抛异常；状态复位交给 readLoop 的 finally */
    private fun killSocket(reason: String) {
        appendAndNotify("连接已关闭：$reason（重新连接后可以断点续传）")
        val s = socket
        socket = null
        runCatching { s?.close() }
    }

    private suspend fun readLoop(client: Socket) {
        socket = client
        val input = DataInputStream(client.getInputStream().buffered())
        val out = DataOutputStream(client.getOutputStream().buffered())
        output = out
        startWatchdog()
        // 被对端拒绝（配对码不对）时不再自动重连：重连还是会以同样的码被拒，
        // 只会来回刷日志，等用户输了新码由 retryWithPairCode 主动连
        var rejectedByPeer = false

        try {
            while (currentCoroutineContext().isActive) {
                val frame = TransferProtocol.readFrame(input) ?: break
                // 收到任何一帧都算链路活着（文本、分片、结束帧、续传应答都算）
                touchIo()
                when (frame.type) {
                    TransferProtocol.TYPE_TEXT -> {
                        val text = frame.asText()
                        if (text.isNotBlank()) appendAndNotify(text, ChatEntry.Kind.PEER)
                    }

                    TransferProtocol.TYPE_FILE_HEADER -> handleFileHeader(frame, out)

                    TransferProtocol.TYPE_FILE_CHUNK -> handleFileChunk(frame)

                    TransferProtocol.TYPE_FILE_END -> handleFileEnd(frame, out)

                    TransferProtocol.TYPE_FILE_CANCEL -> handleFileCancel()

                    // 续传应答只对正在发送的一方有意义，交给等待中的发送协程
                    TransferProtocol.TYPE_FILE_RESUME -> {
                        runCatching { resumeQueue.offer(frame.asResumeOffset()) }
                    }

                    // 报到帧：组主没开校验时会收到它，什么都不用做
                    TransferProtocol.TYPE_HELLO -> Unit

                    // 被拒：多半是配对码不对。这里不走「连接异常」，
                    // 而是明确告诉界面去要码，用户输了正确码还能接着连
                    TransferProtocol.TYPE_PAIR_REJECT -> {
                        val reason = frame.asText().ifBlank { "对方拒绝了本次连接" }
                        appendAndNotify(reason)
                        rejectedByPeer = true
                        mainHandler.post { onPairRejected?.invoke(reason) }
                        emit(State.IDLE, reason)
                        break
                    }

                    // 未知帧只记日志，不能把整个通道判为 ERROR——
                    // 否则连接还在、发送按钮却被禁用，属于误伤。
                    else -> appendAndNotify("收到未知帧类型：${frame.type}，已忽略")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(State.ERROR, "连接异常：${e.message}")
        } finally {
            stopWatchdog()
            abortReceive()
            output = null
            socket = null
            runCatching { client.close() }
            // 无条件复位：即使中途被标成 ERROR，断开后也要回到 IDLE，
            // 否则界面会一直卡在“出错”且无法重连。
            if (state != State.IDLE) emit(State.IDLE, "通道已断开")

            // 已经被 cancel（用户点了断开 / stop()）时协程不再是 active，
            // 这种「主动断开」不该自动连回去
            val stillActive = currentCoroutineContext().isActive
            if (stillActive && !rejectedByPeer) scheduleAutoReconnect()
        }
    }

    /**
     * 自动重连：只做一次，只对客户端角色有效（组主没有「主动连回去」的概念）。
     *
     * 目的很有限——网络瞬断时把通道接回来，让用户不用手动再点一遍；
     * 已经传了一半的文件靠续传机制接着传，这里不碰任何传输状态。
     */
    private fun scheduleAutoReconnect() {
        val host = lastHost ?: return
        if (autoReconnectUsed) return

        autoReconnectUsed = true
        appendAndNotify("连接意外断开，正在自动重连…")
        scope.launch {
            delay(AUTO_RECONNECT_DELAY_MS)
            // 上一轮的 connect 协程可能还挂在 connectJob 上，不清掉会让 connect() 直接 return
            connectJob = null
            connect(host, lastPort, attempts = 8, auto = true)
        }
    }

    // ------------------------------------------------------------------ 文件接收

    private fun handleFileHeader(frame: TransferProtocol.Frame, out: DataOutputStream) {
        // 上一个文件还没收完就又来一个文件头：多半是对端状态错乱或恶意构造，
        // 直接丢弃新的，避免把正在写的目标覆盖掉（那样两个文件都会坏）
        if (receiveTarget != null) {
            appendAndNotify("上一个文件尚未接收完成，已忽略新的文件头")
            receiveDiscarding = true
            return
        }

        val header = runCatching { frame.asFileHeader() }.getOrElse {
            appendAndNotify("文件头解析失败，已忽略本次传输：${it.message}")
            receiveDiscarding = true
            return
        }

        // 落到公共下载目录（Android 10+ 走 MediaStore），重名自动加序号；
        // 大文件会先写半成品，若同名同大小的半成品还在，这里返回的目标就带着已有字节数
        val context = appContext
        val target = if (context == null) {
            null
        } else {
            ReceivedFileStore.create(context, header.fileName, header.fileSize)
        }
        if (target == null) {
            appendAndNotify("无法创建接收文件（存储不可用或无权限），已忽略本次传输")
            receiveDiscarding = true
            return
        }

        receiveTarget = target
        receiveDigest = MessageDigest.getInstance("MD5")
        receiveDiscarding = false
        receivingFileName = header.fileName
        receivingTotalBytes = header.fileSize
        receiveTotal = header.fileSize
        receiveRemaining = header.fileSize - target.resumedFrom
        receiveWritten = target.resumedFrom
        receiveSpeed = 0
        receiveSampleAt = SystemClock.elapsedRealtime()
        receiveSampleBytes = target.resumedFrom
        receivePostAt = 0

        // 新客户端发文件头时还没算完 MD5（写的是占位符），真正的值在结束帧里
        receiveMd5 = if (header.md5Known) header.md5Hex else ""

        // 版本 2 起有续传握手：必须回一个应答，发送端才知道从哪开始。
        // 老客户端（版本 0）不会等这个帧，所以不能给它发，否则它会从头再发一遍。
        if (header.supportsResume) {
            runCatching {
                synchronized(writeLock) { TransferProtocol.writeFileResume(out, target.resumedFrom) }
            }
        }

        val resumedFrom = target.resumedFrom
        if (resumedFrom > 0) {
            appendAndNotify(
                "续传文件：${header.fileName}（${formatSize(header.fileSize)}），" +
                    "已有 ${formatSize(resumedFrom)}，从断点继续"
            )
            // 续传时要把已有部分重新喂给 digest，才能算出整份文件的 MD5
            receiveDigest?.let { target.primeDigest(it) }
        } else {
            appendAndNotify("收到文件：${header.fileName}（${formatSize(header.fileSize)}），保存到 ${target.displayPath}")
        }
        val startRatio = if (header.fileSize > 0) {
            (resumedFrom.toFloat() / header.fileSize.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        mainHandler.post { onReceiveProgress?.invoke(startRatio, 0L) }
        startService("接收 ${header.fileName}", (startRatio * 100).toInt(), 100)
    }

    private fun handleFileChunk(frame: TransferProtocol.Frame) {
        // 已放弃本次传输：继续丢弃分片，直到收到结束帧
        if (receiveDiscarding) return

        val target = receiveTarget ?: run {
            appendAndNotify("收到分片但未收到文件头，已忽略")
            receiveDiscarding = true
            return
        }
        try {
            target.write(frame.payload)
            receiveDigest?.update(frame.payload)
            receiveRemaining -= frame.payload.size
            receiveWritten += frame.payload.size

            val now = SystemClock.elapsedRealtime()
            if (now - receiveSampleAt >= SPEED_SAMPLE_MS) {
                val dt = (now - receiveSampleAt).coerceAtLeast(1L)
                val instant = (receiveWritten - receiveSampleBytes) * 1000L / dt
                // 指数滑动平均：瞬时值抖动太大，直接显示会让用户觉得“忽快忽慢”
                receiveSpeed = if (receiveSpeed == 0L) instant else (receiveSpeed * 7 + instant * 3) / 10
                receiveSampleAt = now
                receiveSampleBytes = receiveWritten
            }

            val progress = if (receiveTotal > 0) {
                (receiveWritten.toFloat() / receiveTotal.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val pct = (progress * 100).toInt()
            if (now - receivePostAt >= PROGRESS_THROTTLE_MS || progress >= 1f) {
                receivePostAt = now
                val speed = receiveSpeed
                mainHandler.post { onReceiveProgress?.invoke(progress, speed) }
            }
            updateService(if (receiveTotal > 0) "接收中 $pct%" else "接收中", pct, 100)
        } catch (e: Exception) {
            // 写盘失败只放弃本次文件，不把连接判为 ERROR
            appendAndNotify("写入文件失败，放弃本次接收：${e.message}")
            abortReceive()
            receiveDiscarding = true
            stopService()
            mainHandler.post { onReceiveDone?.invoke(null) }
        }
    }

    private fun handleFileEnd(frame: TransferProtocol.Frame, out: DataOutputStream) {
        val end = runCatching { frame.asFileEnd() }.getOrElse {
            appendAndNotify("结束帧解析失败：${it.message}")
            abortReceive()
            return
        }
        val received = end.totalBytes

        if (receiveDiscarding) {
            // 本次传输已被放弃，收到结束帧就把标记清掉，连接保持
            abortReceive()
            appendAndNotify("本次文件传输已放弃")
            mainHandler.post { onReceiveDone?.invoke(null) }
            stopService()
            return
        }

        val target = receiveTarget
        val digest = receiveDigest
        receiveTarget = null
        receiveDigest = null
        receivingFileName = ""
        receivingTotalBytes = 0

        if (target == null) {
            appendAndNotify("收到结束帧但没有正在接收的文件，已忽略")
            return
        }

        // 完整性核对
        // 注意：协议里 MD5 以大写十六进制传输，而 Checksum.md5Hex 返回小写，
        // 必须忽略大小写比较，否则永远不相等。
        val written = target.written
        val actualMd5 = digest?.digest()?.toHexUpper().orEmpty()
        // 总大小可能未知（SAF 拿不到 SIZE 时会传 0）：此时只以结束帧声明的字节数为准
        val sizeOk = written == received && (receiveTotal <= 0 || written == receiveTotal)
        // 期望的 MD5 优先取文件头，没有就取结束帧（新客户端走这条）
        val expectedMd5 = receiveMd5.ifBlank { end.md5Hex.orEmpty() }
        val md5Ok = if (expectedMd5.isNotEmpty() && actualMd5.isNotEmpty()) {
            actualMd5.equals(expectedMd5, ignoreCase = true)
        } else {
            true
        }
        val ok = sizeOk && md5Ok

        if (ok) {
            if (target.commit()) {
                // 图片/视频直接给一张卡片（带缩略图）；查不到 Uri 就退回纯文字行，
                // 查询失败不影响传输结果的展示
                val fileUri = appContext?.let { ctx ->
                    runCatching {
                        ReceivedFileStore.list(ctx).firstOrNull { it.name == target.fileName }?.uri
                    }.getOrNull()
                }?.toString()
                if (fileUri != null) {
                    appendAndNotify(
                        "文件接收完成：${target.fileName}，已保存到 ${target.displayPath}",
                        ChatEntry.Kind.FILE_IN,
                        fileUri
                    )
                } else {
                    appendAndNotify("文件接收完成：${target.fileName}，已保存到 ${target.displayPath}")
                }
                mainHandler.post { onReceiveDone?.invoke(target.displayPath) }
            } else {
                // 收是收完了，只是从半成品复制进下载目录时失败。
                // 这时不能删半成品——留着它，用户再传一次就能从断点续上，不用重头来。
                appendAndNotify("文件已收完，但保存到下载目录失败；断点已保留，重发同一文件可续传")
                target.discard(keepPartial = true)
                mainHandler.post { onReceiveDone?.invoke(null) }
            }
        } else {
            appendAndNotify(
                "文件校验失败，已丢弃（大小 $sizeOk，MD5 $md5Ok，" +
                    "收到 $written / 声明 $receiveTotal / 结束帧 $received）"
            )
            // 校验失败说明内容已经不对了，半成品留着只会让下次续传出错
            target.discard()
            mainHandler.post { onReceiveDone?.invoke(null) }
        }
        stopService()
    }

    private fun handleFileCancel() {
        // 对端作废这次传输时，如果本机正在发，也要立刻停手：
        // 否则对方已经不再写盘，我们还会把剩下的分片全推过去，白耗流量还卡着界面。
        // 这里不调 cancelSend()，因为它会再回一个取消帧，两边互相弹。
        if (sending) {
            sendJob?.cancel()
            sendJob = null
            sending = false
            sendingTotalBytes = 0
            stopService()
            appendAndNotify("对方取消了接收")
            mainHandler.post { onSendDone?.invoke(false) }
            return
        }

        val target = receiveTarget
        // 对方主动取消：半成品留着，下次同名同大小可以直接续上
        abortReceive(keepPartial = true)
        if (target != null) {
            appendAndNotify("对方取消了传输")
            mainHandler.post { onReceiveDone?.invoke(null) }
            stopService()
        }
    }

    /**
     * 取消「当前正在进行的传输」，发送和接收都覆盖。
     *
     * 界面上的取消按钮、通知栏的取消动作都走这里：
     * - 正在发：交给 [cancelSend]（会给对端发取消帧）
     * - 正在收：先通知对端别再发了，再丢掉本地半成品（用户主动放弃，不值得留着续传）
     */
    fun cancelActive(): Boolean {
        if (sending) return cancelSend()
        if (receiveTarget == null) return false

        val out = output
        if (out != null) {
            runCatching { synchronized(writeLock) { TransferProtocol.writeFileCancel(out) } }
        }
        abortReceive(keepPartial = false)
        appendAndNotify("已取消接收")
        mainHandler.post { onReceiveDone?.invoke(null) }
        stopService()
        return true
    }

    private fun abortReceive(keepPartial: Boolean = true) {
        // 未完成的目标：小文件直接删掉，大文件保留半成品供续传
        runCatching { receiveTarget?.discard(keepPartial) }
        receiveTarget = null
        receiveDigest = null
        receiveRemaining = 0
        receiveTotal = 0
        receiveWritten = 0
        receiveMd5 = ""
        receiveSpeed = 0
        receiveSampleAt = 0
        receiveSampleBytes = 0
        receivePostAt = 0
        receiveDiscarding = false
        receivingFileName = ""
        receivingTotalBytes = 0
    }

    // ------------------------------------------------------------------ 文件发送

    /**
     * 发送一个文件。调用方保证已连接。
     *
     * 传的是「流工厂」而不是现成的流：极少数情况下需要重新打开一份
     * （比如 SAF 不给文件大小，得先扫一遍才知道真实字节数）。
     *
     * 正常情况下**只读一遍**：文件头先写 MD5 占位符，边读边算 digest，
     * 最后把算出来的 MD5 放进结束帧回传，由接收方核对。
     * 早期实现是「先整读一遍算 MD5、再读一遍发送」，大文件等于读两次、慢一倍。
     *
     * @param openStream 每次调用返回一份从头发起的新流（调用方负责关闭）
     * @return true 表示已开始发送；false 表示通道不可用或已有任务在跑
     */
    fun sendFile(
        openStream: () -> InputStream?,
        fileName: String,
        fileSize: Long
    ): Boolean {
        if (!isConnected) return false
        if (sending) return false

        sending = true
        startService("发送 $fileName", 0, 100)
        sendJob = scope.launch {
            val out = output
            if (out == null) {
                sending = false
                stopService()
                mainHandler.post { onSendDone?.invoke(false) }
                return@launch
            }

            try {
                var knownSize = fileSize
                var headerMd5 = ""

                // 只有拿不到大小时才退化成「先扫一遍」：多读一次总比发错字节数强
                if (knownSize <= 0) {
                    val scanned = openStream()?.use { Checksum.digestOf(it) }
                    if (scanned == null) {
                        appendAndNotify("无法读取文件：$fileName")
                        mainHandler.post { onSendDone?.invoke(false) }
                        stopService()
                        return@launch
                    }
                    knownSize = scanned.size
                    headerMd5 = scanned.md5Hex
                }

                val header = TransferProtocol.FileHeader(fileName, knownSize, headerMd5)
                sendingTotalBytes = knownSize
                resumeQueue.clear()
                synchronized(writeLock) { TransferProtocol.writeFileHeader(out, header) }

                // 等接收端回「我已经有 N 字节」；对方是老版本或没回就从头传
                val startOffset = awaitResumeOffset(knownSize)
                if (startOffset > 0) {
                    appendAndNotify("对方已有 ${formatSize(startOffset)}，从断点继续发送")
                }

                val digest = MessageDigest.getInstance("MD5")
                // position = 已读到的文件位置（含断点之前跳过的部分）；sent = 真正发出去的字节
                var position = 0L
                var sent = 0L
                var speed = 0L
                var sampleAt = SystemClock.elapsedRealtime()
                var sampleBytes = 0L
                var postAt = 0L
                var opened = false

                openStream()?.use { stream ->
                    opened = true
                    val buffer = ByteArray(TransferProtocol.CHUNK_SIZE)
                    while (currentCoroutineContext().isActive) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        // 整份文件都要进 digest：续传时后半段的 MD5 单独算出来是没法和对方对上的
                        digest.update(buffer, 0, read)
                        val readStart = position
                        position += read
                        // 每读一片就喂一次看门狗：真的卡住时（write 阻塞）循环停在写里面，
                        // 这个时间戳就不再前进，看门狗才能发现并掐断
                        touchIo()

                        // 落在断点之前的那部分只算 MD5、不发送
                        val skip = (startOffset - readStart).coerceIn(0L, read.toLong()).toInt()
                        if (skip < read) {
                            val chunk = if (skip == 0 && read == buffer.size) {
                                buffer
                            } else {
                                buffer.copyOfRange(skip, read)
                            }
                            synchronized(writeLock) { TransferProtocol.writeFileChunk(out, chunk) }
                            sent += chunk.size
                        }

                        val now = SystemClock.elapsedRealtime()
                        if (now - sampleAt >= SPEED_SAMPLE_MS) {
                            val dt = (now - sampleAt).coerceAtLeast(1L)
                            val instant = (sent - sampleBytes) * 1000L / dt
                            speed = if (speed == 0L) instant else (speed * 7 + instant * 3) / 10
                            sampleAt = now
                            sampleBytes = sent
                        }

                        val ratio = if (knownSize > 0) {
                            (position.toFloat() / knownSize.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        if (now - postAt >= PROGRESS_THROTTLE_MS || ratio >= 1f) {
                            postAt = now
                            val s = speed
                            mainHandler.post { onSendProgress?.invoke(ratio, s) }
                        }
                        val pct = (ratio * 100).toInt()
                        updateService(if (knownSize > 0) "发送中 $pct%" else "发送中", pct, 100)
                    }
                }

                if (!opened) {
                    appendAndNotify("无法读取文件：$fileName")
                    mainHandler.post { onSendDone?.invoke(false) }
                    stopService()
                    return@launch
                }

                val md5 = digest.digest().toHexUpper()
                synchronized(writeLock) { TransferProtocol.writeFileEnd(out, knownSize, md5) }

                if (position != knownSize) {
                    appendAndNotify("发送字节数异常：$position / $knownSize")
                    mainHandler.post { onSendDone?.invoke(false) }
                } else {
                    appendAndNotify("文件已发送：$fileName")
                    mainHandler.post { onSendDone?.invoke(true) }
                }
                stopService()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendAndNotify("发送文件失败：${e.message}")
                mainHandler.post { onSendDone?.invoke(false) }
                stopService()
            } finally {
                sending = false
                sendingTotalBytes = 0
                sendJob = null
            }
        }
        return true
    }

    /** 是否正在发送文件 */
    val isSending: Boolean get() = sending

    /** 是否正在接收文件（已经建好落盘目标、还没写完） */
    val isReceiving: Boolean get() = receiveTarget != null

    /**
     * 取消当前发送。
     *
     * 除了中断本地协程，还要给对端发一个取消帧——否则对端会一直等剩下的分片，
     * 界面上卡在「接收中 37%」不动，直到超时才断开。
     */
    fun cancelSend(): Boolean {
        if (!sending) return false
        val out = output
        if (out != null) {
            runCatching { synchronized(writeLock) { TransferProtocol.writeFileCancel(out) } }
        }
        sendJob?.cancel()
        sendJob = null
        sending = false
        sendingTotalBytes = 0
        stopService()
        appendAndNotify("已取消发送")
        mainHandler.post { onSendDone?.invoke(false) }
        return true
    }

    // ------------------------------------------------------------------ 内部

    /** 协议统一用大写十六进制，日志与断言更好对 */
    private fun ByteArray.toHexUpper(): String = joinToString("") { "%02X".format(it) }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    // ------------------------------------------------------------------ 前台服务

    private fun startService(title: String, progress: Int, max: Int) {
        val ctx = appContext ?: return
        // 新的一次传输：把通知节流重置，让第一条进度立刻显示出来
        notifyLimiter.reset()
        if (serviceStarted) {
            TransferService.update(ctx, title, "", progress, max)
        } else {
            serviceStarted = true
            TransferService.start(ctx)
            TransferService.update(ctx, title, "", progress, max)
        }
    }

    private fun updateService(text: String, progress: Int, max: Int) {
        val ctx = appContext ?: return
        if (!serviceStarted) return
        // 每个分片都会调进来，这里按 [NOTIFY_THROTTLE_MS] 节流，
        // 否则 10MB/s 就是每秒上百次 startService 跨进程往返
        if (!notifyLimiter.tryAcquire()) return
        TransferService.update(ctx, getServiceTitle(), text, progress, max)
    }

    private fun getServiceTitle(): String = "正在传输文件"

    private fun stopService() {
        val ctx = appContext ?: return
        if (serviceStarted) {
            serviceStarted = false
            TransferService.stop(ctx)
        }
    }

    private fun appendAndNotify(
        line: String,
        kind: ChatEntry.Kind = ChatEntry.Kind.SYSTEM,
        fileUri: String? = null
    ) {
        val entry = ChatEntry(kind, line, fileUri = fileUri)
        synchronized(messageBuffer) {
            messageBuffer.add(entry)
            while (messageBuffer.size > MAX_HISTORY) messageBuffer.removeAt(0)
        }
        mainHandler.post { onMessage?.invoke(entry) }
    }

    private fun emit(newState: State, message: String) {
        state = newState
        mainHandler.post { onStateChanged?.invoke(newState, message) }
    }

    private const val CONNECT_TIMEOUT_MS = 1000

    /** 测速采样窗口：太短会抖，太长反应慢 */
    private const val SPEED_SAMPLE_MS = 400L

    /** 进度回调节流：64KB 分片在局域网里可以到每秒上千帧，不节流会把主线程刷爆 */
    private const val PROGRESS_THROTTLE_MS = 80L

    /**
     * 通知更新节流。
     *
     * 收 / 发每处理一个 64KB 分片都会走到 [updateService]，而它底层是一次
     * `startService(Intent)`——10MB/s 时等于每秒上百次跨进程调用，通知栏还会跟着抖。
     * 进度条本身是系统画的，一秒钟刷两次肉眼看不出差别，所以这里卡到 450ms。
     */
    private const val NOTIFY_THROTTLE_MS = 450L

    /** 等续传应答的超时：超时就从头传，绝不能因为对端没应答而卡住 */
    private const val RESUME_WAIT_MS = 600L

    /**
     * 组主等接入方报到帧的时间。
     *
     * 报到帧在 TCP 连上后立刻发出，局域网里几十毫秒就到；
     * 2 秒都等不到，基本可以确定对方不是带码的新客户端，按拒绝处理。
     */
    private const val PAIR_WAIT_MS = 2_000

    /** 意外断连后自动重连一次：稍等一下让旧 socket 彻底释放 */
    private const val AUTO_RECONNECT_DELAY_MS = 800L

    /**
     * 断连判定：传输中超过这么久没有任何字节流动，就当链路已经死了。
     *
     * 只看「真的在传」的那段时间——空闲挂着的连接可以一直连着聊天，
     * 不能用 socket 读超时一刀切（那会把正常空闲误判成断线）。
     */
    private const val LINK_IDLE_TIMEOUT_MS = 30_000L
    private const val LINK_WATCHDOG_INTERVAL_MS = 2_000L

    private const val MAX_HISTORY = 200

    /** 已有连接时回给第二个接入方的话，对端会直接显示在聊天区 */
    private const val BUSY_MESSAGE = "对方已有其它设备连接，请稍后再试"

    private const val BIND_RETRIES = 3
    private const val BIND_RETRY_DELAY_MS = 300L
}
