package com.vrplayer.bilisbs.bilibili

import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * B站二维码登录流程
 *
 * 流程：
 * 1. 调 API 生成二维码 URL + qrcode_key
 * 2. 跳转 B 站 App 让用户确认登录
 * 3. 轮询登录状态，成功后提取 SESSDATA
 */
class QRCodeLogin {

    companion object {
        private const val TAG = "QRCodeLogin"
        private const val API_GENERATE =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        private const val API_POLL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // 轮询状态码
        const val STATUS_SUCCESS = 0
        const val STATUS_NOT_SCANNED = 86101
        const val STATUS_SCANNED_NOT_CONFIRMED = 86090
        const val STATUS_EXPIRED = 86038
    }

    data class QRCodeResult(
        val url: String,        // 二维码对应的 URL（用于跳转 B 站 App）
        val qrcodeKey: String   // 轮询用的 key
    )

    data class PollResult(
        val code: Int,          // 状态码
        val message: String,    // 状态描述
        val sessdata: String?,  // 登录成功时的 SESSDATA
        val biliJct: String?,   // 登录成功时的 bili_jct
        val dedeUserId: String? // 登录成功时的用户 ID
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)  // 不自动跟随重定向，以便捕获 Set-Cookie
        .build()

    private val gson = Gson()

    /**
     * 步骤 1：生成二维码登录请求
     */
    fun generate(): QRCodeResult {
        val request = Request.Builder()
            .url(API_GENERATE)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.body?.string()
                ?: throw RuntimeException("生成二维码失败：响应为空")
            val root = gson.fromJson(json, JsonObject::class.java)

            val code = root.get("code")?.asInt ?: -1
            if (code != 0) {
                throw RuntimeException("生成二维码失败: ${root.get("message")?.asString}")
            }

            val data = root.getAsJsonObject("data")
            val url = data.get("url").asString
            val qrcodeKey = data.get("qrcode_key").asString

            Log.d(TAG, "二维码已生成, key=$qrcodeKey")
            return QRCodeResult(url = url, qrcodeKey = qrcodeKey)
        }
    }

    /**
     * 步骤 3：轮询登录状态
     *
     * 登录成功时，B 站会在返回 JSON 的 data.url 中嵌入 Cookie 参数
     */
    fun poll(qrcodeKey: String): PollResult {
        val url = "$API_POLL?qrcode_key=$qrcodeKey"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.body?.string()
                ?: throw RuntimeException("轮询登录状态失败：响应为空")
            val root = gson.fromJson(json, JsonObject::class.java)

            val data = root.getAsJsonObject("data")
                ?: return PollResult(code = -1, message = "数据为空", null, null, null)

            val statusCode = data.get("code")?.asInt ?: -1
            val message = data.get("message")?.asString ?: ""

            Log.d(TAG, "轮询状态: code=$statusCode, message=$message")

            if (statusCode == STATUS_SUCCESS) {
                // 登录成功！从 data.url 中解析 Cookie
                val redirectUrl = data.get("url")?.asString ?: ""
                val uri = Uri.parse(redirectUrl)
                val sessdata = uri.getQueryParameter("SESSDATA")
                val biliJct = uri.getQueryParameter("bili_jct")
                val dedeUserId = uri.getQueryParameter("DedeUserID")

                Log.d(TAG, "登录成功! SESSDATA=${sessdata?.take(10)}...")

                return PollResult(
                    code = STATUS_SUCCESS,
                    message = "登录成功",
                    sessdata = sessdata,
                    biliJct = biliJct,
                    dedeUserId = dedeUserId
                )
            }

            return PollResult(
                code = statusCode,
                message = when (statusCode) {
                    STATUS_NOT_SCANNED -> "等待扫码..."
                    STATUS_SCANNED_NOT_CONFIRMED -> "已扫码，等待确认..."
                    STATUS_EXPIRED -> "二维码已过期"
                    else -> message
                },
                sessdata = null,
                biliJct = null,
                dedeUserId = null
            )
        }
    }
}
