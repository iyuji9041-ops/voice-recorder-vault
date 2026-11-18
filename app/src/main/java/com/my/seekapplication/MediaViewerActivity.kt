package com.my.seekapplication

import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.my.seekapplication.crypto.VaultCipherStream
import com.my.seekapplication.data.MediaTypeHelper
import com.my.seekapplication.data.VaultItem
import com.my.seekapplication.data.VaultRestoreManager
import com.my.seekapplication.data.VaultSession
import com.my.seekapplication.player.EncryptedVaultDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.ArrayList
import java.util.Locale

/**
 * MediaViewerActivity: Fullscreen in-memory media viewer with Media3 ExoPlayer.
 *
 * Implements:
 * 1. Safe photo editing: atomic write with choice "Save Copy" or "Replace Original".
 * 2. Format preservation (PNG, WEBP, JPEG) and EXIF orientation handling.
 * 3. Media type classification via stored MIME type (MediaTypeHelper).
 * 4. Audio focus management, rewind/forward 10s, and full audio playback controls.
 * 5. MediaStore-based restoration with rollback verification.
 * 6. Direct vault deletion with confirmation dialog.
 * 7. Automatic job cleanup via lifecycleScope.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaViewerActivity : BaseVaultActivity() {

    private val tag = "MediaViewerActivity"

    private lateinit var viewPager: ViewPager2
    private lateinit var tvMediaCounter: TextView
    private lateinit var viewerTopBar: RelativeLayout
    private lateinit var layoutPhotoEditing: LinearLayout

    private lateinit var filePaths: ArrayList<String>
    private var fileNames: ArrayList<String>? = null
    private var itemIds: ArrayList<String>? = null
    private var mimeTypes: ArrayList<String>? = null

    private lateinit var adapter: MediaPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        supportActionBar?.hide()
        enableEdgeToEdge()

        setContentView(R.layout.activity_media_viewer)

        val rootMediaViewer = findViewById<RelativeLayout>(R.id.rootMediaViewer)
        viewerTopBar = findViewById(R.id.viewerTopBar)
        layoutPhotoEditing = findViewById(R.id.layoutPhotoEditing)
        viewPager = findViewById(R.id.viewPager)
        tvMediaCounter = findViewById(R.id.tvMediaCounter)
        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val btnRestoreViewer = findViewById<ImageView>(R.id.btnRestoreViewer)
        val btnDeleteViewer = findViewById<ImageView>(R.id.btnDeleteViewer)

        ViewCompat.setOnApplyWindowInsetsListener(rootMediaViewer) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            rootMediaViewer.setPadding(systemBars.left, 0, systemBars.right, 0)
            viewerTopBar.layoutParams.height = (56 * density).toInt() + systemBars.top
            viewerTopBar.setPadding(viewerTopBar.paddingLeft, systemBars.top, viewerTopBar.paddingRight, 0)

            layoutPhotoEditing.layoutParams.height = (64 * density).toInt() + systemBars.bottom
            layoutPhotoEditing.setPadding(layoutPhotoEditing.paddingLeft, 0, layoutPhotoEditing.paddingRight, systemBars.bottom)
            insets
        }

        filePaths = intent.getStringArrayListExtra("FILE_LIST") ?: ArrayList()
        fileNames = intent.getStringArrayListExtra("NAME_LIST")
        itemIds = intent.getStringArrayListExtra("ID_LIST")
        mimeTypes = intent.getStringArrayListExtra("MIME_LIST")
        val startPosition = intent.getIntExtra("POSITION", 0)

        if (filePaths.isEmpty()) {
            finish()
            return
        }

        adapter = MediaPagerAdapter(filePaths)
        viewPager.adapter = adapter
        viewPager.setCurrentItem(startPosition, false)
        updateCounter(startPosition)
        updateToolbarVisibility(startPosition)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateCounter(position)
                updateToolbarVisibility(position)
            }
        })

        btnBack.setOnClickListener { finish() }
        btnRestoreViewer.setOnClickListener { restoreCurrentMedia() }
        btnDeleteViewer.setOnClickListener { deleteCurrentMedia() }

        // Setup photo editing buttons
        findViewById<View>(R.id.btnRotate)?.setOnClickListener {
            val holder = getCurrentViewHolder()
            holder?.imageView?.rotate90Clockwise()
        }

        findViewById<View>(R.id.btnFlip)?.setOnClickListener {
            val holder = getCurrentViewHolder()
            holder?.imageView?.toggleFlipHorizontal()
        }

        findViewById<View>(R.id.btnFilter)?.setOnClickListener {
            val holder = getCurrentViewHolder()
            val filterName = holder?.imageView?.cycleFilterMode() ?: "Normal"
            Toast.makeText(this, "Filter: $filterName", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnSaveEdit)?.setOnClickListener {
            promptSaveEditedPhoto()
        }
    }

    private fun getCurrentViewHolder(): MediaPagerAdapter.MediaViewHolder? {
        val currentPos = viewPager.currentItem
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView ?: return null
        return recyclerView.findViewHolderForAdapterPosition(currentPos) as? MediaPagerAdapter.MediaViewHolder
    }

    private fun promptSaveEditedPhoto() {
        val holder = getCurrentViewHolder() ?: return
        val editedBitmap = holder.imageView.createEditedBitmap() ?: run {
            Toast.makeText(this, "No changes to save.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Save Edited Photo")
            .setMessage("Do you want to replace the original photo in the vault or save it as a new copy?")
            .setPositiveButton("Save Copy") { _, _ ->
                saveEditedPhotoInternal(editedBitmap, replaceOriginal = false)
            }
            .setNeutralButton("Replace Original") { _, _ ->
                saveEditedPhotoInternal(editedBitmap, replaceOriginal = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveEditedPhotoInternal(editedBitmap: Bitmap, replaceOriginal: Boolean) {
        val currentPos = viewPager.currentItem
        if (currentPos !in filePaths.indices) return

        val repo = VaultSession.getRepositoryOrNull() ?: run {
            Toast.makeText(this, "Vault session locked.", Toast.LENGTH_SHORT).show()
            return
        }

        val filePath = filePaths[currentPos]
        val file = File(filePath)
        val itemId = itemIds?.getOrNull(currentPos) ?: file.name.removeSuffix(".enc")
        val item = repo.getItemById(itemId) ?: run {
            Toast.makeText(this, "Item not found in vault.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Saving photo...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                var tempFile: File? = null
                try {
                    // Determine compression format matching original mime type
                    val mimeLower = item.mimeType.lowercase()
                    val (format, quality) = when {
                        mimeLower.contains("png") -> Bitmap.CompressFormat.PNG to 100
                        mimeLower.contains("webp") -> {
                            val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                Bitmap.CompressFormat.WEBP_LOSSY
                            } else {
                                @Suppress("DEPRECATION")
                                Bitmap.CompressFormat.WEBP
                            }
                            webpFormat to 92
                        }
                        else -> Bitmap.CompressFormat.JPEG to 92
                    }

                    tempFile = File.createTempFile("edit_", ".tmp", cacheDir)
                    FileOutputStream(tempFile).use { fos ->
                        editedBitmap.compress(format, quality, fos)
                        fos.flush()
                    }

                    if (replaceOriginal) {
                        FileInputStream(tempFile).use { fis ->
                            repo.updateItemContent(item, fis)
                        }
                    } else {
                        val base = item.originalName.substringBeforeLast('.')
                        val ext = item.originalName.substringAfterLast('.', "jpg")
                        val newName = "${base}_edited.$ext"
                        val newItem = FileInputStream(tempFile).use { fis ->
                            repo.importStream(fis, newName, "Photos", item.mimeType)
                        }

                        withContext(Dispatchers.Main) {
                            filePaths.add(currentPos + 1, repo.getFileForItem(newItem).absolutePath)
                            fileNames?.add(currentPos + 1, newItem.originalName)
                            itemIds?.add(currentPos + 1, newItem.id)
                            mimeTypes?.add(currentPos + 1, newItem.mimeType)
                            adapter.notifyItemInserted(currentPos + 1)
                            viewPager.setCurrentItem(currentPos + 1, true)
                        }
                    }
                    true
                } catch (e: Exception) {
                    Log.e(tag, "Failed saving edited photo: ${e.message}", e)
                    false
                } finally {
                    tempFile?.delete()
                }
            }

            if (success) {
                Toast.makeText(this@MediaViewerActivity, if (replaceOriginal) "Original replaced in vault!" else "Saved as copy in vault!", Toast.LENGTH_SHORT).show()
                if (replaceOriginal) {
                    adapter.notifyItemChanged(currentPos)
                }
            } else {
                Toast.makeText(this@MediaViewerActivity, "Failed to save photo.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateCounter(position: Int) {
        tvMediaCounter.text = "${position + 1} / ${filePaths.size}"
    }

    private fun updateToolbarVisibility(position: Int) {
        if (position !in filePaths.indices) return
        val path = filePaths[position]
        val name = fileNames?.getOrNull(position) ?: File(path).name
        val mime = mimeTypes?.getOrNull(position)

        val isImage = MediaTypeHelper.isImage(mime, name)
        layoutPhotoEditing.visibility = if (isImage) View.VISIBLE else View.GONE
    }

    private fun restoreCurrentMedia() {
        val currentPos = viewPager.currentItem
        if (currentPos !in filePaths.indices) return

        val repo = VaultSession.getRepositoryOrNull() ?: run {
            Toast.makeText(this, "Vault session expired.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val filePath = filePaths[currentPos]
        val file = File(filePath)
        val itemId = itemIds?.getOrNull(currentPos) ?: file.name.removeSuffix(".enc")
        val item = repo.getItemById(itemId) ?: run {
            Toast.makeText(this, "Item not found in vault.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Restoring ${item.originalName}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                VaultRestoreManager.restoreItem(this@MediaViewerActivity, repo, item)
            }

            if (result.success) {
                Toast.makeText(this@MediaViewerActivity, "Restored ${item.originalName} to public gallery!", Toast.LENGTH_LONG).show()
                removeItemAt(currentPos)
            } else {
                Toast.makeText(this@MediaViewerActivity, "Restore failed: ${result.error}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteCurrentMedia() {
        val currentPos = viewPager.currentItem
        if (currentPos !in filePaths.indices) return

        val repo = VaultSession.getRepositoryOrNull() ?: run {
            Toast.makeText(this, "Vault session expired.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val filePath = filePaths[currentPos]
        val file = File(filePath)
        val itemId = itemIds?.getOrNull(currentPos) ?: file.name.removeSuffix(".enc")
        val name = fileNames?.getOrNull(currentPos) ?: file.name

        AlertDialog.Builder(this)
            .setTitle("Delete Permanently?")
            .setMessage("Are you sure you want to permanently delete \"$name\" from the vault? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        repo.deleteItem(itemId)
                    }
                    if (deleted) {
                        Toast.makeText(this@MediaViewerActivity, "File deleted.", Toast.LENGTH_SHORT).show()
                        removeItemAt(currentPos)
                    } else {
                        Toast.makeText(this@MediaViewerActivity, "Failed to delete file.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeItemAt(pos: Int) {
        if (pos in filePaths.indices) {
            filePaths.removeAt(pos)
            fileNames?.removeAt(pos)
            itemIds?.removeAt(pos)
            mimeTypes?.removeAt(pos)
            adapter.notifyItemRemoved(pos)
            adapter.notifyItemRangeChanged(pos, filePaths.size)
        }

        if (filePaths.isEmpty()) {
            finish()
        } else {
            val newPos = pos.coerceAtMost(filePaths.size - 1)
            viewPager.setCurrentItem(newPos, false)
            updateCounter(newPos)
            updateToolbarVisibility(newPos)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        VaultSession.wipeAppCache(this)
    }

    inner class MediaPagerAdapter(private val paths: List<String>) : RecyclerView.Adapter<MediaPagerAdapter.MediaViewHolder>() {

        inner class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ZoomableImageView = view.findViewById(R.id.viewerImage)
            val playerView: PlayerView = view.findViewById(R.id.viewerPlayerView)
            val videoTouchOverlay: View = view.findViewById(R.id.videoTouchOverlay)
            val videoControlsLayout: RelativeLayout = view.findViewById(R.id.videoControlsLayout)
            val layoutAudioInfo: LinearLayout = view.findViewById(R.id.layoutAudioInfo)
            val tvAudioViewerTitle: TextView = view.findViewById(R.id.tvAudioViewerTitle)
            val btnPlayPause: ImageView = view.findViewById(R.id.btnVideoPlayPause)
            val btnRewind10: ImageView = view.findViewById(R.id.btnRewind10)
            val btnForward10: ImageView = view.findViewById(R.id.btnForward10)
            val videoSeekBar: SeekBar = view.findViewById(R.id.videoSeekBar)
            val tvCurrentTime: TextView = view.findViewById(R.id.tvVideoCurrentTime)
            val tvTotalTime: TextView = view.findViewById(R.id.tvVideoTotalTime)

            var exoPlayer: ExoPlayer? = null
            val handler = Handler(Looper.getMainLooper())

            val progressUpdateRunnable: Runnable = object : Runnable {
                override fun run() {
                    exoPlayer?.let { player ->
                        if (player.isPlaying) {
                            val current = player.currentPosition.toInt()
                            val total = player.duration.coerceAtLeast(0).toInt()
                            videoSeekBar.max = total
                            videoSeekBar.progress = current
                            tvCurrentTime.text = formatTime(current)
                            tvTotalTime.text = formatTime(total)
                        }
                    }
                    handler.postDelayed(this, 500)
                }
            }

            fun releasePlayer() {
                handler.removeCallbacks(progressUpdateRunnable)
                exoPlayer?.release()
                exoPlayer = null
                playerView.player = null
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_viewer, parent, false)
            return MediaViewHolder(view)
        }

        override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
            holder.releasePlayer()

            val path = paths[position]
            val file = File(path)
            val name = fileNames?.getOrNull(position) ?: file.name
            val mime = mimeTypes?.getOrNull(position)

            val isVideo = MediaTypeHelper.isVideo(mime, name)
            val isAudio = MediaTypeHelper.isAudio(mime, name)

            if (isVideo || isAudio) {
                holder.imageView.visibility = View.GONE
                holder.playerView.visibility = View.VISIBLE
                holder.videoTouchOverlay.visibility = View.VISIBLE
                holder.videoControlsLayout.visibility = View.VISIBLE

                if (isAudio) {
                    holder.layoutAudioInfo.visibility = View.VISIBLE
                    holder.tvAudioViewerTitle.text = name
                } else {
                    holder.layoutAudioInfo.visibility = View.GONE
                }

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(if (isAudio) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()

                val player = ExoPlayer.Builder(this@MediaViewerActivity)
                    .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
                    .build().apply {
                        repeatMode = Player.REPEAT_MODE_ALL
                    }
                holder.exoPlayer = player
                holder.playerView.player = player

                val repo = VaultSession.getRepositoryOrNull()
                val mediaSource = if (repo != null && file.name.endsWith(".enc")) {
                    val dsFactory = EncryptedVaultDataSource.Factory(VaultCipherStream(), repo.masterKey)
                    ProgressiveMediaSource.Factory(dsFactory)
                        .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
                } else {
                    ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this@MediaViewerActivity))
                        .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
                }

                player.setMediaSource(mediaSource)
                player.prepare()
                player.playWhenReady = true

                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            val dur = player.duration.coerceAtLeast(0).toInt()
                            holder.videoSeekBar.max = dur
                            holder.tvTotalTime.text = formatTime(dur)
                            holder.handler.post(holder.progressUpdateRunnable)
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            holder.btnPlayPause.setImageResource(R.drawable.ic_pause_vector)
                        } else {
                            holder.btnPlayPause.setImageResource(R.drawable.ic_play_vector)
                        }
                    }
                })

                holder.btnPlayPause.setOnClickListener {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.play()
                    }
                }

                holder.btnRewind10.setOnClickListener {
                    val target = (player.currentPosition - 10000).coerceAtLeast(0)
                    player.seekTo(target)
                    holder.videoSeekBar.progress = target.toInt()
                }

                holder.btnForward10.setOnClickListener {
                    val target = (player.currentPosition + 10000).coerceAtMost(player.duration.coerceAtLeast(0))
                    player.seekTo(target)
                    holder.videoSeekBar.progress = target.toInt()
                }

                holder.videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            player.seekTo(progress.toLong())
                            holder.tvCurrentTime.text = formatTime(progress)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })

                holder.videoTouchOverlay.setOnClickListener {
                    val isShown = holder.videoControlsLayout.visibility == View.VISIBLE
                    holder.videoControlsLayout.visibility = if (isShown) View.GONE else View.VISIBLE
                }

            } else {
                holder.playerView.visibility = View.GONE
                holder.videoTouchOverlay.visibility = View.GONE
                holder.videoControlsLayout.visibility = View.GONE
                holder.layoutAudioInfo.visibility = View.GONE
                holder.imageView.visibility = View.VISIBLE

                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        val dm = resources.displayMetrics
                        VaultImageLoader.decodeImageBitmap(file, dm.widthPixels, dm.heightPixels)
                    }
                    if (bitmap != null) {
                        holder.imageView.setImageBitmap(bitmap)
                    }
                }
            }
        }

        override fun onViewRecycled(holder: MediaViewHolder) {
            super.onViewRecycled(holder)
            holder.releasePlayer()
        }

        override fun getItemCount(): Int = paths.size

        private fun formatTime(millis: Int): String {
            val totalSeconds = (millis / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
}
