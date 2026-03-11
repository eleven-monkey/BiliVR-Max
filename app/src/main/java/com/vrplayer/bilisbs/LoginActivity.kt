package com.vrplayer.bilisbs

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vrplayer.bilisbs.bilibili.CookieStore

/**
 * B站 WebView 登录页
 *
 * 加载 B 站移动端登录页面，用户用手机号+验证码登录。
 * 登录成功后自动提取 SESSDATA Cookie 并保存。
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"
        private const val LOGIN_URL = "https://passport.bilibili.com/login"
        const val RESULT_LOGIN_SUCCESS = 1001
    }

    private lateinit var webView: WebView
    private lateinit var cookieStore: CookieStore
    private var loginDetected = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cookieStore = CookieStore(this)

        webView = WebView(this)
        setContentView(webView)

        // 配置 WebView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Phone) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        // 确保 CookieManager 启用
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                Log.d(TAG, "页面跳转: $url")

                // 检测是否登录成功（登录后 B 站会跳转到首页或 crossDomain）
                if (url.contains("bilibili.com") && !url.contains("passport")) {
                    checkAndExtractCookies()
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "页面加载完成: $url")

                // 每次页面加载完成都检查 Cookie
                if (url != null && url.contains("bilibili.com")) {
                    checkAndExtractCookies()
                }
            }
        }

        // 加载登录页
        Log.d(TAG, "加载登录页: $LOGIN_URL")
        webView.loadUrl(LOGIN_URL)
    }

    /**
     * 从 WebView 的 CookieManager 中提取 SESSDATA
     */
    private fun checkAndExtractCookies() {
        if (loginDetected) return

        val cookieManager = CookieManager.getInstance()

        // 从多个域名尝试获取 Cookie
        val domains = listOf(
            "https://www.bilibili.com",
            "https://bilibili.com",
            "https://passport.bilibili.com",
            "https://.bilibili.com"
        )

        for (domain in domains) {
            val cookies = cookieManager.getCookie(domain)
            if (cookies != null && cookies.contains("SESSDATA")) {
                Log.d(TAG, "从 $domain 获取到 Cookie")
                parseCookiesAndSave(cookies)
                return
            }
        }
    }

    /**
     * 解析 Cookie 字符串并保存
     */
    private fun parseCookiesAndSave(cookieString: String) {
        if (loginDetected) return
        loginDetected = true

        Log.d(TAG, "原始 Cookie: ${cookieString.take(100)}...")

        var sessdata: String? = null
        var biliJct: String? = null
        var dedeUserId: String? = null

        // Cookie 格式: "key1=value1; key2=value2; ..."
        cookieString.split(";").forEach { part ->
            val trimmed = part.trim()
            when {
                trimmed.startsWith("SESSDATA=") -> sessdata = trimmed.substringAfter("=")
                trimmed.startsWith("bili_jct=") -> biliJct = trimmed.substringAfter("=")
                trimmed.startsWith("DedeUserID=") -> dedeUserId = trimmed.substringAfter("=")
            }
        }

        if (sessdata != null) {
            cookieStore.save(
                sessdata = sessdata!!,
                biliJct = biliJct ?: "",
                dedeUserId = dedeUserId ?: ""
            )
            Log.d(TAG, "🎉 登录成功! SESSDATA=${sessdata!!.take(10)}..., UID=$dedeUserId")

            Toast.makeText(this, "🎉 登录成功！", Toast.LENGTH_SHORT).show()

            setResult(RESULT_LOGIN_SUCCESS)
            finish()
        } else {
            loginDetected = false
            Log.w(TAG, "Cookie 中未找到 SESSDATA")
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
