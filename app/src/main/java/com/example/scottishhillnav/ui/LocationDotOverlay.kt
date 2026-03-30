package com.example.scottishhillnav.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.location.Location
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the user's GPS position as an orange teardrop map pin.
 *
 * When [bearing] is set (user is moving):
 *  - The pin rotates so the SHARP TIP points in the direction of travel.
 *  - A translucent orange cone fans forward from the tip.
 *  - A 16-point compass label (N / NE / E … NNW) is drawn inside the
 *    round head of the pin, upright and readable at all orientations.
 */
class LocationDotOverlay(private val density: Float) : Overlay() {

    var location: Location? = null
    /** Direction of travel in degrees (0 = north, clockwise). Null = no bearing known. */
    var bearing: Float? = null

    private val pt       = android.graphics.Point()
    private val pinPath  = Path()
    private val conePath = Path()

    // Pin proportions — headR:tailLen ≈ 1:2.2 matches the app icon silhouette
    private val headR   = 13f * density
    private val tailLen = headR * 2.2f
    private val innerR  = headR * 0.38f   // dark hole radius (hidden when direction label shown)

    // Direction cone: fans 56° (±28°) forward from the tip
    private val coneLen     = headR * 5f
    private val coneHalfDeg = 28f

    private val accuracyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x22FF6D00.toInt()
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
        color = 0xFFFF6D00.toInt()
    }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC3A1500.toInt()
    }
    private val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    // Label inside the head circle — white text, bold, small enough to fit
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style     = Paint.Style.FILL
        color     = 0xFFFFFFFF.toInt()
        textSize  = 8.5f * density
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.DEFAULT_BOLD
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val loc = location ?: return

        mapView.projection.toPixels(GeoPoint(loc.latitude, loc.longitude), pt)
        val tipX = pt.x.toFloat()
        val tipY = pt.y.toFloat()

        // Accuracy ring — always a circle, drawn before any rotation
        if (loc.hasAccuracy()) {
            val mpp = metersPerPixel(mapView, loc.latitude)
            val px  = (loc.accuracy / mpp).toFloat()
            if (px in 10f..400f) {
                canvas.drawCircle(tipX, tipY, px, accuracyFill)
                canvas.drawCircle(tipX, tipY, px, accuracyStroke)
            }
        }

        val bear = bearing
        if (bear != null) {
            // Rotate by (bear + 180°) so the TIP — not the head — faces the direction of travel.
            // At bear=0 (north): without offset, head would be above (north) and tip below (south).
            // Adding 180° flips the pin so tip is above (north) = tip points north. ✓
            canvas.save()
            canvas.rotate(bear + 180f, tipX, tipY)

            // ── Direction cone from the tip ───────────────────────────────────
            // In this rotated canvas the tip (forward direction) points upward (negative Y).
            // The cone fans upward from the tip apex.
            val halfRad = Math.toRadians(coneHalfDeg.toDouble()).toFloat()
            val sinH = sin(halfRad.toDouble()).toFloat()
            val cosH = cos(halfRad.toDouble()).toFloat()

            conePath.reset()
            conePath.moveTo(tipX, tipY)                             // apex = GPS point
            conePath.lineTo(tipX - coneLen * sinH, tipY - coneLen * cosH)  // left edge
            conePath.lineTo(tipX + coneLen * sinH, tipY - coneLen * cosH)  // right edge
            conePath.close()

            // Gradient: opaque orange at the apex, transparent at the far end
            conePaint.shader = RadialGradient(
                tipX, tipY, coneLen,
                intArrayOf(0xAAFF6D00.toInt(), 0x00FF6D00.toInt()),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(conePath, conePaint)

            // ── Pin (drawn over the cone so cone appears to radiate from tip) ─
            buildPinPath(tipX, tipY)
            canvas.drawPath(pinPath, borderPaint)
            canvas.drawPath(pinPath, pinFill)
            // Omit the dark hole — replaced by the direction label drawn below

            canvas.restore()

            // ── Compass label inside the head circle (drawn in screen space) ──
            // Compute head centre in screen coordinates after rotation by (bear+180°).
            // Vector from tip to head in unrotated space: (0, -tailLen).
            // After CW rotation by θ = bear+180:
            //   headSX = tipX + tailLen * sin(θ_rad)
            //   headSY = tipY - tailLen * cos(θ_rad)
            val thetaRad = Math.toRadians((bear + 180.0))
            val headSX = tipX + tailLen * sin(thetaRad).toFloat()
            val headSY = tipY - tailLen * cos(thetaRad).toFloat()

            val label = bearingLabel(bear)
            // Vertically centre the text in the circle
            val textOffset = (labelPaint.descent() - labelPaint.ascent()) / 2f - labelPaint.descent()
            canvas.drawText(label, headSX, headSY + textOffset, labelPaint)

        } else {
            // No bearing — draw static pin with dark hole
            buildPinPath(tipX, tipY)
            canvas.drawPath(pinPath, borderPaint)
            canvas.drawPath(pinPath, pinFill)
            canvas.drawCircle(tipX, tipY - tailLen, innerR, holePaint)
        }
    }

    /** 16-point compass label for a bearing in degrees. */
    private fun bearingLabel(deg: Float): String {
        val dirs = arrayOf(
            "N","NNE","NE","ENE","E","ESE","SE","SSE",
            "S","SSW","SW","WSW","W","WNW","NW","NNW"
        )
        return dirs[((deg + 11.25f) / 22.5f).toInt() % 16]
    }

    private fun buildPinPath(tipX: Float, tipY: Float) {
        val cx = tipX
        val cy = tipY - tailLen

        val sinA = (headR / tailLen).coerceAtMost(1f)
        val cosA = sqrt(1f - sinA * sinA)
        val halfAngleDeg = Math.toDegrees(asin(sinA.toDouble())).toFloat()

        val leftAngle  = 180f - halfAngleDeg
        val sweepAngle = 180f + 2f * halfAngleDeg

        val lx = cx - headR * cosA
        val ly = cy + headR * sinA

        pinPath.reset()
        pinPath.moveTo(lx, ly)
        pinPath.arcTo(cx - headR, cy - headR, cx + headR, cy + headR, leftAngle, sweepAngle, false)
        pinPath.lineTo(tipX, tipY)
        pinPath.close()
    }

    private fun metersPerPixel(mapView: MapView, lat: Double): Double {
        val zoom = mapView.zoomLevelDouble
        return 156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)
    }
}
