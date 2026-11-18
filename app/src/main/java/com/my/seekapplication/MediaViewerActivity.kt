package com.my.seekapplication

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.my.seekapplication.crypto.VaultCipherStream
import com.my.seekapplication.data.VaultSession
import com.my.seekapplication.player.EncryptedVaultDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList
import java.util.Locale

/**
 * MediaViewerActivity: Fullscreen in-memory media viewer with Media3 ExoPlayer.
 *
 * Guarantees:
 * 1. ZERO plaintext files on disk for video, audio, and photo viewing.
 * 2. In-memory AES-256-GCM chunk streaming with seek support via EncryptedVaultDataSource.
 * 3. Android 16 Edge-to-Edge with notch / cutout protection.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaViewerActivity : AppCompatActivity() {

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
    private val viewerScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FLAG_SECURE prevents screenshots and screen recording
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

        // Android 16 Window Insets: avoid edge-to-edge overlaps & squishing
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
            saveEditedPhoto()
        }
    }

    private fun getCurrentViewHolder(): MediaPagerAdapter.MediaViewHolder? {
        val currentPos = viewPager.currentItem
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView ?: return null
        return recyclerView.findViewHolderForAdapterPosition(currentPos) as? MediaPagerAdapter.MediaViewHolder
    }

    private fun saveEditedPhoto() {
        val holder = getCurrentViewHolder() ?: return
        val editedBitmap = holder.imageView.createEditedBitmap() ?: run {
            Toast.makeText(this, "No changes to save.", Toast.LENGTH_SHORT).show()
            return
        }

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

        Toast.makeText(this, "Saving edited photo...", Toast.LENGTH_SHORT).show()

        viewerScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val baos = java.io.ByteArrayOutputStream()
                    editedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos)
                    java.io.ByteArrayInputStream(baos.toByteArray()).use { bais ->
                        repo.updateItemContent(item, bais)
                    }
                    true
                } catch (e: Exception) {
                    Log.e(tag, "Failed saving edited photo: ${e.message}", e)
                    false
                }
            }

            if (success) {
                Toast.makeText(this@MediaViewerActivity, "Photo saved to vault!", Toast.LENGTH_SHORT).show()
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
        val isVideoOrAudio = isVideoOrAudio(name)

        layoutPhotoEditing.visibility = if (isVideoOrAudio) View.GONE else View.VISIBLE
    }

    private fun isVideoOrAudio(name: String): Boolean {
        val lower = name.lowercase().removeSuffix(".enc")
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".3gp") ||
                lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a")
    }

    private fun restoreCurrentMedia() {
        val currentPos = viewPager.currentItem
        if (currentPos !in filePaths.indices) return

        val path = filePaths[currentPos]
        val file = File(path)
        val repo = VaultSession.getRepositoryOrNull()
        val originalName = fileNames?.getOrNull(currentPos) ?: file.name.removeSuffix(".enc")
        val itemId = itemIds?.getOrNull(currentPos)

        Toast.makeText(this, "Restoring file...", Toast.LENGTH_SHORT).show()

        viewerScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val restoreFolder = File(publicDir, "Vault_Restored")
                    if (!restoreFolder.exists()) restoreFolder.mkdirs()

                    val restoredFile = File(restoreFolder, originalName)
                    FileOutputStream(restoredFile).use { fos ->
                        if (repo != null && file.name.endsWith(".enc")) {
                            val item = itemId?.let { repo.getItemById(it) }
                            if (item != null) {
                                repo.exportItemToStream(item, fos)
                            } else {
                                VaultCipherStream().getDecryptedStream(file, repo.masterKey).use { input ->
                                    input.copyTo(fos)
                                }
                            }
                        } else {
                            file.copyTo(restoredFile, overwrite = true)
                        }
                    }

                    if (repo != null && itemId != null) {
                        repo.deleteItem(itemId)
                    } else {
                        file.delete()
                    }
                    true
                } catch (e: Exception) {
                    Log.e(tag, "Failed to restore: ${e.message}", e)
                    false
                }
            }

            if (success) {
                Toast.makeText(this@MediaViewerActivity, "Restored Successfully to Downloads/Vault_Restored!", Toast.LENGTH_SHORT).show()
                if (currentPos in filePaths.indices) {
                    filePaths.removeAt(currentPos)
                    fileNames?.removeAt(currentPos)
                    itemIds?.removeAt(currentPos)
                    mimeTypes?.removeAt(currentPos)
                    adapter.notifyItemRemoved(currentPos)
                    adapter.notifyItemRangeChanged(currentPos, filePaths.size)
                }

                if (filePaths.isEmpty()) {
                    finish()
                } else {
                    val newPos = currentPos.coerceAtMost(filePaths.size - 1)
                    updateCounter(newPos)
                    updateToolbarVisibility(newPos)
                }
            } else {
                Toast.makeText(this@MediaViewerActivity, "Restore Failed!", Toast.LENGTH_SHORT).show()
            }
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
            val btnPlayPause: ImageView = view.findViewById(R.id.btnVideoPlayPause)
            val btnRewind10: ImageView = view.findViewById(R.id.btnRewind10)
            val btnForward10: ImageView = view.findViewById(R.id.btnForward10)
            val videoSeekBar: SeekBar = view.findViewById(R.id.videoSeekBar)
            val tvCurrentTime: TextView = view.findViewById(R.id.tvVideoCurrentTime)
            val tvTotalTime: TextView = view.findViewById(R.id.tvVideoTotalTime)

            var exoPlayer: ExoPlayer? = null
            var currentJob: Job? = null
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
            holder.currentJob?.cancel()
            holder.releasePlayer()

            val path = paths[position]
            val file = File(path)
            val name = fileNames?.getOrNull(position) ?: file.name
            val isMedia = isVideoOrAudio(name)

            if (isMedia) {
                holder.imageView.visibility = View.GONE
                holder.playerView.visibility = View.VISIBLE
                holder.videoTouchOverlay.visibility = View.VISIBLE
                holder.videoControlsLayout.visibility = View.VISIBLE

                val player = ExoPlayer.Builder(this@MediaViewerActivity).build().apply {
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
                holder.imageView.visibility = View.VISIBLE

                holder.currentJob = viewerScope.launch {
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
            holder.currentJob?.cancel()
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
