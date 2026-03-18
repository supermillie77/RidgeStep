package com.example.scottishhillnav.ui

import android.content.Context
import android.graphics.*
import android.view.View
import com.example.scottishhillnav.navigation.ElevationProfileModel

class ElevationProfileView(context: Context) : View(context) {

    private var model: ElevationProfileModel? = null
    private var progressFraction = 0f

    init {
        setBackgroundColor(0xFF1A1A2E.toInt())  // dark navy — always visible
    }

    // Filled area under the profile line
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        alpha = 100
        style = Paint.Style.FILL
    }
    // Profile line
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    // Progress vertical bar
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    // Dot at current position on the line
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    // Labels for min/max elevation
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBDBDBD.toInt()
        textSize = 26f
    }
    // "No data" message
    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF607D8B.toInt()
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    fun setProfile(profile: ElevationProfileModel, progressMeters: Double) {
        model = profile
        progressFraction = if (profile.totalDistance > 0)
            (progressMeters / profile.totalDistance).toFloat().coerceIn(0f, 1f)
        else 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = model ?: return

        val w = width.toFloat()
        val h = height.toFloat()
        val padL = 48f   // left margin for labels
        val padR = 8f
        val padT = 12f
        val padB = 22f   // bottom margin for labels
        val plotW = w - padL - padR
        val plotH = h - padT - padB

        if (m.distances.isEmpty() || plotW <= 0f || plotH <= 0f) return

        val size  = m.distances.size
        val total = m.totalDistance.takeIf { it > 0 } ?: 1.0
        val minE  = m.elevations.minOrNull() ?: 0.0
        val maxE  = m.elevations.maxOrNull() ?: 0.0
        val range = maxE - minE

        // ── No data fallback ─────────────────────────────────────────────────
        if (range < 5.0) {
            canvas.drawText(
                "Elevation data not available",
                w / 2f, h / 2f + noDataPaint.textSize / 3f,
                noDataPaint
            )
            canvas.drawText(
                "(map pack uses V0 nodes — no altitude)",
                w / 2f, h / 2f + noDataPaint.textSize * 1.5f,
                noDataPaint.also { it.textSize = 20f }
            )
            return
        }

        fun xAt(i: Int) = padL + (m.distances[i] / total * plotW).toFloat()
        fun yAt(i: Int) = padT + ((1.0 - (m.elevations[i] - minE) / range) * plotH).toFloat()

        // ── Filled area ───────────────────────────────────────────────────────
        val fill = Path().apply {
            moveTo(xAt(0), h - padB)
            lineTo(xAt(0), yAt(0))
            for (i in 1 until size) lineTo(xAt(i), yAt(i))
            lineTo(xAt(size - 1), h - padB)
            close()
        }
        canvas.drawPath(fill, fillPaint)

        // ── Profile line ─────────────────────────────────────────────────────
        val line = Path().apply {
            moveTo(xAt(0), yAt(0))
            for (i in 1 until size) lineTo(xAt(i), yAt(i))
        }
        canvas.drawPath(line, linePaint)

        // ── Progress indicator ───────────────────────────────────────────────
        val px = padL + progressFraction * plotW
        canvas.drawLine(px, padT, px, h - padB, progressPaint)
        val nearIdx = ((progressFraction * (size - 1)).toInt()).coerceIn(0, size - 1)
        canvas.drawCircle(px, yAt(nearIdx), 5f, dotPaint)

        // ── Elevation labels (right-aligned in left margin) ───────────────────
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("%.0fm".format(maxE), padL - 4f, padT + labelPaint.textSize, labelPaint)
        canvas.drawText("%.0fm".format(minE), padL - 4f, h - padB, labelPaint)

        // ── Distance label at right ───────────────────────────────────────────
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("%.1f km".format(total / 1000.0), w - padR, h - 2f, labelPaint)
    }
}
