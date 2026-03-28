package com.example.scottishhillnav.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.example.scottishhillnav.R
import com.example.scottishhillnav.hills.Hill
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.roundToInt

/**
 * Bottom sheet shown when the user taps a summit triangle on the map.
 * Displays hill stats and a "Route from here" button.
 */
class SummitInfoSheet : BottomSheetDialogFragment() {

    lateinit var hill: Hill
    var distanceM: Double = -1.0   // negative = location unknown
    var onRouteFromHere: ((Hill) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_summit_info, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.summitCategoryStrip)
            .setBackgroundColor(SummitOverlay.categoryColor(hill.category))

        view.findViewById<TextView>(R.id.summitName).text = hill.name

        val categoryArea = buildString {
            append(hill.category)
            if (hill.area.isNotEmpty()) append("  ·  ${hill.area}")
        }
        view.findViewById<TextView>(R.id.summitCategoryArea).text = categoryArea

        view.findViewById<TextView>(R.id.summitElevation).text =
            if (hill.elevationM > 0) "${hill.elevationM} m" else "—"

        view.findViewById<TextView>(R.id.summitDistance).text =
            if (distanceM >= 0) formatDist(distanceM) else "—"

        view.findViewById<TextView>(R.id.summitEta).text =
            if (distanceM >= 0) estimateTime(hill, distanceM) else "—"

        view.findViewById<TextView>(R.id.summitRouteBtn).setOnClickListener {
            onRouteFromHere?.invoke(hill)
            dismiss()
        }

        view.findViewById<TextView>(R.id.summitDismissBtn).setOnClickListener { dismiss() }
    }

    private fun formatDist(m: Double): String =
        if (m < 1000) "${m.roundToInt()} m" else "${"%.1f".format(m / 1000)} km"

    private fun estimateTime(hill: Hill, distanceM: Double): String {
        val distKm    = distanceM / 1000.0
        val ascentM   = hill.elevationM.toDouble()
        val totalMins = (distKm / 4.0 * 60.0 + ascentM / 600.0 * 60.0).toInt()
        val h = totalMins / 60; val m = totalMins % 60
        return if (h > 0) "~${h}h ${m}m" else "~${m}m"
    }
}
