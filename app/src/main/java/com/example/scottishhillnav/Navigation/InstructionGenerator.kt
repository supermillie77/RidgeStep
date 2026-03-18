package com.example.scottishhillnav.navigation

import com.example.scottishhillnav.routing.Graph
import kotlin.math.*

data class RouteInstruction(
    val distanceFromStart: Double,  // metres: trigger when progressMeters passes this value
    val text: String
)

/**
 * Builds voice instructions keyed to route position.
 *
 * Each instruction fires once when the walker passes its [distanceFromStart] threshold.
 *
 * Announcements are made:
 *  • At the start   — "Head North East for 600 metres"
 *  • At each decision point (fork / significant bend > 45°) — "At the fork, head North for 300 metres"
 *  • On arrival     — "You have arrived"
 *
 * No periodic km-milestone chatter; no double-announcement 120 m before.
 * Off-track detection is handled separately in MainActivity so this class stays
 * purely about pre-computed route instructions.
 */
class InstructionGenerator(private val graph: Graph) {

    companion object {
        private const val TRIGGER_BEFORE_M = 15.0  // fire instruction this many metres before node
        private const val BEND_THRESHOLD   = 45.0  // announce non-junction bends above this angle
    }

    fun generate(nodeIds: List<Int>, cumulativeDistance: DoubleArray): List<RouteInstruction> {
        if (nodeIds.size < 2 || cumulativeDistance.isEmpty()) return emptyList()

        // ── Collect decision points ───────────────────────────────────────────
        data class DecisionPoint(val nodeIdx: Int, val distFromStart: Double, val isJunction: Boolean)
        val decisions = mutableListOf<DecisionPoint>()

        for (i in 1 until nodeIds.size - 1) {
            val prevId = nodeIds[i - 1]
            val currId = nodeIds[i]
            val nextId = nodeIds[i + 1]

            val prev = graph.nodes[prevId] ?: continue
            val curr = graph.nodes[currId] ?: continue
            val next = graph.nodes[nextId] ?: continue

            val inB  = bearing(prev.lat, prev.lon, curr.lat, curr.lon)
            val outB = bearing(curr.lat, curr.lon, next.lat, next.lon)
            val turn = normalizeAngle(outB - inB)

            val edges      = graph.edges[currId] ?: emptyList()
            val isJunction = edges.any { it.to != prevId && it.to != nextId }

            if (isJunction || abs(turn) > BEND_THRESHOLD) {
                decisions.add(DecisionPoint(i, cumulativeDistance[i], isJunction))
            }
        }

        val instructions = mutableListOf<RouteInstruction>()
        val totalDist    = cumulativeDistance.last()

        // ── Opening instruction ───────────────────────────────────────────────
        val startHeading = bearing(
            graph.nodes[nodeIds[0]]?.lat ?: 0.0, graph.nodes[nodeIds[0]]?.lon ?: 0.0,
            graph.nodes[nodeIds[minOf(1, nodeIds.size - 1)]]?.lat ?: 0.0,
            graph.nodes[nodeIds[minOf(1, nodeIds.size - 1)]]?.lon ?: 0.0
        )
        val distToFirstDecision = decisions.firstOrNull()?.distFromStart ?: totalDist
        instructions += RouteInstruction(
            0.0,
            "Navigation started. Head ${compass(startHeading)} for ${fmt(distToFirstDecision)}."
        )

        // ── Decision-point instructions ───────────────────────────────────────
        for ((idx, dp) in decisions.withIndex()) {
            val curr = graph.nodes[nodeIds[dp.nodeIdx]] ?: continue
            val next = graph.nodes[nodeIds[dp.nodeIdx + 1]] ?: continue

            val outBearing  = bearing(curr.lat, curr.lon, next.lat, next.lon)
            val nextDecDist = if (idx + 1 < decisions.size)
                decisions[idx + 1].distFromStart - dp.distFromStart
            else
                totalDist - dp.distFromStart

            val prefix = if (dp.isJunction) "At the fork, " else ""
            val text   = "${prefix}head ${compass(outBearing)} for ${fmt(nextDecDist)}."

            // Trigger slightly before the node so the walker hears it just in time
            val trigger = (dp.distFromStart - TRIGGER_BEFORE_M).coerceAtLeast(0.0)
            instructions += RouteInstruction(trigger, text.replaceFirstChar { it.uppercaseChar() })
        }

        // ── Arrival ───────────────────────────────────────────────────────────
        instructions += RouteInstruction((totalDist - 60.0).coerceAtLeast(0.0),
            "Approaching destination.")
        instructions += RouteInstruction((totalDist - 5.0).coerceAtLeast(0.0),
            "You have arrived.")

        return instructions.sortedBy { it.distanceFromStart }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** True (0-360) compass bearing, North = 0. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val φ1   = Math.toRadians(lat1)
        val φ2   = Math.toRadians(lat2)
        val y    = sin(dLon) * cos(φ2)
        val x    = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** 8-point compass name for a bearing (clear for speech). */
    fun compass(b: Double): String {
        val n = ((b % 360.0) + 360.0) % 360.0
        return when {
            n <  22.5 || n >= 337.5 -> "North"
            n <  67.5               -> "North East"
            n < 112.5               -> "East"
            n < 157.5               -> "South East"
            n < 202.5               -> "South"
            n < 247.5               -> "South West"
            n < 292.5               -> "West"
            else                    -> "North West"
        }
    }

    /** Signed turn angle in [-180, 180]. Negative = left, positive = right. */
    private fun normalizeAngle(a: Double): Double {
        var r = a % 360.0
        if (r >  180.0) r -= 360.0
        if (r < -180.0) r += 360.0
        return r
    }

    private fun fmt(m: Double): String {
        if (m >= 950) return "%.1f kilometres".format(m / 1000.0)
        // Round to nearest 50 m, minimum 50 m
        val rounded = ((m / 50.0 + 0.5).toInt() * 50).coerceAtLeast(50)
        return "$rounded metres"
    }
}
