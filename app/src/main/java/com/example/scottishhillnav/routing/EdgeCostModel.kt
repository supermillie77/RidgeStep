package com.example.scottishhillnav.routing

/**
 * Defines how expensive it is to traverse an edge.
 * Router is agnostic: it just minimises this cost.
 */
interface EdgeCostModel {
    fun cost(fromNodeId: Int, toNodeId: Int, edge: Edge): Double
}