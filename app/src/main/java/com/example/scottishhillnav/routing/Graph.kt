// app/src/main/java/com/example/scottishhillnav/routing/Graph.kt
package com.example.scottishhillnav.routing

import java.util.PriorityQueue
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
    // ── Spatial grid index ────────────────────────────────────────────────────
    // Divides the node set into 0.02° × 0.02° cells (~1.5 km grid at Scottish latitudes).
    // Built once at construction time; makes nearestNodeIds ~50× faster than a full
    // O(n) linear scan of the 200k+ bundled-graph nodes.
    private companion object {
        const val CELL_DEG = 0.02
    }

    private val spatialGrid: Map<Long, List<Int>>

    init {
        val grid = HashMap<Long, MutableList<Int>>(nodes.size / 4 + 16)
        for ((id, n) in nodes) {
            grid.getOrPut(cellKey(n.lat, n.lon)) { mutableListOf() }.add(id)
        }
        spatialGrid = grid.mapValues { it.value.toList() }
    }

    private fun cellKey(lat: Double, lon: Double): Long {
        // Offset by +1000 before truncation so negative coordinates map to distinct
        // positive cell indices without collision (Scotland: lat ~55-59, lon ~-8 to -1).
        val r = ((lat + 1000.0) / CELL_DEG).toLong()
        val c = ((lon + 1000.0) / CELL_DEG).toLong()
        return r * 200_000L + c
    }

    fun nearestNodeId(lat: Double, lon: Double): Int? =
        nearestNodeIds(lat, lon, 1).firstOrNull()

    /**
     * Returns up to [k] nearest node IDs, sorted closest-first.
     *
     * Uses the spatial grid to search a 5-cell radius (~10 km diameter) first —
     * typically ~120 candidates for the bundled graph. Falls back to a full linear
     * scan only if the grid search finds fewer than [k] nodes (e.g. very remote areas
     * at the edge of graph coverage).
     */
    fun nearestNodeIds(lat: Double, lon: Double, k: Int): List<Int> {
        data class Candidate(val id: Int, val dist: Double)

        val rCenter = ((lat + 1000.0) / CELL_DEG).toLong()
        val cCenter = ((lon + 1000.0) / CELL_DEG).toLong()
        val seen = HashSet<Int>()
        val candidates = ArrayList<Candidate>()

        // 5-cell radius = 11×11 = 121 cells ≈ 10 km diameter; sufficient for k ≤ 50
        for (dr in -5..5) {
            for (dc in -5..5) {
                val ids = spatialGrid[(rCenter + dr) * 200_000L + (cCenter + dc)] ?: continue
                for (id in ids) {
                    if (seen.add(id)) {
                        val n = nodes[id] ?: continue
                        val d = (n.lat - lat).pow(2) + (n.lon - lon).pow(2)
                        candidates.add(Candidate(id, d))
                    }
                }
            }
        }

        if (candidates.size >= k) {
            candidates.sortBy { it.dist }
            return candidates.take(k).map { it.id }
        }

        // Fallback: full linear scan with bounded top-k via max-heap.
        // Avoids sorting the entire node set (O(n log n)) — O(n log k) instead.
        // Max-heap ordered by descending distance so we can efficiently evict the worst.
        val heap = PriorityQueue<Pair<Int, Double>>(k + 1, compareByDescending { it.second })
        for ((id, n) in nodes) {
            val d = (n.lat - lat).pow(2) + (n.lon - lon).pow(2)
            heap.add(id to d)
            if (heap.size > k) heap.poll()   // drop the farthest candidate
        }
        return heap.sortedBy { it.second }.map { it.first }
    }

    fun landmarkId(key: String): Int? = landmarks[key]
}
