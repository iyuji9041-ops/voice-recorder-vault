package com.my.seekapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * ZoomableImageView: Custom ImageView supporting smooth pinch-to-zoom, panning,
 * double-tap zoom, rotation, and photo editing filters.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val imageMatrixCurr = Matrix()
    private val matrixValues = FloatArray(9)

    private val lastTouch = PointF()
    private val startTouch = PointF()

    private var currentScale = 1.0f
    private val minScale = 1.0f
    private val maxScale = 5.0f

    private var isDragging = false

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    var currentRotationDegrees = 0f
        private set
    var isFlippedHorizontal = false
        private set
    var currentFilterMode = 0 // 0: Normal, 1: Grayscale, 2: High Contrast, 3: Warm
        private set

    init {
        scaleType = ScaleType.MATRIX
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val point = PointF(event.x, event.y)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch.set(point)
                startTouch.set(lastTouch)
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(currentScale > 1.05f)
            }

            MotionEvent.ACTION_MOVE -> {
                if (currentScale > 1.05f) {
                    val deltaX = point.x - lastTouch.x
                    val deltaY = point.y - lastTouch.y

                    imageMatrixCurr.postTranslate(deltaX, deltaY)
                    clampTranslation()
                    imageMatrix = imageMatrixCurr
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                lastTouch.set(point.x, point.y)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        return true
    }

    private fun clampTranslation() {
        val drawable = drawable ?: return
        imageMatrixCurr.getValues(matrixValues)

        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val scaleY = matrixValues[Matrix.MSCALE_Y]

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val imageWidth = drawable.intrinsicWidth * scaleX
        val imageHeight = drawable.intrinsicHeight * scaleY

        var minX = 0f
        var maxX = 0f
        if (imageWidth > viewWidth) {
            minX = viewWidth - imageWidth
            maxX = 0f
        } else {
            val centerOffset = (viewWidth - imageWidth) / 2f
            minX = centerOffset
            maxX = centerOffset
        }

        var minY = 0f
        var maxY = 0f
        if (imageHeight > viewHeight) {
            minY = viewHeight - imageHeight
            maxY = 0f
        } else {
            val centerOffset = (viewHeight - imageHeight) / 2f
            minY = centerOffset
            maxY = centerOffset
        }

        val clampedX = transX.coerceIn(minX, maxX)
        val clampedY = transY.coerceIn(minY, maxY)

        matrixValues[Matrix.MTRANS_X] = clampedX
        matrixValues[Matrix.MTRANS_Y] = clampedY
        imageMatrixCurr.setValues(matrixValues)
    }

    fun fitImageToView() {
        val drawable = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return

        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()
        if (imageWidth <= 0 || imageHeight <= 0) return

        val scale = (viewWidth / imageWidth).coerceAtMost(viewHeight / imageHeight)
        val dx = (viewWidth - imageWidth * scale) / 2f
        val dy = (viewHeight - imageHeight * scale) / 2f

        imageMatrixCurr.reset()
        imageMatrixCurr.setScale(scale, scale)
        imageMatrixCurr.postTranslate(dx, dy)
        currentScale = 1.0f
        imageMatrix = imageMatrixCurr
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && currentScale == 1.0f) {
            fitImageToView()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // PHOTO EDITING TOOLS
    // ---------------------------------------------------------------------------------------------

    fun rotate90Clockwise() {
        currentRotationDegrees = (currentRotationDegrees + 90f) % 360f
        applyTransformations()
    }

    fun toggleFlipHorizontal() {
        isFlippedHorizontal = !isFlippedHorizontal
        applyTransformations()
    }

    fun cycleFilterMode(): String {
        currentFilterMode = (currentFilterMode + 1) % 4
        when (currentFilterMode) {
            1 -> {
                // Grayscale
                val matrix = ColorMatrix()
                matrix.setSaturation(0f)
                colorFilter = ColorMatrixColorFilter(matrix)
                return "Filter: Black & White"
            }
            2 -> {
                // High Contrast
                val matrix = ColorMatrix(floatArrayOf(
                    1.4f, 0f, 0f, 0f, -20f,
                    0f, 1.4f, 0f, 0f, -20f,
                    0f, 0f, 1.4f, 0f, -20f,
                    0f, 0f, 0f, 1f, 0f
                ))
                colorFilter = ColorMatrixColorFilter(matrix)
                return "Filter: High Contrast"
            }
            3 -> {
                // Warm / Vivid
                val matrix = ColorMatrix(floatArrayOf(
                    1.2f, 0f, 0f, 0f, 10f,
                    0f, 1.1f, 0f, 0f, 5f,
                    0f, 0f, 0.9f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                ))
                colorFilter = ColorMatrixColorFilter(matrix)
                return "Filter: Warm Vivid"
            }
            else -> {
                // Normal
                colorFilter = null
                return "Filter: Original"
            }
        }
    }

    private fun applyTransformations() {
        val drawable = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return

        val isSwapped = (currentRotationDegrees.toInt() % 180 != 0)
        val effWidth = if (isSwapped) imageHeight else imageWidth
        val effHeight = if (isSwapped) imageWidth else imageHeight

        val scale = (viewWidth / effWidth).coerceAtMost(viewHeight / effHeight)

        imageMatrixCurr.reset()
        // Center pivot
        imageMatrixCurr.postTranslate(-imageWidth / 2f, -imageHeight / 2f)

        if (isFlippedHorizontal) {
            imageMatrixCurr.postScale(-1f, 1f)
        }

        imageMatrixCurr.postRotate(currentRotationDegrees)
        imageMatrixCurr.postScale(scale, scale)
        imageMatrixCurr.postTranslate(viewWidth / 2f, viewHeight / 2f)

        currentScale = 1.0f
        imageMatrix = imageMatrixCurr
    }

    /**
     * Renders the edited image with all current rotation, flip, and filters applied.
     */
    fun createEditedBitmap(): Bitmap? {
        val drawable = drawable as? BitmapDrawable ?: return null
        val srcBitmap = drawable.bitmap ?: return null

        val matrix = Matrix()
        if (isFlippedHorizontal) {
            matrix.postScale(-1f, 1f)
        }
        if (currentRotationDegrees != 0f) {
            matrix.postRotate(currentRotationDegrees)
        }

        var result = Bitmap.createBitmap(srcBitmap, 0, 0, srcBitmap.width, srcBitmap.height, matrix, true)

        if (currentFilterMode != 0) {
            val filtered = Bitmap.createBitmap(result.width, result.height, result.config ?: Bitmap.Config.ARGB_8888)
            val canvas = Canvas(filtered)
            val paint = android.graphics.Paint()
            paint.colorFilter = colorFilter
            canvas.drawBitmap(result, 0f, 0f, paint)
            result = filtered
        }

        return result
    }

    // ---------------------------------------------------------------------------------------------
    // GESTURE LISTENERS
    // ---------------------------------------------------------------------------------------------

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newScale = currentScale * scaleFactor

            if (newScale in minScale..maxScale) {
                currentScale = newScale
                imageMatrixCurr.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                clampTranslation()
                imageMatrix = imageMatrixCurr
            }
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > 1.2f) {
                fitImageToView()
            } else {
                val targetScale = 2.5f
                val scaleFactor = targetScale / currentScale
                currentScale = targetScale
                imageMatrixCurr.postScale(scaleFactor, scaleFactor, e.x, e.y)
                clampTranslation()
                imageMatrix = imageMatrixCurr
            }
            return true
        }
    }
}
