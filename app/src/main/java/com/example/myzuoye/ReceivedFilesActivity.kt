package com.example.myzuoye

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myzuoye.transfer.ReceivedFileStore
import com.example.myzuoye.ui.ReceivedFileAdapter

/**
 * 应用内查看「下载/WiFi直传」目录下已接收的文件。
 *
 * 只读展示，点击用系统应用打开（打开前确保该 Uri 对目标应用可读）。
 */
class ReceivedFilesActivity : AppCompatActivity() {

    private lateinit var adapter: ReceivedFileAdapter
    private lateinit var rvFiles: RecyclerView
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_received_files)

        tvCount = findViewById(R.id.tvFilesCount)
        tvEmpty = findViewById(R.id.tvFilesEmpty)
        rvFiles = findViewById(R.id.rvFiles)

        applyWindowInsets()

        adapter = ReceivedFileAdapter { file -> openFile(file) }
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = adapter

        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 从“打开文件”返回时刷新，展示最新状态（也可能中途被删了）
        refresh()
    }

    private fun refresh() {
        val files = ReceivedFileStore.list(this)
        adapter.submit(files)
        tvCount.text = getString(R.string.files_count, files.size)
        tvEmpty.visibility = if (files.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        rvFiles.visibility = if (files.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun openFile(file: ReceivedFileStore.ReceivedFile) {
        val mime = mimeOf(file.name)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(file.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(intent)
        }.onFailure {
            // 没有能打开该类型的应用时，退化成“用文件管理器看目录”或直接提示
            Toast.makeText(this, R.string.files_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun applyWindowInsets() {
        val root = findViewById<android.view.View>(R.id.filesRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
