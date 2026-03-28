// app/src/main/java/com/example/scottishhillnav/routing/RouteMetricsCalculator.kt
package com.example.scottishhillnav.routing

import kotlin.math.*

@Suppress("UNUSED_PARAMETER")
class RouteMetricsCalculator(private val graph: Graph) {

    fun calculate(nodeIds: List<Int>): RouteMetrics {
        return RouteMetrics(
            distanceMeters         = calculateDistance(nodeIds),
            ascentMeters           = 0.0,
            estimatedTimeMinutes   = estimateTime(nodeIds)
        )
    }

    fun p95Slope(nodeIds: List<Int>): Double = 0.0

    /**
     * Haversine distance along the route in metres.
     *
     * Previously used `sqrt(dLat²+dLon²) × 111_000` which:
     *  - ignores the cos(lat) shrinkage of longitude degrees at ~56°N (~44 % error on E-W legs)
     *  - treats lat and lon as a Cartesian plane (overestimates diagonal paths)
     * Replacing with the same haversine used throughout the rest of the codebase gives
     * correct distances and therefore correct Naismith time estimates.
     */
    private fun calculateDistance(nodeIds: List<Int>): Double {
        var dist = 0.0
        for (i in 0 until nodeIds.size - 1) {
            val from = graph.nodes[nodeIds[i]]     ?: continue
            val to   = graph.nodes[nodeIds[i + 1]] ?: continue
            dist += haversine(from.lat, from.lon, to.lat, to.lon)
        }
        return dist
    }

    /** Naismith walking speed proxy: ~4.8 km/h → 80 m/min. */
    private fun estimateTime(nodeIds: List<Int>): Int =
        (calculateDistance(nodeIds) / 80.0).toInt()

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r    = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
