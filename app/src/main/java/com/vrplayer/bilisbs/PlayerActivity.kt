package com.vrplayer.bilisbs

import android.database.Cursor
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.vrplayer.bilisbs.renderer.SBSRenderer
import java.io.File
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_AUDIO_URL = "audio_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MODE = "mode"
        const val EXTRA_START_POSITION_MS = "start_position_ms"
        const val MODE_VR = "vr"
        const val MODE_NORMAL = "normal"
        private const val CONTROLS_HIDE_DELAY = 4000L
        private const val SEEK_INCREMENT_MS = 10000L
        private const val SUBTITLE_BOTTOM_PADDING_FRACTION = 0.18f
    }

    private lateinit var glSurfaceView: GLSurfaceView
    private var normalPlayerView: PlayerView? = null
    private var player: ExoPlayer? = null
    private var renderer: SBSRenderer? = null
    private lateinit var historyStore: PlaybackHistoryStore
    private lateinit var vrSettings: VRSettings

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

    private var lastViewportInfo: SBSRenderer.ViewportInfo? = null
    private var subtitleLeftBaseX = 0f
    private var subtitleRightBaseX = 0f
    private var subtitleBaseY = 0f
    private var controlsVisible = false
    private var isSeeking = false
    private var mode = MODE_VR
    private var historySavedPositionMs = -1L

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            savePlaybackHistory()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        historyStore = PlaybackHistoryStore(this)
        vrSettings = VRSettings(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_VR

        if (mode == MODE_NORMAL) {
            setupNormalPlayer()
        } else {
            setupVrPlayer()
        }
    }

    private fun setupNormalPlayer() {
        val playerView = PlayerView(this).apply {
            useController = true
            keepScreenOn = true
        }
        normalPlayerView = playerView
        setContentView(playerView)

        val exoPlayer = buildPlayer()
        playerView.player = exoPlayer
        player = exoPlayer
        setPlayerMedia(exoPlayer)
        exoPlayer.addListener(commonPlayerListener(exoPlayer))
        exoPlayer.prepare()
        seekToStartPosition(exoPlayer)
        exoPlayer.playWhenReady = true
        handler.post(updateProgressRunnable)
    }

    private fun setupVrPlayer() {
        val rootLayout = FrameLayout(this)
        glSurfaceView = GLSurfaceView(this).apply { setEGLContextClientVersion(2) }
        renderer = SBSRenderer(
            onSurfaceReady = { surface -> runOnUiThread { initVrPlayer(surface) } },
            requestRender = { glSurfaceView.requestRender() }
        )
        renderer?.setOnViewportChangedListener { info -> runOnUiThread { updateSubtitleLayout(info) } }
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        rootLayout.addView(glSurfaceView)

        val controlsView = layoutInflater.inflate(R.layout.player_controls, rootLayout, false)
        rootLayout.addView(controlsView)
        setContentView(rootLayout)
        bindControls()
        applyVRSettings()
        findViewById<View>(R.id.subtitleContainer)?.apply {
            bringToFront()
            isClickable = false
            isFocusable = false
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (settingsPanel.visibility == View.VISIBLE) {
                    settingsPanel.visibility = View.GONE
                    return true
                }
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (e.x < glSurfaceView.width / 2) {
                    seekRelative(-SEEK_INCREMENT_MS)
                    showToast("-10 秒")
                } else {
                    seekRelative(SEEK_INCREMENT_MS)
                    showToast("+10 秒")
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
            it.setBottomPaddingFraction(SUBTITLE_BOTTOM_PADDING_FRACTION)
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
                    val duration = player?.duration ?: 0L
                    tvCurrentTime.text = formatTime(duration * progress / 1000L)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeeking = false
                val duration = player?.duration ?: 0L
                player?.seekTo(duration * (sb?.progress ?: 0) / 1000L)
                resetAutoHide()
            }
        })
        seekScale.setOnSeekBarChangeListener(createVRSeekListener {
            vrSettings.screenScale = it
            renderer?.setDisplayParams(it, vrSettings.screenGap)
            tvScaleLabel.text = "画面大小: $it%"
        })
        seekGap.setOnSeekBarChangeListener(createVRSeekListener {
            val realGap = it - 600
            vrSettings.screenGap = realGap
            renderer?.setDisplayParams(vrSettings.screenScale, realGap)
            tvGapLabel.text = "双屏间距: $realGap"
        })
        seekK1.setOnSeekBarChangeListener(createVRSeekListener {
            vrSettings.distortionK1 = it
            renderer?.setDistortion(it / 100f, vrSettings.distortionK2 / 100f)
            tvK1Label.text = "畸变矫正 K1: ${String.format(Locale.US, "%.2f", it / 100f)}"
        })
        seekK2.setOnSeekBarChangeListener(createVRSeekListener {
            vrSettings.distortionK2 = it
            renderer?.setDistortion(vrSettings.distortionK1 / 100f, it / 100f)
            tvK2Label.text = "畸变矫正 K2: ${String.format(Locale.US, "%.2f", it / 100f)}"
        })
        switchSBS3D.setOnCheckedChangeListener { _, isChecked ->
            vrSettings.sbs3dMode = isChecked
            renderer?.setSBS3DMode(isChecked)
        }
        seekSubtitleDepth.setOnSeekBarChangeListener(createVRSeekListener {
            vrSettings.subtitleDepth = it
            applySubtitleDepth(it)
            tvSubtitleDepthLabel.text = "字幕立体深度: $it"
        })
        seekSubtitleSize.setOnSeekBarChangeListener(createVRSeekListener {
            vrSettings.subtitleSize = it
            applySubtitleSize(it)
            tvSubtitleSizeLabel.text = "字幕大小: ${String.format(Locale.US, "%.1f%%", it / 10f)}"
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

    private fun initVrPlayer(surface: Surface) {
        val exoPlayer = buildPlayer()
        exoPlayer.setVideoSurface(surface)
        setPlayerMedia(exoPlayer)
        exoPlayer.addListener(commonPlayerListener(exoPlayer, vrMode = true))
        exoPlayer.prepare()
        seekToStartPosition(exoPlayer)
        exoPlayer.playWhenReady = true
        player = exoPlayer
        handler.post(updateProgressRunnable)
    }

    private fun buildPlayer(): ExoPlayer {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("BiliSBSVRPlayer")
            .setDefaultRequestProperties(mapOf("Referer" to "https://www.bilibili.com"))
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .build()
    }

    private fun setPlayerMedia(exoPlayer: ExoPlayer) {
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: return
        val audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL)
        val dataSourceFactory = DefaultDataSource.Factory(
            this,
            DefaultHttpDataSource.Factory()
                .setUserAgent("BiliSBSVRPlayer")
                .setDefaultRequestProperties(mapOf("Referer" to "https://www.bilibili.com"))
        )
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(videoUrl))
            .setSubtitleConfigurations(findSubtitleConfigurations(videoUrl))
        if (audioUrl != null) {
            val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItemBuilder.build())
            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(Uri.parse(audioUrl)))
            exoPlayer.setMediaSource(MergingMediaSource(videoSource, audioSource))
        } else {
            exoPlayer.setMediaItem(mediaItemBuilder.build())
        }
    }

    private fun commonPlayerListener(exoPlayer: ExoPlayer, vrMode: Boolean = false) = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (vrMode) renderer?.setVideoSize(videoSize.width, videoSize.height)
        }
        override fun onPlayerError(error: PlaybackException) {
            showToast("播放错误: ${error.message}")
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (vrMode && playbackState == Player.STATE_READY) tvDuration.text = formatTime(exoPlayer.duration)
            if (playbackState == Player.STATE_ENDED) savePlaybackHistory(force = true)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (vrMode) updatePlayPauseButton()
        }
        override fun onCues(cueGroup: CueGroup) {
            if (vrMode) {
                subtitleLeft.setCues(cueGroup.cues)
                subtitleRight.setCues(cueGroup.cues)
            }
        }
    }

    private fun findSubtitleConfigurations(videoUrl: String): List<MediaItem.SubtitleConfiguration> {
        val realPath = getRealPathFromUri(videoUrl) ?: return emptyList()
        val videoFile = File(realPath)
        val dir = videoFile.parentFile ?: return emptyList()
        val baseName = videoFile.nameWithoutExtension
        return dir.listFiles { _, name ->
            name.startsWith(baseName) && (name.endsWith(".srt", true) || name.endsWith(".ass", true))
        }?.map { subFile ->
            val mimeType = if (subFile.name.endsWith(".ass", true)) MimeTypes.TEXT_SSA else MimeTypes.APPLICATION_SUBRIP
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subFile))
                .setMimeType(mimeType)
                .setLanguage("zh")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        } ?: emptyList()
    }

    private fun seekToStartPosition(exoPlayer: ExoPlayer) {
        val startPositionMs = intent.getLongExtra(EXTRA_START_POSITION_MS, 0L)
        if (startPositionMs > 0L) exoPlayer.seekTo(startPositionMs)
    }

    private fun applySubtitleDepth(depth: Int) {
        val vpW = lastViewportInfo?.vpW ?: return
        val shiftPx = depth.toFloat() / VRSettings.SUBTITLE_DEPTH_MAX * vpW * 0.05f
        subtitleLeft.translationX = subtitleLeftBaseX + shiftPx
        subtitleLeft.translationY = subtitleBaseY
        subtitleRight.translationX = subtitleRightBaseX - shiftPx
        subtitleRight.translationY = subtitleBaseY
    }

    private fun applySubtitleSize(size: Int) {
        val fractionalSize = size / 1000f
        listOf(subtitleLeft, subtitleRight).forEach { it.setFractionalTextSize(fractionalSize) }
    }

    private fun updateSubtitleLayout(info: SBSRenderer.ViewportInfo) {
        lastViewportInfo = info
        subtitleLeft.layoutParams = FrameLayout.LayoutParams(info.vpW, info.vpH)
        subtitleRight.layoutParams = FrameLayout.LayoutParams(info.vpW, info.vpH)
        subtitleLeftBaseX = info.leftX.toFloat()
        subtitleRightBaseX = info.rightX.toFloat()
        subtitleBaseY = info.vpY.toFloat()
        applySubtitleDepth(vrSettings.subtitleDepth)
    }

    private fun togglePlayback() { player?.let { it.playWhenReady = !it.playWhenReady } }

    private fun seekRelative(deltaMs: Long) {
        player?.let {
            val duration = if (it.duration > 0) it.duration else Long.MAX_VALUE
            it.seekTo((it.currentPosition + deltaMs).coerceIn(0L, duration))
        }
    }

    private fun updatePlayPauseButton() {
        btnPlayPause.setImageResource(if (player?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    private fun updateProgress() {
        if (isSeeking) return
        player?.let {
            if (mode == MODE_VR && it.duration > 0) {
                seekBar.progress = (it.currentPosition * 1000L / it.duration).toInt()
                tvCurrentTime.text = formatTime(it.currentPosition)
            }
        }
    }

    private fun savePlaybackHistory(force: Boolean = false) {
        val p = player ?: return
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: return
        val position = p.currentPosition.coerceAtLeast(0L)
        if (!force && kotlin.math.abs(position - historySavedPositionMs) < 5000L) return
        historySavedPositionMs = position
        historyStore.upsert(
            PlaybackHistoryItem(
                videoUrl = videoUrl,
                audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL),
                title = intent.getStringExtra(EXTRA_TITLE),
                positionMs = position,
                durationMs = p.duration.takeIf { it > 0L } ?: 0L
            )
        )
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
        if (mode != MODE_VR || !::controlsOverlay.isInitialized) return
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
        val s = ms.coerceAtLeast(0L) / 1000L
        return if (s >= 3600L) {
            String.format(Locale.US, "%d:%02d:%02d", s / 3600L, (s % 3600L) / 60L, s % 60L)
        } else {
            String.format(Locale.US, "%02d:%02d", s / 60L, s % 60L)
        }
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
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun getRealPathFromUri(uriString: String): String? {
        try {
            if (uriString.startsWith("file://")) return uriString.substring(7)
            if (uriString.startsWith("/")) return uriString
            if (!uriString.startsWith("content://")) return null

            val uri = Uri.parse(uriString)
            if (uri.authority == "com.android.externalstorage.documents") {
                val split = DocumentsContract.getDocumentId(uri).split(":")
                if (split.size >= 2 && split[0].equals("primary", ignoreCase = true)) {
                    return "${android.os.Environment.getExternalStorageDirectory()}/${split[1]}"
                }
            }

            if (uri.authority == "com.android.providers.media.documents") {
                val split = DocumentsContract.getDocumentId(uri).split(":")
                if (split.size < 2) return null
                val contentUri = when (split[0]) {
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    else -> return null
                }
                return getDataColumn(contentUri, "_id=?", arrayOf(split[1]))
            }
            return getDataColumn(uri, null, null)
        } catch (_: Exception) {
            return null
        }
    }

    private fun getDataColumn(uri: Uri, selection: String?, selectionArgs: Array<String>?): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf("_data"), selection, selectionArgs, null)
            if (cursor?.moveToFirst() == true) cursor.getString(cursor.getColumnIndexOrThrow("_data")) else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    override fun onResume() {
        super.onResume()
        if (mode == MODE_VR && ::glSurfaceView.isInitialized) glSurfaceView.onResume()
        player?.playWhenReady = true
        handler.removeCallbacks(updateProgressRunnable)
        handler.post(updateProgressRunnable)
    }

    override fun onPause() {
        savePlaybackHistory(force = true)
        super.onPause()
        if (mode == MODE_VR && ::glSurfaceView.isInitialized) glSurfaceView.onPause()
        player?.playWhenReady = false
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(hideControlsRunnable)
    }

    override fun onDestroy() {
        savePlaybackHistory(force = true)
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        normalPlayerView?.player = null
        player?.release()
        player = null
        renderer?.release()
        renderer = null
    }
}
