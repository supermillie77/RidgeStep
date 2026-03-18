package com.example.scottishhillnav.routing

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay

class RouteOverlay(
    private val mapView: MapView,
    private val onStartSelected: (GeoPoint) -> Unit,
    private val onEndSelected: (GeoPoint) -> Unit
) : Overlay() {

    private var startPoint: GeoPoint? = null
    private var endPoint: GeoPoint? = null

    /** Full routed geometry (edge-based, ordered) */
    private var routePoints: List<GeoPoint> = emptyList()

    private val startMarker = Marker(mapView).apply {
        title = "Start"
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    }

    private val endMarker = Marker(mapView).apply {
        title = "Finish"
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    }

    private val routePaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    /** Called by routing engine once a real route is computed */
    fun setRoute(points: List<GeoPoint>) {
        routePoints = points
        mapView.invalidate()
    }

    override fun onSingleTapConfirmed(
        e: MotionEvent,
        mapView: MapView
    ): Boolean {

        val point = mapView.projection.fromPixels(
            e.x.toInt(),
            e.y.toInt()
        ) as GeoPoint

        // First tap → start
        if (startPoint == null) {
            clearRouteOnly()
            startPoint = point
            startMarker.position = point
            onStartSelected(point)

            if (!mapView.overlays.contains(startMarker)) {
                mapView.overlays.add(startMarker)
            }

            mapView.invalidate()
            return true
        }

        // Second tap → end
        if (endPoint == null) {
            endPoint = point
            endMarker.position = point
            onEndSelected(point)

            if (!mapView.overlays.contains(endMarker)) {
                mapView.overlays.add(endMarker)
            }

            mapView.invalidate()
            return true
        }

        return false
    }

    override fun onLongPress(
        e: MotionEvent,
        mapView: MapView
    ): Boolean {
        clearAll()
        mapView.invalidate()
        return true
    }

    private fun clearRouteOnly() {
        routePoints = emptyList()
    }

    private fun clearAll() {
        startPoint = null
        endPoint = null
        routePoints = emptyList()
        mapView.overlays.remove(startMarker)
        mapView.overlays.remove(endMarker)
    }

    override fun draw(
        canvas: Canvas,
        mapView: MapView,
        shadow: Boolean
    ) {
        if (shadow) return
        if (routePoints.size < 2) return

        val proj = mapView.projection
        val path = Path()

        val first = proj.toPixels(routePoints.first(), null)
        path.moveTo(first.x.toFloat(), first.y.toFloat())

        for (i in 1 until routePoints.size) {
            val p = proj.toPixels(routePoints[i], null)
            path.lineTo(p.x.toFloat(), p.y.toFloat())
        }

        canvas.drawPath(path, routePaint)
    }

    override fun onTouchEvent(
        event: MotionEvent,
        mapView: MapView
    ): Boolean = false
}