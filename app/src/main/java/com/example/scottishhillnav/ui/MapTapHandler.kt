package com.example.scottishhillnav.ui

import android.util.Log
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint

class MapTapHandler(
    private val onTap: (GeoPoint) -> Unit,
    private val onLongPress: (GeoPoint) -> Unit
) : MapEventsReceiver {

    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
        Log.i("TAP", "Single tap at ${p.latitude}, ${p.longitude}")
        onTap(p)
        return true
    }

    override fun longPressHelper(p: GeoPoint): Boolean {
        Log.i("TAP", "Long press at ${p.latitude}, ${p.longitude}")
        onLongPress(p)
        return true
    }
}
