package com.example.scottishhillnav.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.scottishhillnav.R
import com.example.scottishhillnav.hills.Hill
import com.example.scottishhillnav.routing.RouteWarningPolicy
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.roundToInt

/**
 * Bottom sheet listing hills within a user-selected radius of their GPS position.
 * Radius choices: 5, 10, 15, 20, 25 miles. Distances are displayed in miles.
 * Times are Naismith estimates to the summit (one-way, straight-line distance).
 */
class NearbyHillsSheet : BottomSheetDialogFragment() {

    data class Entry(val hill: Hill, val distanceM: Double)

    /** All hills with distances from the reference point — the sheet filters by radius. */
    var allEntries: List<Entry> = emptyList()
    var onHillPicked: ((Hill) -> Unit)? = null

    private val radiusOptions = intArrayOf(5, 10, 15, 20, 25)   // miles
    private var selectedRadiusMiles = 10

    private lateinit var subtitle:   TextView
    private lateinit var container:  LinearLayout
    private lateinit var chipsRow:   LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_nearby_hills, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        subtitle  = view.findViewById(R.id.nearbySubtitle)
        container = view.findViewById(R.id.nearbyHillsContainer)
        chipsRow  = view.findViewById(R.id.radiusChipsRow)

        buildChips()
        rebuildList()
    }

    /** Force the sheet fully open so the list isn't hidden behind a peek area. */
    override fun onStart() {
        super.onStart()
        val d = dialog as? BottomSheetDialog ?: return
        val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(sheet)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    // ── Chips ─────────────────────────────────────────────────────────────────

    private fun buildChips() {
        chipsRow.removeAllViews()
        val dp   = resources.displayMetrics.density
        val hPad = (14 * dp).toInt()
        val vPad = (6  * dp).toInt()
        val gap  = (8  * dp).toInt()

        for (miles in radiusOptions) {
            val chip = TextView(requireContext()).apply {
                text = "$miles mi"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(hPad, vPad, hPad, vPad)
                setOnClickListener {
                    if (selectedRadiusMiles != miles) {
                        selectedRadiusMiles = miles
                        buildChips()
                        rebuildList()
                    }
                }
            }
            styleChip(chip, miles == selectedRadiusMiles)

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, gap, 0) }
            chipsRow.addView(chip, params)
        }
    }

    private fun styleChip(chip: TextView, active: Boolean) {
        if (active) {
            chip.setBackgroundColor(0xFFFF6B35.toInt())
            chip.setTextColor(0xFFFFFFFF.toInt())
        } else {
            chip.setBackgroundColor(0xFF1E3347.toInt())
            chip.setTextColor(0xFF7B95A9.toInt())
        }
    }

    // ── List ──────────────────────────────────────────────────────────────────

    private fun rebuildList() {
        container.removeAllViews()

        val radiusM = selectedRadiusMiles * 1609.34
        val visible = allEntries
            .filter { it.distanceM <= radiusM }
            .sortedBy { it.distanceM }

        if (visible.isEmpty()) {
            subtitle.text = "No hills within $selectedRadiusMiles miles"
            return
        }

        subtitle.text = "${visible.size} hill${if (visible.size == 1) "" else "s"} within $selectedRadiusMiles miles  ·  times are to the summit, one way"

        for (entry in visible) {
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

            row.findViewById<TextView>(R.id.hillDistance).text = formatMiles(entry.distanceM)
            row.findViewById<TextView>(R.id.hillEta).text = "to summit " + estimateTime(entry.hill, entry.distanceM)

            val grade = RouteWarningPolicy.gradeForCategory(entry.hill.category)
            val gradeView = row.findViewById<TextView>(R.id.hillGrade)
            gradeView.text = RouteWarningPolicy.gradeLabel(grade)
            gradeView.setTextColor(when (grade) {
                RouteWarningPolicy.DifficultyGrade.MODERATE_WALK   -> 0xFF4CAF50.toInt()
                RouteWarningPolicy.DifficultyGrade.STRENUOUS_WALK  -> 0xFFFF9800.toInt()
                RouteWarningPolicy.DifficultyGrade.SCRAMBLE        -> 0xFFFFEB3B.toInt()
                RouteWarningPolicy.DifficultyGrade.TECHNICAL_CLIMB -> 0xFFF44336.toInt()
            })

            row.setOnClickListener {
                onHillPicked?.invoke(entry.hill)
                dismiss()
            }

            container.addView(row)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatMiles(m: Double): String {
        val miles = m / 1609.34
        return "${"%.1f".format(miles)} mi"
    }

    /**
     * Naismith's Rule: 4 km/h walking + 1 hr per 600 m ascent.
     * Distance is straight-line from user to summit (conservative overestimate for
     * route distance). Ascent assumes start at sea level — real ascent will be less
     * if the car park is elevated.
     */
    private fun estimateTime(hill: Hill, distanceM: Double): String {
        val distKm    = distanceM / 1000.0
        val ascentM   = hill.elevationM.toDouble()
        val totalMins = (distKm / 4.0 * 60.0 + ascentM / 600.0 * 60.0).toInt()
        val h = totalMins / 60; val m = totalMins % 60
        return if (h > 0) "~${h}h ${m}m" else "~${m}m"
    }
}
