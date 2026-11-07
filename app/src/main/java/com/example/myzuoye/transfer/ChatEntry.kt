package com.example.myzuoye.transfer

/**
 * 聊天区的一条消息。
 *
 * 之前整个聊天区是「一行字符串」——「对方：」「我：」这类前缀就是全部的方向信息，
 * 界面想区分左右对齐、想加时间戳都得去猜文本内容。
 * 现在由传输层在写入历史时就标好方向，界面只管按 [kind] 摆放。
 *
 * @param kind 方向：我方气泡 / 对方气泡 / 系统状态行 / 收到的文件卡
 * @param text 消息内容（MINE / PEER 是纯文本，不含任何前缀）
 * @param at 消息时间（epoch 毫秒），气泡旁边显示 HH:mm
 * @param fileUri 仅 [Kind.FILE_IN]：接收完成后的本地 Uri（content:// 或 file://），
 *   用于在气泡里直接显示缩略图
 */
data class ChatEntry(
    val kind: Kind,
    val text: String,
    val at: Long = System.currentTimeMillis(),
    val fileUri: String? = null
) {
    enum class Kind { MINE, PEER, SYSTEM, FILE_IN }
}
