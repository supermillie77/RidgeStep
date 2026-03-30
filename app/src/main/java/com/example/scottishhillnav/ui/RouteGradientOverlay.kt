// app/src/main/java/com/example/scottishhillnav/ui/RouteGradientOverlay.kt
package com.example.scottishhillnav.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Point
import com.example.scottishhillnav.routing.Graph
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class RouteGradientOverlay(private val graphSupplier: () -> Graph) : Overlay() {

    private val graph get() = graphSupplier()

    private var activeNodeIds: List<Int> = emptyList()
    private var inactiveRoutes: List<List<Int>> = emptyList()

    // Per-segment colours precomputed in setRoute() so draw() does projection + paint only.
    private var segmentColors: IntArray = IntArray(0)

    // Gradient paint for the active route (color set per segment)
    private val gradientPaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
        strokeCap   = Paint.Cap.ROUND
        strokeJoin  = Paint.Join.ROUND
    }

    // White casing behind inactive routes
    private val casingPaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 9f
        isAntiAlias = true
        strokeCap   = Paint.Cap.BUTT
        color       = Color.WHITE
    }

    // Black dotted line for inactive routes (OS footpath style)
    private val dotPaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
        strokeCap   = Paint.Cap.ROUND
        color       = Color.BLACK
        pathEffect  = DashPathEffect(floatArrayOf(4f, 8f), 0f)
    }

    // Reusable pixel points — avoids allocating new Point objects per segment per frame
    private val p1 = Point()
    private val p2 = Point()

    fun setRoute(nodeIds: List<Int>) {
        activeNodeIds = nodeIds
        // Precompute per-segment slope colours once here (O(n) haversine + moving average).
        // draw() then only does projection + paint — no repeated computation per frame.
        val g = graph
        segmentColors = if (nodeIds.size < 2) IntArray(0) else {
            IntArray(nodeIds.size - 1) { i -> slopeToColor(smoothedSlope(nodeIds, i, g)) }
        }
    }

    fun setInactiveRoutes(routes: List<List<Int>>) {
        inactiveRoutes = routes
    }

    fun clear() {
        activeNodeIds  = emptyList()
        inactiveRoutes = emptyList()
        segmentColors  = IntArray(0)
    }

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection

        // Draw inactive routes first (underneath) as black dotted lines
        for (route in inactiveRoutes) {
            if (route.size < 2) continue
            for (i in 0 until route.size - 1) {
                val a = graph.nodes[route[i]]     ?: continue
                val b = graph.nodes[route[i + 1]] ?: continue
                proj.toPixels(GeoPoint(a.lat, a.lon), p1)
                proj.toPixels(GeoPoint(b.lat, b.lon), p2)
                c.drawLine(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat(), casingPaint)
                c.drawLine(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat(), dotPaint)
            }
        }

        // Draw active route with precomputed slope-gradient colouring
        val ids    = activeNodeIds
        val colors = segmentColors
        if (ids.size < 2 || colors.size < ids.size - 1) return
        for (i in 0 until ids.size - 1) {
            val a = graph.nodes[ids[i]]     ?: continue
            val b = graph.nodes[ids[i + 1]] ?: continue
            gradientPaint.color = colors[i]
            proj.toPixels(GeoPoint(a.lat, a.lon), p1)
            proj.toPixels(GeoPoint(b.lat, b.lon), p2)
            c.drawLine(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat(), gradientPaint)
        }
    }

    // ── Precomputation helpers — called from setRoute(), never from draw() ───

    private fun smoothedSlope(ids: List<Int>, index: Int, g: Graph): Double {
        val window    = 12
        val endIndex  = (index + window).coerceAtMost(ids.size - 1)
        val startNode = g.nodes[ids[index]]    ?: return 0.0
        val endNode   = g.nodes[ids[endIndex]] ?: return 0.0
        val startElev = avgElev(ids, index,    window, g)
        val endElev   = avgElev(ids, endIndex, window, g)
        val dist      = haversine(startNode.lat, startNode.lon, endNode.lat, endNode.lon)
        if (dist == 0.0) return 0.0
        return min(abs(endElev - startElev) / dist * 100.0, 35.0)
    }

    private fun avgElev(ids: List<Int>, centre: Int, radius: Int, g: Graph): Double {
        var sum = 0.0; var count = 0
        for (i in (centre - radius).coerceAtLeast(0)..(centre + radius).coerceAtMost(ids.size - 1)) {
            val node = g.nodes[ids[i]] ?: continue
            sum += node.elevation
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun slopeToColor(slope: Double): Int = when {
        slope < 4  -> Color.rgb(0, 200, 0)
        slope < 8  -> lerp(0, 200, 0, 255, 200, 0, (slope - 4) / 4)
        slope < 15 -> lerp(255, 200, 0, 255, 120, 0, (slope - 8) / 7)
        else       -> Color.rgb(200, 0, 0)
    }

    private fun lerp(r1: Int, g1: Int, b1: Int, r2: Int, g2: Int, b2: Int, t: Double): Int {
        val c = t.coerceIn(0.0, 1.0)
        return Color.rgb((r1 + (r2 - r1) * c).toInt(), (g1 + (g2 - g1) * c).toInt(), (b1 + (b2 - b1) * c).toInt())
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r    = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
