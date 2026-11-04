package com.example.myzuoye.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myzuoye.R
import com.example.myzuoye.transfer.ReceivedFileStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 已接收文件列表适配器。
 */
class ReceivedFileAdapter(
    private val onClick: (ReceivedFileStore.ReceivedFile) -> Unit
) : RecyclerView.Adapter<ReceivedFileAdapter.FileViewHolder>() {

    private val items = mutableListOf<ReceivedFileStore.ReceivedFile>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submit(files: List<ReceivedFileStore.ReceivedFile>) {
        items.clear()
        items.addAll(files)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_received_file, parent, false)
        return FileViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = items[position]
        holder.name.text = file.name
        holder.meta.text = "${formatSize(file.sizeBytes)}  ·  ${dateFormat.format(Date(file.dateModifiedMillis))}"
        holder.itemView.setOnClickListener { onClick(file) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvFileName)
        val meta: TextView = view.findViewById(R.id.tvFileMeta)
    }
}
