package com.example.scottishhillnav.ui

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.scottishhillnav.R
import com.example.scottishhillnav.hills.Attraction
import com.example.scottishhillnav.hills.AttractionSearchService
import com.example.scottishhillnav.hills.Hill
import com.example.scottishhillnav.routing.RouteWarningPolicy
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.roundToInt

class NearbyHillsSheet : BottomSheetDialogFragment() {

    data class Entry(val hill: Hill, val distanceM: Double)

    // ── Inputs set by caller ──────────────────────────────────────────────────
    var allEntries: List<Entry> = emptyList()
    /** Reference location used for attraction searches. */
    var refLat: Double = 0.0
    var refLon: Double = 0.0
    var onHillPicked: ((Hill) -> Unit)? = null
    var onAttractionPicked: ((Attraction) -> Unit)? = null

    // ── Internal state ────────────────────────────────────────────────────────
    private enum class Mode { HILLS, WALKS }
    private var mode = Mode.HILLS

    private val radiusOptions = intArrayOf(5, 10, 15, 20, 25)
    private var selectedRadiusMiles = 10

    /** Cached attraction results per radius so switching mode doesn't re-fetch. */
    private val attractionCache = HashMap<Int, List<Attraction>>()
    private var attractionFetchInProgress = false

    private lateinit var subtitle:   TextView
    private lateinit var container:  LinearLayout
    private lateinit var chipsRow:   LinearLayout
    private lateinit var modeRow:    LinearLayout

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_nearby_hills, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        subtitle  = view.findViewById(R.id.nearbySubtitle)
        container = view.findViewById(R.id.nearbyHillsContainer)
        chipsRow  = view.findViewById(R.id.radiusChipsRow)
        modeRow   = view.findViewById(R.id.modeChipsRow)

        buildModeChips()
        buildRadiusChips()
        rebuildList()
    }

    override fun onStart() {
        super.onStart()
        val d = dialog as? BottomSheetDialog ?: return
        val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(sheet)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    // ── Mode chips ────────────────────────────────────────────────────────────

    private fun buildModeChips() {
        modeRow.removeAllViews()
        val dp   = resources.displayMetrics.density
        val hPad = (16 * dp).toInt()
        val vPad = (7  * dp).toInt()
        val gap  = (8  * dp).toInt()

        fun addModeChip(label: String, chipMode: Mode) {
            val chip = TextView(requireContext()).apply {
                text = label
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(hPad, vPad, hPad, vPad)
                setOnClickListener {
                    if (mode != chipMode) {
                        mode = chipMode
                        buildModeChips()
                        rebuildList()
                    }
                }
                if (chipMode == mode) {
                    setBackgroundColor(0xFFFF6B35.toInt())
                    setTextColor(0xFFFFFFFF.toInt())
                } else {
                    setBackgroundColor(0xFF1E3347.toInt())
                    setTextColor(0xFF7B95A9.toInt())
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, gap, 0) }
            modeRow.addView(chip, params)
        }

        addModeChip("Hills", Mode.HILLS)
        addModeChip("Walks & places", Mode.WALKS)
    }

    // ── Radius chips ──────────────────────────────────────────────────────────

    private fun buildRadiusChips() {
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
                        buildRadiusChips()
                        rebuildList()
                    }
                }
                if (miles == selectedRadiusMiles) {
                    setBackgroundColor(0xFFFF6B35.toInt())
                    setTextColor(0xFFFFFFFF.toInt())
                } else {
                    setBackgroundColor(0xFF1E3347.toInt())
                    setTextColor(0xFF7B95A9.toInt())
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, gap, 0) }
            chipsRow.addView(chip, params)
        }
    }

    // ── List ──────────────────────────────────────────────────────────────────

    private fun rebuildList() {
        when (mode) {
            Mode.HILLS -> showHills()
            Mode.WALKS -> showAttractions()
        }
    }

    private fun showHills() {
        container.removeAllViews()
        val radiusM = selectedRadiusMiles * 1609.34
        val visible = allEntries.filter { it.distanceM <= radiusM }.sortedBy { it.distanceM }

        if (visible.isEmpty()) {
            subtitle.text = "No hills within $selectedRadiusMiles miles"
            return
        }
        subtitle.text = "${visible.size} hill${if (visible.size == 1) "" else "s"} within $selectedRadiusMiles miles  ·  times to summit, one way"

        for (entry in visible) {
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_nearby_hill, container, false)

            row.findViewById<View>(R.id.categoryBar)
                .setBackgroundColor(SummitOverlay.categoryColor(entry.hill.category))
            row.findViewById<TextView>(R.id.hillName).text = entry.hill.name

            val meta = buildString {
                append(entry.hill.category)
                if (entry.hill.area.isNotEmpty()) append("  ·  ${entry.hill.area}")
                if (entry.hill.elevationM > 0)   append("  ·  ${entry.hill.elevationM} m")
            }
            row.findViewById<TextView>(R.id.hillMeta).text = meta
            row.findViewById<TextView>(R.id.hillDistance).text = formatMiles(entry.distanceM)
            row.findViewById<TextView>(R.id.hillEta).text = "to summit " + hillTime(entry.hill, entry.distanceM)

            val grade = RouteWarningPolicy.gradeForCategory(entry.hill.category)
            val gradeView = row.findViewById<TextView>(R.id.hillGrade)
            gradeView.text = RouteWarningPolicy.gradeLabel(grade)
            gradeView.setTextColor(gradeColor(grade))

            row.setOnClickListener { onHillPicked?.invoke(entry.hill); dismiss() }
            container.addView(row)
        }
    }

    private fun showAttractions() {
        container.removeAllViews()

        val cached = attractionCache[selectedRadiusMiles]
        if (cached != null) {
            populateAttractions(cached)
            return
        }

        // Show loading state while fetching
        subtitle.text = "Searching within $selectedRadiusMiles miles…"
        val loadingRow = TextView(requireContext()).apply {
            text = "Loading walks & places…"
            textSize = 13f
            setTextColor(0xFF7B95A9.toInt())
            setPadding(
                (20 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                0, 0
            )
        }
        container.addView(loadingRow)

        if (attractionFetchInProgress) return
        attractionFetchInProgress = true

        val radiusM = (selectedRadiusMiles * 1609.34).toInt()
        val fetchLat = refLat
        val fetchLon = refLon
        val fetchRadius = selectedRadiusMiles

        Thread {
            val results = try {
                AttractionSearchService.findNearby(fetchLat, fetchLon, radiusM)
            } catch (_: Exception) { emptyList() }

            mainHandler.post {
                attractionFetchInProgress = false
                if (!isAdded) return@post
                attractionCache[fetchRadius] = results
                // Only apply if the user hasn't switched away
                if (mode == Mode.WALKS && selectedRadiusMiles == fetchRadius) {
                    container.removeAllViews()
                    populateAttractions(results)
                }
            }
        }.start()
    }

    private fun populateAttractions(attractions: List<Attraction>) {
        val radiusM = selectedRadiusMiles * 1609.34
        // Filter to radius (already sorted by AttractionSearchService)
        val visible = attractions.filter {
            haversine(refLat, refLon, it.lat, it.lon) <= radiusM
        }

        if (visible.isEmpty()) {
            subtitle.text = "No walks & places within $selectedRadiusMiles miles"
            val msg = TextView(requireContext()).apply {
                text = "Try increasing the search radius, or check your connection."
                textSize = 13f
                setTextColor(0xFF7B95A9.toInt())
                setPadding(
                    (20 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(),
                    (20 * resources.displayMetrics.density).toInt(), 0
                )
            }
            container.addView(msg)
            return
        }
        subtitle.text = "${visible.size} walk${if (visible.size == 1) "" else "s"} & places within $selectedRadiusMiles miles"

        for (attraction in visible) {
            val dist = haversine(refLat, refLon, attraction.lat, attraction.lon)
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_nearby_hill, container, false)

            row.findViewById<View>(R.id.categoryBar)
                .setBackgroundColor(SummitOverlay.categoryColor(attraction.category))
            row.findViewById<TextView>(R.id.hillName).text = attraction.name
            row.findViewById<TextView>(R.id.hillMeta).text = attraction.category
            row.findViewById<TextView>(R.id.hillDistance).text = formatMiles(dist)
            row.findViewById<TextView>(R.id.hillEta).text = "~" + walkTime(dist)
            row.findViewById<TextView>(R.id.hillGrade).apply {
                text = "🟢 Easy low-level walk"
                setTextColor(0xFF4CAF50.toInt())
            }

            row.setOnClickListener { onAttractionPicked?.invoke(attraction); dismiss() }
            container.addView(row)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatMiles(m: Double) = "${"%.1f".format(m / 1609.34)} mi"

    /** Naismith for hills: 4 km/h walking + 1 hr per 600 m ascent. */
    private fun hillTime(hill: Hill, distanceM: Double): String {
        val totalMins = (distanceM / 1000.0 / 4.0 * 60.0 + hill.elevationM / 600.0 * 60.0).toInt()
        val h = totalMins / 60; val m = totalMins % 60
        return if (h > 0) "~${h}h ${m}m" else "~${m}m"
    }

    /** Flat walking time: 5 km/h for low-level walks. */
    private fun walkTime(distanceM: Double): String {
        val totalMins = (distanceM / 1000.0 / 5.0 * 60.0).toInt().coerceAtLeast(5)
        val h = totalMins / 60; val m = totalMins % 60
        return if (h > 0) "${h}h ${m}m walk" else "${m}m walk"
    }

    private fun gradeColor(grade: RouteWarningPolicy.DifficultyGrade): Int = when (grade) {
        RouteWarningPolicy.DifficultyGrade.MODERATE_WALK   -> 0xFF4CAF50.toInt()
        RouteWarningPolicy.DifficultyGrade.STRENUOUS_WALK  -> 0xFFFF9800.toInt()
        RouteWarningPolicy.DifficultyGrade.SCRAMBLE        -> 0xFFFFEB3B.toInt()
        RouteWarningPolicy.DifficultyGrade.TECHNICAL_CLIMB -> 0xFFF44336.toInt()
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}
