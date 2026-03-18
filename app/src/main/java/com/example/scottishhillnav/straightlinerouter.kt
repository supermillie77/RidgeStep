package com.example.scottishhillnav

import org.osmdroid.util.GeoPoint

/**
 * Simple fallback router that returns a straight-line route.
 * Uses OSMDroid GeoPoint only.
 */
class StraightLineRouter {

    fun route(start: GeoPoint, end: GeoPoint): List<GeoPoint> {
        return listOf(start, end)
    }
}
