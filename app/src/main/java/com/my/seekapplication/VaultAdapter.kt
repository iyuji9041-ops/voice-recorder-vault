package com.my.seekapplication

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.my.seekapplication.data.MediaTypeHelper
import com.my.seekapplication.data.VaultItem
import com.my.seekapplication.data.VaultRepository
import java.io.File

/**
 * VaultAdapter: Modern ListAdapter with DiffUtil for rendering encrypted items in a responsive grid.
 *
 * Guarantees:
 * 1. Background DiffUtil calculations to eliminate wasteful full-adapter reconstructions.
 * 2. Multi-selection mode with visual check indicators and highlight overlays.
 * 3. Media type classification driven by MediaTypeHelper (stored MIME type).
 * 4. Automatic cancellation of inflight image loading jobs upon view recycling.
 * 5. Lifecycle-delegated restore callbacks.
 */
class VaultAdapter(
    private val repository: VaultRepository,
    private val onItemClick: (item: VaultItem, position: Int) -> Unit,
    private val onItemLongClick: (item: VaultItem, position: Int) -> Boolean,
    private val onSelectionChanged: (count: Int) -> Unit,
    private val onRestoreItemClick: (item: VaultItem) -> Unit
) : ListAdapter<VaultItem, VaultAdapter.VaultViewHolder>(VaultItemDiffCallback()) {

    var isSelectionMode: Boolean = false
        private set

    private val selectedIds = mutableSetOf<String>()

    class VaultItemDiffCallback : DiffUtil.ItemCallback<VaultItem>() {
        override fun areItemsTheSame(oldItem: VaultItem, newItem: VaultItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: VaultItem, newItem: VaultItem): Boolean {
            return oldItem == newItem
        }
    }

    class VaultViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.savedImage)
        val vSelectionOverlay: View = view.findViewById(R.id.vSelectionOverlay)
        val btnRestore: ImageView = view.findViewById(R.id.btnRestore)
        val ivVideoBadge: ImageView = view.findViewById(R.id.ivVideoBadge)
        val ivAudioBadge: ImageView = view.findViewById(R.id.ivAudioBadge)
        val tvItemTitle: TextView = view.findViewById(R.id.tvItemTitle)
        val ivSelectionCheck: ImageView = view.findViewById(R.id.ivSelectionCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VaultViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_vault_image, parent, false)
        return VaultViewHolder(view)
    }

    override fun onBindViewHolder(holder: VaultViewHolder, position: Int) {
        val item = getItem(position)
        val file = repository.getFileForItem(item)
        val context = holder.itemView.context

        val isVideo = MediaTypeHelper.isVideo(item.mimeType, item.originalName)
        val isAudio = MediaTypeHelper.isAudio(item.mimeType, item.originalName)
        val isSelected = selectedIds.contains(item.id)

        // Title label for Audio and Video
        if (isAudio || isVideo) {
            holder.tvItemTitle.visibility = View.VISIBLE
            holder.tvItemTitle.text = item.originalName
        } else {
            holder.tvItemTitle.visibility = View.GONE
        }

        // Media Type badges and thumbnail rendering
        when {
            isVideo -> {
                holder.ivVideoBadge.visibility = View.VISIBLE
                holder.ivAudioBadge.visibility = View.GONE
                VaultImageLoader.loadThumbnail(context, file, holder.imageView, 320, 320, item.mimeType)
            }
            isAudio -> {
                holder.ivVideoBadge.visibility = View.GONE
                holder.ivAudioBadge.visibility = View.VISIBLE
                holder.imageView.setBackgroundColor(Color.parseColor("#1B2A1B"))
                holder.imageView.setImageDrawable(null)
            }
            else -> {
                holder.ivVideoBadge.visibility = View.GONE
                holder.ivAudioBadge.visibility = View.GONE
                VaultImageLoader.loadThumbnail(context, file, holder.imageView, 320, 320, item.mimeType)
            }
        }

        // Selection mode UI state
        if (isSelectionMode) {
            holder.btnRestore.visibility = View.GONE
            holder.ivSelectionCheck.visibility = View.VISIBLE
            if (isSelected) {
                holder.vSelectionOverlay.visibility = View.VISIBLE
                holder.ivSelectionCheck.setBackgroundResource(R.drawable.bg_selection_circle_checked)
                holder.ivSelectionCheck.setImageResource(R.drawable.ic_check_vector)
            } else {
                holder.vSelectionOverlay.visibility = View.GONE
                holder.ivSelectionCheck.setBackgroundResource(R.drawable.bg_glass_fab_round)
                holder.ivSelectionCheck.setImageDrawable(null)
            }
        } else {
            holder.btnRestore.visibility = View.VISIBLE
            holder.ivSelectionCheck.visibility = View.GONE
            holder.vSelectionOverlay.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener
            val currentItem = getItem(currentPos)

            if (isSelectionMode) {
                toggleSelection(currentItem.id)
            } else {
                onItemClick(currentItem, currentPos)
            }
        }

        holder.itemView.setOnLongClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
            val currentItem = getItem(currentPos)

            if (!isSelectionMode) {
                setSelectionMode(true)
                toggleSelection(currentItem.id)
                onItemLongClick(currentItem, currentPos)
                true
            } else {
                toggleSelection(currentItem.id)
                true
            }
        }

        holder.btnRestore.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                onRestoreItemClick(getItem(currentPos))
            }
        }
    }

    override fun onViewRecycled(holder: VaultViewHolder) {
        super.onViewRecycled(holder)
        VaultImageLoader.cancelLoad(holder.imageView)
    }

    fun setSelectionMode(enabled: Boolean) {
        if (isSelectionMode != enabled) {
            isSelectionMode = enabled
            if (!enabled) {
                selectedIds.clear()
            }
            notifyItemRangeChanged(0, itemCount)
            onSelectionChanged(selectedIds.size)
        }
    }

    fun toggleSelection(itemId: String) {
        if (selectedIds.contains(itemId)) {
            selectedIds.remove(itemId)
        } else {
            selectedIds.add(itemId)
        }
        val pos = currentList.indexOfFirst { it.id == itemId }
        if (pos != -1) {
            notifyItemChanged(pos)
        }
        onSelectionChanged(selectedIds.size)
        if (selectedIds.isEmpty() && isSelectionMode) {
            setSelectionMode(false)
        }
    }

    fun selectAll() {
        if (!isSelectionMode) {
            isSelectionMode = true
        }
        selectedIds.clear()
        currentList.forEach { selectedIds.add(it.id) }
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged(selectedIds.size)
    }

    fun clearSelection() {
        setSelectionMode(false)
    }

    fun getSelectedItems(): List<VaultItem> {
        return currentList.filter { selectedIds.contains(it.id) }
    }
}
