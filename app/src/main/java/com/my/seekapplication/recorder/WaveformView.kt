package com.my.seekapplication.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10

/**
 * WaveformView: Real-time dynamic sound amplitude visualizer for Voice Recorder.
 *
 * Guarantees:
 * 1. Live 50-100ms amplitude updates from MediaRecorder.maxAmplitude.
 * 2. Smoothly renders rounded amplitude bars centered vertically.
 * 3. Right-to-left scrolling window simulating high-fidelity hardware voice recorders.
 * 4. Graceful idle resting state.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3B30")
        style = Paint.Style.FILL
    }

    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.FILL
    }

    private val density = resources.displayMetrics.density
    private val barWidth = 4f * density
    private val barSpace = 3f * density
    private val cornerRadius = 2f * density
    private val minBarHeight = 4f * density

    private val amplitudes = ArrayDeque<Float>()
    private val rect = RectF()

    var isRecording: Boolean = false
        set(value) {
            field = value
            barPaint.color = if (value) Color.parseColor("#FF3B30") else Color.parseColor("#00E676")
            invalidate()
        }

    fun addAmplitude(maxAmp: Int) {
        val db = if (maxAmp > 0) 20 * log10(maxAmp.toDouble()) else 0.0
        val normalized = (db / 90.0).coerceIn(0.05, 1.0).toFloat()

        val maxBars = (width / (barWidth + barSpace)).toInt().coerceAtLeast(10)
        while (amplitudes.size >= maxBars) {
            amplitudes.removeFirst()
        }
        amplitudes.addLast(normalized)
        postInvalidateOnAnimation()
    }

    fun clearWaveform() {
        amplitudes.clear()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewHeight = height.toFloat()
        val centerY = viewHeight / 2f
        val step = barWidth + barSpace
        val maxBars = (width / step).toInt().coerceAtLeast(1)

        if (amplitudes.isEmpty()) {
            // Draw baseline idle bars
            val numBars = (width / step).toInt()
            for (i in 0 until numBars) {
                val left = width - (i + 1) * step
                if (left < 0) break
                val right = left + barWidth
                val top = centerY - (minBarHeight / 2f)
                val bottom = centerY + (minBarHeight / 2f)
                rect.set(left, top, right, bottom)
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, idlePaint)
            }
            return
        }

        // Draw newest amplitudes from right to left
        var index = 0
        for (i in amplitudes.indices.reversed()) {
            val amp = amplitudes[i]
            val left = width - (index + 1) * step
            if (left < 0) break
            val right = left + barWidth

            val barHeight = (viewHeight * 0.85f * amp).coerceAtLeast(minBarHeight)
            val top = centerY - (barHeight / 2f)
            val bottom = centerY + (barHeight / 2f)

            rect.set(left, top, right, bottom)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, barPaint)
            index++
        }
    }
}
