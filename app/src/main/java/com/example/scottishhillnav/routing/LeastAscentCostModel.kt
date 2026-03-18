package com.example.scottishhillnav.routing

import kotlin.math.max

/**
 * Penalises uphill.
 * cost = distance + ascent * ascentWeight
 */
class LeastAscentCostModel(
    private val terrainService: TerrainService,
    private val ascentWeight: Double = 10.0
) : EdgeCostModel {

    override fun cost(fromNodeId: Int, toNodeId: Int, edge: Edge): Double {
        val elevFrom = terrainService.elevationMeters(fromNodeId)
        val elevTo = terrainService.elevationMeters(toNodeId)
        val ascent = max(0.0, elevTo - elevFrom)
        return edge.cost + ascent * ascentWeight
    }
}