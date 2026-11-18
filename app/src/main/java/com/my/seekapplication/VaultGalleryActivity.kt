package com.my.seekapplication

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.my.seekapplication.crypto.VaultCipherStream
import com.my.seekapplication.data.MediaTypeHelper
import com.my.seekapplication.data.VaultItem
import com.my.seekapplication.data.VaultRepository
import com.my.seekapplication.data.VaultRestoreManager
import com.my.seekapplication.data.VaultSession
import com.my.seekapplication.player.EncryptedVaultDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Locale

/**
 * VaultGalleryActivity: Modernized encrypted vault media gallery and audio player.
 *
 * Implements:
 * 1. Multi-select import via Photo Picker / Open Document with live progress bar.
 * 2. Post-import verification (size & checksum) before deletion of public originals.
 * 3. MediaStore.createDeleteRequest (Android 11+) & direct delete (older OS).
 * 4. ListAdapter with DiffUtil to eliminate redundant adapter re-creation.
 * 5. LifecycleScope for automatic job cleanup.
 * 6. Inline ExoPlayer for Audios tab with audio focus, play/pause, and seeking.
 * 7. Multi-select batch deletion and restoration with confirmation.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VaultGalleryActivity : BaseVaultActivity() {

    private val tag = "VaultGalleryActivity"

    private lateinit var emptyStateLayout: LinearLayout
    private lateinit var photosRecyclerView: RecyclerView
    private lateinit var vaultAdapter: VaultAdapter

    private var isDecoy = false
    private var currentTab = "Photos"
    private lateinit var tabPhotos: TextView
    private lateinit var tabVideos: TextView
    private lateinit var tabAudios: TextView

    // Selection Bar Views
    private lateinit var layoutSelectionBar: RelativeLayout
    private lateinit var btnCloseSelection: ImageView
    private lateinit var tvSelectionCount: TextView
    private lateinit var btnSelectAll: ImageView
    private lateinit var btnRestoreSelected: ImageView
    private lateinit var btnDeleteSelected: ImageView

    // Import Progress Views
    private lateinit var layoutImportProgress: RelativeLayout
    private lateinit var tvImportTitle: TextView
    private lateinit var tvImportSubtitle: TextView
    private lateinit var importProgressBar: ProgressBar

    // Inline Audio Player Views
    private lateinit var layoutAudioPlayerBar: RelativeLayout
    private lateinit var btnAudioPlayPause: ImageView
    private lateinit var tvAudioTrackTitle: TextView
    private lateinit var audioPlayerSeekBar: SeekBar
    private lateinit var tvAudioTrackTime: TextView
    private lateinit var btnCloseAudio: ImageView

    private var inlinePlayer: ExoPlayer? = null
    private var currentPlayingItemId: String? = null
    private val audioHandler = Handler(Looper.getMainLooper())

    private val pendingUrisToDelete = mutableListOf<Uri>()

    // Launcher for system delete confirmation on Android 11+
    private val deleteOriginalsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Files hidden and originals removed from gallery.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Files saved to vault. Public originals were kept in gallery.", Toast.LENGTH_LONG).show()
        }
        pendingUrisToDelete.clear()
        loadHiddenFiles()
    }

    // Modern Photo Picker for multi-select
    private val pickVisualMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            handleImportUris(uris)
        }
    }

    // Document Picker for audio or fallback multi-select
    private val openMultipleDocsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            handleImportUris(uris)
        }
    }

    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            inlinePlayer?.let { player ->
                if (player.isPlaying) {
                    val current = player.currentPosition.toInt()
                    val total = player.duration.coerceAtLeast(0).toInt()
                    audioPlayerSeekBar.max = total
                    audioPlayerSeekBar.progress = current
                    tvAudioTrackTime.text = "${formatTime(current)} / ${formatTime(total)}"
                }
            }
            audioHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        supportActionBar?.hide()
        enableEdgeToEdge()

        setContentView(R.layout.activity_vault_gallery)

        val root = findViewById<RelativeLayout>(R.id.rootVaultGallery)
        val addPhotoButton = findViewById<RelativeLayout>(R.id.addPhotoButton)
        val btnSettings = findViewById<View>(R.id.btnSettings)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            val horizontalPadding = (16 * density).toInt()
            val topPadding = systemBars.top + (8 * density).toInt()
            val bottomPadding = systemBars.bottom + (8 * density).toInt()
            root.setPadding(
                systemBars.left + horizontalPadding,
                topPadding,
                systemBars.right + horizontalPadding,
                bottomPadding
            )
            insets
        }

        isDecoy = intent.getBooleanExtra("IS_DECOY", false)

        emptyStateLayout = findViewById(R.id.emptyStateLayout)
        photosRecyclerView = findViewById(R.id.photosRecyclerView)

        tabPhotos = findViewById(R.id.tabPhotos)
        tabVideos = findViewById(R.id.tabVideos)
        tabAudios = findViewById(R.id.tabAudios)

        // Selection Bar bindings
        layoutSelectionBar = findViewById(R.id.layoutSelectionBar)
        btnCloseSelection = findViewById(R.id.btnCloseSelection)
        tvSelectionCount = findViewById(R.id.tvSelectionCount)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnRestoreSelected = findViewById(R.id.btnRestoreSelected)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)

        // Import Progress bindings
        layoutImportProgress = findViewById(R.id.layoutImportProgress)
        tvImportTitle = findViewById(R.id.tvImportTitle)
        tvImportSubtitle = findViewById(R.id.tvImportSubtitle)
        importProgressBar = findViewById(R.id.importProgressBar)

        // Audio Player Bar bindings
        layoutAudioPlayerBar = findViewById(R.id.layoutAudioPlayerBar)
        btnAudioPlayPause = findViewById(R.id.btnAudioPlayPause)
        tvAudioTrackTitle = findViewById(R.id.tvAudioTrackTitle)
        audioPlayerSeekBar = findViewById(R.id.audioPlayerSeekBar)
        tvAudioTrackTime = findViewById(R.id.tvAudioTrackTime)
        btnCloseAudio = findViewById(R.id.btnCloseAudio)

        setupAdapter()
        setupAudioPlayerBar()
        setupSelectionBar()

        tabPhotos.setOnClickListener { switchTab("Photos") }
        tabVideos.setOnClickListener { switchTab("Videos") }
        tabAudios.setOnClickListener { switchTab("Audios") }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        addPhotoButton.setOnClickListener {
            openPicker()
        }

        switchTab("Photos")
        loadHiddenFiles()
    }

    override fun onResume() {
        super.onResume()
        loadHiddenFiles()
    }

    override fun onStop() {
        super.onStop()
        pauseInlineAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseInlineAudio()
    }

    private fun setupAdapter() {
        val repo = VaultSession.getRepositoryOrNull()
        if (repo == null) {
            finish()
            return
        }

        vaultAdapter = VaultAdapter(
            repository = repo,
            onItemClick = { item, position ->
                if (MediaTypeHelper.isAudio(item.mimeType, item.originalName)) {
                    playInlineAudio(item)
                } else {
                    openMediaViewer(position)
                }
            },
            onItemLongClick = { _, _ ->
                layoutSelectionBar.visibility = View.VISIBLE
                true
            },
            onSelectionChanged = { count ->
                if (count == 0) {
                    layoutSelectionBar.visibility = View.GONE
                } else {
                    layoutSelectionBar.visibility = View.VISIBLE
                    tvSelectionCount.text = "$count selected"
                }
            },
            onRestoreItemClick = { item ->
                restoreSingleItem(item)
            }
        )

        photosRecyclerView.layoutManager = GridLayoutManager(this, 3)
        photosRecyclerView.adapter = vaultAdapter
    }

    private fun openMediaViewer(position: Int) {
        val repo = VaultSession.getRepositoryOrNull() ?: return
        val currentItems = vaultAdapter.currentList
        if (currentItems.isEmpty() || position !in currentItems.indices) return

        val intent = Intent(this, MediaViewerActivity::class.java)
        val pathList = currentItems.map { repo.getFileForItem(it).absolutePath }.toCollection(ArrayList())
        val nameList = currentItems.map { it.originalName }.toCollection(ArrayList())
        val idList = currentItems.map { it.id }.toCollection(ArrayList())
        val mimeList = currentItems.map { it.mimeType }.toCollection(ArrayList())

        intent.putStringArrayListExtra("FILE_LIST", pathList)
        intent.putStringArrayListExtra("NAME_LIST", nameList)
        intent.putStringArrayListExtra("ID_LIST", idList)
        intent.putStringArrayListExtra("MIME_LIST", mimeList)
        intent.putExtra("POSITION", position)
        startActivity(intent)
    }

    private fun setupSelectionBar() {
        btnCloseSelection.setOnClickListener {
            vaultAdapter.clearSelection()
            layoutSelectionBar.visibility = View.GONE
        }

        btnSelectAll.setOnClickListener {
            vaultAdapter.selectAll()
        }

        btnDeleteSelected.setOnClickListener {
            val selected = vaultAdapter.getSelectedItems()
            if (selected.isEmpty()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle("Delete Files Permanently?")
                .setMessage("Are you sure you want to permanently delete ${selected.size} file(s) from the vault? This action cannot be undone.")
                .setPositiveButton("Delete Permanently") { _, _ ->
                    deleteSelectedItems(selected)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnRestoreSelected.setOnClickListener {
            val selected = vaultAdapter.getSelectedItems()
            if (selected.isEmpty()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle("Restore to Gallery?")
                .setMessage("Restore ${selected.size} file(s) to public storage? Files will be decrypted and removed from vault.")
                .setPositiveButton("Restore") { _, _ ->
                    restoreSelectedItems(selected)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun deleteSelectedItems(items: List<VaultItem>) {
        val repo = VaultSession.getRepositoryOrNull() ?: return
        lifecycleScope.launch {
            var deletedCount = 0
            withContext(Dispatchers.IO) {
                for (item in items) {
                    if (repo.deleteItem(item.id)) {
                        deletedCount++
                    }
                }
            }
            vaultAdapter.clearSelection()
            layoutSelectionBar.visibility = View.GONE
            Toast.makeText(this@VaultGalleryActivity, "$deletedCount file(s) deleted.", Toast.LENGTH_SHORT).show()
            loadHiddenFiles()
        }
    }

    private fun restoreSelectedItems(items: List<VaultItem>) {
        val repo = VaultSession.getRepositoryOrNull() ?: return
        lifecycleScope.launch {
            layoutImportProgress.visibility = View.VISIBLE
            tvImportTitle.text = "Restoring Files..."
            importProgressBar.max = items.size
            importProgressBar.progress = 0

            var successCount = 0
            val errors = mutableListOf<String>()

            withContext(Dispatchers.IO) {
                items.forEachIndexed { index, item ->
                    withContext(Dispatchers.Main) {
                        tvImportSubtitle.text = "Restoring ${index + 1} of ${items.size}: ${item.originalName}"
                        importProgressBar.progress = index + 1
                    }
                    val result = VaultRestoreManager.restoreItem(this@VaultGalleryActivity, repo, item)
                    if (result.success) {
                        successCount++
                    } else {
                        errors.add("${item.originalName}: ${result.error}")
                    }
                }
            }

            layoutImportProgress.visibility = View.GONE
            vaultAdapter.clearSelection()
            layoutSelectionBar.visibility = View.GONE

            if (errors.isNotEmpty()) {
                AlertDialog.Builder(this@VaultGalleryActivity)
                    .setTitle("Restoration Issues")
                    .setMessage("Restored $successCount of ${items.size} files.\nErrors:\n" + errors.joinToString("\n") { "- $it" })
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                Toast.makeText(this@VaultGalleryActivity, "Successfully restored $successCount file(s) to public storage.", Toast.LENGTH_LONG).show()
            }
            loadHiddenFiles()
        }
    }

    private fun restoreSingleItem(item: VaultItem) {
        val repo = VaultSession.getRepositoryOrNull() ?: return
        Toast.makeText(this, "Restoring ${item.originalName}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                VaultRestoreManager.restoreItem(this@VaultGalleryActivity, repo, item)
            }

            if (result.success) {
                Toast.makeText(this@VaultGalleryActivity, "Restored ${item.originalName} to public gallery!", Toast.LENGTH_LONG).show()
                loadHiddenFiles()
            } else {
                Toast.makeText(this@VaultGalleryActivity, "Restore failed: ${result.error}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun switchTab(tabName: String) {
        currentTab = tabName

        tabPhotos.background = null
        tabPhotos.setTextColor(Color.parseColor("#88FFFFFF"))
        tabVideos.background = null
        tabVideos.setTextColor(Color.parseColor("#88FFFFFF"))
        tabAudios.background = null
        tabAudios.setTextColor(Color.parseColor("#88FFFFFF"))

        when (tabName) {
            "Photos" -> {
                tabPhotos.setBackgroundResource(R.drawable.bg_glass_note_card)
                tabPhotos.setTextColor(Color.parseColor("#00E676"))
            }
            "Videos" -> {
                tabVideos.setBackgroundResource(R.drawable.bg_glass_note_card)
                tabVideos.setTextColor(Color.parseColor("#00E676"))
            }
            "Audios" -> {
                tabAudios.setBackgroundResource(R.drawable.bg_glass_note_card)
                tabAudios.setTextColor(Color.parseColor("#00E676"))
            }
        }

        if (vaultAdapter.isSelectionMode) {
            vaultAdapter.clearSelection()
            layoutSelectionBar.visibility = View.GONE
        }

        loadHiddenFiles()
    }

    private fun openPicker() {
        try {
            when (currentTab) {
                "Photos" -> {
                    pickVisualMediaLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
                "Videos" -> {
                    pickVisualMediaLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                }
                "Audios" -> {
                    openMultipleDocsLauncher.launch(arrayOf("audio/*"))
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Photo Picker not supported; falling back to open multiple documents: ${e.message}")
            openMultipleDocsLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun getOriginalFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            result = cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed querying display name: ${e.message}")
            }
        }
        if (result.isNullOrEmpty()) {
            val mimeType = contentResolver.getType(uri)
            val ext = when (currentTab) {
                "Photos" -> "jpg"
                "Videos" -> "mp4"
                else -> "m4a"
            }
            result = "vault_${System.currentTimeMillis()}.$ext"
        }
        return result ?: "vault_${System.currentTimeMillis()}"
    }

    private fun handleImportUris(uris: List<Uri>) {
        val repo = VaultSession.getRepositoryOrNull()
        if (repo == null) {
            Toast.makeText(this, "Vault session expired.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        layoutImportProgress.visibility = View.VISIBLE
        tvImportTitle.text = "Encrypting & Importing..."
        importProgressBar.max = uris.size
        importProgressBar.progress = 0

        pendingUrisToDelete.clear()

        lifecycleScope.launch {
            val successfulImports = mutableListOf<VaultItem>()
            val errors = mutableListOf<String>()

            withContext(Dispatchers.IO) {
                uris.forEachIndexed { index, uri ->
                    val originalName = getOriginalFileName(uri)
                    val mimeType = contentResolver.getType(uri) ?: MediaTypeHelper.resolveMimeType(originalName)

                    withContext(Dispatchers.Main) {
                        tvImportSubtitle.text = "File ${index + 1} of ${uris.size}: $originalName"
                        importProgressBar.progress = index + 1
                    }

                    try {
                        val inputStream: InputStream = contentResolver.openInputStream(uri)
                            ?: throw Exception("Could not open stream for $originalName")

                        // Stream while computing SHA-256
                        val shaDigest = MessageDigest.getInstance("SHA-256")
                        val digestIn = DigestInputStream(inputStream, shaDigest)

                        val importedItem = digestIn.use { ins ->
                            repo.importStream(
                                source = ins,
                                originalName = originalName,
                                category = currentTab,
                                mimeType = mimeType
                            )
                        }

                        val sourceHash = shaDigest.digest()

                        // Verify encrypted file on disk
                        val encFile = repo.getFileForItem(importedItem)
                        if (!encFile.exists() || encFile.length() <= 0L) {
                            repo.deleteItem(importedItem.id)
                            throw Exception("Encrypted vault file was not written properly.")
                        }

                        // Read back decrypted content and verify checksum & length
                        val verifyDigest = MessageDigest.getInstance("SHA-256")
                        var verifyLength = 0L
                        repo.getDecryptedStream(importedItem).use { verifyIn ->
                            val buf = ByteArray(32 * 1024)
                            var read: Int
                            while (verifyIn.read(buf).also { read = it } != -1) {
                                verifyDigest.update(buf, 0, read)
                                verifyLength += read
                            }
                        }

                        if (verifyLength != importedItem.fileSize || !MessageDigest.isEqual(sourceHash, verifyDigest.digest())) {
                            repo.deleteItem(importedItem.id)
                            throw Exception("Verification failed: corrupted encrypted data.")
                        }

                        successfulImports.add(importedItem)
                        pendingUrisToDelete.add(uri)
                    } catch (e: Exception) {
                        Log.e(tag, "Failed importing $originalName: ${e.message}", e)
                        errors.add("$originalName: ${e.message ?: "Encryption error"}")
                    }
                }
            }

            layoutImportProgress.visibility = View.GONE

            if (errors.isNotEmpty()) {
                AlertDialog.Builder(this@VaultGalleryActivity)
                    .setTitle("Import Errors")
                    .setMessage("Encountered errors with ${errors.size} file(s):\n" + errors.joinToString("\n") { "- $it" })
                    .setPositiveButton("OK", null)
                    .show()
            }

            if (pendingUrisToDelete.isNotEmpty()) {
                promptOriginalDeletion(pendingUrisToDelete)
            } else {
                loadHiddenFiles()
            }
        }
    }

    private fun promptOriginalDeletion(uris: List<Uri>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val deleteRequest = MediaStore.createDeleteRequest(contentResolver, uris)
                deleteOriginalsLauncher.launch(
                    IntentSenderRequest.Builder(deleteRequest.intentSender).build()
                )
                return
            } catch (e: Exception) {
                Log.w(tag, "createDeleteRequest failed (possibly non-MediaStore URIs): ${e.message}")
            }
        }

        // Direct delete or confirmation for Android 10 and below or non-MediaStore URIs
        AlertDialog.Builder(this)
            .setTitle("Delete Originals from Public Gallery?")
            .setMessage("The files are safely encrypted in your vault. To ensure they are hidden, the unencrypted public originals should be removed from your gallery.")
            .setPositiveButton("Delete Originals") { _, _ ->
                var deletedCount = 0
                for (uri in uris) {
                    try {
                        if (contentResolver.delete(uri, null, null) > 0) {
                            deletedCount++
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Could not delete original $uri: ${e.message}")
                    }
                }
                if (deletedCount > 0) {
                    Toast.makeText(this, "Files hidden and originals removed from gallery.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Files saved to vault. Please remove originals from gallery manually.", Toast.LENGTH_LONG).show()
                }
                pendingUrisToDelete.clear()
                loadHiddenFiles()
            }
            .setNegativeButton("Keep Originals") { _, _ ->
                Toast.makeText(this, "Files saved to vault. Public originals were kept in gallery.", Toast.LENGTH_LONG).show()
                pendingUrisToDelete.clear()
                loadHiddenFiles()
            }
            .setCancelable(false)
            .show()
    }

    private fun loadHiddenFiles() {
        val repo = VaultSession.getRepositoryOrNull() ?: return
        val items = repo.getItems(currentTab)

        if (items.isEmpty()) {
            emptyStateLayout.visibility = View.VISIBLE
            photosRecyclerView.visibility = View.GONE
        } else {
            emptyStateLayout.visibility = View.GONE
            photosRecyclerView.visibility = View.VISIBLE
        }

        vaultAdapter.submitList(items)
    }

    // ---------------------------------------------------------------------------------------------
    // INLINE AUDIO PLAYBACK (Media3 ExoPlayer)
    // ---------------------------------------------------------------------------------------------

    private fun setupAudioPlayerBar() {
        btnAudioPlayPause.setOnClickListener {
            inlinePlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            }
        }

        btnCloseAudio.setOnClickListener {
            stopInlineAudio()
        }

        audioPlayerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    inlinePlayer?.seekTo(progress.toLong())
                    tvAudioTrackTime.text = "${formatTime(progress)} / ${formatTime(inlinePlayer?.duration?.toInt() ?: 0)}"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun playInlineAudio(item: VaultItem) {
        val repo = VaultSession.getRepositoryOrNull() ?: return
        val file = repo.getFileForItem(item)
        if (!file.exists()) return

        layoutAudioPlayerBar.visibility = View.VISIBLE
        tvAudioTrackTitle.text = item.originalName
        currentPlayingItemId = item.id

        if (inlinePlayer == null) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            inlinePlayer = ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
                .build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                val dur = duration.coerceAtLeast(0).toInt()
                                audioPlayerSeekBar.max = dur
                                tvAudioTrackTime.text = "${formatTime(currentPosition.toInt())} / ${formatTime(dur)}"
                                audioHandler.post(progressUpdateRunnable)
                            } else if (state == Player.STATE_ENDED) {
                                btnAudioPlayPause.setImageResource(R.drawable.ic_play_vector)
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying) {
                                btnAudioPlayPause.setImageResource(R.drawable.ic_pause_vector)
                            } else {
                                btnAudioPlayPause.setImageResource(R.drawable.ic_play_vector)
                            }
                        }
                    })
                }
        }

        val mediaSource = if (file.name.endsWith(".enc")) {
            val dsFactory = EncryptedVaultDataSource.Factory(VaultCipherStream(), repo.masterKey)
            ProgressiveMediaSource.Factory(dsFactory)
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
        } else {
            ProgressiveMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(this))
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))
        }

        inlinePlayer?.apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }
    }

    private fun pauseInlineAudio() {
        inlinePlayer?.pause()
    }

    private fun stopInlineAudio() {
        audioHandler.removeCallbacks(progressUpdateRunnable)
        inlinePlayer?.stop()
        layoutAudioPlayerBar.visibility = View.GONE
        currentPlayingItemId = null
    }

    private fun releaseInlineAudio() {
        audioHandler.removeCallbacks(progressUpdateRunnable)
        inlinePlayer?.release()
        inlinePlayer = null
        currentPlayingItemId = null
    }

    private fun formatTime(millis: Int): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
