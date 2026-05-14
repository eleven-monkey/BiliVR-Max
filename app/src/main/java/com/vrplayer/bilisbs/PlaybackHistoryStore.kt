package com.vrplayer.bilisbs

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class PlaybackHistoryItem(
    val videoUrl: String,
    val audioUrl: String? = null,
    val title: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAtMs: Long = System.currentTimeMillis()
)

class PlaybackHistoryStore(context: Context) {
    companion object {
        private const val PREF_NAME = "playback_history"
        private const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 20
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<PlaybackHistoryItem>>() {}.type

    fun getAll(): List<PlaybackHistoryItem> {
        val json = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching { gson.fromJson<List<PlaybackHistoryItem>>(json, listType) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.updatedAtMs }
    }

    fun find(videoUrl: String): PlaybackHistoryItem? {
        return getAll().firstOrNull { it.videoUrl == videoUrl }
    }

    fun upsert(item: PlaybackHistoryItem) {
        val normalizedPosition = if (item.durationMs > 0 && item.positionMs >= item.durationMs - 3_000L) {
            0L
        } else {
            item.positionMs.coerceAtLeast(0L)
        }
        val normalized = item.copy(positionMs = normalizedPosition, updatedAtMs = System.currentTimeMillis())
        val items = (listOf(normalized) + getAll().filterNot { it.videoUrl == item.videoUrl })
            .take(MAX_ITEMS)
        prefs.edit().putString(KEY_ITEMS, gson.toJson(items)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ITEMS).apply()
    }
}
