package com.vrplayer.bilisbs.model

/**
 * B站视频解析结果
 */
data class VideoInfo(
    val title: String,
    val bvid: String,
    val cid: Long,
    val sourceUrl: String,  // 原始 B 站页面链接，用于历史记录重新解析
    val videoUrl: String,   // DASH 视频流地址
    val audioUrl: String?,  // DASH 音频流地址（可能为空）
    val quality: Int,       // 清晰度 (64=720P, 80=1080P, ...)
    val qualityDesc: String, // 清晰度描述
    val subtitlePath: String? = null, // 本地缓存的 WebVTT 字幕文件路径
    val subtitleDesc: String? = null  // 字幕语言描述（如 "中文（中国）"）
)

