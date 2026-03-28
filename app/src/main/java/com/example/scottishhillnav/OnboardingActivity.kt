package com.example.scottishhillnav

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

class OnboardingActivity : AppCompatActivity() {

    private val pageLayouts = intArrayOf(
        R.layout.onboard_page_1,
        R.layout.onboard_page_2,
        R.layout.onboard_page_3,
        R.layout.onboard_page_4
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        val pager    = findViewById<ViewPager2>(R.id.onboardViewPager)
        val dots     = findViewById<LinearLayout>(R.id.dotsContainer)
        val btnNext  = findViewById<TextView>(R.id.btnNext)
        val btnSkip  = findViewById<TextView>(R.id.btnSkip)

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
            if (pos == pageLayouts.lastIndex) {
                btnNext.text = "Get started"
                btnSkip.visibility = View.GONE
            } else {
                btnNext.text = "Next  →"
                btnSkip.visibility = View.VISIBLE
            }
        }

        syncUi(0)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = syncUi(position)
        })

        btnNext.setOnClickListener {
            if (pager.currentItem == pageLayouts.lastIndex) done()
            else pager.currentItem += 1
        }
        btnSkip.setOnClickListener { done() }
    }

    private fun buildDots(container: LinearLayout, active: Int) {
        container.removeAllViews()
        val dp = resources.displayMetrics.density
        val dotH   = (8  * dp).toInt()
        val dotW   = (8  * dp).toInt()
        val activeW = (24 * dp).toInt()
        val margin = (5  * dp).toInt()
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
