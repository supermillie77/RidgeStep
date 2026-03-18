// app/src/main/java/com/example/scottishhillnav/routing/CuratedRouteRegistry.kt
package com.example.scottishhillnav.routing

/**
 * Curated routes are explicitly defined. They do not depend on K-shortest or graph “guessing”.
 *
 * Philosophy lock:
 * - The app presents ALL curated routes every time.
 * - The app does not judge suitability.
 * - Start/end taps only affect ACCESS legs, not which curated routes exist.
 */
object CuratedRouteRegistry {

    enum class Kind {
        /** A curated route computed from the walking graph (direct shortest). */
        GRAPH_WALKING_PRIMARY,

        /** A curated route whose core geometry comes from an imported GPX line. */
        GPX
    }

    data class CuratedRoute(
        val id: String,
        val name: String,
        val shortDescription: String,
        val kind: Kind,

        /** For GPX routes: edge mask to follow the GPX line (SCRAMBLING or MOUNTAINEERING). */
        val gpxMask: Int = 0,

        /** For GPX routes: landmark keys set during import. */
        val startLandmarkKey: String = "",
        val endLandmarkKey: String = ""
    )

    /**
     * Current Ben Nevis catalogue.
     * Add more GPX routes here as you curate them.
     */
    val BEN_NEVIS: List<CuratedRoute> = listOf(
        CuratedRoute(
            id = "tourist_path",
            name = "Tourist Path",
            shortDescription = "Classic walking route (graph)",
            kind = Kind.GRAPH_WALKING_PRIMARY
        ),
        CuratedRoute(
            id = "cmd_arete",
            name = "CMD Arête",
            shortDescription = "Carn Mòr Dearg Arête (curated GPX)",
            kind = Kind.GPX,
            gpxMask = Capability.SCRAMBLING,
            startLandmarkKey = "cmd_arete_start",
            endLandmarkKey = "cmd_arete_end"
        ),
        CuratedRoute(
            id = "ledge_route",
            name = "Ledge Route",
            shortDescription = "North Face mountaineering route (curated GPX)",
            kind = Kind.GPX,
            gpxMask = Capability.MOUNTAINEERING,
            startLandmarkKey = "ledge_route_start",
            endLandmarkKey = "ledge_route_end"
        )
    )
}
