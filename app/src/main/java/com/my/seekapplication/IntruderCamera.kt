package com.my.seekapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import com.my.seekapplication.crypto.IntruderAuditLog
import com.my.seekapplication.crypto.IntruderPhotoCrypto
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IntruderCamera: Silent front-camera security selfie capture using modern Android Camera2 API.
 *
 * Security & Reliability:
 * 1. Uses android.hardware.camera2 instead of deprecated android.hardware.Camera.
 * 2. Never requests runtime permissions during an unlock attempt (silent, uncoerced).
 * 3. Encrypts photos via IntruderPhotoCrypto (RSA-2048 hybrid public-key encryption),
 *    ensuring photos remain encrypted on disk even while the vault is locked.
 * 4. Logs every attempt into IntruderAuditLog (DOOR, VAULT, RECOVERY).
 * 5. Runs on a dedicated daemon background thread with automatic timeout safety.
 * 6. Completely silent: zero toast, zero screen flash, zero camera sound.
 */
object IntruderCamera {

    private const val TAG = "IntruderCamera"
    private val isCapturing = AtomicBoolean(false)

    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Triggers silent capture on failed unlock attempt.
     *
     * @param context Application or Activity context.
     * @param layer The authentication layer where failure occurred ("DOOR", "VAULT", "RECOVERY").
     * @param attemptNumber Number of failed attempts at this layer.
     */
    @SuppressLint("MissingPermission")
    fun captureIntruderSelfie(
        context: Context,
        layer: String = "VAULT",
        attemptNumber: Int = 1
    ) {
        val appContext = context.applicationContext

        // 1. Check permission silently. Do NOT request permission during unlock!
        if (!hasCameraPermission(appContext)) {
            Log.d(TAG, "Camera permission not granted. Logging audit entry without photo.")
            IntruderAuditLog.logAttempt(
                context = appContext,
                layer = layer,
                attemptNumber = attemptNumber,
                status = "PERMISSION_DENIED"
            )
            return
        }

        // Prevent overlapping captures
        if (!isCapturing.compareAndSet(false, true)) {
            Log.d(TAG, "Camera capture already in progress.")
            return
        }

        val thread = HandlerThread("IntruderCameraThread").apply { start() }
        val handler = Handler(thread.looper)

        // Safety timeout to release resources if camera hangs
        handler.postDelayed({
            if (isCapturing.compareAndSet(true, false)) {
                try { thread.quitSafely() } catch (ignored: Exception) {}
            }
        }, 5000)

        handler.post {
            try {
                val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                if (cameraManager == null) {
                    IntruderAuditLog.logAttempt(appContext, layer, attemptNumber, "CAMERA_UNAVAILABLE")
                    cleanup(thread)
                    return@post
                }

                // Find front camera ID (fallback to back camera)
                var targetCameraId: String? = null
                for (id in cameraManager.cameraIdList) {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        targetCameraId = id
                        break
                    }
                }
                if (targetCameraId == null && cameraManager.cameraIdList.isNotEmpty()) {
                    targetCameraId = cameraManager.cameraIdList[0]
                }

                if (targetCameraId == null) {
                    IntruderAuditLog.logAttempt(appContext, layer, attemptNumber, "CAMERA_UNAVAILABLE")
                    cleanup(thread)
                    return@post
                }

                val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
                imageReader.setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()

                            // Asymmetrically encrypt photo to disk
                            val savedFile = IntruderPhotoCrypto.encryptAndSavePhoto(appContext, bytes)
                            IntruderAuditLog.logAttempt(
                                context = appContext,
                                layer = layer,
                                attemptNumber = attemptNumber,
                                status = if (savedFile != null) "CAPTURED" else "ENCRYPT_ERROR",
                                photoFileName = savedFile?.name
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error acquiring image: ${e.message}", e)
                        IntruderAuditLog.logAttempt(appContext, layer, attemptNumber, "CAPTURE_ERROR")
                    } finally {
                        cleanup(thread)
                    }
                }, handler)

                cameraManager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        try {
                            val surfaces = listOf(imageReader.surface)
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(imageReader.surface)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        }
                                        session.capture(builder.build(), null, handler)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error during capture request: ${e.message}", e)
                                        camera.close()
                                        cleanup(thread)
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    camera.close()
                                    cleanup(thread)
                                }
                            }, handler)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error creating capture session: ${e.message}", e)
                            camera.close()
                            cleanup(thread)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cleanup(thread)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "Camera device error: $error")
                        camera.close()
                        cleanup(thread)
                        IntruderAuditLog.logAttempt(appContext, layer, attemptNumber, "CAMERA_ERROR_$error")
                    }
                }, handler)

            } catch (e: Exception) {
                Log.e(TAG, "Failed initiating Camera2 capture: ${e.message}", e)
                IntruderAuditLog.logAttempt(appContext, layer, attemptNumber, "CAMERA_OPEN_ERROR")
                cleanup(thread)
            }
        }
    }

    private fun cleanup(thread: HandlerThread) {
        isCapturing.set(false)
        try {
            thread.quitSafely()
        } catch (ignored: Exception) {}
    }
}
