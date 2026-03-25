package com.example.scottishhillnav

import android.content.Context
import com.example.scottishhillnav.routing.SrtmHgtProvider

/**
 * Singleton elevation lookup backed by offline SRTM .hgt tiles.
 *
 * Call [init] once in Application/Activity onCreate. After that any code
 * (including RouteGradientOverlay and TerrainService) can call [getElevation]
 * without holding a Context.
 *
 * If no .hgt tiles are present on the device, [getElevation] returns 0.0
 * gracefully — the route will draw without colour grading.
 *
 * Tile location on device:
 *   /sdcard/Android/data/com.example.scottishhillnav/files/dem/
 * Required tiles for Scottish Highlands:
 *   N56W004.hgt  N56W005.hgt  N56W006.hgt  N56W007.hgt
 *   N57W004.hgt  N57W005.hgt  N57W006.hgt  N57W007.hgt
 *
 * Tiles can be downloaded from https://dwtkns.com/srtm30m/ (1 arc-sec, ~26 MB each)
 * or converted from the project TIF files using the Python script in /tools/tif_to_hgt.py.
 */
object DemProvider {

    private var provider: SrtmHgtProvider? = null

    fun init(context: Context) {
        provider = SrtmHgtProvider(context.applicationContext)
    }

    fun getElevation(lat: Double, lon: Double): Double =
        provider?.elevationMeters(lat, lon) ?: 0.0
}
