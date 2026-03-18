// app/src/main/java/com/example/scottishhillnav/routing/AccessConnector.kt
package com.example.scottishhillnav.routing

import android.graphics.Color
import android.graphics.DashPathEffect
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import kotlin.math.*

/**
 * ACCESS CONNECTOR
 *
 * Represents explicit off-graph access legs from user tap points
 * to chosen graph nodes, used to guarantee "always produce a route".
 *
 * Behavioural contract:
 *  - Access legs are straight-line (haversine) connectors
 *  - Main route remains a real A* graph route
 *  - Drawing is dashed and visually distinct
 *  - Zero or near-zero legs are suppressed by threshold
 *
 * This class contains NO routing logic beyond pair selection.
 */
class AccessConnector(
    val startTap: GeoPoint,
    val endTap: GeoPoint,
    val startNode: GeoPoint,
    val endNode: GeoPoint,
    val startMeters: Double,
    val endMeters: Double
) {

    companion object {
        private const val DRAW_THRESHOLD_METERS = 30.0

        /**
         * Choose a routable graph node pair, allowing access legs.
         * Behaviour is identical to baseline logic.
         */
        fun resolve(
            startTap: GeoPoint,
            endTap: GeoPoint,
            graph: Graph,
            router: AStarRouter
        ): Pair<Int, Int>? {

            fun nearestNodes(p: GeoPoint, k: Int): List<Int> =
                graph.nodes.entries
                    .asSequence()
                    .map { (id, n) ->
                        id to haversineMeters(p.latitude, p.longitude, n.lat, n.lon)
                    }
                    .sortedBy { it.second }
                    .take(k)
                    .map { it.first }
                    .toList()

            val endNearest = nearestNodes(endTap, 1).firstOrNull() ?: return null
            val startNearest = nearestNodes(startTap, 1).firstOrNull() ?: return null

            // Pass 1: fix end
            run {
                val aCandidates = nearestNodes(startTap, min(5000, graph.nodes.size))
                for (a in aCandidates) {
                    val rr = router.route(a, endNearest)
                    if (rr != null && rr.nodeIds.size >= 2) return a to endNearest
                }
            }

            // Pass 2: fix start
            run {
                val bCandidates = nearestNodes(endTap, min(5000, graph.nodes.size))
                for (b in bCandidates) {
                    val rr = router.route(startNearest, b)
                    if (rr != null && rr.nodeIds.size >= 2) return startNearest to b
                }
            }

            // Pass 3: bounded fallback
            val aCandidates = nearestNodes(startTap, min(300, graph.nodes.size))
            val bCandidates = nearestNodes(endTap, min(300, graph.nodes.size))
            for (a in aCandidates) {
                for (b in bCandidates) {
                    val rr = router.route(a, b)
                    if (rr != null && rr.nodeIds.size >= 2) return a to b
                }
            }

            return null
        }

        fun build(
            startTap: GeoPoint,
            endTap: GeoPoint,
            startNode: GeoPoint,
            endNode: GeoPoint
        ): AccessConnector {
            val startMeters = haversineMeters(
                startTap.latitude, startTap.longitude,
                startNode.latitude, startNode.longitude
            )
            val endMeters = haversineMeters(
                endTap.latitude, endTap.longitude,
                endNode.latitude, endNode.longitude
            )

            return AccessConnector(
                startTap,
                endTap,
                startNode,
                endNode,
                startMeters,
                endMeters
            )
        }

        private fun haversineMeters(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double
        ): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a =
                sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) *
                    cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }
    }

    fun draw(map: MapView): Pair<Polyline?, Polyline?> {
        var startLine: Polyline? = null
        var endLine: Polyline? = null

        if (startMeters > DRAW_THRESHOLD_METERS) {
            startLine = addAccessLine(map, startTap, startNode)
        }
        if (endMeters > DRAW_THRESHOLD_METERS) {
            endLine = addAccessLine(map, endTap, endNode)
        }

        return startLine to endLine
    }

    private fun addAccessLine(
        map: MapView,
        a: GeoPoint,
        b: GeoPoint
    ): Polyline =
        Polyline().apply {
            setPoints(listOf(a, b))
            outlinePaint.color = Color.LTGRAY
            outlinePaint.strokeWidth = 6f
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
            map.overlays.add(this)
        }
}
