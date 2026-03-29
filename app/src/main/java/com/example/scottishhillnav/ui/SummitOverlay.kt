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
 * Draws a colour-coded triangle at every summit in HillRepository.
 * Colour is determined by hill category — see [categoryColor].
 *
 * Single custom Overlay (one draw pass): no per-summit heap allocation,
 * no tap-interception overhead.
 *
 * Triangles visible from zoom 7. Labels at zoom ≥ 11.
 */
class SummitOverlay(density: Float) : Overlay() {

    // Single paint; colour is overwritten per-hill in the draw loop
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        setShadowLayer(3f * density, 0f, 0f, 0xFF222222.toInt())
    }

    private val hills get() = HillRepository.hills
    private val path = Path()
    private val pt   = android.graphics.Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val zoom = mapView.zoomLevelDouble
        if (zoom < 7.0) return

        val showLabels = zoom >= 11.0
        val density    = mapView.resources.displayMetrics.density

        val r = when {
            zoom >= 14.0 -> 18f * density
            zoom >= 12.0 -> 13f * density
            zoom >= 10.0 -> 10f * density
            zoom >= 8.0  ->  7f * density
            else         ->  5f * density
        }

        val projection = mapView.projection

        for (hill in hills) {
            projection.toPixels(GeoPoint(hill.summitLat, hill.summitLon), pt)
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()

            // Equilateral triangle, apex pointing up, circumradius = r
            path.reset()
            path.moveTo(x,              y - r)
            path.lineTo(x - r * 0.866f, y + r * 0.5f)
            path.lineTo(x + r * 0.866f, y + r * 0.5f)
            path.close()

            fillPaint.color = categoryColor(hill.category)
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, outlinePaint)

            if (showLabels) {
                val tw = labelPaint.measureText(hill.name)
                canvas.drawText(hill.name, x - tw / 2f,
                    y - r - 4f * density, labelPaint)
            }
        }
    }

    companion object {
        /**
         * Returns the map marker fill colour for a given hill category.
         * These colours are also used in the onboarding legend and anywhere
         * else a category colour is needed.
         */
        fun categoryColor(category: String): Int = when (category) {
            // Hill categories
            "Munro"          -> 0xFFFF6D00.toInt()   // vivid orange
            "Corbett"        -> 0xFFE53935.toInt()   // red
            "Graham"         -> 0xFFAB47BC.toInt()   // purple
            "Donald"         -> 0xFF43A047.toInt()   // green
            "Fiona"          -> 0xFFFF4081.toInt()   // pink
            "Sub 2000"       -> 0xFFFFD600.toInt()   // amber/yellow
            "Wainwright"     -> 0xFF1E88E5.toInt()   // blue
            "Hewitt"         -> 0xFF00ACC1.toInt()   // cyan
            "Island"         -> 0xFF26A69A.toInt()   // teal
            // Attraction categories
            "Waterfall"      -> 0xFF29B6F6.toInt()   // light blue
            "Castle"         -> 0xFF8D6E63.toInt()   // warm brown
            "Ruins"          -> 0xFFA1887F.toInt()   // lighter brown
            "Monument"       -> 0xFF90A4AE.toInt()   // blue-grey
            "Abbey"          -> 0xFF7986CB.toInt()   // indigo
            "Battlefield"    -> 0xFFEF9A9A.toInt()   // soft red
            "Fort"           -> 0xFFBCAAA4.toInt()   // stone
            "Viewpoint"      -> 0xFF66BB6A.toInt()   // mid green
            "Attraction"     -> 0xFFFFB74D.toInt()   // amber
            "Nature Reserve" -> 0xFF4CAF50.toInt()   // deep green
            "Beach"          -> 0xFFFFF176.toInt()   // sand yellow
            "Cave"           -> 0xFF9E9E9E.toInt()   // grey
            else             -> 0xFFFF7000.toInt()   // fallback orange
        }

        /** All categories in display order, used for legend rendering. */
        val ALL_CATEGORIES = listOf(
            "Munro", "Corbett", "Graham", "Donald",
            "Fiona", "Sub 2000", "Wainwright", "Hewitt", "Island"
        )
    }
}
