package com.vrplayer.bilisbs

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.vrplayer.bilisbs.bilibili.BilibiliParser
import com.vrplayer.bilisbs.bilibili.CookieStore
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val TEST_VIDEO_URL =
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
    }

    private lateinit var editUrl: TextInputEditText
    private lateinit var btnPlayVr: MaterialButton
    private lateinit var btnPlayNormal: MaterialButton
    private lateinit var btnTestUrl: MaterialButton
    private lateinit var btnLocalFile: MaterialButton
    private lateinit var btnLogin: MaterialButton
    private lateinit var loginStatusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var historyTitle: TextView
    private lateinit var historyList: LinearLayout

    private lateinit var cookieStore: CookieStore
    private lateinit var historyStore: PlaybackHistoryStore

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            Toast.makeText(this, "已授予视频读取权限", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要视频读取权限才能选择本地视频", Toast.LENGTH_LONG).show()
        }
    }

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateLoginUI()
        if (cookieStore.isLoggedIn()) {
            Toast.makeText(this, "登录成功，可以解析更高清晰度", Toast.LENGTH_LONG).show()
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            launchPlayer(videoUrl = uri.toString(), title = "本地视频", mode = PlayerActivity.MODE_VR)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cookieStore = CookieStore(this)
        historyStore = PlaybackHistoryStore(this)

        editUrl = findViewById(R.id.editUrl)
        btnPlayVr = findViewById(R.id.btnPlayVr)
        btnPlayNormal = findViewById(R.id.btnPlayNormal)
        btnTestUrl = findViewById(R.id.btnTestUrl)
        btnLocalFile = findViewById(R.id.btnLocalFile)
        btnLogin = findViewById(R.id.btnLogin)
        loginStatusText = findViewById(R.id.loginStatusText)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        historyTitle = findViewById(R.id.historyTitle)
        historyList = findViewById(R.id.historyList)

        updateLoginUI()
        checkAndRequestStoragePermission()

        btnPlayVr.setOnClickListener { playFromInput(PlayerActivity.MODE_VR) }
        btnPlayNormal.setOnClickListener { playFromInput(PlayerActivity.MODE_NORMAL) }

        btnTestUrl.setOnClickListener {
            editUrl.setText(TEST_VIDEO_URL)
            launchPlayer(videoUrl = TEST_VIDEO_URL, title = "测试视频", mode = PlayerActivity.MODE_VR)
        }

        btnLocalFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("video/*"))
        }

        btnLogin.setOnClickListener {
            if (cookieStore.isLoggedIn()) {
                cookieStore.clear()
                updateLoginUI()
                Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
            } else {
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
    }

    private fun playFromInput(mode: String) {
        val url = editUrl.text?.toString()?.trim()
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, R.string.error_empty_url, Toast.LENGTH_SHORT).show()
            return
        }
        if (BilibiliParser.isBilibiliUrl(url)) {
            parseBilibiliAndPlay(url, mode)
        } else {
            val history = historyStore.find(url)
            launchPlayer(videoUrl = url, title = history?.title, mode = mode, startPositionMs = history?.positionMs ?: 0L)
        }
    }

    private fun parseBilibiliAndPlay(url: String, mode: String) {
        setLoading(true, "正在解析 B 站视频...")
        Thread {
            try {
                val parser = BilibiliParser()
                cookieStore.toCookieHeader()?.let { parser.setCookie(it) }
                val videoInfo = parser.parse(url)
                runOnUiThread {
                    setLoading(false)
                    Log.d(TAG, "解析成功: ${videoInfo.title} [${videoInfo.qualityDesc}]")
                    Toast.makeText(this, "${videoInfo.title}\n${videoInfo.qualityDesc}", Toast.LENGTH_SHORT).show()
                    val history = historyStore.find(videoInfo.videoUrl)
                    launchPlayer(
                        videoUrl = videoInfo.videoUrl,
                        audioUrl = videoInfo.audioUrl,
                        title = videoInfo.title,
                        mode = mode,
                        startPositionMs = history?.positionMs ?: 0L
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "B 站视频解析失败", e)
                runOnUiThread {
                    setLoading(false)
                    Toast.makeText(this, "解析失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun launchPlayer(
        videoUrl: String,
        audioUrl: String? = null,
        title: String? = null,
        mode: String,
        startPositionMs: Long = 0L
    ) {
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_VIDEO_URL, videoUrl)
        intent.putExtra(PlayerActivity.EXTRA_MODE, mode)
        intent.putExtra(PlayerActivity.EXTRA_START_POSITION_MS, startPositionMs)
        audioUrl?.let { intent.putExtra(PlayerActivity.EXTRA_AUDIO_URL, it) }
        title?.let { intent.putExtra(PlayerActivity.EXTRA_TITLE, it) }
        startActivity(intent)
    }

    private fun renderHistory() {
        val items = historyStore.getAll()
        historyTitle.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        historyList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        historyList.removeAllViews()

        items.take(8).forEach { item ->
            val row = layoutInflater.inflate(R.layout.history_item, historyList, false)
            row.findViewById<TextView>(R.id.historyItemTitle).text = item.title?.takeIf { it.isNotBlank() } ?: "未命名视频"
            row.findViewById<TextView>(R.id.historyItemMeta).text = buildHistoryMeta(item)
            row.findViewById<MaterialButton>(R.id.btnHistoryResume).setOnClickListener {
                launchPlayer(item.videoUrl, item.audioUrl, item.title, PlayerActivity.MODE_VR, item.positionMs)
            }
            row.findViewById<MaterialButton>(R.id.btnHistoryReplay).setOnClickListener {
                launchPlayer(item.videoUrl, item.audioUrl, item.title, PlayerActivity.MODE_VR, 0L)
            }
            row.setOnClickListener {
                launchPlayer(item.videoUrl, item.audioUrl, item.title, PlayerActivity.MODE_VR, item.positionMs)
            }
            historyList.addView(row)
        }
    }

    private fun buildHistoryMeta(item: PlaybackHistoryItem): String {
        val position = formatTime(item.positionMs)
        val duration = if (item.durationMs > 0) formatTime(item.durationMs) else "--:--"
        return String.format(Locale.US, "上次看到 %s / %s", position, duration)
    }

    private fun updateLoginUI() {
        if (cookieStore.isLoggedIn()) {
            btnLogin.text = "退出 B 站登录"
            loginStatusText.text = "已登录，支持解析高清"
        } else {
            btnLogin.text = "登录 B 站"
            loginStatusText.text = "未登录，部分视频清晰度受限"
        }
        loginStatusText.visibility = View.VISIBLE
    }

    private fun setLoading(loading: Boolean, message: String = "") {
        btnPlayVr.isEnabled = !loading
        btnPlayNormal.isEnabled = !loading
        btnTestUrl.isEnabled = !loading
        btnLocalFile.isEnabled = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        statusText.visibility = if (loading) View.VISIBLE else View.GONE
        statusText.text = message
    }

    private fun checkAndRequestStoragePermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissionLauncher.launch(permissions)
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000L
        return if (s >= 3600L) {
            String.format(Locale.US, "%d:%02d:%02d", s / 3600L, (s % 3600L) / 60L, s % 60L)
        } else {
            String.format(Locale.US, "%02d:%02d", s / 60L, s % 60L)
        }
    }
}
