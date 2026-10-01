package com.my.seekapplication

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * IntruderListActivity: Screen displaying captured security selfies with timestamps and deletion controls.
 *
 * Changes made & Rationale:
 * 1. [PRIORITY 1 - Requirement 3]: Replaced placeholder Toast with a fully functional Intruder Selfies screen.
 *    - Lists all captured intruder selfies with human-readable timestamps.
 *    - Allows viewing full-size photo in dialog.
 *    - Allows deleting individual photos or clearing all photos at once.
 * 2. [PRIORITY 3 - Requirement 14]: Added FLAG_SECURE to prevent screenshots of intruder selfies.
 */
class IntruderListActivity : AppCompatActivity() {

    private lateinit var rvIntruderList: RecyclerView
    private lateinit var emptyStateLayout: LinearLayout
    private lateinit var tvPermissionWarning: TextView
    private lateinit var adapter: IntruderAdapter
    private val photosList = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [Requirement 14]: FLAG_SECURE to prevent screen capture
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        supportActionBar?.hide()
        window.navigationBarColor = Color.parseColor("#0A0A0A")
        window.statusBarColor = Color.parseColor("#0A0A0A")

        setContentView(R.layout.activity_intruder_list)

        val root = findViewById<RelativeLayout>(R.id.rootIntruderList)

        // Android 16 Window Insets: avoid edge squishing
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            root.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val btnClearAll = findViewById<Button>(R.id.btnClearAll)
        emptyStateLayout = findViewById(R.id.emptyStateLayout)
        rvIntruderList = findViewById(R.id.rvIntruderList)
        tvPermissionWarning = findViewById(R.id.tvPermissionWarning)

        btnBack.setOnClickListener { finish() }

        btnClearAll.setOnClickListener {
            if (photosList.isEmpty()) {
                Toast.makeText(this, "No photos to delete", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirmClearAll()
        }

        tvPermissionWarning.setOnClickListener {
            IntruderCamera.requestCameraPermissionIfNeeded(this)
        }

        adapter = IntruderAdapter(this, photosList,
            onItemClick = { file -> showFullPhotoDialog(file) },
            onDeleteClick = { file, pos -> deleteSinglePhoto(file, pos) }
        )
        rvIntruderList.layoutManager = LinearLayoutManager(this)
        rvIntruderList.adapter = adapter

        loadPhotos()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionBanner()
        loadPhotos()
    }

    private fun checkPermissionBanner() {
        if (!IntruderCamera.checkCameraPermission(this)) {
            tvPermissionWarning.visibility = View.VISIBLE
        } else {
            tvPermissionWarning.visibility = View.GONE
        }
    }

    private fun loadPhotos() {
        photosList.clear()
        photosList.addAll(IntruderCamera.getIntruderPhotos(this))

        if (photosList.isEmpty()) {
            emptyStateLayout.visibility = View.VISIBLE
            rvIntruderList.visibility = View.GONE
        } else {
            emptyStateLayout.visibility = View.GONE
            rvIntruderList.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
        }
    }

    private fun deleteSinglePhoto(file: File, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete Security Photo")
            .setMessage("Do you want to delete this captured photo?")
            .setPositiveButton("Delete") { _, _ ->
                file.delete()
                photosList.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.notifyItemRangeChanged(position, photosList.size)
                if (photosList.isEmpty()) {
                    emptyStateLayout.visibility = View.VISIBLE
                    rvIntruderList.visibility = View.GONE
                }
                Toast.makeText(this, "Photo deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Selfies")
            .setMessage("Are you sure you want to delete all captured intruder photos?")
            .setPositiveButton("Clear All") { _, _ ->
                for (file in photosList) {
                    file.delete()
                }
                photosList.clear()
                adapter.notifyDataSetChanged()
                emptyStateLayout.visibility = View.VISIBLE
                rvIntruderList.visibility = View.GONE
                Toast.makeText(this, "All security photos cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFullPhotoDialog(file: File) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_photo_preview)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.9f)
        }

        val dialogTitle = dialog.findViewById<TextView>(R.id.tvPreviewTitle)
        val ivFullPreview = dialog.findViewById<ImageView>(R.id.ivFullPreview)
        val btnClose = dialog.findViewById<Button>(R.id.btnClosePreview)

        val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(Date(file.lastModified()))
        dialogTitle.text = "Captured: $dateStr"

        VaultImageLoader.loadThumbnail(this, file, ivFullPreview, targetWidth = 800, targetHeight = 800)

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private class IntruderAdapter(
        val context: Context,
        val photos: List<File>,
        val onItemClick: (File) -> Unit,
        val onDeleteClick: (File, Int) -> Unit
    ) : RecyclerView.Adapter<IntruderAdapter.IntruderViewHolder>() {

        class IntruderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivPhoto: ImageView = view.findViewById(R.id.ivIntruderPhoto)
            val tvTime: TextView = view.findViewById(R.id.tvIntruderTime)
            val btnDelete: ImageView = view.findViewById(R.id.btnDeleteIntruder)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IntruderViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_intruder, parent, false)
            return IntruderViewHolder(view)
        }

        override fun onBindViewHolder(holder: IntruderViewHolder, position: Int) {
            val file = photos[position]
            val time = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(Date(file.lastModified()))
            holder.tvTime.text = time

            // Load photo thumbnail asynchronously via VaultImageLoader
            VaultImageLoader.loadThumbnail(context, file, holder.ivPhoto, targetWidth = 150, targetHeight = 150)

            holder.itemView.setOnClickListener { onItemClick(file) }
            holder.btnDelete.setOnClickListener { onDeleteClick(file, holder.adapterPosition) }
        }

        override fun getItemCount() = photos.size
    }
}
