package com.example.ptp.transfer

import android.content.Context
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
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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

    /** 接收方保存文件到哪个目录（应用私有目录，避开分区存储权限） */
    lateinit var receiveDir: File

    /** 文件接收进度回调（主线程）。0..1 之间的比例 */
    var onReceiveProgress: ((Float) -> Unit)? = null

    /** 文件接收完成回调（主线程）。成功时为最终文件路径，失败时为 null */
    var onReceiveDone: ((String?) -> Unit)? = null

    /** 文件发送进度回调（主线程）。0..1 之间的比例 */
    var onSendProgress: ((Float) -> Unit)? = null

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

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var output: DataOutputStream? = null
    private val writeLock = Any()

    private val messageBuffer = mutableListOf<String>()

    /** 正在接收的文件写入流；同一时刻只允许一个接收任务 */
    private var receiveFile: File? = null
    private var receiveStream: FileOutputStream? = null
    private var receiveRemaining: Long = 0
    private var receiveTotal: Long = 0
    private var receiveMd5: String = ""

    /** 是否正在发送文件 */
    @Volatile
    private var sending = false

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

        abortReceive()
        sending = false

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

                    TransferProtocol.TYPE_FILE_HEADER -> handleFileHeader(frame)

                    TransferProtocol.TYPE_FILE_CHUNK -> handleFileChunk(frame)

                    TransferProtocol.TYPE_FILE_END -> handleFileEnd(frame, out)

                    TransferProtocol.TYPE_FILE_CANCEL -> handleFileCancel()

                    else -> emit(State.ERROR, "收到未知帧类型：${frame.type}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(State.ERROR, "连接异常：${e.message}")
        } finally {
            abortReceive()
            output = null
            socket = null
            runCatching { client.close() }
            if (state == State.CONNECTED) emit(State.IDLE, "通道已断开")
        }
    }

    // ------------------------------------------------------------------ 文件接收

    private fun handleFileHeader(frame: TransferProtocol.Frame) {
        val header = runCatching { frame.asFileHeader() }.getOrElse {
            emit(State.ERROR, "文件头解析失败：${it.message}")
            return
        }

        // 打开临时文件
        receiveDir.mkdirs()
        val safeName = sanitizeFileName(header.fileName)
        val file = File(receiveDir, safeName)
        val stream = try {
            FileOutputStream(file)
        } catch (e: Exception) {
            emit(State.ERROR, "无法创建接收文件：${e.message}")
            return
        }

        receiveFile = file
        receiveStream = stream
        receiveTotal = header.fileSize
        receiveRemaining = header.fileSize
        receiveMd5 = header.md5Hex

        appendAndNotify("收到文件：${header.fileName}（${formatSize(header.fileSize)}）")
        mainHandler.post { onReceiveProgress?.invoke(0f) }
    }

    private fun handleFileChunk(frame: TransferProtocol.Frame) {
        val stream = receiveStream ?: run {
            emit(State.ERROR, "收到分片但未收到文件头")
            return
        }
        try {
            stream.write(frame.payload)
            receiveRemaining -= frame.payload.size
            val total = receiveTotal
            val progress = if (total > 0) {
                1f - receiveRemaining.toFloat() / total.toFloat()
            } else {
                0f
            }
            mainHandler.post { onReceiveProgress?.invoke(progress.coerceIn(0f, 1f)) }
        } catch (e: Exception) {
            emit(State.ERROR, "写入文件失败：${e.message}")
            abortReceive()
        }
    }

    private fun handleFileEnd(frame: TransferProtocol.Frame, out: DataOutputStream) {
        val received = runCatching { frame.asLong() }.getOrElse {
            emit(State.ERROR, "结束帧解析失败：${it.message}")
            return
        }

        val file = receiveFile
        val stream = receiveStream
        receiveFile = null
        receiveStream = null
        runCatching { stream?.close() }

        if (file == null) {
            emit(State.ERROR, "收到结束帧但没有正在接收的文件")
            return
        }

        // 完整性核对
        val ok = file.length() == received && file.length() == receiveTotal &&
            if (receiveMd5.isNotEmpty()) {
                file.inputStream().use { Checksum.md5Hex(it) == receiveMd5 }
            } else {
                true
            }

        val donePath = if (ok) file.absolutePath else null
        if (ok) {
            appendAndNotify("文件接收完成：${file.name}")
            mainHandler.post { onReceiveDone?.invoke(donePath) }
        } else {
            appendAndNotify("文件校验失败，已丢弃")
            runCatching { file.delete() }
            emit(State.ERROR, "文件校验失败（大小或 MD5 不匹配）")
            mainHandler.post { onReceiveDone?.invoke(null) }
        }
    }

    private fun handleFileCancel() {
        val file = receiveFile
        abortReceive()
        if (file != null) {
            runCatching { file.delete() }
            appendAndNotify("对方取消了传输")
            mainHandler.post { onReceiveDone?.invoke(null) }
        }
    }

    private fun abortReceive() {
        runCatching { receiveStream?.close() }
        receiveStream = null
        receiveFile = null
        receiveRemaining = 0
        receiveTotal = 0
        receiveMd5 = ""
    }

    // ------------------------------------------------------------------ 文件发送

    /**
     * 发送一个文件。调用方保证已连接。
     *
     * @return true 表示已开始发送；false 表示通道不可用或已有任务在跑
     */
    fun sendFile(input: InputStream, fileName: String, fileSize: Long): Boolean {
        if (!isConnected) return false
        if (sending) return false

        sending = true
        scope.launch {
            val out = output
            if (out == null) {
                sending = false
                mainHandler.post { onSendDone?.invoke(false) }
                return@launch
            }

            try {
                val md5 = Checksum.md5Hex(input)

                // 重新打开一份流做实际发送（md5 计算已把流读到末尾）
                val stream = input
                val header = TransferProtocol.FileHeader(fileName, fileSize, md5)
                synchronized(writeLock) { TransferProtocol.writeFileHeader(out, header) }

                val buffer = ByteArray(TransferProtocol.CHUNK_SIZE)
                var sent = 0L
                while (currentCoroutineContext().isActive) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                    synchronized(writeLock) { TransferProtocol.writeFileChunk(out, chunk) }
                    sent += read
                    mainHandler.post {
                        onSendProgress?.invoke(
                            if (fileSize > 0) (sent.toFloat() / fileSize.toFloat()).coerceIn(0f, 1f) else 1f
                        )
                    }
                }
                synchronized(writeLock) { TransferProtocol.writeFileEnd(out, sent) }
                appendAndNotify("文件已发送：$fileName")
                mainHandler.post { onSendDone?.invoke(true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(State.ERROR, "发送文件失败：${e.message}")
                mainHandler.post { onSendDone?.invoke(false) }
            } finally {
                sending = false
            }
        }
        return true
    }

    /** 是否正在发送文件 */
    val isSending: Boolean get() = sending

    // ------------------------------------------------------------------ 内部

    private fun sanitizeFileName(raw: String): String {
        var name = raw.replace('\\', '_').replace('/', '_')
        name = name.trim()
        if (name.isEmpty() || name == "." || name == "..") name = "received_file"
        return name
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
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
