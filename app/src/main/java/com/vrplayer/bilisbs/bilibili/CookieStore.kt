package com.vrplayer.bilisbs.bilibili

import android.content.Context
import android.content.SharedPreferences

/**
 * B站登录 Cookie 持久化存储
 * 使用 SharedPreferences 保存 SESSDATA 等关键信息
 */
class CookieStore(context: Context) {

    companion object {
        private const val PREF_NAME = "bilibili_cookies"
        private const val KEY_SESSDATA = "SESSDATA"
        private const val KEY_BILI_JCT = "bili_jct"
        private const val KEY_DEDE_USER_ID = "DedeUserID"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 保存登录后的 Cookie
     */
    fun save(sessdata: String, biliJct: String = "", dedeUserId: String = "") {
        prefs.edit()
            .putString(KEY_SESSDATA, sessdata)
            .putString(KEY_BILI_JCT, biliJct)
            .putString(KEY_DEDE_USER_ID, dedeUserId)
            .apply()
    }

    /**
     * 获取 SESSDATA（最关键的认证 Cookie）
     */
    fun getSessdata(): String? {
        return prefs.getString(KEY_SESSDATA, null)
    }

    /**
     * 是否已登录（有 SESSDATA）
     */
    fun isLoggedIn(): Boolean {
        return !getSessdata().isNullOrEmpty()
    }

    /**
     * 组装成 HTTP Cookie 头的值
     */
    fun toCookieHeader(): String? {
        val sessdata = getSessdata() ?: return null
        val parts = mutableListOf("SESSDATA=$sessdata")
        prefs.getString(KEY_BILI_JCT, null)?.let { parts.add("bili_jct=$it") }
        prefs.getString(KEY_DEDE_USER_ID, null)?.let { parts.add("DedeUserID=$it") }
        return parts.joinToString("; ")
    }

    /**
     * 退出登录（清除所有 Cookie）
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
