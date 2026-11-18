package com.my.seekapplication

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.my.seekapplication.crypto.IntruderAuditLog
import com.my.seekapplication.crypto.IntruderPhotoCrypto
import com.my.seekapplication.data.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.PrivateKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * IntruderListActivity: Displays encrypted intruder selfies and security audit entries.
 *
 * Guarantees:
 * 1. Photos on disk are RSA-2048 hybrid encrypted (.enc); decrypted only in memory using the Real Master Key.
 * 2. Decoy Parity: In decoy mode, reveals zero intruder photos or logs.
 * 3. Lifecycle-managed auto-lock via BaseVaultActivity.
 */
class IntruderListActivity : BaseVaultActivity() {

    private lateinit var rvIntruderList: RecyclerView
    private lateinit var emptyStateLayout: LinearLayout
    private lateinit var tvPermissionWarning: TextView
    private lateinit var adapter: IntruderAdapter
    private val itemsList = mutableListOf<IntruderDisplayItem>()
    private var privateKey: PrivateKey? = null

    data class IntruderDisplayItem(
        val file: File?,
        val auditEntry: IntruderAuditLog.Entry?,
        val timestamp: Long,
        val label: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        enableEdgeToEdge()

        setContentView(R.layout.activity_intruder_list)

        val root = findViewById<RelativeLayout>(R.id.rootIntruderList)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
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
            if (itemsList.isEmpty()) {
                Toast.makeText(this, "No records to delete", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirmClearAll()
        }

        // Initialize Real Vault private key if unlocked
        if (VaultSession.isUnlocked && !VaultSession.isDecoy) {
            val masterKey = VaultSession.repository.masterKey
            privateKey = IntruderPhotoCrypto.loadPrivateKey(this, masterKey)
        }

        adapter = IntruderAdapter(
            context = this,
            items = itemsList,
            privateKeyProvider = { privateKey },
            onItemClick = { item -> showFullPhotoDialog(item) },
            onDeleteClick = { item, pos -> deleteSingleItem(item, pos) }
        )
        rvIntruderList.layoutManager = LinearLayoutManager(this)
        rvIntruderList.adapter = adapter

        loadItems()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionBanner()
        loadItems()
    }

    private fun checkPermissionBanner() {
        if (!IntruderCamera.hasCameraPermission(this)) {
            tvPermissionWarning.visibility = View.VISIBLE
        } else {
            tvPermissionWarning.visibility = View.GONE
        }
    }

    private fun loadItems() {
        itemsList.clear()

        // Decoy mode: show nothing to preserve plausible deniability
        if (VaultSession.isDecoy) {
            emptyStateLayout.visibility = View.VISIBLE
            rvIntruderList.visibility = View.GONE
            return
        }

        val photos = IntruderPhotoCrypto.getAllEncryptedSelfies(this)
        val audits = IntruderAuditLog.getEntries(this)

        val mapPhotosByName = photos.associateBy { it.name }

        // Combined list of audits and orphan photos
        val handledPhotos = mutableSetOf<String>()

        for (audit in audits) {
            val photoFile = audit.photoFileName?.let { mapPhotosByName[it] }
            if (photoFile != null) {
                handledPhotos.add(photoFile.name)
            }
            val label = "Failed ${audit.layer} Attempt #${audit.attemptNumber}"
            itemsList.add(IntruderDisplayItem(photoFile, audit, audit.timestamp, label))
        }

        for (photo in photos) {
            if (!handledPhotos.contains(photo.name)) {
                itemsList.add(
                    IntruderDisplayItem(
                        file = photo,
                        auditEntry = null,
                        timestamp = photo.lastModified(),
                        label = "Failed Unlock Capture"
                    )
                )
            }
        }

        itemsList.sortByDescending { it.timestamp }

        if (itemsList.isEmpty()) {
            emptyStateLayout.visibility = View.VISIBLE
            rvIntruderList.visibility = View.GONE
        } else {
            emptyStateLayout.visibility = View.GONE
            rvIntruderList.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
        }
    }

    private fun deleteSingleItem(item: IntruderDisplayItem, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete Record")
            .setMessage("Do you want to delete this security entry?")
            .setPositiveButton("Delete") { _, _ ->
                item.file?.delete()
                itemsList.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.notifyItemRangeChanged(position, itemsList.size)
                if (itemsList.isEmpty()) {
                    emptyStateLayout.visibility = View.VISIBLE
                    rvIntruderList.visibility = View.GONE
                }
                Toast.makeText(this, "Record deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Records")
            .setMessage("Are you sure you want to delete all captured intruder photos and audit logs?")
            .setPositiveButton("Clear All") { _, _ ->
                for (item in itemsList) {
                    item.file?.delete()
                }
                IntruderAuditLog.clearLogs(this)
                itemsList.clear()
                adapter.notifyDataSetChanged()
                emptyStateLayout.visibility = View.VISIBLE
                rvIntruderList.visibility = View.GONE
                Toast.makeText(this, "All security records cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFullPhotoDialog(item: IntruderDisplayItem) {
        val file = item.file ?: return
        val privKey = privateKey ?: return

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_photo_preview)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.9f)
        }

        val dialogTitle = dialog.findViewById<TextView>(R.id.tvPreviewTitle)
        val ivFullPreview = dialog.findViewById<ImageView>(R.id.ivFullPreview)
        val btnClose = dialog.findViewById<Button>(R.id.btnClosePreview)

        val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(Date(item.timestamp))
        dialogTitle.text = "${item.label}\n$dateStr"

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val decrypted = IntruderPhotoCrypto.decryptPhoto(file, privKey)
                if (decrypted != null) {
                    BitmapFactory.decodeByteArray(decrypted, 0, decrypted.size)
                } else null
            }
            if (bitmap != null) {
                ivFullPreview.setImageBitmap(bitmap)
            } else {
                Toast.makeText(this@IntruderListActivity, "Failed decrypting photo", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private class IntruderAdapter(
        val context: Context,
        val items: List<IntruderDisplayItem>,
        val privateKeyProvider: () -> PrivateKey?,
        val onItemClick: (IntruderDisplayItem) -> Unit,
        val onDeleteClick: (IntruderDisplayItem, Int) -> Unit
    ) : RecyclerView.Adapter<IntruderAdapter.IntruderViewHolder>() {

        class IntruderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivPhoto: ImageView = view.findViewById(R.id.ivIntruderPhoto)
            val tvLabel: TextView = view.findViewById(R.id.tvIntruderLabel)
            val tvTime: TextView = view.findViewById(R.id.tvIntruderTime)
            val btnDelete: ImageView = view.findViewById(R.id.btnDeleteIntruder)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IntruderViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_intruder, parent, false)
            return IntruderViewHolder(view)
        }

        override fun onBindViewHolder(holder: IntruderViewHolder, position: Int) {
            val item = items[position]
            val time = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(Date(item.timestamp))

            holder.tvLabel.text = item.label
            holder.tvTime.text = time

            val file = item.file
            val privKey = privateKeyProvider()

            if (file != null && privKey != null) {
                holder.ivPhoto.visibility = View.VISIBLE
                // Asynchronously decrypt and render thumbnail
                (context as? IntruderListActivity)?.lifecycleScope?.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        val decrypted = IntruderPhotoCrypto.decryptPhoto(file, privKey)
                        if (decrypted != null) {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                            BitmapFactory.decodeByteArray(decrypted, 0, decrypted.size, opts)
                        } else null
                    }
                    if (bmp != null) {
                        holder.ivPhoto.setImageBitmap(bmp)
                    } else {
                        holder.ivPhoto.setImageResource(R.drawable.ic_camera_vector)
                    }
                }
            } else {
                holder.ivPhoto.setImageResource(R.drawable.ic_shield_vector)
            }

            holder.itemView.setOnClickListener { onItemClick(item) }
            holder.btnDelete.setOnClickListener { onDeleteClick(item, holder.adapterPosition) }
        }

        override fun getItemCount() = items.size
    }
}
