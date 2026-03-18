package com.example.scottishhillnav.routing

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SlopeColors {

    /**
     * slope = rise/run (unitless). Example:
     *  0.10 = 10% uphill
     * -0.10 = 10% downhill
     */
    fun colorForSlope(slope: Double): Int {
        // Clamp to +/- 25% for visualization; steeper saturates.
        val s = slope.coerceIn(-0.25, 0.25)

        // Map slope to color:
        // downhill -> blue
        // flat -> green
        // uphill -> red
        return if (s < 0.0) {
            // blue -> green
            val t = (s + 0.25) / 0.25  // -0.25..0 -> 0..1
            lerpColor(Color.rgb(0, 120, 255), Color.rgb(0, 200, 0), t)
        } else {
            // green -> red
            val t = s / 0.25           // 0..0.25 -> 0..1
            lerpColor(Color.rgb(0, 200, 0), Color.rgb(255, 60, 60), t)
        }
    }

    private fun lerpColor(c1: Int, c2: Int, t: Double): Int {
        val tt = t.coerceIn(0.0, 1.0)
        val r1 = Color.red(c1); val g1 = Color.green(c1); val b1 = Color.blue(c1)
        val r2 = Color.red(c2); val g2 = Color.green(c2); val b2 = Color.blue(c2)
        val r = (r1 + (r2 - r1) * tt).toInt()
        val g = (g1 + (g2 - g1) * tt).toInt()
        val b = (b1 + (b2 - b1) * tt).toInt()
        return Color.rgb(
            r.coerceIn(0, 255),
            g.coerceIn(0, 255),
            b.coerceIn(0, 255)
        )
    }
}
