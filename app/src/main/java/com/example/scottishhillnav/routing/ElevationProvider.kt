package com.example.scottishhillnav.routing

interface ElevationProvider {
    /**
     * @return elevation in meters, or null if unavailable for that coordinate.
     */
    fun elevationMeters(lat: Double, lon: Double): Double?
}
