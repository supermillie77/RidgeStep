// app/src/main/java/com/example/scottishhillnav/routing/KShortestPaths.kt
package com.example.scottishhillnav.routing

import java.util.PriorityQueue

class KShortestPaths(private val router: ConstrainedRouter) {

    data class Path(
        val nodeIds: List<Int>,
        val cost: Double
    )

    fun kShortestPaths(startId: Int, endId: Int, k: Int): List<Path> {
        val first = router.route(startId, endId, emptySet(), emptySet()) ?: return emptyList()
        val shortest = Path(first.nodeIds, first.cost)

        val result = mutableListOf(shortest)
        val candidates = PriorityQueue<Path>(compareBy { it.cost })
        val seen = HashSet<List<Int>>()
        seen += shortest.nodeIds

        for (i in 1 until k) {
            val previous = result[i - 1]

            for (spurIndex in 0 until previous.nodeIds.size - 1) {
                val spurNode = previous.nodeIds[spurIndex]
                val rootPath = previous.nodeIds.subList(0, spurIndex + 1)

                val blockedEdges = HashSet<Pair<Int, Int>>()
                for (p in result) {
                    if (
                        p.nodeIds.size > spurIndex &&
                        p.nodeIds.subList(0, spurIndex + 1) == rootPath
                    ) {
                        blockedEdges += p.nodeIds[spurIndex] to p.nodeIds[spurIndex + 1]
                    }
                }

                val blockedNodes = rootPath.dropLast(1).toSet()
                val spur = router.route(spurNode, endId, blockedNodes, blockedEdges) ?: continue

                val totalPath = rootPath.dropLast(1) + spur.nodeIds
                if (seen.add(totalPath)) {
                    val cost = router.pathCost(rootPath) + spur.cost
                    candidates.add(Path(totalPath, cost))
                }
            }

            if (candidates.isEmpty()) break
            result.add(candidates.poll()!!)
        }

        return result
    }
}
