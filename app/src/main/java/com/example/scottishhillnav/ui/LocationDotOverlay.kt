package com.example.scottishhillnav.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Draws the user's GPS position as an orange teardrop map pin — the same style as the
 * app icon — with the sharp tip pointing to the exact coordinate. A white border makes
 * the pin legible on any map tile colour, and a translucent accuracy ring shows GPS
 * uncertainty around the head of the pin.
 *
 * Updated externally via [location]; does not manage its own location provider.
 */
class LocationDotOverlay(density: Float) : Overlay() {

    var location: Location? = null

    private val pt      = android.graphics.Point()
    private val pinPath = Path()

    // ── Dimensions ───────────────────────────────────────────────────────────
    private val headR   = 14f * density   // head circle radius
    private val tailLen = headR * 1.85f   // distance from tip to centre of head
    private val innerR  = headR * 0.38f   // white inner circle radius

    // ── Paints ───────────────────────────────────────────────────────────────
    /** Translucent orange fill for the accuracy ring */
    private val accuracyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x22FF6D00.toInt()
    }
    /** Slightly stronger orange stroke around the accuracy ring */
    private val accuracyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55FF6D00.toInt()
        strokeWidth = 1.5f * density
    }
    /** White border drawn first, slightly wider than the orange, so pin is always visible */
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 3f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap  = Paint.Cap.ROUND
    }
    /** Orange fill — same hue as Munro markers and the app icon */
    private val pinFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFF6D00.toInt()
    }
    /** White inner circle (the "hole" in the pin head) */
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val loc = location ?: return

        mapView.projection.toPixels(GeoPoint(loc.latitude, loc.longitude), pt)
        val tipX = pt.x.toFloat()
        val tipY = pt.y.toFloat()

        // Head centre (above the tip)
        val headCy = tipY - tailLen

        // Accuracy ring centred on the head, not the tip
        if (loc.hasAccuracy()) {
            val mpp = metersPerPixel(mapView, loc.latitude)
            val px  = (loc.accuracy / mpp).toFloat()
            if (px in 10f..400f) {
                canvas.drawCircle(tipX, headCy, px, accuracyFill)
                canvas.drawCircle(tipX, headCy, px, accuracyStroke)
            }
        }

        buildPinPath(tipX, tipY)

        // Draw border first (wide white stroke), then orange fill, then white hole
        canvas.drawPath(pinPath, borderPaint)
        canvas.drawPath(pinPath, pinFill)
        canvas.drawCircle(tipX, headCy, innerR, holePaint)
    }

    /**
     * Builds the teardrop pin path with the sharp tip at ([tipX], [tipY]).
     *
     * Geometry:
     *  - Head centre is at (tipX, tipY − tailLen).
     *  - Tangent points where the sides leave the circle are at ±30° from the
     *    bottom of the circle (i.e., Canvas angles 210° and 330°).
     *  - The arc sweeps 240° counterclockwise from 210° to 330°, going over the
     *    top of the head.
     *  - Two straight lines connect the tangent points down to the tip.
     */
    private fun buildPinPath(tipX: Float, tipY: Float) {
        val cx = tipX
        val cy = tipY - tailLen   // head centre

        // Tangent points at ±30° from the base of the head circle
        //   210° in Canvas coords → (cx − headR·sin30, cy + headR·cos30)
        //   330° in Canvas coords → (cx + headR·sin30, cy + headR·cos30)
        val sin30 = 0.5f
        val cos30 = 0.8660f
        val lx = cx - headR * sin30
        val ly = cy + headR * cos30   // left tangent (Canvas 210°)
        val rx = cx + headR * sin30   // right tangent (Canvas 330°)

        pinPath.reset()
        pinPath.moveTo(lx, ly)
        // Arc over the top: start 210°, sweep −240° (counterclockwise) → arrives at 330°
        pinPath.arcTo(
            cx - headR, cy - headR,
            cx + headR, cy + headR,
            210f, -240f, false
        )
        // Right tangent → tip → back to left tangent (close)
        pinPath.lineTo(rx, ly)   // right tangent has same Y as left by symmetry
        pinPath.lineTo(tipX, tipY)
        pinPath.close()
    }

    private fun metersPerPixel(mapView: MapView, lat: Double): Double {
        val zoom = mapView.zoomLevelDouble
        return 156543.03392 * Math.cos(Math.toRadians(lat)) / Math.pow(2.0, zoom)
    }
}
