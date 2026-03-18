package com.example.scottishhillnav.routing

/**
 * Temporary placeholder ElevationProvider.
 *
 * Returns null for all queries.
 * Safe default until real DEM is wired in.
 */
class NullElevationProvider : ElevationProvider {

    override fun elevationMeters(lat: Double, lon: Double): Double? {
        return null
    }
}
