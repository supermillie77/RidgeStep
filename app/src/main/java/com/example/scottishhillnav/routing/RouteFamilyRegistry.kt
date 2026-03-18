// app/src/main/java/com/example/scottishhillnav/routing/RouteFamilyRegistry.kt
package com.example.scottishhillnav.routing

/**
 * Central registry for all hard-coded RouteFamilies.
 *
 * This is intentionally explicit and hand-curated.
 * Ben Nevis is the proving ground.
 */
object RouteFamilyRegistry {

    const val BEN_NEVIS = "ben_nevis"

    /**
     * Explicit list (NOT a map) to avoid iteration ambiguity.
     */
    val families: List<RouteFamily> = listOf(

        RouteFamily(
            id = "BEN_TOURIST_PATH",
            mountainId = BEN_NEVIS,
            name = "Tourist Path",
            description = "The classic Mountain Track via the Halfway Lochan",
            intent = RouteIntent.WALKING,
            visibilityPolicy = VisibilityPolicy.DEFAULT,
            anchorStrategy = AnchorStrategy.None
        ),

        RouteFamily(
            id = "BEN_CMD_ARETE",
            mountainId = BEN_NEVIS,
            name = "CMD Arête",
            description = "A high-level traverse via Carn Mòr Dearg with sustained exposure",
            intent = RouteIntent.MOUNTAINEERING,
            visibilityPolicy = VisibilityPolicy.EXPERIENCED,
            anchorStrategy = AnchorStrategy.ViaSequence(
                anchors = listOf(
                    AnchorStrategy.ViaNode(nodeId = 123456), // CMC Hut (placeholder)
                    AnchorStrategy.ViaNode(nodeId = 234567)  // Carn Mòr Dearg (placeholder)
                )
            )
        ),

        RouteFamily(
            id = "BEN_NORTH_FACE",
            mountainId = BEN_NEVIS,
            name = "North Face Approach",
            description = "Remote approach beneath the North Face via the CIC Hut",
            intent = RouteIntent.MOUNTAINEERING,
            visibilityPolicy = VisibilityPolicy.EXPERIENCED,
            anchorStrategy = AnchorStrategy.ViaNode(
                nodeId = 345678 // CIC Hut (placeholder)
            )
        )
    )

    /**
     * Convenience filter used by MainActivity.
     */
    fun forMountain(mountainId: String): List<RouteFamily> =
        families.filter { it.mountainId == mountainId }
}