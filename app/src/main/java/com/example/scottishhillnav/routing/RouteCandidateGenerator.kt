// app/src/main/java/com/example/scottishhillnav/routing/RouteCandidateGenerator.kt
package com.example.scottishhillnav.routing

import android.util.Log
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class RouteCandidateGenerator(
    private val graph: Graph,
    private val router: AStarRouter,
    private val metricsCalculator: RouteMetricsCalculator
) {

    fun generateCuratedCandidates(
        startId: Int,
        endId: Int
    ): List<RouteCandidate> {

        val candidates = mutableListOf<RouteCandidate>()

        // Tourist Path — use curated landmarks if present, otherwise direct walk
        val tourist = buildCuratedStrictAccess(
            id = "tourist",
            familyId = "tourist_family",
            name = "Tourist Path",
            description = "Main Mountain Track",
            startKey = "tourist_start",
            endKey = "tourist_end",
            startId = startId,
            endId = endId
        ) ?: buildDirectWalkCandidate(startId, endId)
        tourist?.let { candidates.add(it) }

        // CMD Arête (GPX import)
        buildCuratedStrictAccess(
            id = "cmd",
            familyId = "cmd_family",
            name = "CMD Arête",
            description = "Carn Mòr Dearg Arête",
            startKey = "cmd_arete_start",
            endKey = "cmd_arete_end",
            startId = startId,
            endId = endId
        )?.let { candidates.add(it) }

        // Ledge Route (GPX import)
        buildCuratedStrictAccess(
            id = "ledge",
            familyId = "ledge_family",
            name = "Ledge Route",
            description = "North Face Ledge",
            startKey = "ledge_route_start",
            endKey = "ledge_route_end",
            startId = startId,
            endId = endId
        )?.let { candidates.add(it) }

        return candidates
    }

    /**
     * Tourist path fallback when no curated tourist_start/tourist_end landmarks exist.
     *
     * Strategy:
     * 1. Force the route through the Mountain Track base (Ben Nevis visitor centre area).
     *    Access leg uses GoalDirectedCostModel to penalise backtracking toward the goal.
     *    Mountain Track leg uses WALKING-only so A* stays on footpaths.
     * 2. If the waypoint split fails (e.g. start is already past the trailhead), fall back
     *    to a direct WALKING route with GoalDirectedCostModel to reduce wandering.
     * 3. Last resort: unrestricted ALL-capability direct route.
     */
    private fun buildDirectWalkCandidate(startId: Int, endId: Int): RouteCandidate? {

        // Mountain Track base at Ben Nevis visitor centre / Torlundy path start.
        // Only use this waypoint when both endpoints are actually near Ben Nevis (< 12 km).
        val mtBaseId = graph.nearestNodeId(MT_BASE_LAT, MT_BASE_LON)
        val startNode = graph.nodes[startId]
        val endNode   = graph.nodes[endId]
        val nearBenNevis = startNode != null && endNode != null &&
            haversine(startNode.lat, startNode.lon, MT_BASE_LAT, MT_BASE_LON) < 12_000.0 &&
            haversine(endNode.lat,   endNode.lon,   MT_BASE_LAT, MT_BASE_LON) < 12_000.0

        if (nearBenNevis && mtBaseId != null && mtBaseId != startId && mtBaseId != endId) {
            // Access leg: any surface, but penalise moving away from the trailhead
            val accessCostModel = GoalDirectedCostModel(
                graph = graph,
                base = ClassicCostModel,
                goalNodeId = mtBaseId,
                sidewaysWeight = 0.4,
                awayWeight = 1.5
            )
            val legAccess = router.routeWithCapabilities(
                startId = startId,
                endId = mtBaseId,
                allowedMask = Capability.ALL,
                blockedNodes = emptySet(),
                blockedEdges = emptySet(),
                costModel = accessCostModel
            )
            // Mountain Track leg: walking-only + penalise leaving the track
            val trackCostModel = GoalDirectedCostModel(
                graph = graph,
                base = ClassicCostModel,
                goalNodeId = endId,
                sidewaysWeight = 0.3,
                awayWeight = 1.2
            )
            val legTrack = router.routeWithCapabilities(
                startId = mtBaseId,
                endId = endId,
                allowedMask = Capability.WALKING,
                blockedNodes = emptySet(),
                blockedEdges = emptySet(),
                costModel = trackCostModel
            )
            if (legAccess != null && legTrack != null) {
                val nodeIds = legAccess.nodeIds.dropLast(1) + legTrack.nodeIds
                val metrics = metricsCalculator.calculate(nodeIds)
                return RouteCandidate(
                    id = "tourist_direct",
                    familyId = "tourist_family",
                    name = "Tourist Path",
                    shortDescription = "Main Mountain Track",
                    nodeIds = nodeIds,
                    metrics = metrics,
                    difficultyProfile = DifficultyProfile(p95Slope = computeP95Slope(legTrack.nodeIds)),
                    warnings = emptyList(),
                    isSelectable = true
                )
            }
        }

        // Fallback: direct walk with goal-directed cost model to reduce wandering
        val directCostModel = GoalDirectedCostModel(
            graph = graph,
            base = ClassicCostModel,
            goalNodeId = endId,
            sidewaysWeight = 0.35,
            awayWeight = 1.25
        )
        val result = router.routeWithCapabilities(
            startId = startId,
            endId = endId,
            allowedMask = Capability.WALKING,
            blockedNodes = emptySet(),
            blockedEdges = emptySet(),
            costModel = directCostModel
        ) ?: router.routeWithCapabilities(
            startId = startId,
            endId = endId,
            allowedMask = Capability.ALL,
            blockedNodes = emptySet(),
            blockedEdges = emptySet(),
            costModel = directCostModel
        ) ?: return null

        val metrics = metricsCalculator.calculate(result.nodeIds)
        return RouteCandidate(
            id = "tourist_direct",
            familyId = "tourist_family",
            name = "Tourist Path",
            shortDescription = "Main Mountain Track",
            nodeIds = result.nodeIds,
            metrics = metrics,
            difficultyProfile = DifficultyProfile(p95Slope = computeP95Slope(result.nodeIds)),
            warnings = emptyList(),
            isSelectable = true
        )
    }

    companion object {
        private const val TAG = "RouteCandidateGen"
        // Ben Nevis visitor centre / Mountain Track north trailhead (Torlundy)
        private const val MT_BASE_LAT = 56.7983
        private const val MT_BASE_LON = -5.0036
    }

    /**
     * STRICT MODE (locked philosophy):
     * - Start/end taps affect access legs only.
     * - Therefore a candidate is only valid if BOTH access legs exist.
     *
     * This prevents routes that "start somewhere else" (curated core only) and makes the UI consistent.
     */
    private fun buildCuratedStrictAccess(
        id: String,
        familyId: String,
        name: String,
        description: String,
        startKey: String,
        endKey: String,
        startId: Int,
        endId: Int
    ): RouteCandidate? {

        val routeStartId = graph.landmarkId(startKey)
        val routeEndId = graph.landmarkId(endKey)

        if (routeStartId == null || routeEndId == null) {
            Log.w(
                TAG,
                "Missing landmarks for $id: startKey=$startKey -> $routeStartId, endKey=$endKey -> $routeEndId. Candidate dropped."
            )
            return null
        }

        // Always build curated core (deterministic)
        val curatedCore = router.route(routeStartId, routeEndId)
        if (curatedCore == null) {
            Log.w(TAG, "Curated core failed for $id: $routeStartId -> $routeEndId. Candidate dropped.")
            return null
        }

        // Access legs MUST exist (locked philosophy)
        val accessToRoute = router.route(startId, routeStartId)
        val accessToEnd = router.route(routeEndId, endId)

        if (accessToRoute == null || accessToEnd == null) {
            Log.w(
                TAG,
                "Access legs failed for $id: startId=$startId -> $routeStartId = ${accessToRoute != null}, " +
                    "$routeEndId -> endId=$endId = ${accessToEnd != null}. Candidate dropped."
            )
            return null
        }

        val fullPath =
            accessToRoute.nodeIds.dropLast(1) +
                curatedCore.nodeIds.dropLast(1) +
                accessToEnd.nodeIds

        val metrics = metricsCalculator.calculate(fullPath)

        // Difficulty should be based on the curated core, not the access legs
        val difficultyProfile = DifficultyProfile(
            p95Slope = computeP95Slope(curatedCore.nodeIds)
        )

        return RouteCandidate(
            id = id,
            familyId = familyId,
            name = name,
            shortDescription = description,
            nodeIds = fullPath,
            metrics = metrics,
            difficultyProfile = difficultyProfile,
            warnings = emptyList(),
            isSelectable = true
        )
    }

    private fun computeP95Slope(nodeIds: List<Int>): Double {

        if (nodeIds.size < 2) return 0.0

        val slopes = mutableListOf<Double>()

        for (i in 0 until nodeIds.size - 1) {

            val a = graph.nodes[nodeIds[i]] ?: continue
            val b = graph.nodes[nodeIds[i + 1]] ?: continue

            val horizontal = haversine(a.lat, a.lon, b.lat, b.lon)
            if (horizontal == 0.0) continue

            val slope = abs(b.elevation - a.elevation) / horizontal * 100.0
            slopes.add(slope)
        }

        if (slopes.isEmpty()) return 0.0

        slopes.sort()
        val index = (slopes.size * 0.95).toInt().coerceAtMost(slopes.lastIndex)
        return slopes[index]
    }

    private fun haversine(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {

        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a =
            kotlin.math.sin(dLat / 2).pow(2) +
                kotlin.math.cos(Math.toRadians(lat1)) *
                kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).pow(2)

        return 2 * r * kotlin.math.asin(sqrt(a))
    }
}
