// app/src/main/java/com/example/scottishhillnav/routing/DivergenceAnchorRouteGenerator.kt
package com.example.scottishhillnav.routing

import kotlin.math.abs

/**
 * Generates a single human-like alternative route by forcing a divergence
 * at a meaningful point along the base route.
 *
 * Philosophy:
 * - Find a stable anchor ~40–55% along the base route
 * - Offer a small set of nearby "via" candidates at that anchor
 * - Pick the best viable alternative that actually diverges
 *
 * NO corridor penalisation.
 * NO global directional bias.
 * NO heuristic hacks.
 */
class DivergenceAnchorRouteGenerator(
    private val graph: Graph,
    private val router: AStarRouter
) {

    /**
     * @param baseRouteNodeIds the already-computed base route
     * @param startNodeId snapped start node
     * @param endNodeId snapped end node
     *
     * @return an alternative RouteResult, or null if no meaningful alternative exists
     */
    fun generateAlternative(
        baseRouteNodeIds: List<Int>,
        startNodeId: Int,
        endNodeId: Int
    ): AStarRouter.RouteResult? {

        if (baseRouteNodeIds.size < 10) return null

        // 1️⃣ Choose anchor index (stable hill behaviour)
        val anchorIndex = (baseRouteNodeIds.size * 0.45).toInt()
            .coerceIn(3, baseRouteNodeIds.size - 4)

        val anchorNodeId = baseRouteNodeIds[anchorIndex]

        // 2️⃣ Collect candidate via-nodes near anchor
        val viaCandidates = nearbyNodes(anchorNodeId, maxCount = 12)

        var bestAlt: AStarRouter.RouteResult? = null
        var bestScore = Double.POSITIVE_INFINITY

        // 3️⃣ Try routing via each candidate
        for (via in viaCandidates) {

            // Must actually diverge
            if (baseRouteNodeIds.contains(via)) continue

            val leg1 = router.route(startNodeId, via) ?: continue
            val leg2 = router.route(via, endNodeId) ?: continue

            val combined = mergeRoutes(leg1, leg2)

            // Reject trivial duplicates
            if (isNearDuplicate(baseRouteNodeIds, combined.nodeIds)) continue

            // Prefer:
            // - reasonable total cost
            // - earlier divergence
            val divergencePenalty = abs(anchorIndex - firstDivergenceIndex(baseRouteNodeIds, combined.nodeIds))
            val score = combined.cost + divergencePenalty * 0.1

            if (score < bestScore) {
                bestScore = score
                bestAlt = combined
            }
        }

        return bestAlt
    }

    // ---------------- internals ----------------

    private fun nearbyNodes(nodeId: Int, maxCount: Int): List<Int> {
        val n = graph.nodes[nodeId] ?: return emptyList()

        return graph.nodes.entries
            .asSequence()
            .filter { it.key != nodeId }
            .map { (id, other) ->
                id to approxDistance(n.lat, n.lon, other.lat, other.lon)
            }
            .sortedBy { it.second }
            .take(maxCount)
            .map { it.first }
            .toList()
    }

    private fun mergeRoutes(
        a: AStarRouter.RouteResult,
        b: AStarRouter.RouteResult
    ): AStarRouter.RouteResult {
        val mergedIds =
            a.nodeIds.dropLast(1) + b.nodeIds

        return AStarRouter.RouteResult(
            nodeIds = mergedIds,
            cost = a.cost + b.cost
        )
    }

    private fun isNearDuplicate(a: List<Int>, b: List<Int>): Boolean {
        val shared = a.toSet().intersect(b.toSet()).size
        val minSize = minOf(a.size, b.size)
        return shared.toDouble() / minSize.toDouble() > 0.85
    }

    private fun firstDivergenceIndex(
        base: List<Int>,
        alt: List<Int>
    ): Int {
        val len = minOf(base.size, alt.size)
        for (i in 0 until len) {
            if (base[i] != alt[i]) return i
        }
        return len
    }

    private fun approxDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dx = lat2 - lat1
        val dy = lon2 - lon1
        return dx * dx + dy * dy
    }
}