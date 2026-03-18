package com.example.scottishhillnav.navigation

import android.location.Location
import com.example.scottishhillnav.routing.Graph
import kotlin.math.pow
import kotlin.math.sqrt

class RouteTrackingEngine(
    private val graph: Graph,
    private val routeIndex: RouteIndex
) {

    var progressDistance = 0.0
        private set

    fun update(location: Location) {

        val lat = location.latitude
        val lon = location.longitude

        var bestDist = Double.MAX_VALUE
        var bestIndex = 0

        val nodes = routeIndex

        for (i in routeIndex.cumulativeDistance.indices) {

            val nodeId = routeIndexNodeId(i)
            val node = graph.nodes[nodeId] ?: continue

            val d =
                (node.lat - lat).pow(2) +
                        (node.lon - lon).pow(2)

            if (d < bestDist) {
                bestDist = d
                bestIndex = i
            }
        }

        progressDistance =
            routeIndex.cumulativeDistance[bestIndex]
    }

    private fun routeIndexNodeId(index: Int): Int {
        return routeIndexNodeIds[index]
    }

    private val routeIndexNodeIds: List<Int>
        get() = routeIndex.javaClass
            .getDeclaredField("nodeIds")
            .apply { isAccessible = true }
            .get(routeIndex) as List<Int>
}
