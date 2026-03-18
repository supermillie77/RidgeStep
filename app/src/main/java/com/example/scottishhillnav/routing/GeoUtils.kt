package com.example.scottishhillnav.routing

import kotlin.math.*

object GeoUtils {

    /**
     * Great-circle distance (meters) using haversine.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a =
            sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        return 2.0 * r * asin(sqrt(a))
    }
}
