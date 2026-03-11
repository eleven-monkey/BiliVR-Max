package com.vrplayer.bilisbs.model

/**
 * B站视频解析结果
 */
data class VideoInfo(
    val title: String,
    val bvid: String,
    val cid: Long,
    val videoUrl: String,   // DASH 视频流地址
    val audioUrl: String?,  // DASH 音频流地址（可能为空）
    val quality: Int,       // 清晰度 (64=720P, 80=1080P, ...)
    val qualityDesc: String // 清晰度描述
)
