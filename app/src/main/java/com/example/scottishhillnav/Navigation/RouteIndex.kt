package com.example.scottishhillnav.navigation

import com.example.scottishhillnav.routing.Graph
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class RouteIndex(
    private val graph: Graph,
    private val nodeIds: List<Int>
) {

    val cumulativeDistance = DoubleArray(nodeIds.size)
    val cumulativeAscent = DoubleArray(nodeIds.size)

    val totalDistance: Double
    val totalAscent: Double

    init {
        var dist = 0.0
        var ascent = 0.0

        cumulativeDistance[0] = 0.0
        cumulativeAscent[0] = 0.0

        for (i in 1 until nodeIds.size) {

            val a = graph.nodes[nodeIds[i - 1]]!!
            val b = graph.nodes[nodeIds[i]]!!

            val segment = haversine(a.lat, a.lon, b.lat, b.lon)
            dist += segment

            val climb = b.elevation - a.elevation
            if (climb > 0) ascent += climb

            cumulativeDistance[i] = dist
            cumulativeAscent[i] = ascent
        }

        totalDistance = dist
        totalAscent = ascent
    }

    private fun haversine(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a =
            kotlin.math.sin(dLat / 2).pow(2) +
                    kotlin.math.cos(Math.toRadians(lat1)) *
                    kotlin.math.cos(Math.toRadians(lat2)) *
                    kotlin.math.sin(dLon / 2).pow(2)

        return 2 * r * kotlin.math.asin(sqrt(a))
    }
}
