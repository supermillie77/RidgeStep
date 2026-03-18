// app/src/main/java/com/example/scottishhillnav/routing/DirectionalBiasCostModel.kt
package com.example.scottishhillnav.routing

import kotlin.math.*

/**
 * Soft directional bias toward the goal.
 *
 * ✅ Per user requirement: intended to be applied ONLY to penalised alternatives.
 *
 * Adds a small penalty for edges that point away from the goal.
 * - aligned to goal => ~0 penalty
 * - sideways => moderate penalty
 * - opposite => max penalty
 *
 * Penalty scales with edge length so tiny wiggles don't get unfairly punished.
 */
class DirectionalBiasCostModel(
    private val graph: Graph,
    private val goalNodeId: Int,
    private val base: EdgeCostModel,
    private val directionWeight: Double = 0.15
) : EdgeCostModel {

    override fun cost(fromNodeId: Int, toNodeId: Int, edge: Edge): Double {
        val baseCost = base.cost(fromNodeId, toNodeId, edge)

        val from = graph.nodes[fromNodeId] ?: return baseCost
        val to = graph.nodes[toNodeId] ?: return baseCost
        val goal = graph.nodes[goalNodeId] ?: return baseCost

        // Vector A: from -> to
        val ax = to.lon - from.lon
        val ay = to.lat - from.lat

        // Vector B: from -> goal
        val bx = goal.lon - from.lon
        val by = goal.lat - from.lat

        val aLen = hypot(ax, ay)
        val bLen = hypot(bx, by)
        if (aLen < 1e-12 || bLen < 1e-12) return baseCost

        // cos(theta) in [-1, 1]
        val cosTheta = ((ax * bx) + (ay * by)) / (aLen * bLen)

        // Deviation in [0..1]:
        // 0 => perfectly aligned (cos=1)
        // 0.5 => perpendicular (cos=0)
        // 1 => opposite (cos=-1)
        val deviation = (1.0 - cosTheta.coerceIn(-1.0, 1.0)) / 2.0

        val edgeLenMeters = haversineMeters(from.lat, from.lon, to.lat, to.lon)
        val penalty = directionWeight * edgeLenMeters * deviation

        return baseCost + penalty
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