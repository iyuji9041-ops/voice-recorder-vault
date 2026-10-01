package com.my.seekapplication

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
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList
import java.util.Locale

/**
 * MediaViewerActivity: Full-screen swipeable viewer for encrypted images and video playback.
 * Features:
 * 1. Pinch-to-zoom, panning, and double-tap zoom via ZoomableImageView.
 * 2. In-app photo editing: Rotate 90°, Flip horizontally, Filters, and direct Save to encrypted vault.
 * 3. Custom video player with Play/Pause, 10s rewind/forward, and seekbar.
 * 4. Android 16 Edge-to-Edge window insets compliance without UI overlap.
 */
class MediaViewerActivity : AppCompatActivity() {

    private val tag = "MediaViewerActivity"

    private lateinit var viewPager: ViewPager2
    private lateinit var tvMediaCounter: TextView
    private lateinit var layoutPhotoEditing: LinearLayout
    private lateinit var viewerTopBar: RelativeLayout

    private var filePaths = ArrayList<String>()
    private lateinit var adapter: MediaPagerAdapter
    private val viewerScope = CoroutineScope(Dispatchers.Main + Job())

    private val tempPlaybackFiles = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FLAG_SECURE prevents screenshots and screen recording
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        supportActionBar?.hide()
        window.navigationBarColor = Color.parseColor("#000000")
        window.statusBarColor = Color.parseColor("#000000")

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
            viewerTopBar.layoutParams.height = (56 * density).toInt() + systemBars.top
            viewerTopBar.setPadding(viewerTopBar.paddingLeft, systemBars.top, viewerTopBar.paddingRight, 0)
            
            layoutPhotoEditing.layoutParams.height = (64 * density).toInt() + systemBars.bottom
            layoutPhotoEditing.setPadding(layoutPhotoEditing.paddingLeft, 0, layoutPhotoEditing.paddingRight, systemBars.bottom)
            insets
        }

        filePaths = intent.getStringArrayListExtra("FILE_LIST") ?: ArrayList()
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

        // Export/Restore to public storage
        btnRestoreViewer.setOnClickListener {
            restoreCurrentFile()
        }

        // Photo editing buttons
        findViewById<View>(R.id.btnRotate).setOnClickListener {
            getCurrentZoomableImageView()?.rotate90Clockwise()
        }

        findViewById<View>(R.id.btnFlip).setOnClickListener {
            getCurrentZoomableImageView()?.toggleFlipHorizontal()
        }

        findViewById<View>(R.id.btnFilter).setOnClickListener {
            val filterName = getCurrentZoomableImageView()?.cycleFilterMode() ?: "Normal"
            Toast.makeText(this, filterName, Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnSaveEdit).setOnClickListener {
            saveEditedPhoto()
        }
    }

    private fun getCurrentZoomableImageView(): ZoomableImageView? {
        val currentView = (viewPager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(viewPager.currentItem)?.itemView
        return currentView?.findViewById(R.id.viewerImage)
    }

    private fun updateCounter(position: Int) {
        tvMediaCounter.text = "${position + 1} / ${filePaths.size}"
    }

    private fun updateToolbarVisibility(position: Int) {
        if (position in filePaths.indices) {
            val file = File(filePaths[position])
            val cleanName = if (file.name.endsWith(".enc")) file.name.removeSuffix(".enc") else file.name
            val isVideo = cleanName.endsWith(".mp4", true) || cleanName.endsWith(".mkv", true) || cleanName.endsWith(".3gp", true)
            val isAudio = cleanName.endsWith(".mp3", true) || cleanName.endsWith(".wav", true) || cleanName.endsWith(".m4a", true)

            layoutPhotoEditing.visibility = if (isVideo || isAudio) View.GONE else View.VISIBLE
        }
    }

    private fun saveEditedPhoto() {
        val zoomable = getCurrentZoomableImageView() ?: return
        val editedBitmap = zoomable.createEditedBitmap() ?: run {
            Toast.makeText(this, "No changes to save.", Toast.LENGTH_SHORT).show()
            return
        }

        val currentPos = viewPager.currentItem
        if (currentPos !in filePaths.indices) return

        val targetFile = File(filePaths[currentPos])
        Toast.makeText(this, "Saving edited photo...", Toast.LENGTH_SHORT).show()

        viewerScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val baos = ByteArrayOutputStream()
                    editedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos)
                    val bytes = baos.toByteArray()

                    if (targetFile.name.endsWith(".enc")) {
                        ByteArrayInputStream(bytes).use { bais ->
                            VaultSecurityManager.encryptStreamToFile(bais, targetFile)
                        }
                    } else {
                        targetFile.writeBytes(bytes)
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

    private fun restoreCurrentFile() {
        val currentPos = viewPager.currentItem
        if (currentPos !in filePaths.indices) return

        val path = filePaths[currentPos]
        val file = File(path)

        Toast.makeText(this, "Restoring file...", Toast.LENGTH_SHORT).show()

        viewerScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val restoreFolder = File(publicDir, "Vault_Restored")
                    if (!restoreFolder.exists()) restoreFolder.mkdirs()

                    val cleanName = if (file.name.endsWith(".enc")) file.name.removeSuffix(".enc") else file.name
                    val restoredFile = File(restoreFolder, cleanName)

                    if (file.name.endsWith(".enc")) {
                        FileOutputStream(restoredFile).use { fos ->
                            VaultSecurityManager.decryptFileToStream(file, fos)
                        }
                    } else {
                        file.copyTo(restoredFile, overwrite = true)
                    }

                    file.delete()
                    true
                } catch (e: Exception) {
                    Log.e(tag, "Failed to restore: ${e.message}", e)
                    false
                }
            }

            if (success) {
                Toast.makeText(this@MediaViewerActivity, "Restored Successfully!", Toast.LENGTH_SHORT).show()
                if (currentPos in filePaths.indices) {
                    filePaths.removeAt(currentPos)
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
        for (f in tempPlaybackFiles) {
            try { f.delete() } catch (ignored: Exception) {}
        }
        tempPlaybackFiles.clear()
    }

    inner class MediaPagerAdapter(private val paths: List<String>) : RecyclerView.Adapter<MediaPagerAdapter.MediaViewHolder>() {

        inner class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ZoomableImageView = view.findViewById(R.id.viewerImage)
            val videoView: VideoView = view.findViewById(R.id.viewerVideo)
            val videoTouchOverlay: View = view.findViewById(R.id.videoTouchOverlay)
            val videoControlsLayout: RelativeLayout = view.findViewById(R.id.videoControlsLayout)
            val btnPlayPause: ImageView = view.findViewById(R.id.btnVideoPlayPause)
            val btnRewind10: ImageView = view.findViewById(R.id.btnRewind10)
            val btnForward10: ImageView = view.findViewById(R.id.btnForward10)
            val videoSeekBar: SeekBar = view.findViewById(R.id.videoSeekBar)
            val tvCurrentTime: TextView = view.findViewById(R.id.tvVideoCurrentTime)
            val tvTotalTime: TextView = view.findViewById(R.id.tvVideoTotalTime)

            var currentJob: Job? = null
            var tempVideoFile: File? = null
            val handler = Handler(Looper.getMainLooper())

            val progressUpdateRunnable: Runnable = object : Runnable {
                override fun run() {
                    if (videoView.isPlaying) {
                        val current = videoView.currentPosition
                        val total = videoView.duration
                        videoSeekBar.max = total
                        videoSeekBar.progress = current
                        tvCurrentTime.text = formatTime(current)
                        tvTotalTime.text = formatTime(total)
                    }
                    handler.postDelayed(this, 500)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_viewer, parent, false)
            return MediaViewHolder(view)
        }

        override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
            holder.currentJob?.cancel()
            holder.handler.removeCallbacks(holder.progressUpdateRunnable)

            val path = paths[position]
            val file = File(path)
            val fileName = file.name.lowercase()
            val cleanName = if (fileName.endsWith(".enc")) fileName.removeSuffix(".enc") else fileName

            val isVideo = cleanName.endsWith(".mp4") || cleanName.endsWith(".mkv") || cleanName.endsWith(".3gp")
            val isAudio = cleanName.endsWith(".mp3") || cleanName.endsWith(".wav") || cleanName.endsWith(".m4a")

            if (isVideo || isAudio) {
                holder.imageView.visibility = View.GONE
                holder.videoView.visibility = View.VISIBLE
                holder.videoTouchOverlay.visibility = View.VISIBLE
                holder.videoControlsLayout.visibility = View.VISIBLE

                holder.currentJob = viewerScope.launch {
                    val playbackUri = withContext(Dispatchers.IO) {
                        try {
                            if (file.name.endsWith(".enc")) {
                                val tempFile = File.createTempFile("playback_", ".tmp", cacheDir)
                                tempPlaybackFiles.add(tempFile)
                                holder.tempVideoFile = tempFile
                                FileOutputStream(tempFile).use { fos ->
                                    VaultSecurityManager.decryptFileToStream(file, fos)
                                }
                                Uri.fromFile(tempFile)
                            } else {
                                Uri.fromFile(file)
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Failed preparing video: ${e.message}", e)
                            null
                        }
                    }

                    if (playbackUri != null) {
                        holder.videoView.setVideoURI(playbackUri)
                        holder.videoView.setOnPreparedListener { mp ->
                            mp.isLooping = true
                            val dur = holder.videoView.duration
                            holder.videoSeekBar.max = dur
                            holder.tvTotalTime.text = formatTime(dur)
                            holder.videoView.start()
                            holder.btnPlayPause.setImageResource(R.drawable.ic_pause_vector)
                            holder.handler.post(holder.progressUpdateRunnable)
                        }

                        holder.btnPlayPause.setOnClickListener {
                            if (holder.videoView.isPlaying) {
                                holder.videoView.pause()
                                holder.btnPlayPause.setImageResource(R.drawable.ic_play_vector)
                            } else {
                                holder.videoView.start()
                                holder.btnPlayPause.setImageResource(R.drawable.ic_pause_vector)
                            }
                        }

                        holder.btnRewind10.setOnClickListener {
                            val target = (holder.videoView.currentPosition - 10000).coerceAtLeast(0)
                            holder.videoView.seekTo(target)
                            holder.videoSeekBar.progress = target
                        }

                        holder.btnForward10.setOnClickListener {
                            val target = (holder.videoView.currentPosition + 10000).coerceAtMost(holder.videoView.duration)
                            holder.videoView.seekTo(target)
                            holder.videoSeekBar.progress = target
                        }

                        holder.videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                                if (fromUser) {
                                    holder.videoView.seekTo(progress)
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
                    }
                }
            } else {
                holder.videoView.visibility = View.GONE
                holder.videoTouchOverlay.visibility = View.GONE
                holder.videoControlsLayout.visibility = View.GONE
                holder.imageView.visibility = View.VISIBLE

                VaultImageLoader.loadThumbnail(this@MediaViewerActivity, file, holder.imageView, targetWidth = 1080, targetHeight = 1920)
            }
        }

        override fun onViewRecycled(holder: MediaViewHolder) {
            super.onViewRecycled(holder)
            holder.currentJob?.cancel()
            holder.handler.removeCallbacks(holder.progressUpdateRunnable)
            if (holder.videoView.visibility == View.VISIBLE) {
                holder.videoView.stopPlayback()
            }
            holder.tempVideoFile?.let {
                try { it.delete() } catch (ignored: Exception) {}
            }
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
