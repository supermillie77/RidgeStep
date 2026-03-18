package com.example.scottishhillnav.routing

/**
 * A concrete, drawable route derived from a RouteFamily.
 */
data class RouteCandidate(
    val id: String,
    val familyId: String,

    val name: String,
    val shortDescription: String,

    val nodeIds: List<Int>,

    val metrics: RouteMetrics,
    val difficultyProfile: DifficultyProfile,

    val warnings: List<RouteWarning>,
    val isSelectable: Boolean
)

/**
 * Shared, top-level metrics type (NOT nested).
 */
data class RouteMetrics(
    val distanceMeters: Double,
    val ascentMeters: Double,
    val estimatedTimeMinutes: Int
)

/**
 * Used for gradient colouring (green → red).
 */
data class DifficultyProfile(
    val p95Slope: Double
)

data class RouteWarning(
    val code: String,
    val message: String
)