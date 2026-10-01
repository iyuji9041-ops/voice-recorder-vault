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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList

/**
 * VaultAdapter: Recycler adapter for displaying encrypted vault thumbnails with gallery badges.
 *
 * Implements:
 * 1. Proper gallery cards with video and audio badge overlays.
 * 2. Background image downsampling and decoding via VaultImageLoader.
 * 3. Background AES-256 decryption and file restoration to Downloads/Vault_Restored.
 * 4. Clean vector drawables without emojis.
 */
class VaultAdapter(
    private val files: MutableList<File>,
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
        val file = files[position]
        val context = holder.itemView.context
        val fileName = file.name.lowercase()
        val cleanName = if (fileName.endsWith(".enc")) fileName.removeSuffix(".enc") else fileName

        val isVideo = cleanName.endsWith(".mp4") || cleanName.endsWith(".mkv") || cleanName.endsWith(".3gp")
        val isAudio = cleanName.endsWith(".mp3") || cleanName.endsWith(".wav") || cleanName.endsWith(".m4a")

        if (isVideo) {
            holder.ivVideoBadge.visibility = View.VISIBLE
            holder.ivAudioBadge.visibility = View.GONE
            VaultImageLoader.loadThumbnail(context, file, holder.imageView, targetWidth = 320, targetHeight = 320)
        } else if (isAudio) {
            holder.ivVideoBadge.visibility = View.GONE
            holder.ivAudioBadge.visibility = View.VISIBLE
            holder.imageView.setBackgroundColor(android.graphics.Color.parseColor("#1B2A1B"))
            holder.imageView.setImageDrawable(null)
        } else {
            holder.ivVideoBadge.visibility = View.GONE
            holder.ivAudioBadge.visibility = View.GONE
            VaultImageLoader.loadThumbnail(context, file, holder.imageView, targetWidth = 320, targetHeight = 320)
        }

        // Open full-screen MediaViewerActivity on click
        holder.itemView.setOnClickListener {
            val intent = Intent(context, MediaViewerActivity::class.java)
            val pathList = files.map { it.absolutePath }.toCollection(ArrayList())
            intent.putStringArrayListExtra("FILE_LIST", pathList)
            intent.putExtra("POSITION", holder.adapterPosition)
            context.startActivity(intent)
        }

        // Background restoration logic (decryption + export)
        holder.btnRestore.setOnClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos < 0 || currentPos >= files.size) return@setOnClickListener

            val targetFile = files[currentPos]
            Toast.makeText(context, "Restoring file...", Toast.LENGTH_SHORT).show()

            adapterScope.launch {
                val success = withContext(Dispatchers.IO) {
                    try {
                        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val restoreFolder = File(publicDir, "Vault_Restored")
                        if (!restoreFolder.exists()) restoreFolder.mkdirs()

                        val cleanRestoredName = if (targetFile.name.endsWith(".enc")) {
                            targetFile.name.removeSuffix(".enc")
                        } else {
                            targetFile.name
                        }

                        val restoredFile = File(restoreFolder, cleanRestoredName)

                        if (targetFile.name.endsWith(".enc")) {
                            FileOutputStream(restoredFile).use { fos ->
                                VaultSecurityManager.decryptFileToStream(targetFile, fos)
                            }
                        } else {
                            targetFile.copyTo(restoredFile, overwrite = true)
                        }

                        targetFile.delete()
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }

                if (success) {
                    Toast.makeText(context, "Restored to Downloads/Vault_Restored", Toast.LENGTH_LONG).show()
                    if (currentPos in files.indices) {
                        files.removeAt(currentPos)
                        notifyItemRemoved(currentPos)
                        notifyItemRangeChanged(currentPos, files.size)
                    }
                    if (files.isEmpty()) {
                        onFilesChanged()
                    }
                } else {
                    Toast.makeText(context, "Restore failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun getItemCount(): Int = files.size
}
