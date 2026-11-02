//import kotlinx.coroutines.*
//import java.net.ServerSocket
//import java.net.Socket
//
//class TcpServer(private val port: Int) {
//
//    // 作用域：IO 调度器 + SupervisorJob（一个子协程挂了不影响其他）
//    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
//
//    // 启动监听
//    fun start() {
//        scope.launch {
//            try {
//                val serverSocket = ServerSocket(port)
//                println("服务端已启动，监听端口: $port")
//
//                // 循环等待客户端连接
//                while (isActive) {
//                    val clientSocket = serverSocket.accept()   // 阻塞，但在 IO 线程里
//                    println("客户端已连接: ${clientSocket.inetAddress}")
//
//                    // 每个客户端开一个子协程处理
//                    launch { handleClient(clientSocket) }
//                }
//            } catch (e: Exception) {
//                if (isActive) e.printStackTrace()
//            }
//        }
//    }
//
//    // 处理单个客户端
//    private suspend fun handleClient(socket: Socket) {
//        socket.use { s ->   // use 自动关闭 socket
//            val reader = s.getInputStream().bufferedReader()
//            val writer = s.getOutputStream().bufferedWriter()
//
//            // 逐行读取客户端发来的数据
//            reader.forEachLine { line ->
//                println("收到: $line")
//
//                // 回一个响应
//                writer.write("已收到: $line\n")
//                writer.flush()
//            }
//        }
//    }
//
//    // 停止服务
//    fun stop() {
//        scope.cancel()
//    }
//}