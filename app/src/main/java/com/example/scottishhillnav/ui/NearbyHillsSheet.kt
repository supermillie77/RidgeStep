package com.example.scottishhillnav.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.scottishhillnav.R
import com.example.scottishhillnav.hills.Hill
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.roundToInt

/**
 * Bottom sheet listing nearby hills sorted by distance.
 * Caller provides [entries] (Hill + distanceM) and sets [onHillPicked].
 */
class NearbyHillsSheet : BottomSheetDialogFragment() {

    data class Entry(val hill: Hill, val distanceM: Double)

    var entries: List<Entry> = emptyList()
    var onHillPicked: ((Hill) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_nearby_hills, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val container = view.findViewById<LinearLayout>(R.id.nearbyHillsContainer)
        val subtitle  = view.findViewById<TextView>(R.id.nearbySubtitle)

        if (entries.isEmpty()) {
            subtitle.text = "No hills found nearby"
            return
        }

        val maxDist = entries.last().distanceM
        subtitle.text = "Within ${formatDist(maxDist)} of your location"

        for (entry in entries) {
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_nearby_hill, container, false)

            row.findViewById<View>(R.id.categoryBar)
                .setBackgroundColor(SummitOverlay.categoryColor(entry.hill.category))

            row.findViewById<TextView>(R.id.hillName).text = entry.hill.name

            val meta = buildString {
                append(entry.hill.category)
                if (entry.hill.area.isNotEmpty()) append("  ·  ${entry.hill.area}")
                if (entry.hill.elevationM > 0) append("  ·  ${entry.hill.elevationM} m")
            }
            row.findViewById<TextView>(R.id.hillMeta).text = meta

            row.findViewById<TextView>(R.id.hillDistance).text = formatDist(entry.distanceM)
            row.findViewById<TextView>(R.id.hillEta).text = estimateTime(entry.hill, entry.distanceM)

            row.setOnClickListener {
                onHillPicked?.invoke(entry.hill)
                dismiss()
            }

            container.addView(row)
        }
    }

    private fun formatDist(m: Double): String =
        if (m < 1000) "${m.roundToInt()} m" else "${"%.1f".format(m / 1000)} km"

    /**
     * Naismith's Rule estimate: 4 km/h walking + 1 hr per 600 m ascent.
     * Ascent is approximated as summit elevation (assumes start near sea level).
     */
    private fun estimateTime(hill: Hill, distanceM: Double): String {
        val distKm    = distanceM / 1000.0
        val ascentM   = hill.elevationM.toDouble()
        val totalMins = (distKm / 4.0 * 60.0 + ascentM / 600.0 * 60.0).toInt()
        val h = totalMins / 60; val m = totalMins % 60
        return if (h > 0) "~${h}h ${m}m" else "~${m}m"
    }
}
