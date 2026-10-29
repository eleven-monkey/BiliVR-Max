package com.vrplayer.bilisbs

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

/**
 * 收藏夹本地存储持久化（基于 SharedPreferences + Gson）
 */
class FavoriteStore(context: Context) {

    companion object {
        private const val PREF_NAME = "favorite_store"
        private const val KEY_ITEMS = "favorite_items"
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val listType = object : TypeToken<List<FavoriteItem>>() {}.type

    /**
     * 获取所有收藏列表（按添加时间倒序排列）
     */
    @Synchronized
    fun getAll(): List<FavoriteItem> {
        val json = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<FavoriteItem>>(json, listType) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.createdAtMs }
    }

    /**
     * 标准化 URL 辅助函数，便于匹配（如去除末尾斜杠、统一小写等）
     */
    private fun normalizeUrl(url: String): String {
        return url.trim().trimEnd('/')
    }

    /**
     * 根据 URL 查找收藏项
     */
    @Synchronized
    fun findByUrl(url: String): FavoriteItem? {
        val target = normalizeUrl(url)
        return getAll().firstOrNull { normalizeUrl(it.url) == target }
    }

    /**
     * 判断某个 URL 是否已收藏
     */
    @Synchronized
    fun isFavorite(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return findByUrl(url) != null
    }

    /**
     * 添加收藏（若已存在相同 URL，则更新标题与时间，避免重复）
     */
    @Synchronized
    fun add(title: String, url: String): FavoriteItem {
        val trimmedTitle = title.trim().ifBlank { "未命名影片" }
        val trimmedUrl = url.trim()
        val current = getAll().toMutableList()

        val existingIndex = current.indexOfFirst { normalizeUrl(it.url) == normalizeUrl(trimmedUrl) }
        val newItem: FavoriteItem
        if (existingIndex >= 0) {
            newItem = current[existingIndex].copy(
                title = trimmedTitle,
                createdAtMs = System.currentTimeMillis()
            )
            current[existingIndex] = newItem
        } else {
            newItem = FavoriteItem(
                title = trimmedTitle,
                url = trimmedUrl,
                createdAtMs = System.currentTimeMillis()
            )
            current.add(0, newItem)
        }

        saveList(current)
        return newItem
    }

    /**
     * 切换收藏状态：若已收藏则删除，未收藏则添加。返回切换后的收藏状态（true 为已收藏，false 为未收藏）
     */
    @Synchronized
    fun toggle(title: String, url: String): Boolean {
        val existing = findByUrl(url)
        return if (existing != null) {
            remove(existing.id)
            false
        } else {
            add(title, url)
            true
        }
    }

    /**
     * 根据 ID 删除收藏
     */
    @Synchronized
    fun remove(id: String) {
        val current = getAll().filterNot { it.id == id }
        saveList(current)
    }

    /**
     * 根据 URL 删除收藏
     */
    @Synchronized
    fun removeByUrl(url: String) {
        val target = normalizeUrl(url)
        val current = getAll().filterNot { normalizeUrl(it.url) == target }
        saveList(current)
    }

    /**
     * 更新单个收藏项
     */
    @Synchronized
    fun update(item: FavoriteItem) {
        val current = getAll().toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            current[index] = item
            saveList(current)
        }
    }

    /**
     * 导出所有收藏为格式化 JSON 字符串
     */
    @Synchronized
    fun exportJson(): String {
        val items = getAll()
        return gson.toJson(items)
    }

    /**
     * 从 JSON 字符串导入收藏记录
     *
     * @param json JSON 数据
     * @param merge 是否采用合并模式（true 为保留现有项、去重合并；false 为完全覆盖现有数据）
     * @return Pair(成功新增数量, 跳过或已存在数量)
     */
    @Synchronized
    fun importJson(json: String, merge: Boolean = true): Pair<Int, Int> {
        val parsedList: List<FavoriteItem> = runCatching {
            gson.fromJson<List<FavoriteItem>>(json, listType)
        }.getOrNull() ?: throw IllegalArgumentException("JSON 数据格式不正确或损坏")

        var addedCount = 0
        var skippedCount = 0

        if (!merge) {
            saveList(parsedList)
            return Pair(parsedList.size, 0)
        }

        val currentList = getAll().toMutableList()
        val existingUrls = currentList.map { normalizeUrl(it.url) }.toMutableSet()

        for (item in parsedList) {
            if (item.url.isBlank()) {
                skippedCount++
                continue
            }
            val norm = normalizeUrl(item.url)
            if (existingUrls.contains(norm)) {
                skippedCount++
            } else {
                currentList.add(item)
                existingUrls.add(norm)
                addedCount++
            }
        }

        saveList(currentList)
        return Pair(addedCount, skippedCount)
    }

    /**
     * 清空所有收藏
     */
    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_ITEMS).apply()
    }

    private fun saveList(items: List<FavoriteItem>) {
        prefs.edit().putString(KEY_ITEMS, gson.toJson(items)).apply()
    }
}
