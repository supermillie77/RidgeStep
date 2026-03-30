package com.example.scottishhillnav

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.scottishhillnav.hills.Hill
import com.example.scottishhillnav.hills.HillRepository
import com.example.scottishhillnav.hills.WalkLogManager
import com.example.scottishhillnav.ui.SummitOverlay

class WalkProgressActivity : AppCompatActivity() {

    /** Known totals per category (approximate, based on official lists). */
    private val CATEGORY_TOTALS = linkedMapOf(
        "Munro"      to 282,
        "Corbett"    to 222,
        "Graham"     to 224,
        "Donald"     to 89,
        "Wainwright" to 214,
        "Hewitt"     to 520,
        "Sub 2000"   to 1387,
        "Fiona"      to 1556,
        "Island"     to 162,
        "Donalds"    to 89
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_walk_progress)

        // Ensure hills are loaded
        if (HillRepository.hills.size <= 1) {
            Thread { HillRepository.initialize(this) }.start()
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnShareLocation).setOnClickListener { shareLocation() }

        populateProgress()
    }

    override fun onResume() {
        super.onResume()
        populateProgress()
    }

    private fun populateProgress() {
        val hills     = HillRepository.hills
        val completed = WalkLogManager.getCompleted(this)

        val totalDone = completed.size
        findViewById<TextView>(R.id.totalCount).text    = totalDone.toString()
        val cats = completed.mapNotNull { id -> hills.find { it.id == id }?.category }
                            .distinct().size
        findViewById<TextView>(R.id.totalSubtitle).text =
            if (cats > 0) "across $cats categor${if (cats == 1) "y" else "ies"}"
            else "Start ticking off summits!"

        val container = findViewById<LinearLayout>(R.id.progressContainer)
        container.removeAllViews()

        val dp = resources.displayMetrics.density

        // ── Category progress cards ───────────────────────────────────────────
        val sectionLabel = buildSectionHeader("Progress by category", dp)
        container.addView(sectionLabel)

        val completedByCategory = WalkLogManager.countByCategory(this, hills)

        // Count totals from CSV (more accurate than hardcoded map)
        val csvTotals = hills.groupingBy { it.category }.eachCount()

        // Ordered categories to show
        val orderedCategories = listOf(
            "Munro", "Corbett", "Graham", "Donald",
            "Wainwright", "Hewitt", "Sub 2000", "Island", "Fiona"
        )
        val shown = mutableSetOf<String>()
        for (cat in orderedCategories) {
            val total = csvTotals[cat] ?: CATEGORY_TOTALS[cat] ?: continue
            shown.add(cat)
            val done  = completedByCategory[cat] ?: 0
            container.addView(buildCategoryCard(cat, done, total, dp))
        }
        // Any extra categories from CSV not in the ordered list
        for ((cat, total) in csvTotals) {
            if (cat !in shown) {
                val done = completedByCategory[cat] ?: 0
                container.addView(buildCategoryCard(cat, done, total, dp))
            }
        }

        // ── Completed hills list ──────────────────────────────────────────────
        if (completed.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Long-press any summit triangle on the map to mark it as climbed."
                textSize = 13f
                setTextColor(0xFF7B95A9.toInt())
                setPadding((20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt(), 0)
                setLineSpacing(0f, 1.4f)
            }
            container.addView(empty)
            return
        }

        container.addView(buildSectionHeader("Completed summits", dp))

        // Group by category, sort alphabetically within each group
        val completedHills = hills.filter { it.id in completed }
            .sortedWith(compareBy({ it.category }, { it.name }))

        var lastCat = ""
        for (hill in completedHills) {
            if (hill.category != lastCat) {
                lastCat = hill.category
                val catHeader = TextView(this).apply {
                    text = hill.category
                    textSize = 11f
                    setTextColor(SummitOverlay.categoryColor(hill.category))
                    setPadding((20 * dp).toInt(), (16 * dp).toInt(), 0, (4 * dp).toInt())
                    isAllCaps = true
                    letterSpacing = 0.08f
                }
                container.addView(catHeader)
            }
            container.addView(buildHillRow(hill, dp))
        }
    }

    private fun buildSectionHeader(title: String, dp: Float): TextView =
        TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF7B95A9.toInt())
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
            isAllCaps = true
            letterSpacing = 0.06f
        }

    private fun buildCategoryCard(
        category: String, done: Int, total: Int, dp: Float
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1B2A.toInt())
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        }

        // Row: colour dot + name + count
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val dot = View(this).apply {
            setBackgroundColor(SummitOverlay.categoryColor(category))
        }
        row.addView(dot, LinearLayout.LayoutParams((10 * dp).toInt(), (10 * dp).toInt()).also {
            it.setMargins(0, 0, (10 * dp).toInt(), 0)
        })

        val nameView = TextView(this).apply {
            text = category
            textSize = 15f
            setTextColor(0xFFE8EDF2.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(nameView)

        val pct = if (total > 0) (done * 100 / total) else 0
        val countView = TextView(this).apply {
            text = "$done / $total  ($pct%)"
            textSize = 13f
            setTextColor(if (done > 0) 0xFFFF6B35.toInt() else 0xFF7B95A9.toInt())
        }
        row.addView(countView)
        card.addView(row)

        // Progress bar
        val barBg = View(this).apply {
            setBackgroundColor(0xFF1E3347.toInt())
        }
        card.addView(barBg, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (6 * dp).toInt()
        ).also { it.topMargin = (8 * dp).toInt() })

        // Fill — custom view
        if (done > 0 && total > 0) {
            val fillView = object : View(this) {
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = SummitOverlay.categoryColor(category)
                }
                override fun onDraw(c: Canvas) {
                    val w = width.toFloat() * done / total
                    c.drawRoundRect(RectF(0f, 0f, w, height.toFloat()), height / 2f, height / 2f, fillPaint)
                }
            }
            // We need to overlay the fill onto barBg; instead just replace it
            // Use a dedicated container
            card.removeView(barBg)
            val barContainer = object : View(this) {
                val bgPaint   = Paint().apply { color = 0xFF1E3347.toInt() }
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = SummitOverlay.categoryColor(category)
                }
                override fun onDraw(c: Canvas) {
                    val h = height.toFloat()
                    c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h/2, h/2, bgPaint)
                    val fw = width.toFloat() * done.toFloat() / total.toFloat()
                    if (fw > 0) c.drawRoundRect(RectF(0f, 0f, fw, h), h/2, h/2, fillPaint)
                }
            }
            card.addView(barContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (6 * dp).toInt()
            ).also { it.topMargin = (8 * dp).toInt() })
        } else {
            // No fill needed — just remove placeholder
        }

        // Bottom margin between cards
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (1 * dp).toInt() }
        card.layoutParams = params
        return card
    }

    private fun buildHillRow(hill: Hill, dp: Float): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), (10 * dp).toInt())
        }

        val stripe = View(this).apply {
            setBackgroundColor(SummitOverlay.categoryColor(hill.category))
        }
        row.addView(stripe, LinearLayout.LayoutParams((3 * dp).toInt(), (36 * dp).toInt()).also {
            it.setMargins(0, 0, (12 * dp).toInt(), 0)
        })

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameView = TextView(this).apply {
            text = hill.name
            textSize = 14f
            setTextColor(0xFFE8EDF2.toInt())
        }
        val metaView = TextView(this).apply {
            text = buildString {
                if (hill.area.isNotEmpty()) append(hill.area)
                if (hill.elevationM > 0) append("  ·  ${hill.elevationM} m")
            }
            textSize = 11f
            setTextColor(0xFF7B95A9.toInt())
        }
        col.addView(nameView)
        col.addView(metaView)
        row.addView(col)

        val tick = TextView(this).apply {
            text = "✓"
            textSize = 16f
            setTextColor(0xFF4CAF50.toInt())
        }
        row.addView(tick)

        // Long-press to remove from log
        row.setOnLongClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(hill.name)
                .setMessage("Remove this summit from your log?")
                .setPositiveButton("Remove") { _, _ ->
                    WalkLogManager.removeCompleted(this, hill.id)
                    populateProgress()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        return row
    }

    private fun shareLocation() {
        val prefs = getSharedPreferences("last_location", MODE_PRIVATE)
        val lat   = prefs.getFloat("lat", Float.NaN)
        val lon   = prefs.getFloat("lon", Float.NaN)
        val bm    = getSystemService(BATTERY_SERVICE) as BatteryManager
        val pct   = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val locText = if (!lat.isNaN() && !lon.isNaN()) {
            val mapsUrl = "https://maps.google.com/?q=$lat,$lon"
            "\uD83D\uDCCD My location: $mapsUrl\n\uD83D\uDD0B Battery: $pct%"
        } else {
            "Location not yet available — open the app and wait for a GPS fix."
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, locText)
        }
        startActivity(Intent.createChooser(intent, "Share my location"))
    }
}
