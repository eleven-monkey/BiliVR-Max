package com.vrplayer.bilisbs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vrplayer.bilisbs.bilibili.BilibiliParser
import com.vrplayer.bilisbs.bilibili.CookieStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FavoriteActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FavoriteActivity"
        const val EXTRA_PREFILL_URL = "extra_prefill_url"
        const val EXTRA_PREFILL_TITLE = "extra_prefill_title"
    }

    private lateinit var btnBack: ImageButton
    private lateinit var tvHeaderTitle: TextView
    private lateinit var btnImportExport: MaterialButton
    private lateinit var btnAddFavorite: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerFavorites: RecyclerView
    private lateinit var emptyLayout: LinearLayout

    private lateinit var favoriteStore: FavoriteStore
    private lateinit var historyStore: PlaybackHistoryStore
    private lateinit var cookieStore: CookieStore
    private lateinit var adapter: FavoriteAdapter

    // 文件导出 Launcher
    private val exportFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            writeExportToUri(uri)
        }
    }

    // 文件导入 Launcher
    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            readImportFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorite)

        favoriteStore = FavoriteStore(this)
        historyStore = PlaybackHistoryStore(this)
        cookieStore = CookieStore(this)

        initViews()
        setupRecyclerView()

        // 检查是否有传入预填信息（例如从主页一键收藏过来）
        val prefillUrl = intent.getStringExtra(EXTRA_PREFILL_URL)
        val prefillTitle = intent.getStringExtra(EXTRA_PREFILL_TITLE)
        if (!prefillUrl.isNullOrBlank()) {
            showAddOrEditDialog(initialUrl = prefillUrl, initialTitle = prefillTitle)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        tvHeaderTitle = findViewById(R.id.tvFavoriteHeaderTitle)
        btnImportExport = findViewById(R.id.btnImportExport)
        btnAddFavorite = findViewById(R.id.btnAddFavorite)
        progressBar = findViewById(R.id.progressBar)
        recyclerFavorites = findViewById(R.id.recyclerFavorites)
        emptyLayout = findViewById(R.id.emptyLayout)

        btnBack.setOnClickListener { finish() }
        btnAddFavorite.setOnClickListener { showAddOrEditDialog() }
        btnImportExport.setOnClickListener { showImportExportMenu() }
    }

    private fun setupRecyclerView() {
        adapter = FavoriteAdapter(
            onPlayVr = { item -> playItem(item, PlayerActivity.MODE_VR) },
            onPlayNormal = { item -> playItem(item, PlayerActivity.MODE_NORMAL) },
            onMoreClick = { item, anchor -> showItemMenu(item, anchor) }
        )
        recyclerFavorites.layoutManager = LinearLayoutManager(this)
        recyclerFavorites.adapter = adapter
    }

    private fun refreshList() {
        val items = favoriteStore.getAll()
        adapter.submitList(items)
        tvHeaderTitle.text = if (items.isNotEmpty()) "我的收藏 (${items.size})" else "我的收藏"
        emptyLayout.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recyclerFavorites.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun expandBilibiliId(input: String): String {
        if (input.matches(Regex("BV[1-9A-HJ-NP-Za-km-z]{10}"))) {
            return "https://www.bilibili.com/video/$input"
        }
        if (input.matches(Regex("av\\d+", RegexOption.IGNORE_CASE))) {
            return "https://www.bilibili.com/video/${input.lowercase()}"
        }
        if (input.matches(Regex("\\d+"))) {
            return "https://www.bilibili.com/video/av$input"
        }
        return input
    }

    private fun playItem(item: FavoriteItem, mode: String) {
        val rawUrl = expandBilibiliId(item.url.trim())
        if (BilibiliParser.isBilibiliUrl(rawUrl)) {
            parseBilibiliAndPlay(rawUrl, item.title, mode)
        } else {
            val history = historyStore.find(rawUrl)
            launchPlayer(
                videoUrl = rawUrl,
                title = item.title,
                mode = mode,
                startPositionMs = history?.positionMs ?: 0L
            )
        }
    }

    private fun parseBilibiliAndPlay(url: String, defaultTitle: String, mode: String) {
        progressBar.visibility = View.VISIBLE
        Toast.makeText(this, "正在解析 B 站视频...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val parser = BilibiliParser()
                cookieStore.toCookieHeader()?.let { parser.setCookie(it) }
                val videoInfo = parser.parse(url, cacheDir)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    val history = historyStore.find(videoInfo.sourceUrl)
                    launchPlayer(
                        videoUrl = videoInfo.videoUrl,
                        audioUrl = videoInfo.audioUrl,
                        sourceUrl = videoInfo.sourceUrl,
                        title = videoInfo.title.ifBlank { defaultTitle },
                        mode = mode,
                        startPositionMs = history?.positionMs ?: 0L,
                        subtitlePath = videoInfo.subtitlePath
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "解析失败", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "解析失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun launchPlayer(
        videoUrl: String,
        audioUrl: String? = null,
        sourceUrl: String? = null,
        title: String? = null,
        mode: String,
        startPositionMs: Long = 0L,
        subtitlePath: String? = null
    ) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URL, videoUrl)
            putExtra(PlayerActivity.EXTRA_MODE, mode)
            putExtra(PlayerActivity.EXTRA_START_POSITION_MS, startPositionMs)
            audioUrl?.let { putExtra(PlayerActivity.EXTRA_AUDIO_URL, it) }
            sourceUrl?.let { putExtra(PlayerActivity.EXTRA_SOURCE_URL, it) }
            title?.let { putExtra(PlayerActivity.EXTRA_TITLE, it) }
            subtitlePath?.let { putExtra(PlayerActivity.EXTRA_SUBTITLE_PATH, it) }
        }
        startActivity(intent)
    }

    private fun showAddOrEditDialog(
        targetItem: FavoriteItem? = null,
        initialUrl: String? = null,
        initialTitle: String? = null
    ) {
        val isEdit = targetItem != null
        val context = this

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val etUrl = EditText(context).apply {
            hint = "视频网址或 BV 号"
            setText(targetItem?.url ?: initialUrl ?: "")
            isEnabled = !isEdit // 编辑模式下主要修改标题备注
            textSize = 14f
        }

        val etTitle = EditText(context).apply {
            hint = "影片标题（可留空，B站链接将自动获取）"
            setText(targetItem?.title ?: initialTitle ?: "")
            textSize = 14f
        }

        layout.addView(etUrl)
        layout.addView(etTitle)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (isEdit) "编辑收藏" else "添加影片收藏")
            .setView(layout)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            val saveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveBtn.setOnClickListener {
                val inputUrl = etUrl.text.toString().trim()
                var inputTitle = etTitle.text.toString().trim()

                if (inputUrl.isEmpty()) {
                    etUrl.error = "请输入视频网址"
                    return@setOnClickListener
                }

                val expandedUrl = expandBilibiliId(inputUrl)

                val editing = targetItem
                if (editing != null) {
                    val updated = editing.copy(
                        title = inputTitle.ifBlank { editing.title },
                        createdAtMs = System.currentTimeMillis()
                    )
                    favoriteStore.update(updated)
                    refreshList()
                    dialog.dismiss()
                    Toast.makeText(this, "修改成功", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // 新增收藏：如果未输入标题且是 B 站链接，尝试异步获取标题
                if (inputTitle.isEmpty() && BilibiliParser.isBilibiliUrl(expandedUrl)) {
                    Toast.makeText(this, "正在获取视频信息...", Toast.LENGTH_SHORT).show()
                    Thread {
                        val parser = BilibiliParser()
                        cookieStore.toCookieHeader()?.let { parser.setCookie(it) }
                        val fetchedTitle = parser.fetchTitleFast(expandedUrl)
                        runOnUiThread {
                            val finalTitle = fetchedTitle?.ifBlank { null } ?: "B站视频: $inputUrl"
                            favoriteStore.add(finalTitle, expandedUrl)
                            refreshList()
                            dialog.dismiss()
                            Toast.makeText(this, "收藏成功: $finalTitle", Toast.LENGTH_SHORT).show()
                        }
                    }.start()
                } else {
                    val finalTitle = inputTitle.ifBlank { "影片: $inputUrl" }
                    favoriteStore.add(finalTitle, expandedUrl)
                    refreshList()
                    dialog.dismiss()
                    Toast.makeText(this, "收藏成功", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()

        // 如果传入了 URL 但没有标题，自动触发一次标题获取填入
        if (!isEdit && !initialUrl.isNullOrBlank() && initialTitle.isNullOrBlank()) {
            val expanded = expandBilibiliId(initialUrl.trim())
            if (BilibiliParser.isBilibiliUrl(expanded)) {
                Thread {
                    val parser = BilibiliParser()
                    cookieStore.toCookieHeader()?.let { parser.setCookie(it) }
                    val fetched = parser.fetchTitleFast(expanded)
                    if (!fetched.isNullOrBlank()) {
                        runOnUiThread {
                            if (etTitle.text.isNullOrBlank()) {
                                etTitle.setText(fetched)
                            }
                        }
                    }
                }.start()
            }
        }
    }

    private fun showItemMenu(item: FavoriteItem, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "修改标题")
        popup.menu.add(0, 2, 1, "复制网址")
        popup.menu.add(0, 3, 2, "删除收藏")

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    showAddOrEditDialog(targetItem = item)
                    true
                }
                2 -> {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("video_url", item.url))
                    Toast.makeText(this, "已复制网址到剪贴板", Toast.LENGTH_SHORT).show()
                    true
                }
                3 -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("确认删除")
                        .setMessage("确定要删除「${item.title}」吗？")
                        .setPositiveButton("删除") { _, _ ->
                            favoriteStore.remove(item.id)
                            refreshList()
                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showImportExportMenu() {
        val options = arrayOf(
            "导出到 JSON 文件（备份）",
            "从 JSON 文件导入（恢复）",
            "复制全部收藏到剪贴板",
            "从剪贴板粘贴导入"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("收藏备份与导入")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportToFile()
                    1 -> importFromFile()
                    2 -> exportToClipboard()
                    3 -> importFromClipboard()
                }
            }
            .show()
    }

    private fun exportToFile() {
        val items = favoriteStore.getAll()
        if (items.isEmpty()) {
            Toast.makeText(this, "当前没有收藏记录可导出", Toast.LENGTH_SHORT).show()
            return
        }
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val fileName = "bilivr_favorites_$dateStr.json"
        exportFileLauncher.launch(fileName)
    }

    private fun writeExportToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, "UTF-8").use { writer ->
                    writer.write(favoriteStore.exportJson())
                }
            }
            Toast.makeText(this, "导出备份成功！", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "导出失败", e)
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importFromFile() {
        importFileLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
    }

    private fun readImportFromUri(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                    reader.readText()
                }
            }
            if (json.isNullOrBlank()) {
                Toast.makeText(this, "文件内容为空", Toast.LENGTH_SHORT).show()
                return
            }
            val result = favoriteStore.importJson(json, merge = true)
            refreshList()
            Toast.makeText(
                this,
                "导入完成！新增 ${result.first} 条，已存在/跳过 ${result.second} 条",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "导入失败", e)
            Toast.makeText(this, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportToClipboard() {
        val items = favoriteStore.getAll()
        if (items.isEmpty()) {
            Toast.makeText(this, "当前没有收藏记录可复制", Toast.LENGTH_SHORT).show()
            return
        }
        val json = favoriteStore.exportJson()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("bilivr_favorites", json))
        Toast.makeText(this, "已将 ${items.size} 条收藏复制到剪贴板", Toast.LENGTH_LONG).show()
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()

        val input = EditText(this).apply {
            hint = "在此粘贴收藏的 JSON 文本"
            setText(clipText ?: "")
            textSize = 13f
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("从剪贴板导入")
            .setView(input)
            .setPositiveButton("开始导入") { _, _ ->
                val content = input.text.toString().trim()
                if (content.isEmpty()) {
                    Toast.makeText(this, "内容为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                try {
                    val result = favoriteStore.importJson(content, merge = true)
                    refreshList()
                    Toast.makeText(
                        this,
                        "导入成功！新增 ${result.first} 条，跳过 ${result.second} 条",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "解析失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
