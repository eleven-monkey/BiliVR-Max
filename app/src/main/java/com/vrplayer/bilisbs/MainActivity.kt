package com.vrplayer.bilisbs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.vrplayer.bilisbs.bilibili.BilibiliParser
import com.vrplayer.bilisbs.bilibili.CookieStore
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val TEST_VIDEO_URL =
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
    }

    private lateinit var editUrl: TextInputEditText
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnTestUrl: MaterialButton
    private lateinit var btnLocalFile: MaterialButton
    private lateinit var btnLogin: MaterialButton
    private lateinit var loginStatusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private lateinit var cookieStore: CookieStore

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "需要存储权限才能加载本地字幕", Toast.LENGTH_LONG).show()
        }
    }

    // 登录结果回调
    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        updateLoginUI()
        if (cookieStore.isLoggedIn()) {
            Toast.makeText(this, "🎉 登录成功！现在可以观看高清视频了", Toast.LENGTH_LONG).show()
        }
    }

    // 本地文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // 获取持久化读取权限
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            Log.d(TAG, "选择了本地文件: $uri")
            launchPlayer(videoUrl = uri.toString(), title = "本地视频")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cookieStore = CookieStore(this)

        editUrl = findViewById(R.id.editUrl)
        btnPlay = findViewById(R.id.btnPlay)
        btnTestUrl = findViewById(R.id.btnTestUrl)
        btnLocalFile = findViewById(R.id.btnLocalFile)
        btnLogin = findViewById(R.id.btnLogin)
        loginStatusText = findViewById(R.id.loginStatusText)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        updateLoginUI()

        // 请求存储权限
        checkAndRequestStoragePermission()

        // 点击"SBS 模式播放"
        btnPlay.setOnClickListener {
            val url = editUrl.text?.toString()?.trim()
            if (url.isNullOrEmpty()) {
                Toast.makeText(this, R.string.error_empty_url, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (BilibiliParser.isBilibiliUrl(url)) {
                parseBilibiliAndPlay(url)
            } else {
                launchPlayer(videoUrl = url)
            }
        }

        // 点击"使用测试视频"
        btnTestUrl.setOnClickListener {
            editUrl.setText(TEST_VIDEO_URL)
            launchPlayer(videoUrl = TEST_VIDEO_URL)
        }

        // 点击"打开本地视频文件"
        btnLocalFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("video/*"))
        }

        // 点击"登录B站" / "退出登录"
        btnLogin.setOnClickListener {
            if (cookieStore.isLoggedIn()) {
                cookieStore.clear()
                updateLoginUI()
                Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, LoginActivity::class.java)
                loginLauncher.launch(intent)
            }
        }
    }

    private fun parseBilibiliAndPlay(url: String) {
        setLoading(true, "正在解析B站视频...")

        Thread {
            try {
                val parser = BilibiliParser()
                cookieStore.toCookieHeader()?.let { parser.setCookie(it) }
                val videoInfo = parser.parse(url)

                runOnUiThread {
                    setLoading(false)
                    Log.d(TAG, "解析成功: ${videoInfo.title} [${videoInfo.qualityDesc}]")
                    Toast.makeText(
                        this,
                        "${videoInfo.title}\n${videoInfo.qualityDesc}",
                        Toast.LENGTH_SHORT
                    ).show()
                    launchPlayer(
                        videoUrl = videoInfo.videoUrl,
                        audioUrl = videoInfo.audioUrl,
                        title = videoInfo.title
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "B站视频解析失败", e)
                runOnUiThread {
                    setLoading(false)
                    Toast.makeText(this, "解析失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun launchPlayer(videoUrl: String, audioUrl: String? = null, title: String? = null) {
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_VIDEO_URL, videoUrl)
        audioUrl?.let { intent.putExtra(PlayerActivity.EXTRA_AUDIO_URL, it) }
        title?.let { intent.putExtra(PlayerActivity.EXTRA_TITLE, it) }
        startActivity(intent)
    }

    private fun updateLoginUI() {
        if (cookieStore.isLoggedIn()) {
            btnLogin.text = "退出登录"
            loginStatusText.text = "✅ 已登录（支持1080P+高清）"
            loginStatusText.visibility = View.VISIBLE
        } else {
            btnLogin.text = "登录B站（解锁高清）"
            loginStatusText.text = "未登录（最高720P）"
            loginStatusText.visibility = View.VISIBLE
        }
    }

    private fun setLoading(loading: Boolean, message: String = "") {
        btnPlay.isEnabled = !loading
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
        
        val needRequest = permissions.any { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }
        
        if (needRequest) {
            permissionLauncher.launch(permissions)
        }
    }
}
