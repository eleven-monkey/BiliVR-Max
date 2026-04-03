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
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.CueGroup
import com.vrplayer.bilisbs.renderer.SBSRenderer
import java.io.File
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.database.Cursor
import java.util.Locale

@OptIn(UnstableApi::class)
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
    private lateinit var seekSubtitleDepth: SeekBar
    private lateinit var tvSubtitleDepthLabel: TextView
    private lateinit var subtitleLeft: SubtitleView
    private lateinit var subtitleRight: SubtitleView
    private lateinit var btnToggleSubtitle: View
    private lateinit var seekSubtitleSize: SeekBar
    private lateinit var tvSubtitleSizeLabel: TextView

    // Viewport 跟踪，用于字幕精确定位
    private var lastViewportInfo: SBSRenderer.ViewportInfo? = null
    private var subtitleLeftBaseX = 0f
    private var subtitleRightBaseX = 0f
    private var subtitleBaseY = 0f

    private lateinit var vrSettings: VRSettings
    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = false
    private var isSeeking = false

    private val hideControlsRunnable = Runnable { hideControls() }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        vrSettings = VRSettings(this)
        val rootLayout = FrameLayout(this)

        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
        }

        renderer = SBSRenderer(
            onSurfaceReady = { surface ->
                runOnUiThread { initPlayer(surface) }
            },
            requestRender = { glSurfaceView.requestRender() }
        )

        renderer?.setOnViewportChangedListener { info ->
            runOnUiThread { updateSubtitleLayout(info) }
        }

        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        rootLayout.addView(glSurfaceView)

        // 先添加控制界面（包含字幕视图），确保在GLSurfaceView上方
        val controlsView = layoutInflater.inflate(R.layout.player_controls, rootLayout, false)
        rootLayout.addView(controlsView)

        setContentView(rootLayout)
        bindControls()
        applyVRSettings()
        
        // 确保字幕视图在最上层且可点击穿透
        findViewById<View>(R.id.subtitleContainer)?.apply {
            bringToFront()
            isClickable = false
            isFocusable = false
        }

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

        rootLayout.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun bindControls() {
        controlsOverlay = findViewById(R.id.controlsOverlay)
        settingsPanel = findViewById(R.id.settingsPanel)
        tvTitle = findViewById(R.id.tvTitle)
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
        seekSubtitleDepth = findViewById(R.id.seekSubtitleDepth)
        tvSubtitleDepthLabel = findViewById(R.id.tvSubtitleDepthLabel)
        subtitleLeft = findViewById(R.id.subtitleLeft)
        subtitleRight = findViewById(R.id.subtitleRight)
        btnToggleSubtitle = findViewById(R.id.btnToggleSubtitle)
        seekSubtitleSize = findViewById(R.id.seekSubtitleSize)
        tvSubtitleSizeLabel = findViewById(R.id.tvSubtitleSizeLabel)

        val captionStyle = CaptionStyleCompat(
            android.graphics.Color.WHITE,
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            null
        )
        listOf(subtitleLeft, subtitleRight).forEach {
            it.setStyle(captionStyle)
            it.setFractionalTextSize(vrSettings.subtitleSize / 1000f)
            it.setApplyEmbeddedStyles(true)
        }

        tvTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: "视频播放中"

        btnPlayPause.setOnClickListener {
            togglePlayback()
            resetAutoHide()
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            resetAutoHide()
        }

        findViewById<View>(R.id.btnCloseSettings).setOnClickListener {
            settingsPanel.visibility = View.GONE
            resetAutoHide()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: 0
                    tvCurrentTime.text = formatTime(duration * progress / 1000)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeeking = false
                val duration = player?.duration ?: 0
                player?.seekTo(duration * (sb?.progress ?: 0) / 1000)
                resetAutoHide()
            }
        })

        seekScale.setOnSeekBarChangeListener(createVRSeekListener { progress ->
            vrSettings.screenScale = progress
            renderer?.setDisplayParams(progress, vrSettings.screenGap)
            tvScaleLabel.text = "画面大小: $progress%"
        })

        seekGap.setOnSeekBarChangeListener(createVRSeekListener { progress ->
            val realGap = progress - 600
            vrSettings.screenGap = realGap
            renderer?.setDisplayParams(vrSettings.screenScale, realGap)
            tvGapLabel.text = "双屏间距: $realGap"
        })

        seekK1.setOnSeekBarChangeListener(createVRSeekListener { progress ->
            vrSettings.distortionK1 = progress
            renderer?.setDistortion(progress / 100f, vrSettings.distortionK2 / 100f)
            tvK1Label.text = "畸变矫正 K1: ${String.format(Locale.US, "%.2f", progress / 100f)}"
        })

        seekK2.setOnSeekBarChangeListener(createVRSeekListener { progress ->
            vrSettings.distortionK2 = progress
            renderer?.setDistortion(vrSettings.distortionK1 / 100f, progress / 100f)
            tvK2Label.text = "畸变矫正 K2: ${String.format(Locale.US, "%.2f", progress / 100f)}"
        })

        switchSBS3D.setOnCheckedChangeListener { _, isChecked ->
            vrSettings.sbs3dMode = isChecked
            renderer?.setSBS3DMode(isChecked)
        }

        seekSubtitleDepth.setOnSeekBarChangeListener(createVRSeekListener { progress ->
            vrSettings.subtitleDepth = progress
            applySubtitleDepth(progress)
            tvSubtitleDepthLabel.text = "字幕立体深度: $progress"
        })

        seekSubtitleSize.setOnSeekBarChangeListener(createVRSeekListener { progress ->
            vrSettings.subtitleSize = progress
            applySubtitleSize(progress)
            tvSubtitleSizeLabel.text = "字幕大小: ${String.format(Locale.US, "%.1f%%", progress / 10f)}"
        })

        btnToggleSubtitle.setOnClickListener {
            player?.let { p ->
                val params = p.trackSelectionParameters
                val isDisabled = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                p.trackSelectionParameters = params.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !isDisabled)
                    .build()
                showToast(if (isDisabled) "字幕已开启" else "字幕已关闭")
                resetAutoHide()
            }
        }
    }

    private fun createVRSeekListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChanged(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun applySubtitleDepth(depth: Int) {
        val vpW = lastViewportInfo?.vpW ?: return
        // 深度偏移：最大为 viewport 宽度的 5%，产生立体出屏效果
        val shiftPx = depth.toFloat() / VRSettings.SUBTITLE_DEPTH_MAX * vpW * 0.05f
        subtitleLeft.translationX = subtitleLeftBaseX + shiftPx
        subtitleLeft.translationY = subtitleBaseY
        subtitleRight.translationX = subtitleRightBaseX - shiftPx
        subtitleRight.translationY = subtitleBaseY
    }

    private fun applySubtitleSize(size: Int) {
        val fractionalSize = size / 1000f
        listOf(subtitleLeft, subtitleRight).forEach {
            it.setFractionalTextSize(fractionalSize)
        }
    }

    private fun updateSubtitleLayout(info: SBSRenderer.ViewportInfo) {
        lastViewportInfo = info
        // 设置字幕视图大小精确匹配 GL viewport
        subtitleLeft.layoutParams = FrameLayout.LayoutParams(info.vpW, info.vpH)
        subtitleRight.layoutParams = FrameLayout.LayoutParams(info.vpW, info.vpH)
        // 记录基准位置
        subtitleLeftBaseX = info.leftX.toFloat()
        subtitleRightBaseX = info.rightX.toFloat()
        subtitleBaseY = info.vpY.toFloat()
        // 应用深度偏移（内含位置更新）
        applySubtitleDepth(vrSettings.subtitleDepth)
    }

    private fun applyVRSettings() {
        val scale = vrSettings.screenScale
        val gap = vrSettings.screenGap
        val k1 = vrSettings.distortionK1
        val k2 = vrSettings.distortionK2
        val sbs3d = vrSettings.sbs3dMode
        val subDepth = vrSettings.subtitleDepth
        val subSize = vrSettings.subtitleSize

        seekScale.progress = scale
        seekGap.progress = gap + 600
        seekK1.progress = k1
        seekK2.progress = k2
        switchSBS3D.isChecked = sbs3d
        seekSubtitleDepth.progress = subDepth
        seekSubtitleSize.progress = subSize

        tvScaleLabel.text = "画面大小: $scale%"
        tvGapLabel.text = "双屏间距: $gap"
        tvK1Label.text = "畸变矫正 K1: ${String.format(Locale.US, "%.2f", k1 / 100f)}"
        tvK2Label.text = "畸变矫正 K2: ${String.format(Locale.US, "%.2f", k2 / 100f)}"
        tvSubtitleDepthLabel.text = "字幕立体深度: $subDepth"
        tvSubtitleSizeLabel.text = "字幕大小: ${String.format(Locale.US, "%.1f%%", subSize / 10f)}"

        renderer?.setDisplayParams(scale, gap)
        renderer?.setDistortion(k1 / 100f, k2 / 100f)
        renderer?.setSBS3DMode(sbs3d)
        applySubtitleDepth(subDepth)
        applySubtitleSize(subSize)
    }

    private fun initPlayer(surface: Surface) {
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: return
        val audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL)

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("BiliSBSVRPlayer")
            .setDefaultRequestProperties(mapOf("Referer" to "https://www.bilibili.com"))
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .build()
        exoPlayer.setVideoSurface(surface)

        val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
        val realPath = getRealPathFromUri(videoUrl)
        Log.d(TAG, "视频URL: $videoUrl, 真实路径: $realPath")
        if (realPath != null) {
            val videoFile = File(realPath)
            Log.d(TAG, "视频文件存在: ${videoFile.exists()}, 可读: ${videoFile.canRead()}")
            if (videoFile.exists()) {
                val dir = videoFile.parentFile
                val baseName = videoFile.nameWithoutExtension
                Log.d(TAG, "查找字幕: 目录=${dir?.absolutePath}, 基础名=$baseName")
                
                if (dir != null && dir.exists()) {
                    Log.d(TAG, "目录可读: ${dir.canRead()}")
                    val allFiles = dir.listFiles()
                    Log.d(TAG, "目录下共有 ${allFiles?.size ?: 0} 个文件")
                    allFiles?.take(10)?.forEach { f ->
                        Log.d(TAG, "  文件: ${f.name}")
                    }
                    
                    val subtitleFiles = dir.listFiles { _, name ->
                        val matches = name.startsWith(baseName) && (name.endsWith(".srt", true) || name.endsWith(".ass", true))
                        if (matches) Log.d(TAG, "  ✅ 匹配字幕: $name")
                        matches
                    }
                    
                    subtitleFiles?.forEach { subFile ->
                        val mimeType = if (subFile.name.endsWith(".ass", true)) MimeTypes.TEXT_SSA else MimeTypes.APPLICATION_SUBRIP
                        subtitleConfigs.add(MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subFile))
                            .setMimeType(mimeType)
                            .setLanguage("zh")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build())
                        Log.d(TAG, "✅ 已挂载字幕: ${subFile.name}")
                    }
                }
            }
        }
        Log.d(TAG, "共找到 ${subtitleConfigs.size} 个字幕文件")

        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(videoUrl)).setSubtitleConfigurations(subtitleConfigs)
        if (audioUrl != null) {
            val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItemBuilder.build())
            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(Uri.parse(audioUrl)))
            exoPlayer.setMediaSource(MergingMediaSource(videoSource, audioSource))
        } else {
            exoPlayer.setMediaItem(mediaItemBuilder.build())
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                renderer?.setVideoSize(videoSize.width, videoSize.height)
            }
            override fun onPlayerError(error: PlaybackException) {
                showToast("播放错误: ${error.message}")
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) tvDuration.text = formatTime(exoPlayer.duration)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { updatePlayPauseButton() }
            override fun onCues(cueGroup: CueGroup) {
                subtitleLeft.setCues(cueGroup.cues)
                subtitleRight.setCues(cueGroup.cues)
            }
        })

        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        player = exoPlayer
        handler.post(updateProgressRunnable)
    }

    private fun togglePlayback() { player?.let { it.playWhenReady = !it.playWhenReady } }
    private fun seekRelative(deltaMs: Long) { player?.let { it.seekTo((it.currentPosition + deltaMs).coerceIn(0, it.duration)) } }
    private fun updatePlayPauseButton() {
        btnPlayPause.setImageResource(if (player?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    private fun updateProgress() {
        if (isSeeking) return
        player?.let {
            if (it.duration > 0) {
                seekBar.progress = (it.currentPosition * 1000 / it.duration).toInt()
                tvCurrentTime.text = formatTime(it.currentPosition)
            }
        }
    }

    private fun toggleControls() { if (controlsVisible) hideControls() else showControls() }
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

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private fun showToast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    /**
     * 将 content:// URI 转换为真实文件路径
     */
    private fun getRealPathFromUri(uriString: String): String? {
        try {
            Log.d(TAG, "开始转换URI: $uriString")
            
            if (uriString.startsWith("file://")) {
                val path = uriString.substring(7)
                Log.d(TAG, "file:// URI, 路径: $path")
                return path
            }
            if (uriString.startsWith("/")) {
                Log.d(TAG, "直接路径: $uriString")
                return uriString
            }
            if (!uriString.startsWith("content://")) {
                Log.d(TAG, "不是content:// URI")
                return null
            }

            val uri = Uri.parse(uriString)
            Log.d(TAG, "content:// URI, authority: ${uri.authority}")
            
            // ExternalStorage Documents Provider (文件管理器选择的文件)
            if (uri.authority == "com.android.externalstorage.documents") {
                val docId = DocumentsContract.getDocumentId(uri)
                Log.d(TAG, "ExternalStorage文档ID: $docId")
                val split = docId.split(":")
                if (split.size < 2) {
                    Log.d(TAG, "文档ID格式错误")
                    return null
                }
                
                val type = split[0]  // "primary" 或 "home" 等
                val relativePath = split[1]  // 相对路径
                
                // primary 表示主外部存储
                if (type.equals("primary", ignoreCase = true)) {
                    val path = "${android.os.Environment.getExternalStorageDirectory()}/$relativePath"
                    Log.d(TAG, "ExternalStorage路径: $path")
                    return path
                }
            }
            
            // MediaStore Documents Provider
            if (uri.authority == "com.android.providers.media.documents") {
                val docId = DocumentsContract.getDocumentId(uri)
                Log.d(TAG, "MediaStore文档ID: $docId")
                val split = docId.split(":")
                if (split.size < 2) {
                    Log.d(TAG, "文档ID格式错误")
                    return null
                }
                
                val contentUri = when (split[0]) {
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    else -> {
                        Log.d(TAG, "未知媒体类型: ${split[0]}")
                        return null
                    }
                }
                
                val path = getDataColumn(contentUri, "_id=?", arrayOf(split[1]))
                Log.d(TAG, "通过MediaStore查询到路径: $path")
                return path
            }
            
            // 直接查询
            val path = getDataColumn(uri, null, null)
            Log.d(TAG, "直接查询到路径: $path")
            return path
        } catch (e: Exception) {
            Log.e(TAG, "获取真实路径异常: $uriString", e)
        }
        return null
    }

    private fun getDataColumn(uri: Uri, selection: String?, selectionArgs: Array<String>?): String? {
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf("_data"), selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) {
                val columnIndex = cursor.getColumnIndexOrThrow("_data")
                return cursor.getString(columnIndex)
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询数据列失败", e)
        } finally {
            cursor?.close()
        }
        return null
    }

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
