package com.my.seekapplication

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.my.seekapplication.recorder.AudioRecordingItem
import com.my.seekapplication.recorder.AudioStorageManager
import com.my.seekapplication.recorder.VoiceRecordingService
import com.my.seekapplication.recorder.WaveformView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VoiceRecorderActivity: Production-grade Voice Recorder decoy with foreground service,
 * real-time waveform visualization, pause/resume, and covert vault activation.
 *
 * Implements:
 * 1. Background foreground service (microphone type) with persistent notifications.
 * 2. MediaRecorder.pause() & resume() support.
 * 3. Activity Result API for runtime permissions (RECORD_AUDIO & POST_NOTIFICATIONS).
 * 4. Scoped MediaStore storage on Android 10+ with IS_PENDING handling.
 * 5. Accurate SystemClock.elapsedRealtime timer and live 75ms WaveformView updates.
 * 6. Hardened covert secret hold logic: <500ms tap, 500ms-holdDuration neutral deadzone.
 * 7. Audio focus and phone call pause handlers with MediaPlayer lifecycle release on onStop().
 * 8. Recording rename, sharing (restricted FileProvider), and batch deletion.
 */
class VoiceRecorderActivity : AppCompatActivity() {

    private val tag = "VoiceRecorder"
    private val prefFirstTimeTutorial = "has_seen_first_launch_tutorial"

    private lateinit var tvTimer: TextView
    private lateinit var tvRecordStatus: TextView
    private lateinit var tvRecordingsCount: TextView
    private lateinit var btnRecord: RelativeLayout
    private lateinit var ivRecordIcon: ImageView
    private lateinit var btnSaveRecording: ImageView
    private lateinit var btnCancelRecording: ImageView
    private lateinit var waveformView: WaveformView
    private lateinit var rvRecordingsList: RecyclerView
    private lateinit var adapter: RecordingAdapter

    // Multi-Select Bar
    private lateinit var selectionBar: RelativeLayout
    private lateinit var tvSelectedCount: TextView
    private lateinit var btnCloseSelection: ImageView
    private lateinit var btnSelectAll: ImageView
    private lateinit var btnDeleteSelected: ImageView

    private val recordings = ArrayList<AudioRecordingItem>()
    private val selectedItems = HashSet<AudioRecordingItem>()
    private var isSelectionMode = false

    private val handler = Handler(Looper.getMainLooper())
    private var recordingService: VoiceRecordingService? = null
    private var isBound = false

    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingUri: Uri? = null
    private var audioManager: AudioManager? = null
    private var playbackFocusRequest: AudioFocusRequest? = null

    // Covert Secret Hold Logic
    private var isHolding = false
    private var holdStartTime = 0L

    private val holdTimerRunnable = object : Runnable {
        override fun run() {
            if (!isHolding) return
            val requiredHoldMs = VaultSecurityManager.getHoldDurationSeconds(this@VoiceRecorderActivity) * 1000L
            val elapsed = SystemClock.elapsedRealtime() - holdStartTime

            if (elapsed >= requiredHoldMs) {
                isHolding = false
                triggerSecretDoorWithHaptics()
            }
        }
    }

    private val serviceListener = object : VoiceRecordingService.RecordingListener {
        override fun onStateChanged(state: VoiceRecordingService.State) {
            updateUiForState(state)
        }

        override fun onTimerTick(elapsedMs: Long) {
            updateTimerDisplay(elapsedMs)
        }

        override fun onAmplitudeTick(amplitude: Int) {
            waveformView.addAmplitude(amplitude)
        }

        override fun onError(message: String) {
            Toast.makeText(this@VoiceRecorderActivity, message, Toast.LENGTH_SHORT).show()
        }

        override fun onRecordingSaved(uri: Uri) {
            loadRecordingsFromStorage()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? VoiceRecordingService.LocalBinder
            val service = localBinder?.getService()
            recordingService = service
            isBound = true

            service?.addListener(serviceListener)
            service?.currentState?.let { updateUiForState(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService?.removeListener(serviceListener)
            recordingService = null
            isBound = false
        }
    }

    // Modern Activity Result API for Microphone and Notifications
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            startOrToggleRecording()
        } else {
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.RECORD_AUDIO
            )
            if (!shouldShowRationale) {
                showSettingsPermissionDialog()
            } else {
                Toast.makeText(this, "Microphone permission is required to record voice notes.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val playbackAudioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            stopAudioPlayback()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        supportActionBar?.hide()
        enableEdgeToEdge()

        setContentView(R.layout.activity_voice_recorder)

        val root = findViewById<RelativeLayout>(R.id.rootVoiceRecorder)
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

        VaultSecurityManager.migrateLegacyDataIfNeeded(this)
        VaultSecurityManager.restoreConfigIfCleared(this)
        com.my.seekapplication.data.VaultSession.wipeAppCache(this)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        tvTimer = findViewById(R.id.tvTimer)
        tvRecordStatus = findViewById(R.id.tvRecordStatus)
        tvRecordingsCount = findViewById(R.id.tvRecordingsCount)
        btnRecord = findViewById(R.id.btnRecord)
        ivRecordIcon = findViewById(R.id.ivRecordIcon)
        btnSaveRecording = findViewById(R.id.btnSaveRecording)
        btnCancelRecording = findViewById(R.id.btnCancelRecording)
        waveformView = findViewById(R.id.waveformView)
        rvRecordingsList = findViewById(R.id.rvRecordingsList)

        selectionBar = findViewById(R.id.selectionBar)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        btnCloseSelection = findViewById(R.id.btnCloseSelection)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)

        btnCloseSelection.setOnClickListener { exitSelectionMode() }
        btnSelectAll.setOnClickListener { selectAllRecordings() }
        btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }

        // Save & Cancel buttons
        btnSaveRecording.setOnClickListener {
            recordingService?.stopRecording(save = true)
        }

        btnCancelRecording.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Discard Recording?")
                .setMessage("Are you sure you want to discard this recording?")
                .setPositiveButton("Discard") { _, _ ->
                    recordingService?.stopRecording(save = false)
                }
                .setNegativeButton("Keep", null)
                .show()
        }

        // Covert Emergency Password Reset: Tap title 5 times or hold 3s
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

        adapter = RecordingAdapter(
            context = this,
            list = recordings,
            selectedSet = selectedItems,
            isSelectionMode = { isSelectionMode },
            isPlaying = { item -> currentPlayingUri == item.uri && mediaPlayer?.isPlaying == true },
            onPlayClick = { item -> togglePlayAudio(item) },
            onShareClick = { item -> shareAudioFile(item) },
            onDeleteClick = { item -> confirmDeleteSingle(item) },
            onRenameClick = { item -> showRenameDialog(item) },
            onItemClick = { item ->
                if (isSelectionMode) {
                    toggleItemSelection(item)
                } else {
                    togglePlayAudio(item)
                }
            },
            onItemLongClick = { item ->
                if (!isSelectionMode) {
                    enterSelectionMode(item)
                }
            }
        )

        rvRecordingsList.layoutManager = LinearLayoutManager(this)
        rvRecordingsList.adapter = adapter

        loadRecordingsFromStorage()

        // Covert secret hold on Record Button (<500ms tap, 500ms-holdDuration neutral, >=holdDuration secret door)
        btnRecord.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isHolding = true
                    holdStartTime = SystemClock.elapsedRealtime()
                    val requiredHoldMs = VaultSecurityManager.getHoldDurationSeconds(this) * 1000L
                    handler.postDelayed(holdTimerRunnable, requiredHoldMs)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = SystemClock.elapsedRealtime() - holdStartTime
                    val requiredHoldMs = VaultSecurityManager.getHoldDurationSeconds(this) * 1000L
                    handler.removeCallbacks(holdTimerRunnable)

                    if (isHolding) {
                        isHolding = false
                        if (duration < 500) {
                            // Short tap: trigger recording toggle
                            handleRecordButtonClick()
                        }
                        // Between 500ms and requiredHoldMs: do nothing (neutral deadzone to eliminate tell)
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

        showFirstLaunchTutorialIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        com.my.seekapplication.data.VaultSession.lock(this)

        val serviceIntent = Intent(this, VoiceRecordingService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        loadRecordingsFromStorage()
    }

    override fun onStop() {
        super.onStop()
        stopAudioPlayback()

        if (isBound) {
            recordingService?.removeListener(serviceListener)
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioPlayback()
        // Do NOT destroy or cancel background recording service!
    }

    // ---------------------------------------------------------------------------------------------
    // RECORDING CONTROL & PERMISSION FLOW
    // ---------------------------------------------------------------------------------------------

    private fun handleRecordButtonClick() {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(Manifest.permission.RECORD_AUDIO)
            }
            requestPermissionsLauncher.launch(permissions)
            return
        }

        startOrToggleRecording()
    }

    private fun startOrToggleRecording() {
        stopAudioPlayback()
        val service = recordingService

        if (service == null || service.currentState == VoiceRecordingService.State.IDLE) {
            val intent = Intent(this, VoiceRecordingService::class.java).apply {
                action = VoiceRecordingService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } else if (service.currentState == VoiceRecordingService.State.RECORDING) {
            service.pauseRecording()
        } else if (service.currentState == VoiceRecordingService.State.PAUSED) {
            service.resumeRecording()
        }
    }

    private fun updateUiForState(state: VoiceRecordingService.State) {
        when (state) {
            VoiceRecordingService.State.RECORDING -> {
                tvRecordStatus.text = "Recording in progress..."
                tvRecordStatus.setTextColor(Color.parseColor("#FF3B30"))
                ivRecordIcon.setImageResource(R.drawable.ic_pause_vector)
                waveformView.isRecording = true
                btnSaveRecording.visibility = View.VISIBLE
                btnCancelRecording.visibility = View.VISIBLE
            }
            VoiceRecordingService.State.PAUSED -> {
                tvRecordStatus.text = "Recording paused"
                tvRecordStatus.setTextColor(Color.parseColor("#FFA500"))
                ivRecordIcon.setImageResource(R.drawable.ic_play_vector)
                waveformView.isRecording = false
                btnSaveRecording.visibility = View.VISIBLE
                btnCancelRecording.visibility = View.VISIBLE
            }
            VoiceRecordingService.State.IDLE -> {
                tvRecordStatus.text = "Tap red button to record"
                tvRecordStatus.setTextColor(Color.parseColor("#AAAAAA"))
                ivRecordIcon.setImageResource(R.drawable.ic_mic_vector)
                waveformView.isRecording = false
                waveformView.clearWaveform()
                btnSaveRecording.visibility = View.GONE
                btnCancelRecording.visibility = View.GONE
                tvTimer.text = "00:00:00"
            }
        }
    }

    private fun updateTimerDisplay(elapsedMs: Long) {
        val totalSec = elapsedMs / 1000
        val hrs = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        tvTimer.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", hrs, mins, secs)
    }

    private fun showSettingsPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Needed")
            .setMessage("Microphone permission was permanently denied. Please allow it in App Settings to record voice notes.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------------------------------------
    // SECRET DOOR TRIGGER
    // ---------------------------------------------------------------------------------------------

    private fun triggerSecretDoorWithHaptics() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (ignored: Exception) {}

        // Do not touch or discard background recording! Recording keeps going.
        stopAudioPlayback()

        if (!com.my.seekapplication.setup.SetupWizardManager.isSetupCompleted(this)) {
            val intent = Intent(this, com.my.seekapplication.setup.SetupWizardActivity::class.java)
            startActivity(intent)
            return
        }

        if (VaultSecurityManager.isDoorLockedOut(this)) {
            val sec = (VaultSecurityManager.getDoorLockoutRemainingMs(this) / 1000) + 1
            AlertDialog.Builder(this)
                .setTitle("Door Passcode Locked Out")
                .setMessage("Too many failed attempts. Entrance locked for $sec seconds.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        showVerifyDoorPasscodeDialog()
    }

    private fun showVerifyDoorPasscodeDialog() {
        if (VaultSecurityManager.isDoorLockedOut(this)) {
            val sec = (VaultSecurityManager.getDoorLockoutRemainingMs(this) / 1000) + 1
            Toast.makeText(this, "Door locked out for $sec seconds.", Toast.LENGTH_SHORT).show()
            return
        }

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
                val intent = Intent(this, DashboardActivity::class.java)
                startActivity(intent)
            } else {
                etInput.text.clear()
                if (VaultSecurityManager.isDoorLockedOut(this)) {
                    dialog.dismiss()
                    val sec = (VaultSecurityManager.getDoorLockoutRemainingMs(this) / 1000) + 1
                    AlertDialog.Builder(this)
                        .setTitle("Door Locked Out")
                        .setMessage("Too many failed attempts. Entrance locked for $sec seconds.")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    Toast.makeText(this, "Incorrect Door Passcode!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    // ---------------------------------------------------------------------------------------------
    // STORAGE & RECORDINGS MANAGEMENT
    // ---------------------------------------------------------------------------------------------

    private fun loadRecordingsFromStorage() {
        recordings.clear()
        val items = AudioStorageManager.queryAllRecordings(this)
        recordings.addAll(items)
        adapter.notifyDataSetChanged()
        updateCountDisplay()
    }

    private fun updateCountDisplay() {
        val count = recordings.size
        tvRecordingsCount.text = "$count Recording${if (count != 1) "s" else ""}"
    }

    private fun togglePlayAudio(item: AudioRecordingItem) {
        if (currentPlayingUri == item.uri && mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            adapter.notifyDataSetChanged()
        } else if (currentPlayingUri == item.uri && mediaPlayer != null) {
            requestPlaybackAudioFocus()
            mediaPlayer?.start()
            adapter.notifyDataSetChanged()
        } else {
            stopAudioPlayback()
            try {
                requestPlaybackAudioFocus()
                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(this@VoiceRecorderActivity, item.uri)
                    prepare()
                    start()
                    setOnCompletionListener {
                        stopAudioPlayback()
                    }
                }
                mediaPlayer = mp
                currentPlayingUri = item.uri
                adapter.notifyDataSetChanged()
            } catch (e: Exception) {
                Log.e(tag, "Playback error: ${e.message}", e)
                Toast.makeText(this, "Playback error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestPlaybackAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val afr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setOnAudioFocusChangeListener(playbackAudioFocusListener)
                .build()
            playbackFocusRequest = afr
            am.requestAudioFocus(afr)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(playbackAudioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun stopAudioPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (ignored: Exception) {}
        mediaPlayer = null
        currentPlayingUri = null

        val am = audioManager
        if (am != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                playbackFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                playbackFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(playbackAudioFocusListener)
            }
        }

        adapter.notifyDataSetChanged()
    }

    private fun shareAudioFile(item: AudioRecordingItem) {
        try {
            val shareUri = if (item.legacyFile != null && item.legacyFile.exists()) {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", item.legacyFile)
            } else {
                item.uri
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Voice Recording"))
        } catch (e: Exception) {
            Log.e(tag, "Share failed: ${e.message}", e)
            Toast.makeText(this, "Failed to share recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRenameDialog(item: AudioRecordingItem) {
        val input = EditText(this).apply {
            setText(item.name.removeSuffix(".m4a"))
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename Recording")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    if (AudioStorageManager.renameRecording(this, item, newName)) {
                        Toast.makeText(this, "Renamed successfully", Toast.LENGTH_SHORT).show()
                        loadRecordingsFromStorage()
                    } else {
                        Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSingle(item: AudioRecordingItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Recording")
            .setMessage("Do you want to delete ${item.name}?")
            .setPositiveButton("Delete") { _, _ ->
                if (currentPlayingUri == item.uri) stopAudioPlayback()
                AudioStorageManager.deleteRecording(this, item)
                loadRecordingsFromStorage()
                Toast.makeText(this, "Recording deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun enterSelectionMode(firstItem: AudioRecordingItem) {
        isSelectionMode = true
        selectedItems.clear()
        selectedItems.add(firstItem)
        selectionBar.visibility = View.VISIBLE
        updateSelectionCount()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedItems.clear()
        selectionBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun toggleItemSelection(item: AudioRecordingItem) {
        if (selectedItems.contains(item)) {
            selectedItems.remove(item)
            if (selectedItems.isEmpty()) {
                exitSelectionMode()
                return
            }
        } else {
            selectedItems.add(item)
        }
        updateSelectionCount()
        adapter.notifyDataSetChanged()
    }

    private fun selectAllRecordings() {
        if (selectedItems.size == recordings.size) {
            selectedItems.clear()
            exitSelectionMode()
        } else {
            selectedItems.clear()
            selectedItems.addAll(recordings)
            updateSelectionCount()
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateSelectionCount() {
        tvSelectedCount.text = "${selectedItems.size} selected"
    }

    private fun confirmDeleteSelected() {
        if (selectedItems.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("Delete ${selectedItems.size} Recordings")
            .setMessage("Are you sure you want to delete all selected voice recordings?")
            .setPositiveButton("Delete") { _, _ ->
                for (item in selectedItems) {
                    if (currentPlayingUri == item.uri) stopAudioPlayback()
                    AudioStorageManager.deleteRecording(this, item)
                }
                exitSelectionMode()
                loadRecordingsFromStorage()
                Toast.makeText(this, "Selected recordings deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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

    private fun showMasterEmergencyRecoveryDialog() {
        if (isFinishing || isDestroyed) return
        if (VaultSecurityManager.isRecoveryLockedOut(this)) {
            val sec = (VaultSecurityManager.getRecoveryLockoutRemainingMs(this) / 1000) + 1
            Toast.makeText(this, "Recovery locked out. Please wait $sec seconds.", Toast.LENGTH_LONG).show()
            return
        }

        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_security_recovery)
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0.85f)
            val dialogWidth = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost((360 * resources.displayMetrics.density).toInt())
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = dialog.findViewById<TextView>(R.id.tvRecoveryTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvRecoverySubtitle)
        val tvQuestion = dialog.findViewById<TextView>(R.id.tvQuestionDisplay)
        val etAnswer = dialog.findViewById<EditText>(R.id.etRecoveryAnswer)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelRecovery)
        val btnSubmit = dialog.findViewById<Button>(R.id.btnSubmitRecovery)

        tvTitle.text = "Emergency Vault Recovery"
        tvSubtitle.text = "Enter your 16-character offline recovery code"
        tvQuestion.text = "Recovery Code:"
        etAnswer.hint = "XXXX-XXXX-XXXX-XXXX"

        btnSubmit.setOnClickListener {
            val code = etAnswer.text.toString().trim()
            if (code.length < 10) {
                Toast.makeText(this, "Please enter your 16-character recovery code.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (VaultSecurityManager.recoverWithRecoveryCode(this, code)) {
                dialog.dismiss()
                Toast.makeText(this, "Identity Verified!", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, DashboardActivity::class.java).apply {
                    putExtra("START_RECOVERY", true)
                }
                startActivity(intent)
            } else {
                if (VaultSecurityManager.isRecoveryLockedOut(this)) {
                    dialog.dismiss()
                    val sec = (VaultSecurityManager.getRecoveryLockoutRemainingMs(this) / 1000) + 1
                    Toast.makeText(this, "Too many wrong attempts. Recovery locked for $sec seconds.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Invalid Recovery Code!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // ---------------------------------------------------------------------------------------------
    // ADAPTER FOR AUDIO RECORDINGS
    // ---------------------------------------------------------------------------------------------

    class RecordingAdapter(
        private val context: Context,
        private val list: List<AudioRecordingItem>,
        private val selectedSet: Set<AudioRecordingItem>,
        private val isSelectionMode: () -> Boolean,
        private val isPlaying: (AudioRecordingItem) -> Boolean,
        private val onPlayClick: (AudioRecordingItem) -> Unit,
        private val onShareClick: (AudioRecordingItem) -> Unit,
        private val onDeleteClick: (AudioRecordingItem) -> Unit,
        private val onRenameClick: (AudioRecordingItem) -> Unit,
        private val onItemClick: (AudioRecordingItem) -> Unit,
        private val onItemLongClick: (AudioRecordingItem) -> Unit
    ) : RecyclerView.Adapter<RecordingAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvAudioTitle)
            val tvDuration: TextView = view.findViewById(R.id.tvAudioDuration)
            val tvDate: TextView = view.findViewById(R.id.tvAudioDate)
            val btnPlayPause: ImageView = view.findViewById(R.id.btnPlayAudio)
            val btnShare: ImageView = view.findViewById(R.id.btnShareAudio)
            val btnDelete: ImageView = view.findViewById(R.id.btnDeleteRecording)
            val btnRename: ImageView = view.findViewById(R.id.btnRenameRecording)
            val ivSelectCheck: ImageView = view.findViewById(R.id.ivSelectCheck)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_voice_recording, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name

            val sizeKb = item.sizeBytes / 1024
            if (item.durationMs > 0) {
                val durStr = AudioStorageManager.formatDurationMs(item.durationMs)
                holder.tvDuration.text = "$durStr • ${sizeKb} KB"
            } else {
                holder.tvDuration.text = "${sizeKb} KB"
            }
            holder.tvDate.text = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(item.dateModifiedMs))

            val inSelection = isSelectionMode()
            if (inSelection) {
                holder.ivSelectCheck.visibility = View.VISIBLE
                val isSelected = selectedSet.contains(item)
                holder.ivSelectCheck.isSelected = isSelected
                holder.ivSelectCheck.setImageResource(if (isSelected) R.drawable.ic_check_vector else R.drawable.bg_glass_fab_round)
                holder.btnPlayPause.visibility = View.GONE
                holder.btnShare.visibility = View.GONE
                holder.btnDelete.visibility = View.GONE
                holder.btnRename.visibility = View.GONE
            } else {
                holder.ivSelectCheck.visibility = View.GONE
                holder.btnPlayPause.visibility = View.VISIBLE
                holder.btnShare.visibility = View.VISIBLE
                holder.btnDelete.visibility = View.VISIBLE
                holder.btnRename.visibility = View.VISIBLE

                val playing = isPlaying(item)
                holder.btnPlayPause.setImageResource(
                    if (playing) R.drawable.ic_pause_vector else R.drawable.ic_play_vector
                )
            }

            holder.btnPlayPause.setOnClickListener { onPlayClick(item) }
            holder.btnShare.setOnClickListener { onShareClick(item) }
            holder.btnDelete.setOnClickListener { onDeleteClick(item) }
            holder.btnRename.setOnClickListener { onRenameClick(item) }

            holder.itemView.setOnClickListener { onItemClick(item) }
            holder.itemView.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }

        override fun getItemCount(): Int = list.size
    }
}
