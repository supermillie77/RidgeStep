// app/src/main/java/com/example/scottishhillnav/routing/CapabilityFilteredRouter.kt
package com.example.scottishhillnav.routing

/**
 * Restricts routing to edges allowed by allowedMask.
 * Used for walking/scrambling K-shortest so it cannot traverse mountaineering GPX edges.
 */
class CapabilityFilteredRouter(
    private val router: AStarRouter,
    private val allowedMask: Int
) : ConstrainedRouter {

    override fun route(
        startId: Int,
        endId: Int,
        blockedNodes: Set<Int>,
        blockedEdges: Set<Pair<Int, Int>>
    ): ConstrainedRouter.Result? {
        return router.routeWithCapabilities(
            startId = startId,
            endId = endId,
            allowedMask = allowedMask,
            blockedNodes = blockedNodes,
            blockedEdges = blockedEdges
        )
    }

    override fun pathCost(nodeIds: List<Int>): Double = router.pathCost(nodeIds)
}
