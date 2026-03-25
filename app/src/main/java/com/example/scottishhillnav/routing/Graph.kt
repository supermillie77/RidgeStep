// app/src/main/java/com/example/scottishhillnav/routing/Graph.kt
package com.example.scottishhillnav.routing

import kotlin.math.pow

data class Node(
    val lat: Double,
    val lon: Double,
    val elevation: Double = 0.0
)

data class Edge(
    val to: Int,
    val cost: Double,
    val requiredMask: Int = Capability.WALKING
)

class Graph(
    val nodes: Map<Int, Node>,
    val edges: Map<Int, List<Edge>>,
    val landmarks: Map<String, Int> = emptyMap(),
    /** Ordered node sequences for named GPX-imported routes, keyed by route ID (e.g. "lomond_tourist"). */
    val routeSequences: Map<String, List<Int>> = emptyMap()
) {
    fun nearestNodeId(lat: Double, lon: Double): Int? =
        nearestNodeIds(lat, lon, 1).firstOrNull()

    /** Returns up to [k] nearest node IDs, sorted closest-first. */
    fun nearestNodeIds(lat: Double, lon: Double, k: Int): List<Int> {
        data class Candidate(val id: Int, val dist: Double)

        val heap = ArrayList<Candidate>(k + 1)
        var worstInHeap = Double.MAX_VALUE

        for ((id, n) in nodes) {
            val d = (n.lat - lat).pow(2) + (n.lon - lon).pow(2)
            if (heap.size < k || d < worstInHeap) {
                heap.add(Candidate(id, d))
                if (heap.size > k) {
                    val worstIdx = heap.indices.maxByOrNull { heap[it].dist }!!
                    heap.removeAt(worstIdx)
                }
                worstInHeap = heap.maxOfOrNull { it.dist } ?: Double.MAX_VALUE
            }
        }
        heap.sortBy { it.dist }
        return heap.map { it.id }
    }

    fun landmarkId(key: String): Int? = landmarks[key]
}
