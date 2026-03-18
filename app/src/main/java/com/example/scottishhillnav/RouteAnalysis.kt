package com.example.scottishhillnav

import org.osmdroid.util.GeoPoint
import kotlin.math.*

/**
 * Pure route analysis utilities.
 * NO rendering.
 * NO map access.
 * Safe to evolve independently.
 */
object RouteAnalysis {

    data class Result(
        val distanceMeters: Double,
        val ascentMeters: Double,
        val descentMeters: Double,
        val minElevation: Double,
        val maxElevation: Double
    )

    /**
     * Analyse a straight-line route between two points.
     *
     * @param start start point
     * @param end end point
     * @param sampleSpacingMeters distance between elevation samples
     * @param elevationAt function that returns elevation in meters or null
     */
    fun analyseStraightLine(
        start: GeoPoint,
        end: GeoPoint,
        sampleSpacingMeters: Double,
        elevationAt: (lat: Double, lon: Double) -> Double?
    ): Result {

        val totalDistance = haversine(start, end)
        val samples = max(2, ceil(totalDistance / sampleSpacingMeters).toInt())

        var ascent = 0.0
        var descent = 0.0
        var minElev = Double.POSITIVE_INFINITY
        var maxElev = Double.NEGATIVE_INFINITY

        var prevElev: Double? = null

        for (i in 0 until samples) {
            val t = i.toDouble() / (samples - 1)

            val lat = lerp(start.latitude, end.latitude, t)
            val lon = lerp(start.longitude, end.longitude, t)

            val elev = elevationAt(lat, lon) ?: continue

            minElev = min(minElev, elev)
            maxElev = max(maxElev, elev)

            if (prevElev != null) {
                val delta = elev - prevElev
                if (delta > 0) ascent += delta
                else descent -= delta
            }

            prevElev = elev
        }

        return Result(
            distanceMeters = totalDistance,
            ascentMeters = ascent,
            descentMeters = descent,
            minElevation = minElev,
            maxElevation = maxElev
        )
    }

    // ---------- helpers ----------

    private fun lerp(a: Double, b: Double, t: Double): Double =
        a + (b - a) * t

    /**
     * Haversine distance in meters.
     */
    private fun haversine(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)

        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val h =
            sin(dLat / 2).pow(2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)

        return 2 * r * asin(sqrt(h))
    }
}
