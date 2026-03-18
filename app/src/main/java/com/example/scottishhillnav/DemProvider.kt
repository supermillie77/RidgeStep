package com.example.scottishhillnav

import kotlin.math.sin

object DemProvider {

    fun getElevation(lat: Double, lon: Double): Double {
        return 200.0 +
            100.0 * sin(lat * 100.0) +
            100.0 * sin(lon * 100.0)
    }
}
