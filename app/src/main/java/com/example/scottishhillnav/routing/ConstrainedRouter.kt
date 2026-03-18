// app/src/main/java/com/example/scottishhillnav/routing/ConstrainedRouter.kt
package com.example.scottishhillnav.routing

interface ConstrainedRouter {

    data class Result(
        val nodeIds: List<Int>,
        val cost: Double
    )

    fun route(
        startId: Int,
        endId: Int,
        blockedNodes: Set<Int>,
        blockedEdges: Set<Pair<Int, Int>>
    ): Result?

    fun pathCost(nodeIds: List<Int>): Double
}
