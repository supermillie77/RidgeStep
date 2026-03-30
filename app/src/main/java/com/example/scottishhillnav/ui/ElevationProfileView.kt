package com.example.scottishhillnav.ui

import android.content.Context
import android.graphics.*
import android.view.View
import com.example.scottishhillnav.navigation.ElevationProfileModel

/**
 * Draws an elevation profile chart for the active route.
 *
 * Performance notes:
 *  - All per-profile computation (min/max, Path geometry, label strings, padL) is done
 *    once in [setProfile] and cached as fields. [onDraw] only projects the progress
 *    indicator and issues canvas draw calls — no allocation, no format(), no measureText().
 *  - [setProgressMeters] updates only the progress fraction and triggers an invalidate.
 *  - [noDataPaint] is not mutated inside [onDraw].
 */
class ElevationProfileView(context: Context) : View(context) {

    init {
        setBackgroundColor(0xFF1A1A2E.toInt())  // dark navy — always visible
    }

    // ── Paints ───────────────────────────────────────────────────────────────

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt(); alpha = 100; style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt(); strokeWidth = 3f
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt(); strokeWidth = 2.5f; style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt(); style = Paint.Style.FILL
    }
    private val markerLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt(); textSize = 22f; textAlign = Paint.Align.LEFT
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBDBDBD.toInt(); textSize = 26f; textAlign = Paint.Align.RIGHT
    }
    // noDataPaint is never mutated after construction — textSize is fixed
    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF607D8B.toInt(); textSize = 28f; textAlign = Paint.Align.CENTER
    }
    private val noDataSmallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF607D8B.toInt(); textSize = 20f; textAlign = Paint.Align.CENTER
    }

    // ── Precomputed drawing inputs (set in setProfile / onSizeChanged) ────────

    private var hasData = false
    private var progressFraction = 0f

    // Cached paths — rebuilt in setProfile / onSizeChanged
    private val fillPath = Path()
    private val linePath = Path()

    // Cached layout constants derived from model + view size
    private var padL  = 0f
    private var padR  = 8f
    private var padT  = 12f
    private var padB  = 24f
    private var plotW = 0f
    private var plotH = 0f

    // Cached per-sample x/y arrays for progress indicator positioning
    private var xs: FloatArray = FloatArray(0)
    private var ys: FloatArray = FloatArray(0)

    // Cached label strings so format() is never called in onDraw()
    private var minELabel  = ""
    private var maxELabel  = ""
    private var distLabel  = ""
    private var totalKm    = 0.0
    private var modelRef: ElevationProfileModel? = null

    fun setProfile(profile: ElevationProfileModel, progressMeters: Double) {
        modelRef = profile
        progressFraction = if (profile.totalDistance > 0)
            (progressMeters / profile.totalDistance).toFloat().coerceIn(0f, 1f) else 0f
        rebuildCache()
        invalidate()
    }

    /** Update just the progress indicator without rebuilding the full path cache. */
    fun setProgressMeters(progressMeters: Double) {
        val m = modelRef ?: return
        progressFraction = if (m.totalDistance > 0)
            (progressMeters / m.totalDistance).toFloat().coerceIn(0f, 1f) else 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildCache()
    }

    private fun rebuildCache() {
        val m = modelRef
        if (m == null || m.distances.isEmpty() || width == 0 || height == 0) {
            hasData = false; return
        }

        val total = m.totalDistance.takeIf { it > 0 } ?: 1.0
        val minE  = m.elevations.minOrNull() ?: 0.0
        val maxE  = m.elevations.maxOrNull() ?: 0.0
        val range = maxE - minE

        if (range < 5.0) { hasData = false; return }
        hasData = true

        minELabel = "%.0fm".format(minE)
        maxELabel = "%.0fm".format(maxE)
        totalKm   = total / 1000.0
        distLabel = "%.1f km".format(totalKm)

        padL  = maxOf(labelPaint.measureText(minELabel), labelPaint.measureText(maxELabel)) + 10f
        plotW = width  - padL - padR
        plotH = height - padT - padB

        val size = m.distances.size
        xs = FloatArray(size) { i -> (padL + (m.distances[i] / total * plotW)).toFloat() }
        ys = FloatArray(size) { i -> (padT + (1.0 - (m.elevations[i] - minE) / range) * plotH).toFloat() }

        // Pre-build fill + line paths
        val h = height.toFloat()
        fillPath.reset()
        fillPath.moveTo(xs[0], h - padB)
        fillPath.lineTo(xs[0], ys[0])
        for (i in 1 until size) fillPath.lineTo(xs[i], ys[i])
        fillPath.lineTo(xs[size - 1], h - padB)
        fillPath.close()

        linePath.reset()
        linePath.moveTo(xs[0], ys[0])
        for (i in 1 until size) linePath.lineTo(xs[i], ys[i])
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (!hasData) {
            canvas.drawText("Elevation data not available",
                w / 2f, h / 2f + noDataPaint.textSize / 3f, noDataPaint)
            canvas.drawText("(map pack uses V0 nodes — no altitude)",
                w / 2f, h / 2f + noDataPaint.textSize * 1.5f, noDataSmallPaint)
            return
        }

        // ── Pre-built paths ─────────────────────────────────────────────────
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // ── Progress indicator ───────────────────────────────────────────────
        val px = padL + progressFraction * plotW
        canvas.drawLine(px, padT, px, h - padB, progressPaint)
        val nearIdx = ((progressFraction * (xs.size - 1)).toInt()).coerceIn(0, xs.size - 1)
        val dotY = ys[nearIdx]
        canvas.drawCircle(px, dotY, 9f, dotPaint)
        val elevLabel = "%.0fm".format(modelRef?.elevations?.getOrElse(nearIdx) { 0.0 } ?: 0.0)
        val labelX = if (px + 14f + markerLabelPaint.measureText(elevLabel) < w - padR)
            px + 14f else px - 14f - markerLabelPaint.measureText(elevLabel)
        canvas.drawText(elevLabel, labelX, dotY + markerLabelPaint.textSize / 3f, markerLabelPaint)

        // ── Axis labels ──────────────────────────────────────────────────────
        canvas.drawText(maxELabel, padL - 4f, padT + labelPaint.textSize, labelPaint)
        canvas.drawText(minELabel, padL - 4f, h - padB, labelPaint)
        canvas.drawText(distLabel, w - padR, h - 2f, labelPaint)
    }
}
