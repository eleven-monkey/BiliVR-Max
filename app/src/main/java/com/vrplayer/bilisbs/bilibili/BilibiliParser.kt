package com.vrplayer.bilisbs.bilibili

import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vrplayer.bilisbs.model.VideoInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * B站视频解析器
 *
 * 从 B 站网页链接中提取 BVid，然后调用 B 站公开 API 获取
 * DASH 格式的视频流和音频流地址。
 */
class BilibiliParser {

    companion object {
        private const val TAG = "BilibiliParser"

        // B站 API 地址
        private const val API_VIEW = "https://api.bilibili.com/x/web-interface/view"
        private const val API_PLAYURL = "https://api.bilibili.com/x/player/playurl"

        // 浏览器 User-Agent
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // BVid 正则
        private val BVID_REGEX = Regex("BV[a-zA-Z0-9]{10}")

        // URL 正则
        private val URL_REGEX = Regex("(https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|])")

        /**
         * 判断一个 URL 是否是 B 站视频链接
         */
        fun isBilibiliUrl(url: String): Boolean {
            return url.contains("bilibili.com/video/") ||
                   url.contains("b23.tv/") ||
                   url.contains("bilibili.com/s/video/") ||
                   BVID_REGEX.containsMatchIn(url)
        }

        /**
         * 从包含中文等其他字符的分享文本中提取出真实的网址
         */
        fun extractUrl(text: String): String? {
            return URL_REGEX.find(text)?.value
        }

        /**
         * 从 URL 中提取 BVid
         */
        fun extractBvid(url: String): String? {
            val match = BVID_REGEX.find(url)
            return match?.value
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    // 登录后的 Cookie（含 SESSDATA，可解锁高清）
    private var cookieHeader: String? = null

    /**
     * 设置登录 Cookie，解锁 1080P+ 清晰度
     */
    fun setCookie(cookie: String?) {
        cookieHeader = cookie
    }

    /**
     * 解析 B 站视频链接，返回 VideoInfo
     */
    fun parse(url: String): VideoInfo {
        // -1. 从包含文字的分享内容中提取真实的 URL
        val extractedUrl = extractUrl(url)
            ?: throw IllegalArgumentException("无法从文本中提取出有效链接: $url")

        // 0. 解析短链接（如果是 b23.tv 这种可能会有 HTTP 跳转）
        val realUrl = resolveShortUrl(extractedUrl)

        // 1. 提取 BVid
        val bvid = extractBvid(realUrl)
            ?: throw IllegalArgumentException("无法从链接中提取 BVid: $realUrl")
        Log.d(TAG, "提取到 BVid: $bvid")

        // 2. 从 URL 提取分P编号（?p=8 → 8，默认 1）
        val pageIndex = Uri.parse(url).getQueryParameter("p")?.toIntOrNull() ?: 1
        Log.d(TAG, "目标分P: P$pageIndex")

        // 3. 获取视频基本信息（title + pages 数组）
        val viewInfo = fetchVideoView(bvid)
        val title = viewInfo.get("title").asString

        // 4. 从 pages 数组中找到目标分P的 cid
        val pagesArray = viewInfo.getAsJsonArray("pages")
        var cid = viewInfo.get("cid").asLong  // 默认 P1
        var partTitle = ""
        if (pagesArray != null) {
            for (page in pagesArray) {
                val pageObj = page.asJsonObject
                if (pageObj.get("page")?.asInt == pageIndex) {
                    cid = pageObj.get("cid").asLong
                    partTitle = pageObj.get("part")?.asString ?: ""
                    break
                }
            }
        }

        // 分P标题：「视频总标题 - P8 分集名称」
        val fullTitle = if (partTitle.isNotEmpty() && pageIndex > 1) {
            "$title - P$pageIndex $partTitle"
        } else {
            title
        }
        Log.d(TAG, "视频标题: $fullTitle, CID: $cid")

        // 5. 获取 DASH 播放地址
        val playInfo = fetchPlayUrl(bvid, cid)
        val dash = playInfo.getAsJsonObject("dash")
            ?: throw RuntimeException("API 未返回 DASH 数据，可能需要登录或视频不可用")

        // 6. 选择最佳视频流（列表已按 bandwidth 降序，取第一个）
        val videoArray = dash.getAsJsonArray("video")
        if (videoArray == null || videoArray.size() == 0) {
            throw RuntimeException("未找到可用的视频流")
        }
        val bestVideo = videoArray[0].asJsonObject
        val videoStreamUrl = bestVideo.get("baseUrl")?.asString
            ?: bestVideo.get("base_url")?.asString
            ?: throw RuntimeException("无法获取视频流地址")
        val videoQuality = bestVideo.get("id")?.asInt ?: 0

        // 7. 选择最佳音频流
        var audioStreamUrl: String? = null
        val audioArray = dash.getAsJsonArray("audio")
        if (audioArray != null && audioArray.size() > 0) {
            val bestAudio = audioArray[0].asJsonObject
            audioStreamUrl = bestAudio.get("baseUrl")?.asString
                ?: bestAudio.get("base_url")?.asString
        }

        // 8. 清晰度描述
        val qualityDesc = getQualityDesc(videoQuality)

        Log.d(TAG, "视频流: $videoStreamUrl")
        Log.d(TAG, "音频流: $audioStreamUrl")
        Log.d(TAG, "清晰度: $qualityDesc ($videoQuality)")

        return VideoInfo(
            title = fullTitle,
            bvid = bvid,
            cid = cid,
            videoUrl = videoStreamUrl,
            audioUrl = audioStreamUrl,
            quality = videoQuality,
            qualityDesc = qualityDesc
        )
    }

    /**
     * 解析短链接 (b23.tv 等) 的真实地址
     * 如果不需要跳转或者本身就是长链接，则原样返回
     */
    private fun resolveShortUrl(originalUrl: String): String {
        // 简单判断：如果已经包含 BV 号了，说明不用跳转
        if (extractBvid(originalUrl) != null) {
            return originalUrl
        }

        if (!originalUrl.contains("b23.tv") && !originalUrl.contains("bilibili.com/s/")) {
            return originalUrl
        }

        Log.d(TAG, "准备解析短链接: $originalUrl")

        // 为了防止 OkHttp 自己处理了跳转导致拿不到中间地址，
        // 我们建一个临时的 不跟随跳转(followRedirects=false) 的 client
        val noRedirectClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val request = Request.Builder()
            .url(originalUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        try {
            val response = noRedirectClient.newCall(request).execute()
            response.use {
                // 如果是 301, 302 跳转
                if (it.isRedirect) {
                    val location = it.header("Location")
                    if (!location.isNullOrEmpty()) {
                        // 拿到了真实跳转地址
                        Log.d(TAG, "短链接跳转到: $location")
                        return location
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析短链接失败: ${e.message}")
        }
        return originalUrl
    }

    /**
     * 获取视频基本信息（标题、cid 等）
     */
    private fun fetchVideoView(bvid: String): JsonObject {
        val url = "$API_VIEW?bvid=$bvid"
        val json = httpGet(url)
        val root = gson.fromJson(json, JsonObject::class.java)

        val code = root.get("code")?.asInt ?: -1
        if (code != 0) {
            val message = root.get("message")?.asString ?: "未知错误"
            throw RuntimeException("获取视频信息失败 (code=$code): $message")
        }

        return root.getAsJsonObject("data")
            ?: throw RuntimeException("API 返回的数据为空")
    }

    /**
     * 获取 DASH 播放地址
     *
     * fnval=4048 请求最全的 DASH 格式（包含视频+音频+杜比等）
     * qn=120 请求最高清晰度（实际返回取决于是否登录）
     */
    private fun fetchPlayUrl(bvid: String, cid: Long): JsonObject {
        val url = "$API_PLAYURL?bvid=$bvid&cid=$cid&qn=120&fnval=4048&fourk=1"
        val json = httpGet(url)
        val root = gson.fromJson(json, JsonObject::class.java)

        val code = root.get("code")?.asInt ?: -1
        if (code != 0) {
            val message = root.get("message")?.asString ?: "未知错误"
            throw RuntimeException("获取播放地址失败 (code=$code): $message")
        }

        return root.getAsJsonObject("data")
            ?: throw RuntimeException("API 返回的播放数据为空")
    }

    /**
     * 执行 HTTP GET 请求
     */
    private fun httpGet(url: String): String {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com")

        // 如果已登录，带上 Cookie
        cookieHeader?.let {
            requestBuilder.header("Cookie", it)
        }

        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP 请求失败: ${response.code} ${response.message}")
            }
            return response.body?.string()
                ?: throw RuntimeException("HTTP 响应体为空")
        }
    }

    /**
     * 清晰度数字 → 文字描述
     */
    private fun getQualityDesc(qn: Int): String {
        return when (qn) {
            127 -> "8K 超高清"
            126 -> "杜比视界"
            125 -> "HDR 真彩"
            120 -> "4K 超清"
            116 -> "1080P 60帧"
            112 -> "1080P 高码率"
            80 -> "1080P"
            74 -> "720P 60帧"
            64 -> "720P"
            32 -> "480P"
            16 -> "360P"
            else -> "${qn}P"
        }
    }
}
