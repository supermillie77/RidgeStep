package com.example.scottishhillnav

import org.osmdroid.util.GeoPoint
import kotlin.math.hypot

fun distance(a: GeoPoint, b: GeoPoint): Double =
    hypot(a.latitude - b.latitude, a.longitude - b.longitude)
