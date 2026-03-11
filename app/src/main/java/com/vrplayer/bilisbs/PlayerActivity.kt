package com.vrplayer.bilisbs

import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.vrplayer.bilisbs.renderer.SBSRenderer

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_AUDIO_URL = "audio_url"
        const val EXTRA_TITLE = "title"
        private const val TAG = "PlayerActivity"
        private const val CONTROLS_HIDE_DELAY = 4000L
        private const val SEEK_INCREMENT_MS = 10000L
    }

    private lateinit var glSurfaceView: GLSurfaceView
    private var player: ExoPlayer? = null
    private var renderer: SBSRenderer? = null

    // 控件
    private lateinit var controlsOverlay: View
    private lateinit var settingsPanel: View
    private lateinit var tvTitle: TextView
    private lateinit var tvQuality: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvScaleLabel: TextView
    private lateinit var tvGapLabel: TextView
    private lateinit var tvK1Label: TextView
    private lateinit var tvK2Label: TextView
    private lateinit var seekScale: SeekBar
    private lateinit var seekGap: SeekBar
    private lateinit var seekK1: SeekBar
    private lateinit var seekK2: SeekBar
    private lateinit var switchSBS3D: SwitchCompat

    private lateinit var vrSettings: VRSettings
    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = false
    private var isSeeking = false

    // 自动隐藏控件的 Runnable
    private val hideControlsRunnable = Runnable { hideControls() }

    // 定时更新进度条
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    // 手势检测器
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        vrSettings = VRSettings(this)

        // 创建根布局
        val rootLayout = FrameLayout(this)

        // 1. GLSurfaceView
        glSurfaceView = GLSurfaceView(this)
        glSurfaceView.setEGLContextClientVersion(2)

        renderer = SBSRenderer(
            onSurfaceReady = { surface ->
                runOnUiThread { initPlayer(surface) }
            },
            requestRender = { glSurfaceView.requestRender() }
        )

        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        rootLayout.addView(glSurfaceView)

        // 2. 控件覆盖层
        val controlsView = layoutInflater.inflate(R.layout.player_controls, rootLayout, false)
        rootLayout.addView(controlsView)

        setContentView(rootLayout)

        // 绑定控件
        bindControls()

        // 应用保存的 VR 设置
        applyVRSettings()

        // 手势检测
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (settingsPanel.visibility == View.VISIBLE) {
                    settingsPanel.visibility = View.GONE
                    return true
                }
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val screenMiddle = glSurfaceView.width / 2
                if (e.x < screenMiddle) {
                    seekRelative(-SEEK_INCREMENT_MS)
                    showToast("⏪ -10秒")
                } else {
                    seekRelative(SEEK_INCREMENT_MS)
                    showToast("⏩ +10秒")
                }
                return true
            }
        })

        // 触摸事件分发给手势检测器
        rootLayout.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun bindControls() {
        controlsOverlay = findViewById(R.id.controlsOverlay)
        settingsPanel = findViewById(R.id.settingsPanel)
        tvTitle = findViewById(R.id.tvTitle)
        tvQuality = findViewById(R.id.tvQuality)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        seekBar = findViewById(R.id.seekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvDuration = findViewById(R.id.tvDuration)
        tvScaleLabel = findViewById(R.id.tvScaleLabel)
        tvGapLabel = findViewById(R.id.tvGapLabel)
        tvK1Label = findViewById(R.id.tvK1Label)
        tvK2Label = findViewById(R.id.tvK2Label)
        seekScale = findViewById(R.id.seekScale)
        seekGap = findViewById(R.id.seekGap)
        seekK1 = findViewById(R.id.seekK1)
        seekK2 = findViewById(R.id.seekK2)
        switchSBS3D = findViewById(R.id.switchSBS3D)

        // 显示标题
        val title = intent.getStringExtra(EXTRA_TITLE)
        tvTitle.text = title ?: "视频播放中"

        // 暂停/播放按钮
        btnPlayPause.setOnClickListener {
            togglePlayback()
            resetAutoHide()
        }

        // 设置按钮
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            settingsPanel.visibility =
                if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            resetAutoHide()
        }

        // 关闭设置按钮
        findViewById<View>(R.id.btnCloseSettings).setOnClickListener {
            settingsPanel.visibility = View.GONE
            resetAutoHide()
        }

        // 进度条
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: 0
                    val position = duration * progress / 1000
                    tvCurrentTime.text = formatTime(position)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeeking = false
                val duration = player?.duration ?: 0
                val position = duration * (sb?.progress ?: 0) / 1000
                player?.seekTo(position)
                resetAutoHide()
            }
        })

        // VR 画面大小滑块
        seekScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvScaleLabel.text = "画面大小: ${progress}%"
                if (fromUser) {
                    vrSettings.screenScale = progress
                    val realGap = seekGap.progress - 600
                    renderer?.setDisplayParams(progress, realGap)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // VR 双屏间距滑块 (SeekBar 0~1200, 中点600 = 间距0)
        seekGap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val realGap = progress - 600  // 转为 -600 ~ +600
                tvGapLabel.text = "双屏间距: ${realGap}"
                if (fromUser) {
                    vrSettings.screenGap = realGap
                    renderer?.setDisplayParams(seekScale.progress, realGap)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 畸变矫正 K1 滑块
        seekK1.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvK1Label.text = "畸变矫正 K1: ${String.format("%.2f", progress / 100f)}"
                if (fromUser) {
                    vrSettings.distortionK1 = progress
                    renderer?.setDistortion(progress / 100f, seekK2.progress / 100f)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 畸变矫正 K2 滑块
        seekK2.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvK2Label.text = "畸变矫正 K2: ${String.format("%.2f", progress / 100f)}"
                if (fromUser) {
                    vrSettings.distortionK2 = progress
                    renderer?.setDistortion(seekK1.progress / 100f, progress / 100f)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // SBS 3D 模式开关
        switchSBS3D.setOnCheckedChangeListener { _, isChecked ->
            vrSettings.sbs3dMode = isChecked
            renderer?.setSBS3DMode(isChecked)
        }
    }

    /** 从 SharedPreferences 恢复设置并应用 */
    private fun applyVRSettings() {
        val scale = vrSettings.screenScale
        val gap = vrSettings.screenGap
        val k1 = vrSettings.distortionK1
        val k2 = vrSettings.distortionK2

        seekScale.progress = scale
        seekGap.progress = gap + 600
        seekK1.progress = k1
        seekK2.progress = k2

        tvScaleLabel.text = "画面大小: ${scale}%"
        tvGapLabel.text = "双屏间距: ${gap}"
        tvK1Label.text = "畸变矫正 K1: ${String.format("%.2f", k1 / 100f)}"
        tvK2Label.text = "畸变矫正 K2: ${String.format("%.2f", k2 / 100f)}"

        renderer?.setDisplayParams(scale, gap)
        renderer?.setDistortion(k1 / 100f, k2 / 100f)

        val sbs3d = vrSettings.sbs3dMode
        switchSBS3D.isChecked = sbs3d
        renderer?.setSBS3DMode(sbs3d)
    }

    // =========== 播放控制 ===========

    private fun initPlayer(surface: Surface) {
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        val audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL)

        if (videoUrl.isNullOrEmpty()) {
            Toast.makeText(this, "未提供视频URL", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setDefaultRequestProperties(mapOf("Referer" to "https://www.bilibili.com"))

        // 使用 DefaultDataSource包装HttpDataSource，使其同时支持 http(s), content://, file:// 等多种协议
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .build()
        exoPlayer.setVideoSurface(surface)

        if (audioUrl != null) {
            val progressiveFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
            val videoSource = progressiveFactory.createMediaSource(MediaItem.fromUri(Uri.parse(videoUrl)))
            val audioSource = progressiveFactory.createMediaSource(MediaItem.fromUri(Uri.parse(audioUrl)))
            exoPlayer.setMediaSource(MergingMediaSource(videoSource, audioSource))
        } else {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                renderer?.setVideoSize(videoSize.width, videoSize.height)
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "播放错误: ${error.message}", error)
                runOnUiThread {
                    Toast.makeText(this@PlayerActivity, "播放失败: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    tvDuration.text = formatTime(exoPlayer.duration)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton()
            }
        })

        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        player = exoPlayer

        // 开始定时更新进度
        handler.post(updateProgressRunnable)
    }

    private fun togglePlayback() {
        player?.let {
            it.playWhenReady = !it.playWhenReady
        }
    }

    private fun seekRelative(deltaMs: Long) {
        player?.let {
            val newPos = (it.currentPosition + deltaMs).coerceIn(0, it.duration)
            it.seekTo(newPos)
        }
    }

    private fun updatePlayPauseButton() {
        val isPlaying = player?.isPlaying == true
        btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun updateProgress() {
        if (isSeeking) return
        player?.let {
            if (it.duration > 0) {
                val progress = (it.currentPosition * 1000 / it.duration).toInt()
                seekBar.progress = progress
                tvCurrentTime.text = formatTime(it.currentPosition)
            }
        }
    }

    // =========== 控件显示/隐藏 ===========

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        controlsVisible = true
        controlsOverlay.visibility = View.VISIBLE
        controlsOverlay.alpha = 0f
        controlsOverlay.animate().alpha(1f).setDuration(200).start()
        updatePlayPauseButton()
        resetAutoHide()
    }

    private fun hideControls() {
        controlsOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            controlsOverlay.visibility = View.GONE
            settingsPanel.visibility = View.GONE
        }.start()
        controlsVisible = false
    }

    private fun resetAutoHide() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY)
    }

    // =========== 工具方法 ===========

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    // =========== 生命周期 ===========

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        player?.playWhenReady = true
        handler.post(updateProgressRunnable)
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
        player?.playWhenReady = false
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(hideControlsRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        renderer?.release()
        renderer = null
    }
}
