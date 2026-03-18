package com.example.scottishhillnav.routing

import com.example.scottishhillnav.DemProvider

/**
 * Elevation lookup with per-node caching.
 * Keeps DEM usage out of the router.
 */
class TerrainService(private val graph: Graph) {

    private val elevationCache = HashMap<Int, Double>()

    fun elevationMeters(nodeId: Int): Double {
        return elevationCache.getOrPut(nodeId) {
            val n = graph.nodes[nodeId] ?: error("Unknown nodeId $nodeId")
            DemProvider.getElevation(n.lat, n.lon)
        }
    }
}