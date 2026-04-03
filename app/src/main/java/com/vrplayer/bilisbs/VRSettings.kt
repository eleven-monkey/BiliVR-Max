package com.vrplayer.bilisbs

import android.content.Context
import android.content.SharedPreferences

/**
 * VR 显示设置持久化
 * 保存画面大小（百分比）和双屏间距
 */
class VRSettings(context: Context) {

    companion object {
        private const val PREF_NAME = "vr_settings"
        private const val KEY_SCREEN_SCALE = "screen_scale"
        private const val KEY_SCREEN_GAP = "screen_gap"
        private const val KEY_SBS3D_MODE = "sbs3d_mode"

        const val SCALE_MIN = 50
        const val SCALE_MAX = 100
        const val SCALE_DEFAULT = 85

        const val GAP_MIN = -600
        const val GAP_MAX = 600
        const val GAP_DEFAULT = 0

        private const val KEY_DISTORTION_K1 = "distortion_k1"
        private const val KEY_DISTORTION_K2 = "distortion_k2"

        // k1/k2 用整数存储（实际值 = 存储值 / 100f），范围 0~50 → 0.0~0.5
        const val DISTORTION_MIN = 0
        const val DISTORTION_K1_MAX = 50
        const val DISTORTION_K2_MAX = 30
        const val DISTORTION_DEFAULT = 0

        private const val KEY_SUBTITLE_DEPTH = "subtitle_depth"
        const val SUBTITLE_DEPTH_MIN = 0
        const val SUBTITLE_DEPTH_MAX = 100
        const val SUBTITLE_DEPTH_DEFAULT = 15

        private const val KEY_SUBTITLE_SIZE = "subtitle_size"
        const val SUBTITLE_SIZE_MIN = 20
        const val SUBTITLE_SIZE_MAX = 100
        const val SUBTITLE_SIZE_DEFAULT = 40
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 画面大小百分比 (50~100) */
    var screenScale: Int
        get() = prefs.getInt(KEY_SCREEN_SCALE, SCALE_DEFAULT)
        set(value) = prefs.edit().putInt(KEY_SCREEN_SCALE, value.coerceIn(SCALE_MIN, SCALE_MAX)).apply()

    /** 双屏间距 px (-600~600) */
    var screenGap: Int
        get() = prefs.getInt(KEY_SCREEN_GAP, GAP_DEFAULT)
        set(value) = prefs.edit().putInt(KEY_SCREEN_GAP, value.coerceIn(GAP_MIN, GAP_MAX)).apply()

    /** 畸变系数 K1 (整数 0~50，实际使用时除以100) */
    var distortionK1: Int
        get() = prefs.getInt(KEY_DISTORTION_K1, DISTORTION_DEFAULT)
        set(value) = prefs.edit().putInt(KEY_DISTORTION_K1, value.coerceIn(DISTORTION_MIN, DISTORTION_K1_MAX)).apply()

    /** 畸变系数 K2 (整数 0~30，实际使用时除以100) */
    var distortionK2: Int
        get() = prefs.getInt(KEY_DISTORTION_K2, DISTORTION_DEFAULT)
        set(value) = prefs.edit().putInt(KEY_DISTORTION_K2, value.coerceIn(DISTORTION_MIN, DISTORTION_K2_MAX)).apply()

    /** SBS 3D 模式（视频本身是左右分屏的） */
    var sbs3dMode: Boolean
        get() = prefs.getBoolean(KEY_SBS3D_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SBS3D_MODE, value).apply()

    /** 字幕深度（正值表示往内靠拢，立体出屏偏移量） */
    var subtitleDepth: Int
        get() = prefs.getInt(KEY_SUBTITLE_DEPTH, SUBTITLE_DEPTH_DEFAULT)
        set(value) = prefs.edit().putInt(KEY_SUBTITLE_DEPTH, value.coerceIn(SUBTITLE_DEPTH_MIN, SUBTITLE_DEPTH_MAX)).apply()

    /** 字幕大小（整数 20~100，实际使用时除以 1000，即 0.02~0.10 的比例） */
    var subtitleSize: Int
        get() = prefs.getInt(KEY_SUBTITLE_SIZE, SUBTITLE_SIZE_DEFAULT)
        set(value) = prefs.edit().putInt(KEY_SUBTITLE_SIZE, value.coerceIn(SUBTITLE_SIZE_MIN, SUBTITLE_SIZE_MAX)).apply()
}
