package com.example.scottishhillnav.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
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
import kotlin.math.sqrt

/**
 * Draws the user's GPS position as an orange teardrop map pin matching the app icon.
 *
 * When [bearing] is set:
 *  - The pin rotates so the head points in the direction of travel.
 *  - A translucent direction-cone (like Google Maps) fans out from the pin head.
 *  - A compass-direction label (N / NE / E … NNW etc.) is drawn below the pin tip.
 */
class LocationDotOverlay(private val density: Float) : Overlay() {

    var location: Location? = null
    /** Direction of travel in degrees (0 = north, clockwise). Null = no bearing known. */
    var bearing: Float? = null

    private val pt      = android.graphics.Point()
    private val pinPath = Path()
    private val conePath = Path()

    // Pin proportions — headR:tailLen ≈ 1:2.2 matches the app icon silhouette
    private val headR   = 13f * density
    private val tailLen = headR * 2.2f
    private val innerR  = headR * 0.38f

    // Direction cone dimensions
    private val coneLen    = headR * 4.5f   // how far the cone reaches from the head centre
    private val coneHalfDeg = 28f           // half-angle of the cone (56° total)

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
    // Cone: bright orange in the centre fading to transparent at the edges
    private val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC0D1B2A.toInt()
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFF6D00.toInt()
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val loc = location ?: return

        mapView.projection.toPixels(GeoPoint(loc.latitude, loc.longitude), pt)
        val tipX = pt.x.toFloat()
        val tipY = pt.y.toFloat()

        // Accuracy ring (drawn before rotation — it's always a circle)
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
            canvas.save()
            canvas.rotate(bear, tipX, tipY)

            // ── Direction cone ────────────────────────────────────────────────
            // The head centre is at (tipX, tipY - tailLen).
            // The cone fans UPWARD from the head (which is pointing in the travel direction).
            val hcx = tipX
            val hcy = tipY - tailLen

            // Build cone path in "pin-up" space (bearing already applied by canvas.rotate)
            val halfRad = Math.toRadians(coneHalfDeg.toDouble()).toFloat()
            val tipConeX = hcx
            val tipConeY = hcy - coneLen  // cone apex above the head
            val leftX  = hcx - coneLen * Math.sin(halfRad.toDouble()).toFloat()
            val leftY  = hcy - coneLen * Math.cos(halfRad.toDouble()).toFloat()
            val rightX = hcx + coneLen * Math.sin(halfRad.toDouble()).toFloat()
            val rightY = hcy - coneLen * Math.cos(halfRad.toDouble()).toFloat()

            conePath.reset()
            conePath.moveTo(hcx, hcy)
            conePath.lineTo(leftX, leftY)
            conePath.lineTo(rightX, rightY)
            conePath.close()

            // Radial gradient: opaque orange at the head → transparent at the tip
            conePaint.shader = RadialGradient(
                hcx, hcy, coneLen,
                intArrayOf(0xAAFF6D00.toInt(), 0x00FF6D00.toInt()),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(conePath, conePaint)
        }

        // ── Pin (drawn inside rotation block if bearing is set) ───────────────
        buildPinPath(tipX, tipY)
        canvas.drawPath(pinPath, borderPaint)
        canvas.drawPath(pinPath, pinFill)
        canvas.drawCircle(tipX, tipY - tailLen, innerR, holePaint)

        if (bear != null) canvas.restore()

        // ── Compass label (drawn AFTER restore, in screen space) ─────────────
        if (bear != null) {
            val label   = bearingLabel(bear)
            val textW   = labelTextPaint.measureText(label)
            val padH    = 4f * density
            val padV    = 2f * density
            val bgLeft  = tipX - textW / 2f - padH
            val bgRight = tipX + textW / 2f + padH
            val bgTop   = tipY + tailLen * 0.3f
            val bgBot   = bgTop + labelTextPaint.textSize + padV * 2f
            val radius  = 3f * density
            canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBot, radius, radius, labelBgPaint)
            canvas.drawText(label, tipX, bgBot - padV, labelTextPaint)
        }
    }

    /** 16-point compass label for a bearing in degrees. */
    private fun bearingLabel(deg: Float): String {
        val dirs = arrayOf(
            "N","NNE","NE","ENE","E","ESE","SE","SSE",
            "S","SSW","SW","WSW","W","WNW","NW","NNW"
        )
        val index = ((deg + 11.25f) / 22.5f).toInt() % 16
        return dirs[index]
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
        pinPath.arcTo(
            cx - headR, cy - headR,
            cx + headR, cy + headR,
            leftAngle, sweepAngle, false
        )
        pinPath.lineTo(tipX, tipY)
        pinPath.close()
    }

    private fun metersPerPixel(mapView: MapView, lat: Double): Double {
        val zoom = mapView.zoomLevelDouble
        return 156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)
    }
}
