package com.vrplayer.bilisbs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FavoriteAdapter(
    private val onPlayVr: (FavoriteItem) -> Unit,
    private val onPlayNormal: (FavoriteItem) -> Unit,
    private val onMoreClick: (FavoriteItem, View) -> Unit
) : ListAdapter<FavoriteItem, FavoriteAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FavoriteItem>() {
            override fun areItemsTheSame(oldItem: FavoriteItem, newItem: FavoriteItem): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: FavoriteItem, newItem: FavoriteItem): Boolean {
                return oldItem == newItem
            }
        }

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvFavoriteTitle)
        val tvUrl: TextView = view.findViewById(R.id.tvFavoriteUrl)
        val tvTime: TextView = view.findViewById(R.id.tvFavoriteTime)
        val btnMore: ImageButton = view.findViewById(R.id.btnFavoriteMore)
        val btnPlayNormal: MaterialButton = view.findViewById(R.id.btnFavoritePlayNormal)
        val btnPlayVr: MaterialButton = view.findViewById(R.id.btnFavoritePlayVr)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_favorite, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvTitle.text = item.title.ifBlank { "未命名影片" }
        holder.tvUrl.text = item.url
        holder.tvTime.text = dateFormat.format(Date(item.createdAtMs))

        holder.itemView.setOnClickListener { onPlayVr(item) }
        holder.btnPlayVr.setOnClickListener { onPlayVr(item) }
        holder.btnPlayNormal.setOnClickListener { onPlayNormal(item) }
        holder.btnMore.setOnClickListener { v -> onMoreClick(item, v) }
    }
}
