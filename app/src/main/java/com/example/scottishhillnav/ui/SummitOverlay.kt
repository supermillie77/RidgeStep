package com.example.scottishhillnav.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.example.scottishhillnav.hills.HillRepository
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Draws an orange triangle marker at each summit in HillRepository.
 * Uses a single custom Overlay (one draw pass) rather than individual Marker
 * objects — no per-summit heap allocation, no tap-interception overhead.
 *
 * Triangles are always visible from zoom 7 upwards.
 * Summit name labels appear at zoom ≥ 11.
 * Triangle size scales with zoom level for readability.
 */
class SummitOverlay(density: Float) : Overlay() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF7000.toInt()   // vivid orange
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        typeface = Typeface.DEFAULT_BOLD
        // Dark halo so text is legible on both light and dark map tiles
        setShadowLayer(3f * density, 0f, 0f, 0xFF222222.toInt())
    }

    private val hills = HillRepository.hills
    private val path = Path()
    private val pt = android.graphics.Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val zoom = mapView.zoomLevelDouble
        if (zoom < 7.0) return   // too zoomed out to be useful

        val showLabels = zoom >= 11.0

        // Circumradius of the equilateral triangle in pixels, scales with zoom
        val r = when {
            zoom >= 14.0 -> 18f * mapView.resources.displayMetrics.density
            zoom >= 12.0 -> 13f * mapView.resources.displayMetrics.density
            zoom >= 10.0 -> 10f * mapView.resources.displayMetrics.density
            zoom >= 8.0  ->  7f * mapView.resources.displayMetrics.density
            else         ->  5f * mapView.resources.displayMetrics.density
        }

        val projection = mapView.projection

        for (hill in hills) {
            projection.toPixels(GeoPoint(hill.summitLat, hill.summitLon), pt)
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()

            // Equilateral triangle, apex pointing up, circumradius = r
            //   Apex:         (x,           y - r)
            //   Bottom-left:  (x - r×0.866, y + r×0.5)
            //   Bottom-right: (x + r×0.866, y + r×0.5)
            path.reset()
            path.moveTo(x,               y - r)
            path.lineTo(x - r * 0.866f,  y + r * 0.5f)
            path.lineTo(x + r * 0.866f,  y + r * 0.5f)
            path.close()

            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, outlinePaint)

            if (showLabels) {
                val label = hill.name
                val tw = labelPaint.measureText(label)
                // Draw label centred above the triangle apex
                canvas.drawText(label, x - tw / 2f, y - r - 4f * mapView.resources.displayMetrics.density, labelPaint)
            }
        }
    }
}
