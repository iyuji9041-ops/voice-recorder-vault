package com.my.seekapplication.recorder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.my.seekapplication.R
import com.my.seekapplication.VoiceRecorderActivity
import java.io.IOException

/**
 * VoiceRecordingService: Production-grade Foreground Service with microphone type.
 *
 * Guarantees:
 * 1. Continues recording reliably across activity destruction, backgrounding, and screen rotation.
 * 2. MediaRecorder.pause() and resume() support (API 24+).
 * 3. Accurate, non-drifting elapsed timer via SystemClock.elapsedRealtime().
 * 4. High-frequency 75ms amplitude sampling for real-time waveform drawing.
 * 5. Automatic pause on phone call / audio focus loss.
 * 6. Low storage space protection (stops and saves if disk < 20 MB).
 * 7. Never discards in-progress recording on activity destruction.
 */
class VoiceRecordingService : Service() {

    companion object {
        private const val TAG = "VoiceRecordingService"
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "voice_recording_service_channel"

        const val ACTION_START = "com.my.seekapplication.recorder.START"
        const val ACTION_PAUSE = "com.my.seekapplication.recorder.PAUSE"
        const val ACTION_RESUME = "com.my.seekapplication.recorder.RESUME"
        const val ACTION_STOP_SAVE = "com.my.seekapplication.recorder.STOP_SAVE"
    }

    enum class State {
        IDLE,
        RECORDING,
        PAUSED
    }

    interface RecordingListener {
        fun onStateChanged(state: State)
        fun onTimerTick(elapsedMs: Long)
        fun onAmplitudeTick(amplitude: Int)
        fun onError(message: String)
        fun onRecordingSaved(uri: Uri)
    }

    private val binder = LocalBinder()
    private val listeners = mutableSetOf<RecordingListener>()

    private var mediaRecorder: MediaRecorder? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var currentUri: Uri? = null

    var currentState: State = State.IDLE
        private set

    private var startElapsedRealtime: Long = 0L
    private var accumulatedPauseDuration: Long = 0L
    private var pauseStartTime: Long = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Phone call receiver to automatically pause recording during an incoming/active call
    private val phoneCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                if (stateStr == TelephonyManager.EXTRA_STATE_RINGING ||
                    stateStr == TelephonyManager.EXTRA_STATE_OFFHOOK
                ) {
                    if (currentState == State.RECORDING) {
                        Log.i(TAG, "Phone call detected: pausing recording")
                        pauseRecording()
                    }
                }
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (currentState == State.RECORDING) {
                    Log.i(TAG, "Audio focus lost: pausing recording")
                    pauseRecording()
                }
            }
        }
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (currentState == State.RECORDING) {
                val elapsed = getElapsedRecordingMs()
                val amp = try { mediaRecorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }

                // Check available storage space (minimum 20MB required)
                if (isStorageLow()) {
                    Log.w(TAG, "Low storage space detected: stopping and saving recording")
                    notifyError("Low storage space: recording saved and stopped.")
                    stopRecording(save = true)
                    return
                }

                listeners.forEach {
                    it.onTimerTick(elapsed)
                    it.onAmplitudeTick(amp)
                }
                updateNotification()
                handler.postDelayed(this, 75)
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceRecordingService = this@VoiceRecordingService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                phoneCallReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        } catch (ignored: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP_SAVE -> stopRecording(save = true)
        }
        return START_NOT_STICKY
    }

    fun addListener(listener: RecordingListener) {
        listeners.add(listener)
        listener.onStateChanged(currentState)
        listener.onTimerTick(getElapsedRecordingMs())
    }

    fun removeListener(listener: RecordingListener) {
        listeners.remove(listener)
    }

    fun getElapsedRecordingMs(): Long {
        return when (currentState) {
            State.RECORDING -> {
                val now = SystemClock.elapsedRealtime()
                (now - startElapsedRealtime) - accumulatedPauseDuration
            }
            State.PAUSED -> {
                (pauseStartTime - startElapsedRealtime) - accumulatedPauseDuration
            }
            State.IDLE -> 0L
        }
    }

    @Synchronized
    fun startRecording(): Boolean {
        if (currentState != State.IDLE) return false

        startForegroundServiceNotification()

        if (isStorageLow()) {
            notifyError("Cannot record: storage space is low (less than 20MB free).")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return false
        }

        requestAudioFocus()

        try {
            val (uri, pfd) = AudioStorageManager.createPendingRecording(this)
            currentUri = uri
            currentPfd = pfd

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
            recorder.setOutputFile(pfd.fileDescriptor)
            recorder.prepare()
            recorder.start()

            mediaRecorder = recorder
            currentState = State.RECORDING
            startElapsedRealtime = SystemClock.elapsedRealtime()
            accumulatedPauseDuration = 0L

            updateNotification()

            listeners.forEach { it.onStateChanged(currentState) }
            handler.post(tickerRunnable)

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder: ${e.message}", e)
            cleanupPending()
            currentState = State.IDLE
            notifyError("Failed to start recording: ${e.message}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return false
        }
    }

    @Synchronized
    fun pauseRecording(): Boolean {
        if (currentState != State.RECORDING) return false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.pause()
            }
            currentState = State.PAUSED
            pauseStartTime = SystemClock.elapsedRealtime()

            handler.removeCallbacks(tickerRunnable)
            updateNotification()
            listeners.forEach { it.onStateChanged(currentState) }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing MediaRecorder: ${e.message}", e)
            return false
        }
    }

    @Synchronized
    fun resumeRecording(): Boolean {
        if (currentState != State.PAUSED) return false

        if (isStorageLow()) {
            notifyError("Cannot resume: storage space is critically low.")
            stopRecording(save = true)
            return false
        }

        requestAudioFocus()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
            }
            val pauseDuration = SystemClock.elapsedRealtime() - pauseStartTime
            accumulatedPauseDuration += pauseDuration
            currentState = State.RECORDING

            updateNotification()
            listeners.forEach { it.onStateChanged(currentState) }
            handler.post(tickerRunnable)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming MediaRecorder: ${e.message}", e)
            return false
        }
    }

    @Synchronized
    fun stopRecording(save: Boolean = true): Uri? {
        if (currentState == State.IDLE) return null

        handler.removeCallbacks(tickerRunnable)

        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Exception during MediaRecorder stop/release: ${e.message}")
        }
        mediaRecorder = null

        try {
            currentPfd?.close()
        } catch (ignored: Exception) {}
        currentPfd = null

        val uri = currentUri
        if (uri != null) {
            AudioStorageManager.finishPendingRecording(this, uri, save)
            if (save) {
                listeners.forEach { it.onRecordingSaved(uri) }
            }
        }

        currentUri = null
        currentState = State.IDLE
        abandonAudioFocus()

        stopForeground(STOP_FOREGROUND_REMOVE)
        listeners.forEach { it.onStateChanged(currentState) }

        stopSelf()
        return uri
    }

    private fun isStorageLow(): Boolean {
        return try {
            val stat = StatFs(filesDir.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            availableBytes < (20 * 1024 * 1024L) // 20 MB threshold
        } catch (e: Exception) {
            false
        }
    }

    private fun notifyError(message: String) {
        listeners.forEach { it.onError(message) }
    }

    private fun cleanupPending() {
        try {
            currentPfd?.close()
        } catch (ignored: Exception) {}
        currentPfd = null

        currentUri?.let { uri ->
            AudioStorageManager.finishPendingRecording(this, uri, save = false)
        }
        currentUri = null
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val afr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(playbackAttributes)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()

            audioFocusRequest = afr
            am.requestAudioFocus(afr)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Recorder",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live recording status"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, VoiceRecorderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val textStatus = when (currentState) {
            State.RECORDING -> "Voice Recorder: recording"
            State.PAUSED -> "Voice Recorder: paused"
            State.IDLE -> "Voice Recorder: ready"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Recorder")
            .setContentText(textStatus)
            .setSmallIcon(R.drawable.ic_mic_vector)
            .setOngoing(currentState != State.IDLE)
            .setContentIntent(pendingOpen)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Actions: Pause / Resume, and Stop & Save
        if (currentState == State.RECORDING) {
            val pauseIntent = Intent(this, VoiceRecordingService::class.java).apply { action = ACTION_PAUSE }
            val pendingPause = PendingIntent.getService(this, 1, pauseIntent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(R.drawable.ic_pause_vector, "Pause", pendingPause)
        } else if (currentState == State.PAUSED) {
            val resumeIntent = Intent(this, VoiceRecordingService::class.java).apply { action = ACTION_RESUME }
            val pendingResume = PendingIntent.getService(this, 2, resumeIntent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(R.drawable.ic_play_vector, "Resume", pendingResume)
        }

        val stopIntent = Intent(this, VoiceRecordingService::class.java).apply { action = ACTION_STOP_SAVE }
        val pendingStop = PendingIntent.getService(this, 3, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(R.drawable.ic_save_vector, "Save", pendingStop)

        return builder.build()
    }

    private fun startForegroundServiceNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickerRunnable)
        try {
            unregisterReceiver(phoneCallReceiver)
        } catch (ignored: Exception) {}

        // Never discard an in-progress recording on destroy: save it!
        if (currentState != State.IDLE) {
            stopRecording(save = true)
        }
    }
}
