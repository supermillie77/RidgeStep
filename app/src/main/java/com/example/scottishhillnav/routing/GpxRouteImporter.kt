// app/src/main/java/com/example/scottishhillnav/routing/GpxRouteImporter.kt
package com.example.scottishhillnav.routing

import android.content.Context
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class GpxRouteImporter(
    private val snapMeters: Double = 20.0,
    private val attachMeters: Double = 120.0
) {

    data class ImportSpec(
        val assetName: String,
        val startLandmarkKey: String,
        val endLandmarkKey: String,
        val requiredMask: Int,
        val bidirectional: Boolean = true,
        /**
         * If set, the ordered list of route node IDs is stored in [Graph.routeSequences] under
         * this key. Used by [RouteCandidateGenerator] to build the curated core directly from
         * the GPX track rather than via A*, preventing path conflation when two routes share
         * start/end nodes (e.g. Ben Lomond Tourist & Ptarmigan routes).
         */
        val routeSequenceKey: String? = null
    )

    private data class TrackPoint(
        val lat: Double,
        val lon: Double,
        val ele: Double
    )

    fun importFromAssets(
        context: Context,
        base: Graph,
        spec: ImportSpec
    ): Graph {

        val pts = parseGpxTrackPointsFromAssets(context, spec.assetName)

        if (pts.size < 2) {
            Log.w("GpxRouteImporter", "GPX ${spec.assetName}: <2 track points (size=${pts.size})")
            return base
        }

        return importPoints(base, pts, spec)
    }

    private fun importPoints(base: Graph, pts: List<TrackPoint>, spec: ImportSpec): Graph {

        val nodes = base.nodes.toMutableMap()
        val edges = base.edges.mapValues { it.value.toMutableList() }.toMutableMap()
        val landmarks = base.landmarks.toMutableMap()
        val routeSequences = base.routeSequences.toMutableMap()

        // Snapshot of base graph node IDs BEFORE adding any GPX nodes.
        val baseNodeIds = base.nodes.keys.toSet()

        var nextId = (nodes.keys.maxOrNull() ?: 0) + 1

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
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

        // ── Spatial grid for O(1) nearest-node lookup ─────────────────────────
        // Each cell is ~1.1 km × ~0.7 km at UK latitudes; a 3×3 neighbourhood
        // covers >3 km — far beyond the snap (20 m) and attach (120 m) radii.
        // This reduces nearestWithin from O(140 000) to O(~200) per GPX point.
        val CELL = 0.01   // degrees (~1.1 km)
        fun cellRow(lat: Double) = (lat / CELL).toInt()
        fun cellCol(lon: Double) = (lon / CELL).toInt()

        // Use a flat Long key to avoid Pair boxing overhead
        fun cellKey(row: Int, col: Int): Long = row * 1_000_000L + col

        val grid = HashMap<Long, MutableList<Int>>(nodes.size * 2)
        for ((id, n) in nodes) {
            grid.getOrPut(cellKey(cellRow(n.lat), cellCol(n.lon))) { mutableListOf() }.add(id)
        }

        fun nearestWithin(
            p: TrackPoint,
            withinMeters: Double,
            restrictToBaseOnly: Boolean
        ): Int? {
            var bestId: Int? = null
            var bestD = Double.MAX_VALUE

            val pRow = cellRow(p.lat)
            val pCol = cellCol(p.lon)
            for (dr in -1..1) for (dc in -1..1) {
                val bucket = grid[cellKey(pRow + dr, pCol + dc)] ?: continue
                for (id in bucket) {
                    if (restrictToBaseOnly && id !in baseNodeIds) continue
                    val n = nodes[id] ?: continue
                    val d = haversineMeters(p.lat, p.lon, n.lat, n.lon)
                    if (d < bestD) { bestD = d; bestId = id }
                }
            }
            return if (bestD <= withinMeters) bestId else null
        }

        fun getOrCreateNode(p: TrackPoint): Int {
            val snapped = nearestWithin(p, snapMeters, restrictToBaseOnly = false)
            if (snapped != null) return snapped

            val id = nextId++
            val newNode = Node(p.lat, p.lon, p.ele)
            nodes[id] = newNode
            // Keep grid consistent as new nodes are added
            grid.getOrPut(cellKey(cellRow(newNode.lat), cellCol(newNode.lon))) { mutableListOf() }.add(id)
            return id
        }

        // Build the GPX path node IDs (snapped or created)
        val rawRouteNodeIds = pts
            .map { getOrCreateNode(it) }
            .fold(mutableListOf<Int>()) { acc, id ->
                if (acc.isEmpty() || acc.last() != id) acc.add(id)
                acc
            }

        if (rawRouteNodeIds.size < 2) return base

        // Force-attach the route ends to the BASE graph (deterministic + guarantees connectivity)
        val startAttachId = nearestWithin(pts.first(), attachMeters, restrictToBaseOnly = true)
        val endAttachId = nearestWithin(pts.last(), attachMeters, restrictToBaseOnly = true)

        val routeNodeIds = rawRouteNodeIds.toMutableList()

        if (startAttachId != null) {
            routeNodeIds[0] = startAttachId
        }

        if (endAttachId != null) {
            routeNodeIds[routeNodeIds.lastIndex] = endAttachId
        }

        // Remove accidental duplicates caused by replacement
        val deduped = mutableListOf<Int>()
        for (id in routeNodeIds) {
            if (deduped.isEmpty() || deduped.last() != id) deduped.add(id)
        }

        if (deduped.size < 2) return base

        // Build edges along the route
        for (i in 0 until deduped.size - 1) {

            val a = deduped[i]
            val b = deduped[i + 1]

            val na = nodes[a] ?: continue
            val nb = nodes[b] ?: continue

            val cost = haversineMeters(na.lat, na.lon, nb.lat, nb.lon)

            addEdge(edges, a, b, cost, spec.requiredMask)
            if (spec.bidirectional) addEdge(edges, b, a, cost, spec.requiredMask)
        }

        // Landmark keys must always point to the forced-attached ends (or raw ends if no attach possible)
        landmarks[spec.startLandmarkKey] = deduped.first()
        landmarks[spec.endLandmarkKey] = deduped.last()

        // Store the full ordered node sequence so RouteCandidateGenerator can use it directly
        // as the curated core rather than running A* (which would conflate routes sharing
        // start/end nodes, e.g. Ben Lomond Tourist and Ptarmigan routes).
        spec.routeSequenceKey?.let { routeSequences[it] = deduped.toList() }

        // Helpful debug logging
        Log.i(
            "GpxRouteImporter",
            "Imported ${spec.assetName}: pts=${pts.size} routeNodes=${deduped.size} " +
                "startAttach=${startAttachId != null} endAttach=${endAttachId != null}"
        )

        return Graph(
            nodes = nodes.toMap(),
            edges = edges.mapValues { it.value.toList() },
            landmarks = landmarks.toMap(),
            routeSequences = routeSequences.toMap()
        )
    }

    private fun addEdge(
        edges: MutableMap<Int, MutableList<Edge>>,
        from: Int,
        to: Int,
        cost: Double,
        requiredMask: Int
    ) {
        val list = edges.getOrPut(from) { mutableListOf() }
        if (list.none { it.to == to && it.requiredMask == requiredMask }) {
            list.add(Edge(to = to, cost = cost, requiredMask = requiredMask))
        }
    }

    private fun parseGpxTrackPointsFromAssets(
        context: Context,
        assetName: String
    ): List<TrackPoint> {

        context.assets.open(assetName).use { input ->

            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(input, null)

            val points = ArrayList<TrackPoint>()

            var lat: Double? = null
            var lon: Double? = null
            var ele: Double = 0.0

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {

                when (eventType) {

                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "trkpt" -> {
                                lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                                lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                                ele = 0.0
                            }

                            "ele" -> {
                                ele = parser.nextText().toDoubleOrNull() ?: 0.0
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name == "trkpt") {
                            if (lat != null && lon != null) {
                                points.add(TrackPoint(lat, lon, ele))
                            }
                            lat = null
                            lon = null
                            ele = 0.0
                        }
                    }
                }

                eventType = parser.next()
            }

            return points
        }
    }
}
