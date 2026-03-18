// app/src/main/java/com/example/scottishhillnav/routing/AnchorStrategy.kt
package com.example.scottishhillnav.routing

/**
 * Describes how a RouteFamily should be resolved into
 * concrete graph paths.
 */
sealed class AnchorStrategy {

    /** No enforced anchors (used rarely; usually resolved elsewhere) */
    object None : AnchorStrategy()

    /** Force route to pass near a specific node */
    data class ViaNode(
        val nodeId: Int,
        val toleranceMeters: Double = 200.0
    ) : AnchorStrategy()

    /** Force route through a known region (future work) */
    data class ViaRegion(
        val regionId: String
    ) : AnchorStrategy()

    /** Force route through a sequence of anchor nodes (CMD, Tourist Path, etc.) */
    data class ViaSequence(
        val anchors: List<ViaNode>
    ) : AnchorStrategy()
}