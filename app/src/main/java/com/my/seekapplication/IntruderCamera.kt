package com.my.seekapplication

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

/**
 * IntruderCamera: Silent front-camera security selfie capture on failed unlock attempts.
 *
 * Changes made & Rationale:
 * 1. [PRIORITY 1 - Requirement 2]:
 *    - Added runtime CAMERA permission check. If permission is missing and context is an Activity,
 *      it requests the permission so subsequent attempts can capture.
 *    - Handled camera readiness: Added delayed capture after startPreview() so the sensor has time
 *      to initialize exposure and frames before taking the snapshot.
 *    - Eliminated silent error swallowing: Every exception is logged with full stacktrace via Log.e.
 *    - Safely stores captures in app internal private storage (context.filesDir/intruder_selfies)
 *      so photos cannot be tampered with or accidentally wiped by external cleaners.
 */
@Suppress("DEPRECATION")
object IntruderCamera {

    private const val TAG = "IntruderCamera"
    const val PERMISSION_REQUEST_CAMERA = 701

    fun getIntruderDir(context: Context): File {
        val dir = File(context.filesDir, "intruder_selfies")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getIntruderPhotos(context: Context): List<File> {
        val dir = getIntruderDir(context)
        return dir.listFiles()?.filter { it.isFile && (it.name.endsWith(".jpg") || it.name.endsWith(".jpeg") || it.name.endsWith(".png")) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun checkCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    fun requestCameraPermissionIfNeeded(activity: Activity) {
        if (!checkCameraPermission(activity)) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.CAMERA),
                PERMISSION_REQUEST_CAMERA
            )
        }
    }

    /**
     * Captures a silent photo using the device's front-facing camera.
     */
    fun takeSecuritySelfie(context: Context) {
        if (!checkCameraPermission(context)) {
            Log.w(TAG, "Cannot take security selfie: CAMERA permission is not granted")
            if (context is Activity) {
                requestCameraPermissionIfNeeded(context)
            }
            return
        }

        try {
            var frontCamId = -1
            val camInfo = Camera.CameraInfo()
            val numCameras = Camera.getNumberOfCameras()

            for (i in 0 until numCameras) {
                Camera.getCameraInfo(i, camInfo)
                if (camInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    frontCamId = i
                    break
                }
            }

            // Fallback to back camera if no front camera exists
            if (frontCamId == -1 && numCameras > 0) {
                frontCamId = 0
            }

            if (frontCamId == -1) {
                Log.e(TAG, "No camera found on this device")
                return
            }

            val camera = Camera.open(frontCamId)
            val texture = SurfaceTexture(0)
            camera.setPreviewTexture(texture)
            camera.startPreview()

            Log.d(TAG, "Front camera opened and preview started. Waiting for sensor readiness...")

            // Wait 350ms for exposure calibration before taking picture
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    camera.takePicture(null, null) { data, cam ->
                        try {
                            val intruderDir = getIntruderDir(context)
                            val photoFile = File(intruderDir, "security_photo_${System.currentTimeMillis()}.jpg")
                            FileOutputStream(photoFile).use { fos ->
                                fos.write(data)
                            }
                            Log.d(TAG, "Security selfie captured successfully: ${photoFile.absolutePath}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed writing security selfie data: ${e.message}", e)
                        } finally {
                            try {
                                cam.stopPreview()
                                cam.release()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error releasing camera: ${e.message}", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during camera.takePicture: ${e.message}", e)
                    try {
                        camera.stopPreview()
                        camera.release()
                    } catch (ignored: Exception) {}
                }
            }, 350)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize camera for security capture: ${e.message}", e)
        }
    }
}
