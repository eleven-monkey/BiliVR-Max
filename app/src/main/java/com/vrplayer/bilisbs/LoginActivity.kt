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

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Phone) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                Log.d(TAG, "page redirect: $url")
                if (url.contains("bilibili.com") && !url.contains("passport")) {
                    checkAndExtractCookies()
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null && url.contains("bilibili.com")) {
                    checkAndExtractCookies()
                }
            }
        }

        webView.loadUrl(LOGIN_URL)
    }

    private fun checkAndExtractCookies() {
        if (loginDetected) return
        val cookieManager = CookieManager.getInstance()
        val domains = listOf(
            "https://www.bilibili.com",
            "https://bilibili.com",
            "https://passport.bilibili.com",
            "https://.bilibili.com"
        )

        for (domain in domains) {
            val cookies = cookieManager.getCookie(domain)
            if (cookies != null && cookies.contains("SESSDATA")) {
                parseCookiesAndSave(cookies)
                return
            }
        }
    }

    private fun parseCookiesAndSave(cookieString: String) {
        if (loginDetected) return
        loginDetected = true

        var sessdata: String? = null
        var biliJct: String? = null
        var dedeUserId: String? = null

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
            Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
            setResult(RESULT_LOGIN_SUCCESS)
            finish()
        } else {
            loginDetected = false
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
