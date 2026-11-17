package com.my.seekapplication

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10

/**
 * VoiceRecorderActivity: Production-grade Voice Recorder decoy.
 *
 * Implements:
 * 1. Audio recording directly saved to device public storage (Recordings/MyVoiceRecordings) accessible in Files app.
 * 2. Android 16 Edge-to-Edge window insets compliance without UI overlap.
 * 3. First-time launch interactive secret tutorial popup.
 * 4. Completely covert long-press (no green line progress bar) with configurable duration from Settings.
 * 5. Layer 1 Door Passcode prompt before entering the Dashboard Swipe gate (Triple Protection).
 * 6. In-line audio playback, sharing, and batch deletion.
 */
class VoiceRecorderActivity : AppCompatActivity() {

    private val tag = "VoiceRecorder"
    private val requestRecordAudio = 201
    private val prefFirstTimeTutorial = "has_seen_first_launch_tutorial"

    private lateinit var tvTimer: TextView
    private lateinit var tvRecordStatus: TextView
    private lateinit var tvRecordingsCount: TextView
    private lateinit var btnRecord: RelativeLayout
    private lateinit var ivRecordIcon: ImageView
    private lateinit var rvRecordingsList: RecyclerView
    private lateinit var adapter: RecordingAdapter

    // Multi-Select Bar
    private lateinit var selectionBar: RelativeLayout
    private lateinit var tvSelectedCount: TextView
    private lateinit var btnCloseSelection: ImageView
    private lateinit var btnSelectAll: ImageView
    private lateinit var btnDeleteSelected: ImageView

    private val waveBars = ArrayList<View>()
    private val recordings = ArrayList<File>()
    private val selectedFiles = HashSet<File>()
    private var isSelectionMode = false

    private val handler = Handler(Looper.getMainLooper())
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingFile: File? = null

    private var isRecording = false
    private var recordSeconds = 0
    private var currentRecordFile: File? = null

    // Covert Secret Hold Logic (Configurable Duration, No Green Line)
    private var isHolding = false
    private var holdStartTime = 0L

    private val holdTimerRunnable = object : Runnable {
        override fun run() {
            if (!isHolding) return
            val requiredHoldMs = VaultSecurityManager.getHoldDurationSeconds(this@VoiceRecorderActivity) * 1000L
            val elapsed = System.currentTimeMillis() - holdStartTime

            if (elapsed >= requiredHoldMs) {
                isHolding = false
                triggerSecretDoor()
            } else {
                handler.postDelayed(this, 100)
            }
        }
    }

    private val recordTimerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                recordSeconds++
                updateTimerDisplay()
                updateRealWaveforms()
                handler.postDelayed(this, 1000)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FLAG_SECURE prevents screen recording
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        supportActionBar?.hide()
        enableEdgeToEdge()

        setContentView(R.layout.activity_voice_recorder)

        val root = findViewById<RelativeLayout>(R.id.rootVoiceRecorder)

        // Android 16 Window Insets: Avoid UI overlap & edge squishing
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val density = resources.displayMetrics.density
            val horizontalPadding = (16 * density).toInt()
            root.setPadding(
                systemBars.left + horizontalPadding,
                systemBars.top + (8 * density).toInt(),
                systemBars.right + horizontalPadding,
                systemBars.bottom + (8 * density).toInt()
            )
            insets
        }

        // Migrate any legacy data / restore config if app data was cleared
        VaultSecurityManager.migrateLegacyDataIfNeeded(this)
        VaultSecurityManager.restoreConfigIfCleared(this)
        com.my.seekapplication.data.VaultSession.wipeAppCache(this)

        tvTimer = findViewById(R.id.tvTimer)
        tvRecordStatus = findViewById(R.id.tvRecordStatus)
        tvRecordingsCount = findViewById(R.id.tvRecordingsCount)
        btnRecord = findViewById(R.id.btnRecord)
        ivRecordIcon = findViewById(R.id.ivRecordIcon)
        rvRecordingsList = findViewById(R.id.rvRecordingsList)

        selectionBar = findViewById(R.id.selectionBar)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        btnCloseSelection = findViewById(R.id.btnCloseSelection)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)

        btnCloseSelection.setOnClickListener { exitSelectionMode() }
        btnSelectAll.setOnClickListener { selectAllRecordings() }
        btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }

        // Covert Emergency Password Reset: Tap title 5 times or hold 3s to reset keys
        val tvAppTitle = findViewById<TextView>(R.id.tvAppTitle)
        var titleTapCount = 0
        var lastTitleTapTime = 0L

        tvAppTitle.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTitleTapTime > 2500) {
                titleTapCount = 0
            }
            lastTitleTapTime = now
            titleTapCount++
            if (titleTapCount >= 5) {
                titleTapCount = 0
                showMasterEmergencyRecoveryDialog()
            }
        }

        tvAppTitle.setOnLongClickListener {
            showMasterEmergencyRecoveryDialog()
            true
        }

        val waveIds = listOf(
            R.id.waveBar1, R.id.waveBar2, R.id.waveBar3, R.id.waveBar4,
            R.id.waveBar5, R.id.waveBar6, R.id.waveBar7, R.id.waveBar8, R.id.waveBar9
        )
        for (id in waveIds) {
            findViewById<View>(id)?.let { waveBars.add(it) }
        }

        loadRecordingsFromStorage()

        adapter = RecordingAdapter(
            context = this,
            list = recordings,
            selectedSet = selectedFiles,
            isSelectionMode = { isSelectionMode },
            isPlaying = { file -> currentPlayingFile == file && mediaPlayer?.isPlaying == true },
            onPlayClick = { file -> togglePlayAudio(file) },
            onShareClick = { file -> shareAudioFile(file) },
            onDeleteClick = { file -> confirmDeleteSingle(file) },
            onItemClick = { file ->
                if (isSelectionMode) {
                    toggleFileSelection(file)
                } else {
                    togglePlayAudio(file)
                }
            },
            onItemLongClick = { file ->
                if (!isSelectionMode) {
                    enterSelectionMode(file)
                }
            }
        )

        rvRecordingsList.layoutManager = LinearLayoutManager(this)
        rvRecordingsList.adapter = adapter
        updateCountDisplay()

        // Covert secret hold on Record Button (No green progress bar)
        btnRecord.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isHolding = true
                    holdStartTime = System.currentTimeMillis()
                    handler.post(holdTimerRunnable)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val heldDuration = System.currentTimeMillis() - holdStartTime
                    val requiredHoldMs = VaultSecurityManager.getHoldDurationSeconds(this) * 1000L
                    isHolding = false
                    handler.removeCallbacks(holdTimerRunnable)

                    if (heldDuration < requiredHoldMs) {
                        handleRecordButtonClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isHolding = false
                    handler.removeCallbacks(holdTimerRunnable)
                    true
                }
                else -> false
            }
        }

        // Show first launch tutorial if never seen
        showFirstLaunchTutorialIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        // Lock any vault session and wipe cache whenever returning to the public recorder screen
        com.my.seekapplication.data.VaultSession.lock(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioPlayback()
        stopAudioRecording(save = false)
    }

    // ---------------------------------------------------------------------------------------------
    // FIRST TIME LAUNCH TUTORIAL
    // ---------------------------------------------------------------------------------------------

    private fun showFirstLaunchTutorialIfNeeded() {
        val prefs = getSharedPreferences("AppTutorialPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(prefFirstTimeTutorial, false)) {
            val dialog = Dialog(this)
            dialog.setContentView(R.layout.dialog_first_time_tutorial)
            dialog.window?.let { window ->
                window.setBackgroundDrawableResource(android.R.color.transparent)
                window.setDimAmount(0.85f)
            }

            dialog.findViewById<Button>(R.id.btnTutorialGotIt).setOnClickListener {
                prefs.edit().putBoolean(prefFirstTimeTutorial, true).apply()
                dialog.dismiss()
            }
            dialog.setCancelable(false)
            dialog.show()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // SECRET DOOR & LAYER 1 DOOR PASSCODE
    // ---------------------------------------------------------------------------------------------

    private fun triggerSecretDoor() {
        try {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(80)
        } catch (ignored: Exception) {}

        stopAudioPlayback()
        stopAudioRecording(save = false)

        if (!VaultSecurityManager.hasDoorPasscode(this)) {
            // First time setup for Door Passcode
            showSetDoorPasscodeDialog()
        } else {
            // Prompt Door Passcode (Layer 1 of Triple Protection)
            showVerifyDoorPasscodeDialog()
        }
    }

    private fun showSetDoorPasscodeDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_door_passcode)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = dialog.findViewById<TextView>(R.id.tvDoorTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvDoorSubtitle)
        val etInput = dialog.findViewById<EditText>(R.id.etDoorPasscodeInput)
        val btnForgot = dialog.findViewById<TextView>(R.id.btnDoorForgotPassword)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelDoor)
        val btnSubmit = dialog.findViewById<Button>(R.id.btnSubmitDoor)

        tvTitle.text = "Set Door Passcode (Layer 1)"
        tvSubtitle.text = "Set a 4-digit entrance code to guard your vault gate"
        btnForgot.visibility = View.GONE

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSubmit.setOnClickListener {
            val code = etInput.text.toString().trim()
            if (code.length < 4) {
                Toast.makeText(this, "Passcode must be at least 4 digits!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            VaultSecurityManager.setDoorPasscode(this, code)
            dialog.dismiss()
            Toast.makeText(this, "Door Passcode Saved!", Toast.LENGTH_SHORT).show()
            launchDashboardGate()
        }

        dialog.show()
    }

    private fun showVerifyDoorPasscodeDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_door_passcode)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val etInput = dialog.findViewById<EditText>(R.id.etDoorPasscodeInput)
        val btnForgot = dialog.findViewById<TextView>(R.id.btnDoorForgotPassword)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelDoor)
        val btnSubmit = dialog.findViewById<Button>(R.id.btnSubmitDoor)

        btnForgot.visibility = View.VISIBLE
        btnForgot.setOnClickListener {
            dialog.dismiss()
            showMasterEmergencyRecoveryDialog()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSubmit.setOnClickListener {
            val code = etInput.text.toString().trim()
            if (VaultSecurityManager.checkDoorPasscode(this, code)) {
                dialog.dismiss()
                launchDashboardGate()
            } else {
                etInput.text.clear()
                Toast.makeText(this, "Incorrect Door Passcode!", Toast.LENGTH_SHORT).show()
                try { IntruderCamera.takeSecuritySelfie(this) } catch (ignored: Exception) {}
            }
        }

        dialog.show()
    }

    private fun launchDashboardGate() {
        val intent = Intent(this, DashboardActivity::class.java)
        startActivity(intent)
    }

    // ---------------------------------------------------------------------------------------------
    // MASTER EMERGENCY PASSWORD RECOVERY (Security Question)
    // ---------------------------------------------------------------------------------------------

    private fun showMasterEmergencyRecoveryDialog() {
        if (isFinishing || isDestroyed) return

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_security_recovery)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = dialog.findViewById<TextView>(R.id.tvRecoveryTitle)
        val tvQuestion = dialog.findViewById<TextView>(R.id.tvQuestionDisplay)
        val etAnswer = dialog.findViewById<EditText>(R.id.etRecoveryAnswer)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelRecovery)
        val btnSubmit = dialog.findViewById<Button>(R.id.btnSubmitRecovery)

        tvTitle.text = "Emergency Vault Recovery"
        val q = if (VaultSecurityManager.hasSecurityQuestion(this)) {
            VaultSecurityManager.getSecurityQuestion(this)
        } else {
            "What was your first school or favorite keyword?"
        }
        tvQuestion.text = "Security Question:\n$q"

        btnSubmit.setOnClickListener {
            val ans = etAnswer.text.toString().trim()
            if (VaultSecurityManager.verifySecurityAnswer(this, ans) || !VaultSecurityManager.hasSecurityQuestion(this)) {
                dialog.dismiss()
                Toast.makeText(this, "Identity Verified! Choose what to reset.", Toast.LENGTH_SHORT).show()
                showChooseResetTargetDialog()
            } else {
                Toast.makeText(this, "Incorrect answer!", Toast.LENGTH_SHORT).show()
                try { IntruderCamera.takeSecuritySelfie(this) } catch (ignored: Exception) {}
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showChooseResetTargetDialog() {
        val options = arrayOf(
            "Reset Door Passcode (Layer 1)",
            "Reset Vault Master Key (Layer 3)",
            "Reset Both & Open Vault Gate"
        )
        AlertDialog.Builder(this)
            .setTitle("Identity Verified! Choose Action:")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSetDoorPasscodeDialog()
                    1 -> {
                        val intent = Intent(this, DashboardActivity::class.java).apply {
                            putExtra("START_RECOVERY", true)
                        }
                        startActivity(intent)
                    }
                    2 -> {
                        showSetDoorPasscodeDialog()
                        val intent = Intent(this, DashboardActivity::class.java).apply {
                            putExtra("START_RECOVERY", true)
                        }
                        startActivity(intent)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------------------------------------
    // AUDIO RECORDING (Saved in Public Storage: Recordings/MyVoiceRecordings)
    // ---------------------------------------------------------------------------------------------

    private fun handleRecordButtonClick() {
        if (!checkRecordPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), requestRecordAudio)
            return
        }

        if (!isRecording) {
            startAudioRecording()
        } else {
            stopAudioRecording(save = true)
        }
    }

    private fun checkRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Primary storage folder: Standard public Recordings directory so files can be easily
     * browsed and accessed in the phone's Files / Storage app.
     */
    private fun getRecordingsFolder(): File {
        val dirName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Environment.DIRECTORY_RECORDINGS
        } else {
            "Recordings"
        }
        val publicRecordings = Environment.getExternalStoragePublicDirectory(dirName)
        val dir = File(publicRecordings, "MyVoiceRecordings")
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                // Fallback to app external files dir if public root is inaccessible
                val fallbackDir = File(getExternalFilesDir(dirName) ?: filesDir, "Recordings")
                if (!fallbackDir.exists()) fallbackDir.mkdirs()
                return fallbackDir
            }
        }
        return dir
    }

    private fun startAudioRecording() {
        stopAudioPlayback()

        try {
            val folder = getRecordingsFolder()
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val audioFile = File(folder, "REC_$timeStamp.m4a")
            currentRecordFile = audioFile

            @Suppress("DEPRECATION")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(audioFile.absolutePath)
            recorder.prepare()
            recorder.start()

            mediaRecorder = recorder
            isRecording = true
            recordSeconds = 0
            updateTimerDisplay()

            tvRecordStatus.text = "Recording in progress..."
            tvRecordStatus.setTextColor(Color.parseColor("#FF3B30"))
            ivRecordIcon.setImageResource(R.drawable.ic_pause_vector)

            handler.post(recordTimerRunnable)
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(tag, "Failed to start recording: ${e.message}", e)
            Toast.makeText(this, "Recording error: ${e.message}", Toast.LENGTH_SHORT).show()
            isRecording = false
        }
    }

    private fun stopAudioRecording(save: Boolean) {
        if (!isRecording) return

        handler.removeCallbacks(recordTimerRunnable)
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (ignored: Exception) {}
        mediaRecorder = null
        isRecording = false

        tvRecordStatus.text = "Tap red button to record"
        tvRecordStatus.setTextColor(Color.parseColor("#AAAAAA"))
        ivRecordIcon.setImageResource(R.drawable.ic_mic_vector)
        resetWaveforms()

        val file = currentRecordFile
        if (save && file != null && file.exists() && file.length() > 0) {
            // Index file in MediaStore so it appears in Music and Files apps
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)

            recordings.add(0, file)
            adapter.notifyItemInserted(0)
            rvRecordingsList.scrollToPosition(0)
            updateCountDisplay()
            Toast.makeText(this, "Recording saved: ${file.name}", Toast.LENGTH_SHORT).show()
        } else {
            file?.delete()
        }
        currentRecordFile = null
    }

    private fun updateTimerDisplay() {
        val hrs = recordSeconds / 3600
        val mins = (recordSeconds % 3600) / 60
        val secs = recordSeconds % 60
        tvTimer.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", hrs, mins, secs)
    }

    private fun updateRealWaveforms() {
        val recorder = mediaRecorder ?: return
        val maxAmp = try { recorder.maxAmplitude } catch (e: Exception) { 0 }
        val db = if (maxAmp > 0) 20 * log10(maxAmp.toDouble()) else 0.0
        val norm = (db / 90.0).coerceIn(0.1, 1.0)

        for (i in waveBars.indices) {
            val bar = waveBars[i]
            val variance = ((i % 3) * 0.15)
            val h = (36f * (norm + variance).coerceIn(0.15, 1.0)).toInt()
            val params = bar.layoutParams
            params.height = h
            bar.layoutParams = params
        }
    }

    private fun resetWaveforms() {
        val defaultHeights = listOf(10, 18, 26, 16, 32, 20, 28, 14, 8)
        for (i in waveBars.indices) {
            val bar = waveBars[i]
            val params = bar.layoutParams
            params.height = defaultHeights.getOrElse(i) { 16 }
            bar.layoutParams = params
        }
    }

    private fun loadRecordingsFromStorage() {
        recordings.clear()
        val folder = getRecordingsFolder()
        val files = folder.listFiles { f -> f.isFile && f.name.endsWith(".m4a", true) }
        if (files != null) {
            recordings.addAll(files.sortedByDescending { it.lastModified() })
        }
    }

    private fun updateCountDisplay() {
        val count = recordings.size
        tvRecordingsCount.text = "$count Recording${if (count != 1) "s" else ""}"
    }

    // ---------------------------------------------------------------------------------------------
    // AUDIO PLAYBACK (MediaPlayer)
    // ---------------------------------------------------------------------------------------------

    private fun togglePlayAudio(file: File) {
        if (currentPlayingFile == file && mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            adapter.notifyDataSetChanged()
        } else if (currentPlayingFile == file && mediaPlayer != null) {
            mediaPlayer?.start()
            adapter.notifyDataSetChanged()
        } else {
            stopAudioPlayback()
            try {
                val mp = MediaPlayer()
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                mp.start()
                mediaPlayer = mp
                currentPlayingFile = file

                mp.setOnCompletionListener {
                    stopAudioPlayback()
                }
                adapter.notifyDataSetChanged()
            } catch (e: Exception) {
                Log.e(tag, "Playback error: ${e.message}", e)
                Toast.makeText(this, "Playback error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopAudioPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (ignored: Exception) {}
        mediaPlayer = null
        currentPlayingFile = null
        adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------------------------------------
    // SHARING (FileProvider)
    // ---------------------------------------------------------------------------------------------

    private fun shareAudioFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Voice Recording"))
        } catch (e: Exception) {
            Log.e(tag, "Share failed: ${e.message}", e)
            Toast.makeText(this, "Failed to share recording", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // MULTI-SELECTION & BATCH DELETION
    // ---------------------------------------------------------------------------------------------

    private fun enterSelectionMode(firstFile: File) {
        isSelectionMode = true
        selectedFiles.clear()
        selectedFiles.add(firstFile)
        selectionBar.visibility = View.VISIBLE
        updateSelectionCount()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedFiles.clear()
        selectionBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun toggleFileSelection(file: File) {
        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file)
            if (selectedFiles.isEmpty()) {
                exitSelectionMode()
                return
            }
        } else {
            selectedFiles.add(file)
        }
        updateSelectionCount()
        adapter.notifyDataSetChanged()
    }

    private fun selectAllRecordings() {
        if (selectedFiles.size == recordings.size) {
            selectedFiles.clear()
            exitSelectionMode()
        } else {
            selectedFiles.clear()
            selectedFiles.addAll(recordings)
            updateSelectionCount()
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateSelectionCount() {
        tvSelectedCount.text = "${selectedFiles.size} selected"
    }

    private fun confirmDeleteSingle(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete Recording")
            .setMessage("Do you want to delete ${file.name}?")
            .setPositiveButton("Delete") { _, _ ->
                if (currentPlayingFile == file) stopAudioPlayback()
                file.delete()
                val index = recordings.indexOf(file)
                if (index != -1) {
                    recordings.removeAt(index)
                    adapter.notifyItemRemoved(index)
                    updateCountDisplay()
                }
                Toast.makeText(this, "Recording deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSelected() {
        if (selectedFiles.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("Delete ${selectedFiles.size} Recordings")
            .setMessage("Are you sure you want to delete all selected voice recordings?")
            .setPositiveButton("Delete") { _, _ ->
                for (file in selectedFiles) {
                    if (currentPlayingFile == file) stopAudioPlayback()
                    file.delete()
                    recordings.remove(file)
                }
                exitSelectionMode()
                adapter.notifyDataSetChanged()
                updateCountDisplay()
                Toast.makeText(this, "Selected recordings deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------------------------------------
    // ADAPTER FOR RECORDINGS
    // ---------------------------------------------------------------------------------------------

    class RecordingAdapter(
        private val context: Context,
        private val list: List<File>,
        private val selectedSet: Set<File>,
        private val isSelectionMode: () -> Boolean,
        private val isPlaying: (File) -> Boolean,
        private val onPlayClick: (File) -> Unit,
        private val onShareClick: (File) -> Unit,
        private val onDeleteClick: (File) -> Unit,
        private val onItemClick: (File) -> Unit,
        private val onItemLongClick: (File) -> Unit
    ) : RecyclerView.Adapter<RecordingAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvAudioTitle)
            val tvDuration: TextView = view.findViewById(R.id.tvAudioDuration)
            val tvDate: TextView = view.findViewById(R.id.tvAudioDate)
            val btnPlayPause: ImageView = view.findViewById(R.id.btnPlayAudio)
            val btnShare: ImageView = view.findViewById(R.id.btnShareAudio)
            val btnDelete: ImageView = view.findViewById(R.id.btnDeleteRecording)
            val ivSelectCheck: ImageView = view.findViewById(R.id.ivSelectCheck)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_voice_recording, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = list[position]
            holder.tvName.text = file.name

            val sizeKb = file.length() / 1024
            holder.tvDuration.text = "${sizeKb} KB"
            holder.tvDate.text = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(file.lastModified()))

            val inSelection = isSelectionMode()
            if (inSelection) {
                holder.ivSelectCheck.visibility = View.VISIBLE
                val isSelected = selectedSet.contains(file)
                holder.ivSelectCheck.isSelected = isSelected
                holder.btnPlayPause.visibility = View.GONE
                holder.btnShare.visibility = View.GONE
                holder.btnDelete.visibility = View.GONE
            } else {
                holder.ivSelectCheck.visibility = View.GONE
                holder.btnPlayPause.visibility = View.VISIBLE
                holder.btnShare.visibility = View.VISIBLE
                holder.btnDelete.visibility = View.VISIBLE

                val playing = isPlaying(file)
                holder.btnPlayPause.setImageResource(
                    if (playing) R.drawable.ic_pause_vector else R.drawable.ic_play_vector
                )
            }

            holder.btnPlayPause.setOnClickListener { onPlayClick(file) }
            holder.btnShare.setOnClickListener { onShareClick(file) }
            holder.btnDelete.setOnClickListener { onDeleteClick(file) }

            holder.itemView.setOnClickListener { onItemClick(file) }
            holder.itemView.setOnLongClickListener {
                onItemLongClick(file)
                true
            }
        }

        override fun getItemCount(): Int = list.size
    }
}
