// app/src/main/java/com/example/scottishhillnav/routing/AccessConnectorGraphBuilder.kt
package com.example.scottishhillnav.routing

import org.osmdroid.util.GeoPoint
import kotlin.math.*

/**
 * Option C: ACCESS CONNECTORS
 *
 * Creates a temporary graph that injects 2 nodes (start/end access nodes) at the
 * user's tapped lat/lon and connects each to the nearest WALKING/SCRAMBLING network
 * node with a bidirectional edge.
 *
 * This ensures a route can exist from "anywhere" as long as the walking network is present.
 *
 * IMPORTANT PHILOSOPHY:
 * - We do NOT judge suitability (no filtering of routes).
 * - We do NOT pair-snap or replace endpoints with a "better" pair.
 * - We simply provide an access mechanism into the graph.
 */
object AccessConnectorGraphBuilder {

    data class Result(
        val graph: Graph,
        val startId: Int,
        val endId: Int
    )

    private val WALKING_MASK = Capability.WALKING or Capability.SCRAMBLING

    fun build(
        base: Graph,
        startTap: GeoPoint,
        endTap: GeoPoint
    ): Result {

        val nodes = base.nodes.toMutableMap()
        val edges = base.edges.mapValues { it.value.toMutableList() }.toMutableMap()
        val landmarks = base.landmarks.toMutableMap()

        val nextIdStart = (nodes.keys.maxOrNull() ?: 0) + 1
        val nextIdEnd = nextIdStart + 1

        // Insert access nodes at tap locations
        nodes[nextIdStart] = Node(startTap.latitude, startTap.longitude)
        nodes[nextIdEnd] = Node(endTap.latitude, endTap.longitude)

        val startAnchor = nearestWalkingAnchorNode(base, startTap)
        val endAnchor = nearestWalkingAnchorNode(base, endTap)

        // If no anchors exist (edge case), fall back to nearest node of any kind.
        val startConnectTo = startAnchor ?: base.nearestNodeId(startTap.latitude, startTap.longitude)
        val endConnectTo = endAnchor ?: base.nearestNodeId(endTap.latitude, endTap.longitude)

        // If even that fails, return a graph with isolated access nodes (routing will fail cleanly).
        if (startConnectTo != null) {
            connectBidirectional(
                nodes = nodes,
                edges = edges,
                from = nextIdStart,
                to = startConnectTo,
                requiredMask = Capability.WALKING
            )
        }

        if (endConnectTo != null) {
            connectBidirectional(
                nodes = nodes,
                edges = edges,
                from = nextIdEnd,
                to = endConnectTo,
                requiredMask = Capability.WALKING
            )
        }

        val g = Graph(
            nodes = nodes.toMap(),
            edges = edges.mapValues { it.value.toList() },
            landmarks = landmarks.toMap()
        )

        return Result(g, nextIdStart, nextIdEnd)
    }

    /**
     * Find the nearest node that participates in the WALKING/SCRAMBLING network.
     * "Participates" means it is incident to at least one edge allowed by WALKING_MASK.
     */
    private fun nearestWalkingAnchorNode(base: Graph, p: GeoPoint): Int? {
        val anchors = walkingAnchors(base)
        if (anchors.isEmpty()) return null

        var bestId: Int? = null
        var bestD = Double.MAX_VALUE

        for (id in anchors) {
            val n = base.nodes[id] ?: continue
            val d = haversineMeters(p.latitude, p.longitude, n.lat, n.lon)
            if (d < bestD) {
                bestD = d
                bestId = id
            }
        }
        return bestId
    }

    private fun walkingAnchors(base: Graph): Set<Int> {
        val s = HashSet<Int>()
        for ((from, list) in base.edges) {
            for (e in list) {
                if (!Capability.hasAll(WALKING_MASK, e.requiredMask)) continue
                s.add(from)
                s.add(e.to)
            }
        }
        return s
    }

    private fun connectBidirectional(
        nodes: Map<Int, Node>,
        edges: MutableMap<Int, MutableList<Edge>>,
        from: Int,
        to: Int,
        requiredMask: Int
    ) {
        val a = nodes[from] ?: return
        val b = nodes[to] ?: return
        val cost = haversineMeters(a.lat, a.lon, b.lat, b.lon)

        edges.getOrPut(from) { mutableListOf() }.add(
            Edge(to = to, cost = cost, requiredMask = requiredMask)
        )
        edges.getOrPut(to) { mutableListOf() }.add(
            Edge(to = from, cost = cost, requiredMask = requiredMask)
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
