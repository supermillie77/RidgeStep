package com.example.scottishhillnav.routing

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Soft "mountain sense" bias for alternative routes:
 * - prefer movement that heads toward the goal (reduces wandering laterally)
 * - discourage moving away from the goal
 * - optionally discourage downhill steps (helps stop detours that drop then re-climb)
 *
 * This does NOT forbid anything — it just nudges costs.
 */
class GoalDirectedCostModel(
    private val graph: Graph,
    private val base: EdgeCostModel,
    private val goalNodeId: Int,
    private val terrainService: TerrainService? = null,

    // Penalties (tuned to be gentle by default)
    private val sidewaysWeight: Double = 0.35,
    private val awayWeight: Double = 1.25,
    private val downhillWeight: Double = 0.015
) : EdgeCostModel {

    override fun cost(fromNodeId: Int, toNodeId: Int, edge: Edge): Double {
        val baseCost = base.cost(fromNodeId, toNodeId, edge)

        val from = graph.nodes[fromNodeId] ?: return baseCost
        val to = graph.nodes[toNodeId] ?: return baseCost
        val goal = graph.nodes[goalNodeId] ?: return baseCost

        // Vector (from -> to)
        val ex = (to.lon - from.lon)
        val ey = (to.lat - from.lat)

        // Vector (from -> goal)
        val gx = (goal.lon - from.lon)
        val gy = (goal.lat - from.lat)

        val eLen = sqrt(ex * ex + ey * ey)
        val gLen = sqrt(gx * gx + gy * gy)

        if (eLen <= 0.0 || gLen <= 0.0) return baseCost

        // Cosine similarity between edge direction and goal direction
        val cos = (ex * gx + ey * gy) / (eLen * gLen)

        var extra = 0.0

        // Penalise moving away from the goal (cos < 0)
        if (cos < 0.0) {
            extra += (-cos) * awayWeight * baseCost
        } else {
            // Penalise sideways movement (cos near 0), but gently
            // (1 - cos) is ~0 when aligned, ~1 when sideways
            extra += (1.0 - cos) * sidewaysWeight * baseCost
        }

        // Optional downhill discouragement
        val ts = terrainService
        if (ts != null) {
            val elevFrom = ts.elevationMeters(fromNodeId)
            val elevTo = ts.elevationMeters(toNodeId)
            val delta = elevTo - elevFrom
            if (delta < 0.0) {
                extra += (-delta) * downhillWeight
            }
        }

        return baseCost + extra
    }
}