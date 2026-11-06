package com.example.myzuoye.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myzuoye.R
import com.example.myzuoye.transfer.ReceivedFileStore
import com.example.myzuoye.util.Formatters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 已接收文件列表适配器。
 *
 * 每行三个动作：点整行打开、点「分享」调系统分享、点「删除」删掉。
 * 缩略图走 [ThumbnailLoader]，只有图片和视频才去解码，其余类型保持纯色块，
 * 免得为了一个 .zip 去打开输入流。
 */
class ReceivedFileAdapter(
    private val onClick: (ReceivedFileStore.ReceivedFile) -> Unit,
    private val onShare: (ReceivedFileStore.ReceivedFile) -> Unit,
    private val onDelete: (ReceivedFileStore.ReceivedFile) -> Unit
) : RecyclerView.Adapter<ReceivedFileAdapter.FileViewHolder>() {

    private val items = mutableListOf<ReceivedFileStore.ReceivedFile>()
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

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

    /** 播过入场动画的条目：滚动复用时不重复淡入 */
    private val animatedUris = HashSet<String>()

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = items[position]
        holder.name.text = file.name
        holder.meta.text = buildString {
            append(Formatters.size(file.sizeBytes))
            append("  ·  ")
            append(dateFormat.format(Date(file.dateModifiedMillis)))
            append("  ·  ")
            append(typeLabel(file.name))
        }

        if (ThumbnailLoader.isThumbnailable(file.name)) {
            holder.thumb.visibility = View.VISIBLE
            holder.thumb.backgroundTintList = null
            ThumbnailLoader.load(holder.itemView.context, file.uri, file.name, holder.thumb)
        } else {
            // 换行复用时必须把上一行的图清掉，否则会看到别的文件的封面；
            // 同时按类型给色块上色，纯灰方块看起来像「加载失败」
            holder.thumb.setImageDrawable(null)
            holder.thumb.backgroundTintList =
                android.content.res.ColorStateList.valueOf(typeColor(file.name))
            holder.thumb.visibility = View.VISIBLE
        }

        // 首次出现时错峰淡入（每项 30ms），列表打开更有「加载完成」的节奏感
        if (animatedUris.add(file.uri.toString())) {
            val density = holder.itemView.resources.displayMetrics.density
            holder.itemView.alpha = 0f
            holder.itemView.translationY = 8f * density
            holder.itemView.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(position.coerceAtMost(8) * 30L)
                .setDuration(200L)
                .start()
        } else {
            holder.itemView.alpha = 1f
        }

        holder.itemView.contentDescription = "${file.name}，${holder.meta.text}，点按打开"
        holder.itemView.setOnClickListener { onClick(file) }
        holder.share.setOnClickListener { onShare(file) }
        holder.delete.setOnClickListener { onDelete(file) }
    }

    /** 非图片/视频的类型色块：一眼能区分文本、压缩包、安装包 */
    private fun typeColor(name: String): Int {
        val mime = ThumbnailLoader.mimeOf(name)
        return when {
            mime.startsWith("text/") -> 0xFF3F5A8A.toInt()
            mime.contains("pdf") -> 0xFF8A4A2E.toInt()
            mime.contains("zip") || mime.contains("compressed") -> 0xFF2E6A70.toInt()
            mime.contains("android.package") -> 0xFF2F6A44.toInt()
            mime.startsWith("audio/") -> 0xFF6B3E7A.toInt()
            else -> 0xFF44506A.toInt()
        }
    }

    /** 列表上显示的中文类型，比 MIME 串好读 */
    private fun typeLabel(name: String): String {
        val mime = ThumbnailLoader.mimeOf(name)
        return when {
            mime.startsWith("image/") -> "图片"
            mime.startsWith("video/") -> "视频"
            mime.startsWith("audio/") -> "音频"
            mime.startsWith("text/") -> "文本"
            mime.contains("pdf") -> "PDF"
            mime.contains("zip") || mime.contains("compressed") -> "压缩包"
            mime.contains("android.package") -> "应用"
            else -> "文件"
        }
    }

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.ivThumb)
        val name: TextView = view.findViewById(R.id.tvFileName)
        val meta: TextView = view.findViewById(R.id.tvFileMeta)
        val share: TextView = view.findViewById(R.id.tvFileShare)
        val delete: TextView = view.findViewById(R.id.tvFileDelete)
    }
}
