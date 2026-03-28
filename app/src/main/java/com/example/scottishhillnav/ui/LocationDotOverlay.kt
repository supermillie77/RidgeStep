package com.example.scottishhillnav.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.location.Location
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Draws the user's current GPS position as a blue dot with a white border
 * and a faint accuracy ring. Updated externally via [update]; does not start
 * its own location provider.
 */
class LocationDotOverlay(density: Float) : Overlay() {

    var location: Location? = null

    private val pt = android.graphics.Point()

    private val accuracyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x302979FF.toInt()   // translucent blue
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()   // white border
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF2979FF.toInt()   // vivid blue
    }

    private val dotRadius    = 7f  * density
    private val borderRadius = 10f * density

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val loc = location ?: return

        mapView.projection.toPixels(GeoPoint(loc.latitude, loc.longitude), pt)
        val x = pt.x.toFloat()
        val y = pt.y.toFloat()

        // Accuracy ring (only when accuracy is meaningful and not too large to be useful)
        if (loc.hasAccuracy()) {
            val accuracyM     = loc.accuracy
            val metersPerPixel = metersPerPixel(mapView, loc.latitude)
            val accuracyPx    = (accuracyM / metersPerPixel).toFloat()
            if (accuracyPx in 10f..300f) {
                canvas.drawCircle(x, y, accuracyPx, accuracyPaint)
            }
        }

        // White border then blue dot
        canvas.drawCircle(x, y, borderRadius, borderPaint)
        canvas.drawCircle(x, y, dotRadius,    dotPaint)
    }

    private fun metersPerPixel(mapView: MapView, lat: Double): Double {
        val zoom = mapView.zoomLevelDouble
        return 156543.03392 * Math.cos(Math.toRadians(lat)) / Math.pow(2.0, zoom)
    }
}
