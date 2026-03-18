// app/src/main/java/com/example/scottishhillnav/routing/PenalizedCostModel.kt
package com.example.scottishhillnav.routing

/**
 * Wraps a base cost model and applies a multiplier to any edge in [penalizedEdges].
 * This is used to force alternative routes (Google-Maps-style "avoid the chosen corridor").
 */
class PenalizedCostModel(
    private val base: EdgeCostModel,
    private val penalizedEdges: Set<Long>,
    private val penaltyMultiplier: Double
) : EdgeCostModel {

    override fun cost(fromNodeId: Int, toNodeId: Int, edge: Edge): Double {
        val baseCost = base.cost(fromNodeId, toNodeId, edge)
        val key = edgeKey(fromNodeId, toNodeId)
        return if (penalizedEdges.contains(key)) baseCost * penaltyMultiplier else baseCost
    }

    companion object {
        /**
         * Directed edge key. (from,to) and (to,from) are different.
         * This matches typical directed graph routing behaviour.
         */
        fun edgeKey(from: Int, to: Int): Long {
            return (from.toLong() shl 32) xor (to.toLong() and 0xFFFFFFFFL)
        }
    }
}