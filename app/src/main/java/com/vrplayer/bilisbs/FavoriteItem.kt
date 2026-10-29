package com.vrplayer.bilisbs

import java.util.UUID

/**
 * 收藏项数据模型
 *
 * @param id 唯一标识
 * @param title 影片标题或用户自定义备注
 * @param url 影片原始链接（如 B 站视频链接、BV 号、直链等）
 * @param createdAtMs 收藏时间戳（毫秒）
 */
data class FavoriteItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val createdAtMs: Long = System.currentTimeMillis()
)
