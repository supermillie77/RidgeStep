// app/src/main/java/com/example/scottishhillnav/routing/RouteMetricsCalculator.kt
package com.example.scottishhillnav.routing

@Suppress("UNUSED_PARAMETER")
class RouteMetricsCalculator(private val graph: Graph) {

    fun calculate(nodeIds: List<Int>): RouteMetrics {
        // Distance-only baseline; elevation not present
        return RouteMetrics(
            distanceMeters = calculateDistance(nodeIds),
            ascentMeters = 0.0,
            estimatedTimeMinutes = estimateTime(nodeIds)
        )
    }

    fun p95Slope(nodeIds: List<Int>): Double = 0.0

    private fun calculateDistance(nodeIds: List<Int>): Double {
        var dist = 0.0
        for (i in 0 until nodeIds.size - 1) {
            val from = graph.nodes[nodeIds[i]] ?: continue
            val to = graph.nodes[nodeIds[i + 1]] ?: continue
            val dx = from.lat - to.lat
            val dy = from.lon - to.lon
            dist += kotlin.math.sqrt(dx * dx + dy * dy) * 111_000.0
        }
        return dist
    }

    private fun estimateTime(nodeIds: List<Int>): Int {
        return (calculateDistance(nodeIds) / 80.0).toInt()
    }
}
