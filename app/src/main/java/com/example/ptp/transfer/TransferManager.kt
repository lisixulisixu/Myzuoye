package com.example.ptp.transfer

import android.os.Handler
import android.os.Looper
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

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

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var output: DataOutputStream? = null
    private val writeLock = Any()

    private val messageBuffer = mutableListOf<String>()

    @Volatile
    var state: State = State.IDLE
        private set

    var onStateChanged: ((State, String) -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null

    val isConnected: Boolean get() = state == State.CONNECTED

    /** 历史消息（最多保留 [MAX_HISTORY] 条），界面重建时用它恢复聊天区 */
    fun history(): List<String> = synchronized(messageBuffer) { messageBuffer.toList() }

    // ------------------------------------------------------------------ 服务端

    /**
     * 以组主身份开始监听。重复调用是安全的（已在监听则直接返回）。
     */
    fun startServer(port: Int = TransferProtocol.PORT) {
        if (listenJob?.isActive == true || serverSocket != null) return

        listenJob = scope.launch {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                emit(State.LISTENING, "已监听端口 $port，等待对方接入")

                while (currentCoroutineContext().isActive) {
                    val client = ss.accept()
                    emit(State.CONNECTED, "对方已接入：${client.inetAddress.hostAddress}")
                    readLoop(client)
                    emit(State.LISTENING, "连接已断开，继续等待")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(State.ERROR, "监听失败：${e.message}")
            } finally {
                serverSocket = null
                runCatching { ss?.close() }
            }
        }
    }

    // ------------------------------------------------------------------ 客户端

    /**
     * 以客户端身份连接组主。
     *
     * 刚建组时对方的 ServerSocket 可能还没起来，所以带重试：默认 12 次、每次间隔 1 秒。
     */
    fun connect(host: String, port: Int = TransferProtocol.PORT, attempts: Int = 12) {
        if (state == State.CONNECTED) return
        if (connectJob?.isActive == true) return

        connectJob = scope.launch {
            var lastError: Exception? = null
            repeat(attempts) { index ->
                emit(State.CONNECTING, "第 ${index + 1}/$attempts 次连接 $host:$port")
                try {
                    val client = Socket()
                    client.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    emit(State.CONNECTED, "已连接到 $host:$port")
                    readLoop(client)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    delay(RETRY_INTERVAL_MS)
                }
            }
            emit(
                State.ERROR,
                "连接 $host:$port 失败：${lastError?.message ?: "未知原因"}"
            )
        }
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
                appendAndNotify("我：$text")
            } catch (e: Exception) {
                emit(State.ERROR, "发送失败：${e.message}")
            }
        }
        return true
    }

    /** 关闭通道，取消所有任务 */
    fun stop() {
        listenJob?.cancel()
        listenJob = null
        connectJob?.cancel()
        connectJob = null

        val ss = serverSocket
        serverSocket = null
        runCatching { ss?.close() }

        val s = socket
        socket = null
        runCatching { s?.close() }
        output = null

        if (state != State.IDLE) emit(State.IDLE, "通道已关闭")
    }

    /** 清空聊天记录（界面上的“清空”用） */
    fun clearHistory() {
        synchronized(messageBuffer) { messageBuffer.clear() }
    }

    // ------------------------------------------------------------------ 内部

    private suspend fun readLoop(client: Socket) {
        socket = client
        val input = DataInputStream(client.getInputStream().buffered())
        val out = DataOutputStream(client.getOutputStream().buffered())
        output = out

        try {
            while (currentCoroutineContext().isActive) {
                val frame = TransferProtocol.readFrame(input) ?: break
                when (frame.type) {
                    TransferProtocol.TYPE_TEXT -> {
                        val text = frame.asText()
                        if (text.isNotBlank()) appendAndNotify("对方：$text")
                    }

                    else -> emit(State.ERROR, "收到未知帧类型：${frame.type}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(State.ERROR, "连接异常：${e.message}")
        } finally {
            output = null
            socket = null
            runCatching { client.close() }
            if (state == State.CONNECTED) emit(State.IDLE, "通道已断开")
        }
    }

    private fun appendAndNotify(line: String) {
        synchronized(messageBuffer) {
            messageBuffer.add(line)
            while (messageBuffer.size > MAX_HISTORY) messageBuffer.removeAt(0)
        }
        mainHandler.post { onMessage?.invoke(line) }
    }

    private fun emit(newState: State, message: String) {
        state = newState
        mainHandler.post { onStateChanged?.invoke(newState, message) }
    }

    private const val CONNECT_TIMEOUT_MS = 4000
    private const val RETRY_INTERVAL_MS = 1000L
    private const val MAX_HISTORY = 200
}
