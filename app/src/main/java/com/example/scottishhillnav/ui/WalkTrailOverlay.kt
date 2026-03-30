package com.example.scottishhillnav.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Records the user's GPS path and draws it as a semi-transparent orange trail.
 * Points are added via [addPoint]; call [clear] when a new walk begins.
 *
 * Memory safety: the list is capped at [MAX_POINTS]. When the cap is reached every
 * other point is removed (stride-2 decimation), keeping the overall shape while
 * halving memory and draw cost. This repeats as many times as needed, so a very long
 * walk never grows beyond ~2× MAX_POINTS before the next decimation pass.
 */
class WalkTrailOverlay : Overlay() {

    companion object {
        private const val MAX_POINTS = 2_000
    }

    private val points = mutableListOf<GeoPoint>()
    private val pt     = android.graphics.Point()
    private val path   = Path()

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        color       = 0xCCFF6D00.toInt()   // semi-transparent orange
        strokeWidth = 6f
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }
    private val shadowPaint = Paint(trailPaint).apply {
        color       = 0x44000000.toInt()
        strokeWidth = 8f
    }

    fun addPoint(lat: Double, lon: Double) {
        if (points.isNotEmpty()) {
            val last = points.last()
            if (haversine(last.latitude, last.longitude, lat, lon) < 3.0) return
        }
        points.add(GeoPoint(lat, lon))
        if (points.size > MAX_POINTS) decimateInPlace()
    }

    fun clear() = points.clear()

    val pointCount: Int get() = points.size

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || points.size < 2) return
        path.reset()
        var started = false
        for (p in points) {
            mapView.projection.toPixels(p, pt)
            if (!started) { path.moveTo(pt.x.toFloat(), pt.y.toFloat()); started = true }
            else           path.lineTo(pt.x.toFloat(), pt.y.toFloat())
        }
        canvas.drawPath(path, shadowPaint)
        canvas.drawPath(path, trailPaint)
    }

    /** Removes every other point (stride-2 decimation), halving the list in-place. */
    private fun decimateInPlace() {
        val kept = ArrayList<GeoPoint>((points.size + 1) / 2)
        var i = 0
        while (i < points.size) { kept.add(points[i]); i += 2 }
        points.clear()
        points.addAll(kept)
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}
