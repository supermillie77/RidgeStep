package com.example.scottishhillnav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

class OnboardingActivity : AppCompatActivity() {

    private val LOCATION_PERM_REQUEST = 1001

    private val pageLayouts = intArrayOf(
        R.layout.onboard_page_1,
        R.layout.onboard_page_categories,
        R.layout.onboard_page_2,
        R.layout.onboard_page_3,
        R.layout.onboard_page_4,
        R.layout.onboard_page_map_tour,
        R.layout.onboard_page_location    // must be last — "Get started" triggers permission
    )

    private lateinit var pager:   ViewPager2
    private lateinit var btnNext: TextView
    private lateinit var btnSkip: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        pager   = findViewById(R.id.onboardViewPager)
        val dots = findViewById<LinearLayout>(R.id.dotsContainer)
        btnNext  = findViewById(R.id.btnNext)
        btnSkip  = findViewById(R.id.btnSkip)

        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = pageLayouts.size
            override fun getItemViewType(position: Int) = position
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context)
                    .inflate(pageLayouts[viewType], parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
        }

        fun syncUi(pos: Int) {
            buildDots(dots, pos)
            when {
                pos == pageLayouts.lastIndex -> {
                    btnNext.text = "Allow location"
                    btnSkip.text = "Maybe later"
                    btnSkip.visibility = View.VISIBLE
                }
                pos == pageLayouts.lastIndex - 1 -> {
                    btnNext.text = "Next  →"
                    btnSkip.text = "Skip"
                    btnSkip.visibility = View.VISIBLE
                }
                else -> {
                    btnNext.text = "Next  →"
                    btnSkip.text = "Skip"
                    btnSkip.visibility = View.VISIBLE
                }
            }
        }

        syncUi(0)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = syncUi(position)
        })

        btnNext.setOnClickListener {
            if (pager.currentItem == pageLayouts.lastIndex) {
                requestLocationPermission()
            } else {
                pager.currentItem += 1
            }
        }
        btnSkip.setOnClickListener { done() }
    }

    private fun requestLocationPermission() {
        val permission = Manifest.permission.ACCESS_FINE_LOCATION

        // Already granted
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            done(); return
        }

        // Previously asked and user denied (but can ask again)
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(permission, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERM_REQUEST
            )
            return
        }

        // Check if we have previously asked (permanently denied case)
        val alreadyAsked = getSharedPreferences("app_state", MODE_PRIVATE)
            .getBoolean("asked_location_perm", false)

        if (alreadyAsked) {
            // Permission was permanently denied — send user to app settings
            AlertDialog.Builder(this)
                .setTitle("Location permission required")
                .setMessage(
                    "You previously denied location access. Please open Settings, " +
                    "tap Permissions → Location, and select \"Allow only while using the app\"."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                               Uri.fromParts("package", packageName, null))
                    )
                    done()
                }
                .setNegativeButton("Maybe later") { _, _ -> done() }
                .show()
        } else {
            // First time asking
            getSharedPreferences("app_state", MODE_PRIVATE)
                .edit().putBoolean("asked_location_perm", true).apply()
            ActivityCompat.requestPermissions(
                this,
                arrayOf(permission, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERM_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERM_REQUEST) {
            // Proceed regardless of result — MainActivity handles the denied case gracefully
            done()
        }
    }

    private fun buildDots(container: LinearLayout, active: Int) {
        container.removeAllViews()
        val dp = resources.displayMetrics.density
        val dotH    = ( 8 * dp).toInt()
        val dotW    = ( 8 * dp).toInt()
        val activeW = (24 * dp).toInt()
        val margin  = ( 5 * dp).toInt()
        repeat(pageLayouts.size) { i ->
            val v = View(this)
            v.layoutParams = LinearLayout.LayoutParams(
                if (i == active) activeW else dotW, dotH
            ).also { it.setMargins(margin, 0, margin, 0) }
            v.setBackgroundResource(
                if (i == active) R.drawable.dot_active else R.drawable.dot_inactive
            )
            container.addView(v)
        }
    }

    private fun done() {
        getSharedPreferences("app_state", MODE_PRIVATE)
            .edit().putBoolean("seen_welcome", true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
