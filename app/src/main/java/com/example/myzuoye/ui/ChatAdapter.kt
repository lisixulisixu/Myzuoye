package com.example.myzuoye.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.myzuoye.R
import com.example.myzuoye.transfer.ChatEntry
import com.example.myzuoye.util.Formatters

/**
 * 聊天气泡列表的适配器，四种形态：
 * - [ChatEntry.Kind.MINE]：右对齐渐变气泡，时间在左；
 * - [ChatEntry.Kind.PEER]：左对齐深色气泡，时间在右；
 * - [ChatEntry.Kind.SYSTEM]：居中灰色小字（连接状态、续传提示这类）；
 * - [ChatEntry.Kind.FILE_IN]：左对齐文件卡，图片/视频带缩略图。
 *
 * 长按任何一条都会回调 [onEntryLongClick]（复制 / 重发入口在 Activity 里），
 * 适配器只管展示，不做业务。
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val items = mutableListOf<ChatEntry>()

    /**
     * 还没播过入场动画的条目 key。
     *
     * 只记「新增的那条」：列表重建（[submitAll]）和滚动复用都不会重复播，
     * 否则每次滚回顶部都会看到气泡重新弹一遍。
     */
    private val pendingEntrance = HashSet<String>()

    /** 长按某条消息（复制、重发等入口统一走这里） */
    var onEntryLongClick: ((ChatEntry) -> Unit)? = null

    /** 全量替换（界面重建恢复历史时用），空列表时显示占位提示 */
    fun submitAll(entries: List<ChatEntry>) {
        items.clear()
        items.addAll(entries)
        pendingEntrance.clear()
        notifyDataSetChanged()
    }

    /** 追加一条，列表保持滚动位置由外部处理 */
    fun add(entry: ChatEntry) {
        items.add(entry)
        pendingEntrance.add(keyOf(entry))
        notifyItemInserted(items.size - 1)
    }

    private fun keyOf(entry: ChatEntry): String = "${entry.at}|${entry.kind}|${entry.text}"

    /** 列表当前只有一条占位提示（空态），第一条真消息来了要把它顶掉 */
    fun hintOnly(): Boolean = items.size == 1 && items[0].kind == ChatEntry.Kind.SYSTEM

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        when (items[position].kind) {
            ChatEntry.Kind.MINE -> TYPE_MINE
            ChatEntry.Kind.PEER -> TYPE_PEER
            ChatEntry.Kind.SYSTEM -> TYPE_SYSTEM
            ChatEntry.Kind.FILE_IN -> TYPE_FILE
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            TYPE_MINE -> R.layout.item_chat_mine
            TYPE_PEER -> R.layout.item_chat_peer
            TYPE_SYSTEM -> R.layout.item_chat_system
            else -> R.layout.item_chat_file
        }
        val view = inflater.inflate(layout, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.text?.let { tv ->
            // 文件卡里只显示文件名，完整路径已经在系统行里出现过一次，不用重复占地方
            tv.text = if (entry.kind == ChatEntry.Kind.FILE_IN) {
                shortFileName(entry.text)
            } else {
                entry.text
            }
        }
        holder.time?.text = Formatters.clockTime(entry.at)

        holder.thumb?.let { iv ->
            val uriStr = entry.fileUri
            val name = shortFileName(entry.text)
            if (uriStr != null && ThumbnailLoader.isThumbnailable(name)) {
                iv.visibility = View.VISIBLE
                iv.tag = uriStr
                // 宽高在 layout 后才有值，post 一下再加载；加载失败把占位区收起来
                iv.post {
                    ThumbnailLoader.load(iv.context, Uri.parse(uriStr), name, iv) { ok ->
                        if (!ok && iv.tag == uriStr) iv.visibility = View.GONE
                    }
                }
            } else {
                iv.visibility = View.GONE
                iv.setImageDrawable(null)
                iv.tag = null
            }
        }

        holder.itemView.setOnLongClickListener {
            onEntryLongClick?.invoke(entry)
            true
        }

        playEntranceIfNew(holder.itemView, entry)
    }

    /**
     * 新气泡的入场：轻轻从下方浮上来并放大到位。
     *
     * 之前消息是「啪」地直接出现，收到文件时尤其突兀。动画 240ms、位移 8dp，
     * 只对新插入的条目播一次；文件卡用带弹性的插值，落定时有一下轻微的「盖章」感。
     */
    private fun playEntranceIfNew(view: View, entry: ChatEntry) {
        val density = view.resources.displayMetrics.density
        if (!pendingEntrance.remove(keyOf(entry))) {
            // 复用的 View 可能停在动画中途被回收，这里复位，避免显示成半透明错位
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.alpha = 0f
        view.translationY = 8f * density
        view.scaleX = 0.96f
        view.scaleY = 0.96f
        val animator = view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(if (entry.kind == ChatEntry.Kind.FILE_IN) 300L else 240L)
        if (entry.kind == ChatEntry.Kind.FILE_IN) {
            animator.setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
        } else {
            animator.setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
        }
        animator.start()
    }

    /** 「文件接收完成：xxx，已保存到 …」→ `xxx`；解析不出来就退回原文 */
    private fun shortFileName(text: String): String {
        val after = text.substringAfter("文件接收完成：", "").substringBefore("，已保存到")
        return after.ifBlank { text }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView? = view.findViewById(R.id.tvText)
        val time: TextView? = view.findViewById(R.id.tvTime)
        val thumb: ImageView? = view.findViewById(R.id.ivThumb)
    }

    companion object {
        private const val TYPE_MINE = 0
        private const val TYPE_PEER = 1
        private const val TYPE_SYSTEM = 2
        private const val TYPE_FILE = 3
    }
}
