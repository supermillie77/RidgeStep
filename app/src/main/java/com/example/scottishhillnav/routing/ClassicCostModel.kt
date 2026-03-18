// app/src/main/java/com/example/scottishhillnav/routing/ClassicCostModel.kt
package com.example.scottishhillnav.routing

/**
 * Baseline cost model: preserves existing routing behaviour.
 */
object ClassicCostModel : EdgeCostModel {
    override fun cost(fromNodeId: Int, toNodeId: Int, edge: Edge): Double = edge.cost
}