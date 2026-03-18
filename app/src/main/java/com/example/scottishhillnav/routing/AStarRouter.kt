// app/src/main/java/com/example/scottishhillnav/routing/AStarRouter.kt
package com.example.scottishhillnav.routing

import java.util.PriorityQueue
import kotlin.math.abs

class AStarRouter(private val graph: Graph) : ConstrainedRouter {

    data class RouteResult(
        val nodeIds: List<Int>,
        val cost: Double
    )

    private data class QNode(
        val nodeId: Int,
        val g: Double,
        val f: Double,
        val parent: QNode?
    )

    fun route(start: Int, goal: Int): RouteResult? {
        val r = routeWithCapabilities(
            startId = start,
            endId = goal,
            allowedMask = Capability.ALL,
            blockedNodes = emptySet(),
            blockedEdges = emptySet()
        ) ?: return null
        return RouteResult(r.nodeIds, r.cost)
    }

    override fun route(
        startId: Int,
        endId: Int,
        blockedNodes: Set<Int>,
        blockedEdges: Set<Pair<Int, Int>>
    ): ConstrainedRouter.Result? {
        return routeWithCapabilities(
            startId = startId,
            endId = endId,
            allowedMask = Capability.ALL,
            blockedNodes = blockedNodes,
            blockedEdges = blockedEdges
        )
    }

    fun routeWithCapabilities(
        startId: Int,
        endId: Int,
        allowedMask: Int,
        blockedNodes: Set<Int>,
        blockedEdges: Set<Pair<Int, Int>>,
        costModel: EdgeCostModel = ClassicCostModel
    ): ConstrainedRouter.Result? {

        if (!graph.nodes.containsKey(startId) || !graph.nodes.containsKey(endId)) return null
        if (startId in blockedNodes || endId in blockedNodes) return null

        val open = PriorityQueue<QNode>(compareBy { it.f })
        val bestG = HashMap<Int, Double>()

        open.add(
            QNode(
                nodeId = startId,
                g = 0.0,
                f = heuristic(startId, endId),
                parent = null
            )
        )
        bestG[startId] = 0.0

        while (open.isNotEmpty()) {
            val current: QNode = open.remove()

            if (current.nodeId == endId) {
                return reconstruct(current)
            }

            val outEdges = graph.edges[current.nodeId] ?: continue

            for (e in outEdges) {
                if (e.to in blockedNodes) continue
                if ((current.nodeId to e.to) in blockedEdges) continue
                if (!Capability.hasAll(allowedMask, e.requiredMask)) continue

                val newG = current.g + costModel.cost(current.nodeId, e.to, e)
                val prevBest = bestG[e.to]
                if (prevBest != null && newG >= prevBest) continue

                bestG[e.to] = newG
                open.add(
                    QNode(
                        nodeId = e.to,
                        g = newG,
                        f = newG + heuristic(e.to, endId),
                        parent = current
                    )
                )
            }
        }

        return null
    }

    override fun pathCost(nodeIds: List<Int>): Double {
        var cost = 0.0
        for (i in 0 until nodeIds.size - 1) {
            val from = nodeIds[i]
            val to = nodeIds[i + 1]
            val edge = graph.edges[from]?.firstOrNull { it.to == to }
                ?: return Double.POSITIVE_INFINITY
            cost += edge.cost
        }
        return cost
    }

    private fun reconstruct(end: QNode): ConstrainedRouter.Result {
        val path = ArrayList<Int>()
        var cur: QNode = end

        while (true) {
            path.add(cur.nodeId)
            val parent = cur.parent ?: break
            cur = parent
        }

        path.reverse()
        return ConstrainedRouter.Result(path, end.g)
    }

    private fun heuristic(a: Int, b: Int): Double {
        val na = graph.nodes[a] ?: return 0.0
        val nb = graph.nodes[b] ?: return 0.0
        val dLat = na.lat - nb.lat
        val dLon = na.lon - nb.lon
        return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }
}
