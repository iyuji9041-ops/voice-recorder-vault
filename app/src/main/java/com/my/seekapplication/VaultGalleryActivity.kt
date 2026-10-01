package com.my.seekapplication

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * VaultGalleryActivity: Main encrypted gallery showing hidden media files categorized by tab.
 *
 * Implements:
 * 1. Persistent Storage that survives "Clear Data": Files stored in Documents/.secure_vault_system with .nomedia.
 * 2. Proper tab switching for Photos, Videos, and Audios where the active pill highlight dynamically moves.
 * 3. Add button (+) works across all 3 media categories.
 * 4. Android 16 Window Insets: avoids UI overlap with status bar / navigation bar.
 * 5. Decoy mode always shows an empty state.
 */
class VaultGalleryActivity : AppCompatActivity() {

    private val tag = "VaultGalleryActivity"

    private lateinit var emptyStateLayout: LinearLayout
    private lateinit var photosRecyclerView: RecyclerView
    private var isDecoy = false

    private var currentTab = "Photos"
    private lateinit var tabPhotos: TextView
    private lateinit var tabVideos: TextView
    private lateinit var tabAudios: TextView

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    private val contentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val fileUri: Uri? = result.data?.data
            if (fileUri != null) {
                saveFileToVault(fileUri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FLAG_SECURE prevents screenshots and screen recording
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        supportActionBar?.hide()
        window.navigationBarColor = Color.parseColor("#0A0A0A")
        window.statusBarColor = Color.parseColor("#0A0A0A")

        setContentView(R.layout.activity_vault_gallery)

        val root = findViewById<RelativeLayout>(R.id.rootVaultGallery)
        val addPhotoButton = findViewById<RelativeLayout>(R.id.addPhotoButton)
        val btnSettings = findViewById<View>(R.id.btnSettings)

        // Android 16 Window Insets: Avoid UI overlap & edge sticking
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

        tabPhotos.setOnClickListener { switchTab("Photos") }
        tabVideos.setOnClickListener { switchTab("Videos") }
        tabAudios.setOnClickListener { switchTab("Audios") }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        addPhotoButton.setOnClickListener {
            if (isDecoy) {
                Toast.makeText(this, "Cannot add files in Fake Vault!", Toast.LENGTH_SHORT).show()
            } else {
                openPicker()
            }
        }

        // Initialize active tab styling
        switchTab("Photos")

        if (isDecoy) {
            emptyStateLayout.visibility = View.VISIBLE
            photosRecyclerView.visibility = View.GONE
        } else {
            loadHiddenFiles()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isDecoy) {
            loadHiddenFiles()
        }
    }

    // Dynamic pill tab switching
    private fun switchTab(tabName: String) {
        currentTab = tabName

        // Reset all tabs
        tabPhotos.background = null
        tabPhotos.setTextColor(Color.parseColor("#88FFFFFF"))
        tabVideos.background = null
        tabVideos.setTextColor(Color.parseColor("#88FFFFFF"))
        tabAudios.background = null
        tabAudios.setTextColor(Color.parseColor("#88FFFFFF"))

        // Highlight selected tab
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

        if (isDecoy) {
            emptyStateLayout.visibility = View.VISIBLE
            photosRecyclerView.visibility = View.GONE
        } else {
            loadHiddenFiles()
        }
    }

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            when (currentTab) {
                "Photos" -> type = "image/*"
                "Videos" -> type = "video/*"
                "Audios" -> type = "audio/*"
            }
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val title = when (currentTab) {
            "Photos" -> "Select Photo to Hide"
            "Videos" -> "Select Video to Hide"
            else -> "Select Audio to Hide"
        }
        contentLauncher.launch(Intent.createChooser(intent, title))
    }

    private fun getOriginalFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        result = it.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            val mimeType = contentResolver.getType(uri)
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: when (currentTab) {
                "Photos" -> "jpg"
                "Videos" -> "mp4"
                else -> "m4a"
            }
            result = "vault_${System.currentTimeMillis()}.$ext"
        }
        return result ?: "vault_${System.currentTimeMillis()}"
    }

    // Encrypt file into persistent vault directory (Survives Clear Data)
    private fun saveFileToVault(uri: Uri) {
        val originalName = getOriginalFileName(uri)
        val persistentCatDir = VaultSecurityManager.getCategoryDir(this, currentTab)
        val targetEncryptedFile = File(persistentCatDir, "${originalName}.enc")

        Toast.makeText(this, "Encrypting & hiding file...", Toast.LENGTH_SHORT).show()

        activityScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val inputStream: InputStream? = contentResolver.openInputStream(uri)
                    if (inputStream == null) {
                        Log.e(tag, "Cannot open input stream for URI: $uri")
                        return@withContext false
                    }

                    inputStream.use { ins ->
                        VaultSecurityManager.encryptStreamToFile(ins, targetEncryptedFile)
                    }
                    Log.d(tag, "Encrypted file saved: ${targetEncryptedFile.absolutePath}")
                    true
                } catch (e: Exception) {
                    Log.e(tag, "Failed encrypting file into vault: ${e.message}", e)
                    false
                }
            }

            if (result) {
                Toast.makeText(this@VaultGalleryActivity, "File hidden & encrypted! Please delete original from gallery.", Toast.LENGTH_LONG).show()
                loadHiddenFiles()
            } else {
                Toast.makeText(this@VaultGalleryActivity, "Error: Encryption failed!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Load encrypted files from persistent vault directory
    private fun loadHiddenFiles() {
        if (isDecoy) {
            emptyStateLayout.visibility = View.VISIBLE
            photosRecyclerView.visibility = View.GONE
            return
        }

        val persistentDir = VaultSecurityManager.getCategoryDir(this, currentTab)
        val legacyInternalDir = File(File(filesDir, "vault_encrypted"), currentTab)

        val filesSet = mutableSetOf<File>()
        persistentDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.let { filesSet.addAll(it) }
        legacyInternalDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.let { filesSet.addAll(it) }

        val fileList = filesSet.sortedByDescending { it.lastModified() }

        if (fileList.isEmpty()) {
            emptyStateLayout.visibility = View.VISIBLE
            photosRecyclerView.visibility = View.GONE
        } else {
            emptyStateLayout.visibility = View.GONE
            photosRecyclerView.visibility = View.VISIBLE

            photosRecyclerView.layoutManager = GridLayoutManager(this, 3)
            photosRecyclerView.adapter = VaultAdapter(fileList.toMutableList()) { loadHiddenFiles() }
        }
    }
}