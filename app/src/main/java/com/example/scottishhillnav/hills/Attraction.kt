package com.example.scottishhillnav.hills

/**
 * A low-level walk destination — waterfall, castle, viewpoint, beach etc. —
 * retrieved from OpenStreetMap via [AttractionSearchService].
 */
data class Attraction(
    val name: String,
    val category: String,   // "Waterfall", "Castle", "Viewpoint", etc.
    val lat: Double,
    val lon: Double
)
