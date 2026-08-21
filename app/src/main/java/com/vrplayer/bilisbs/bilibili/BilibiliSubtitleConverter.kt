package com.vrplayer.bilisbs.bilibili

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.util.Locale

/**
 * B站字幕转换器
 *
 * 将 B 站 JSON 格式字幕转换为 ExoPlayer 支持的 WebVTT (.vtt) 格式
 */
object BilibiliSubtitleConverter {

    private val gson = Gson()

    /**
     * 将 B 站 JSON 字幕内容转换为 WebVTT 格式字符串
     */
    fun convertJsonToVtt(jsonContent: String): String {
        val sb = StringBuilder("WEBVTT\n\n")
        try {
            val root = gson.fromJson(jsonContent, JsonObject::class.java)
            val body = root?.getAsJsonArray("body") ?: return sb.toString()
            var index = 1
            for (elem in body) {
                if (!elem.isJsonObject) continue
                val item = elem.asJsonObject
                val from = item.get("from")?.asDouble ?: continue
                val to = item.get("to")?.asDouble ?: continue
                val content = item.get("content")?.asString ?: continue

                if (content.isBlank()) continue

                sb.append(index).append("\n")
                sb.append(formatTimestamp(from))
                    .append(" --> ")
                    .append(formatTimestamp(to))
                    .append("\n")
                sb.append(content.trim()).append("\n\n")
                index++
            }
        } catch (_: Exception) {
            // 解析失败时返回基本的 WEBVTT 头部
        }
        return sb.toString()
    }

    /**
     * 将秒数 (Double) 转换为 WebVTT 格式的时间戳 00:00:00.000
     */
    fun formatTimestamp(seconds: Double): String {
        val totalMs = (seconds * 1000).toLong().coerceAtLeast(0L)
        val hours = totalMs / 3600000
        val minutes = (totalMs % 3600000) / 60000
        val secs = (totalMs % 60000) / 1000
        val millis = totalMs % 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, secs, millis)
    }

    /**
     * 将 JSON 字幕转换为 WebVTT 并保存到指定的本地缓存文件
     */
    fun saveJsonAsVttFile(jsonContent: String, targetFile: File): File {
        val vttContent = convertJsonToVtt(jsonContent)
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(vttContent, Charsets.UTF_8)
        return targetFile
    }
}
