package com.example.myzuoye

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myzuoye.transfer.ReceivedFileStore
import com.example.myzuoye.ui.AppDialog
import com.example.myzuoye.ui.ReceivedFileAdapter

/**
 * 应用内查看「下载/WiFi直传」目录下已接收的文件。
 *
 * 支持按类型筛选、点开、分享、删除。
 * 所有文件操作都走 [ReceivedFileStore]，界面层不直接碰 MediaStore / File。
 */
class ReceivedFilesActivity : AppCompatActivity() {

    private lateinit var adapter: ReceivedFileAdapter
    private lateinit var rvFiles: RecyclerView
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView

    /** 当前筛选类型，null 表示全部 */
    private var filter: FileFilter = FileFilter.ALL

    /** 全部文件；筛选只作用于视图，不重复查库 */
    private var allFiles: List<ReceivedFileStore.ReceivedFile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_received_files)

        tvCount = findViewById(R.id.tvFilesCount)
        tvEmpty = findViewById(R.id.tvFilesEmpty)
        rvFiles = findViewById(R.id.rvFiles)

        applyWindowInsets()

        adapter = ReceivedFileAdapter(
            onClick = { file -> openFile(file) },
            onShare = { file -> shareFile(file) },
            onDelete = { file -> confirmDelete(file) }
        )
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = adapter

        setupFilters()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 从「打开文件」返回时刷新，展示最新状态（也可能中途被别的应用删了）
        refresh()
    }

    // ------------------------------------------------------------------ 筛选

    private fun setupFilters() {
        val chips = mapOf(
            FileFilter.ALL to findViewById<TextView>(R.id.chipAll),
            FileFilter.IMAGE to findViewById<TextView>(R.id.chipImage),
            FileFilter.VIDEO to findViewById<TextView>(R.id.chipVideo),
            FileFilter.DOC to findViewById<TextView>(R.id.chipDoc),
            FileFilter.OTHER to findViewById<TextView>(R.id.chipOther)
        )
        chips.forEach { (type, view) ->
            view.setOnClickListener {
                filter = type
                chips.forEach { (t, v) -> v.isSelected = t == type }
                applyFilter()
            }
        }
        chips.getValue(FileFilter.ALL).isSelected = true
    }

    private fun applyFilter() {
        val shown = allFiles.filter { filter.matches(it.name) }
        adapter.submit(shown)
        tvCount.text = getString(R.string.files_count, shown.size)
        val empty = shown.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        rvFiles.visibility = if (empty) View.GONE else View.VISIBLE
        tvEmpty.setText(if (allFiles.isEmpty()) R.string.files_empty else R.string.files_empty_filter)
    }

    // ------------------------------------------------------------------ 数据

    private fun refresh() {
        allFiles = ReceivedFileStore.list(this)
        applyFilter()
    }

    // ------------------------------------------------------------------ 动作

    private fun openFile(file: ReceivedFileStore.ReceivedFile) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(file.uri, mimeOf(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, R.string.files_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    /** 调系统分享面板，把文件发给别的应用（微信、QQ、邮件…） */
    private fun shareFile(file: ReceivedFileStore.ReceivedFile) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(file.name)
            putExtra(Intent.EXTRA_STREAM, file.uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.files_share_chooser)))
        }.onFailure {
            Toast.makeText(this, R.string.files_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(file: ReceivedFileStore.ReceivedFile) {
        AppDialog.builder(this)
            .setTitle(R.string.files_delete_title)
            .setMessage(getString(R.string.files_delete_message, file.name))
            .setPositiveButton(R.string.files_delete) { _, _ ->
                val ok = ReceivedFileStore.delete(this, file)
                Toast.makeText(
                    this,
                    if (ok) R.string.files_deleted else R.string.files_delete_failed,
                    Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.filesRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** 列表顶部的类型筛选 */
    private enum class FileFilter {
        ALL, IMAGE, VIDEO, DOC, OTHER;

        fun matches(name: String): Boolean {
            if (this == ALL) return true
            val mime = mimeOfName(name)
            return when (this) {
                IMAGE -> mime.startsWith("image/")
                VIDEO -> mime.startsWith("video/")
                DOC -> mime.startsWith("text/") ||
                    mime.contains("pdf") ||
                    mime.contains("word") ||
                    mime.contains("sheet") ||
                    mime.contains("presentation") ||
                    mime.contains("document")

                OTHER -> !mime.startsWith("image/") &&
                    !mime.startsWith("video/") &&
                    !mime.startsWith("text/") &&
                    !mime.contains("pdf") &&
                    !mime.contains("word") &&
                    !mime.contains("sheet") &&
                    !mime.contains("presentation") &&
                    !mime.contains("document")

                ALL -> true
            }
        }

        private fun mimeOfName(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return "application/octet-stream"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }
    }
}
