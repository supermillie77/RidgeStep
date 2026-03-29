package com.example.scottishhillnav.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Draws the user's GPS position as an orange teardrop map pin matching the app icon.
 * The sharp tip points to the exact GPS coordinate; the round head sits above it.
 * A translucent orange accuracy ring surrounds the tip when GPS accuracy is meaningful.
 */
class LocationDotOverlay(density: Float) : Overlay() {

    var location: Location? = null

    private val pt      = android.graphics.Point()
    private val pinPath = Path()

    // Pin proportions — headR:tailLen ≈ 1:2.2 matches the app icon silhouette
    private val headR   = 13f * density
    private val tailLen = headR * 2.2f
    private val innerR  = headR * 0.38f   // dark hole in the pin head

    private val accuracyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x22FF6D00.toInt()   // translucent orange
    }
    private val accuracyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x66FF6D00.toInt()
        strokeWidth = 1.5f * density
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap  = Paint.Cap.ROUND
    }
    private val pinFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFF6D00.toInt()   // same orange as app icon and Munro markers
    }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC3A1500.toInt()   // dark brown-black hole, like the icon shadow
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val loc = location ?: return

        mapView.projection.toPixels(GeoPoint(loc.latitude, loc.longitude), pt)
        val tipX = pt.x.toFloat()
        val tipY = pt.y.toFloat()

        // Accuracy ring centred on the GPS point (pin tip)
        if (loc.hasAccuracy()) {
            val mpp = metersPerPixel(mapView, loc.latitude)
            val px  = (loc.accuracy / mpp).toFloat()
            if (px in 10f..400f) {
                canvas.drawCircle(tipX, tipY, px, accuracyFill)
                canvas.drawCircle(tipX, tipY, px, accuracyStroke)
            }
        }

        buildPinPath(tipX, tipY)

        canvas.drawPath(pinPath, borderPaint)
        canvas.drawPath(pinPath, pinFill)
        canvas.drawCircle(tipX, tipY - tailLen, innerR, holePaint)
    }

    /**
     * Builds the teardrop pin path with the sharp tip at (tipX, tipY).
     *
     * Geometry: the half-angle α between the pin's vertical axis and each straight
     * side satisfies sin(α) = headR / tailLen. The two straight sides are true
     * tangents to the circle, touching it at angles (180° − α) and α in Canvas
     * coordinates (where 0°=right, 90°=down, 270°=up). The arc sweeps CLOCKWISE
     * from the left tangent point over the top of the head to the right tangent
     * point — sweep = 360° − 2α (the major arc over the top).
     *
     * Bug that was here before: the arc swept −240° (counterclockwise), which goes
     * through the BOTTOM of the circle and produces an inverted bulge. The correct
     * clockwise sweep is 360° − 2α ≈ 300° for the proportions used here.
     */
    private fun buildPinPath(tipX: Float, tipY: Float) {
        val cx = tipX
        val cy = tipY - tailLen   // head centre

        // True tangent half-angle derived from actual dimensions (not hardcoded)
        val sinA = (headR / tailLen).coerceAtMost(1f)
        val cosA = sqrt(1f - sinA * sinA)
        val halfAngleDeg = Math.toDegrees(asin(sinA.toDouble())).toFloat()

        // Canvas angles of the two tangent points on the circle:
        //   Left  tangent: vector from centre = (−cosA, +sinA) → Canvas angle = 180° − halfAngleDeg
        //   Right tangent: vector from centre = (+cosA, +sinA) → Canvas angle = halfAngleDeg
        val leftAngle  = 180f - halfAngleDeg
        val rightAngle = halfAngleDeg

        // Tangent point coordinates
        val lx = cx - headR * cosA
        val ly = cy + headR * sinA

        // Clockwise arc from left tangent, over the top, to right tangent.
        // Clockwise sweep in Canvas = positive value.
        // Going clockwise from leftAngle to rightAngle over the top (through 270°):
        //   sweep = 360° − (leftAngle − rightAngle) = 360° − (180° − 2·halfAngleDeg)
        //         = 180° + 2·halfAngleDeg
        val sweepAngle = 180f + 2f * halfAngleDeg

        pinPath.reset()
        pinPath.moveTo(lx, ly)
        pinPath.arcTo(
            cx - headR, cy - headR,
            cx + headR, cy + headR,
            leftAngle, sweepAngle, false   // clockwise over the top
        )
        // Arc ends at right tangent point (cx + headR·cosA, cy + headR·sinA)
        pinPath.lineTo(tipX, tipY)   // straight line down to the tip
        pinPath.close()              // close back to left tangent point
    }

    private fun metersPerPixel(mapView: MapView, lat: Double): Double {
        val zoom = mapView.zoomLevelDouble
        return 156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)
    }
}
