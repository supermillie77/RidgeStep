package com.example.scottishhillnav.hills

data class CarPark(
    val name: String,
    val lat: Double,
    val lon: Double,
    val area: String = "",
    /** Road navigation target when different from the walking start (e.g. mainland ferry terminal). */
    val navLat: Double? = null,
    val navLon: Double? = null,
    /**
     * True for the community-recommended / WalkHighlands-preferred car park for this hill.
     * Shown at the top of the selection dialog with a ⭐ prefix.
     */
    val isPreferred: Boolean = false
)

data class Hill(
    val id: String,
    val name: String,
    val area: String,
    val summitLat: Double,
    val summitLon: Double,
    val carParks: List<CarPark> = emptyList(),
    val category: String = "Hill",
    val elevationM: Int = 0
)
