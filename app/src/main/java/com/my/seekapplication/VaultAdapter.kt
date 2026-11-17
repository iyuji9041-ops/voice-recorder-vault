package com.my.seekapplication

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.my.seekapplication.data.VaultItem
import com.my.seekapplication.data.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList

/**
 * VaultAdapter: Recycler adapter for displaying encrypted vault items with gallery badges.
 */
class VaultAdapter(
    private val items: MutableList<VaultItem>,
    private val repository: VaultRepository,
    private val onFilesChanged: () -> Unit
) : RecyclerView.Adapter<VaultAdapter.VaultViewHolder>() {

    private val adapterScope = CoroutineScope(Dispatchers.Main + Job())

    class VaultViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.savedImage)
        val btnRestore: ImageView = view.findViewById(R.id.btnRestore)
        val ivVideoBadge: ImageView = view.findViewById(R.id.ivVideoBadge)
        val ivAudioBadge: ImageView = view.findViewById(R.id.ivAudioBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VaultViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_vault_image, parent, false)
        return VaultViewHolder(view)
    }

    override fun onBindViewHolder(holder: VaultViewHolder, position: Int) {
        val item = items[position]
        val file = repository.getFileForItem(item)
        val context = holder.itemView.context
        val nameLower = item.originalName.lowercase()

        val isVideo = item.mimeType.startsWith("video/") ||
                nameLower.endsWith(".mp4") || nameLower.endsWith(".mkv") || nameLower.endsWith(".3gp")
        val isAudio = item.mimeType.startsWith("audio/") ||
                nameLower.endsWith(".mp3") || nameLower.endsWith(".wav") || nameLower.endsWith(".m4a")

        if (isVideo) {
            holder.ivVideoBadge.visibility = View.VISIBLE
            holder.ivAudioBadge.visibility = View.GONE
            VaultImageLoader.loadThumbnail(context, file, holder.imageView, 320, 320, item.mimeType)
        } else if (isAudio) {
            holder.ivVideoBadge.visibility = View.GONE
            holder.ivAudioBadge.visibility = View.VISIBLE
            holder.imageView.setBackgroundColor(android.graphics.Color.parseColor("#1B2A1B"))
            holder.imageView.setImageDrawable(null)
        } else {
            holder.ivVideoBadge.visibility = View.GONE
            holder.ivAudioBadge.visibility = View.GONE
            VaultImageLoader.loadThumbnail(context, file, holder.imageView, 320, 320, item.mimeType)
        }

        // Open full-screen MediaViewerActivity on click
        holder.itemView.setOnClickListener {
            val intent = Intent(context, MediaViewerActivity::class.java)
            val pathList = items.map { repository.getFileForItem(it).absolutePath }.toCollection(ArrayList())
            val nameList = items.map { it.originalName }.toCollection(ArrayList())
            val idList = items.map { it.id }.toCollection(ArrayList())
            val mimeList = items.map { it.mimeType }.toCollection(ArrayList())

            intent.putStringArrayListExtra("FILE_LIST", pathList)
            intent.putStringArrayListExtra("NAME_LIST", nameList)
            intent.putStringArrayListExtra("ID_LIST", idList)
            intent.putStringArrayListExtra("MIME_LIST", mimeList)
            intent.putExtra("POSITION", holder.adapterPosition)
            context.startActivity(intent)
        }

        // Background restoration logic (decryption + export)
        holder.btnRestore.setOnClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos < 0 || currentPos >= items.size) return@setOnClickListener

            val targetItem = items[currentPos]
            Toast.makeText(context, "Restoring file...", Toast.LENGTH_SHORT).show()

            adapterScope.launch {
                val success = withContext(Dispatchers.IO) {
                    try {
                        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val restoreFolder = File(publicDir, "Vault_Restored")
                        if (!restoreFolder.exists()) restoreFolder.mkdirs()

                        val restoredFile = File(restoreFolder, targetItem.originalName)
                        FileOutputStream(restoredFile).use { fos ->
                            repository.exportItemToStream(targetItem, fos)
                        }

                        repository.deleteItem(targetItem.id)
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }

                if (success) {
                    Toast.makeText(context, "Restored to Downloads/Vault_Restored", Toast.LENGTH_LONG).show()
                    if (currentPos in items.indices) {
                        items.removeAt(currentPos)
                        notifyItemRemoved(currentPos)
                        notifyItemRangeChanged(currentPos, items.size)
                    }
                    if (items.isEmpty()) {
                        onFilesChanged()
                    }
                } else {
                    Toast.makeText(context, "Restore failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
