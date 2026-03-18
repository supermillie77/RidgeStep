package com.example.scottishhillnav.navigation

import kotlin.math.*

/** Converts WGS84 lat/lon to an OS National Grid reference string (e.g. "NN 167 712"). */
object OsGridRef {

    fun fromWGS84(lat: Double, lon: Double): String {
        val (e, n) = wgs84ToNationalGrid(lat, lon)
        return toGridRef(e, n)
    }

    // ── WGS84 → OSGB36 national grid ─────────────────────────────────────────

    private fun wgs84ToNationalGrid(lat: Double, lon: Double): Pair<Double, Double> {
        val φ = Math.toRadians(lat)
        val λ = Math.toRadians(lon)

        // WGS84 ellipsoid
        val aW = 6378137.000
        val bW = 6356752.3142
        val e2W = (aW * aW - bW * bW) / (aW * aW)

        // WGS84 → ECEF
        val νW = aW / sqrt(1 - e2W * sin(φ).pow(2))
        val x = νW * cos(φ) * cos(λ)
        val y = νW * cos(φ) * sin(λ)
        val z = νW * (1 - e2W) * sin(φ)

        // Helmert 7-parameter transform (WGS84 → OSGB36)
        val tx = -446.448; val ty = 125.157; val tz = -542.060
        val s  = 1.0 + 20.4894e-6
        val rx = Math.toRadians(-0.1502 / 3600.0)
        val ry = Math.toRadians(-0.2470 / 3600.0)
        val rz = Math.toRadians(-0.8421 / 3600.0)

        val x2 = tx + s * x  - rz * y + ry * z
        val y2 = ty + rz * x + s * y  - rx * z
        val z2 = tz - ry * x + rx * y + s * z

        // ECEF → OSGB36 (Airy 1830) lat/lon
        val aA = 6377563.396
        val bA = 6356256.910
        val e2A = (aA * aA - bA * bA) / (aA * aA)
        val p = sqrt(x2 * x2 + y2 * y2)

        var φ2 = atan2(z2, p * (1 - e2A))
        repeat(5) {
            val ν2 = aA / sqrt(1 - e2A * sin(φ2).pow(2))
            φ2 = atan2(z2 + e2A * ν2 * sin(φ2), p)
        }
        val λ2 = atan2(y2, x2)

        return transverseMercator(φ2, λ2, aA, bA)
    }

    // ── Transverse Mercator projection (OSGB36) ───────────────────────────────

    private fun transverseMercator(φ: Double, λ: Double, a: Double, b: Double): Pair<Double, Double> {
        val F0 = 0.9996012717
        val φ0 = Math.toRadians(49.0)
        val λ0 = Math.toRadians(-2.0)
        val N0 = -100000.0
        val E0 = 400000.0

        val e2 = (a * a - b * b) / (a * a)
        val n  = (a - b) / (a + b)

        val sinφ = sin(φ); val cosφ = cos(φ); val tanφ = tan(φ)
        val ν = a * F0 / sqrt(1 - e2 * sinφ * sinφ)
        val ρ = a * F0 * (1 - e2) / (1 - e2 * sinφ * sinφ).pow(1.5)
        val η2 = ν / ρ - 1.0

        val Δφ = φ - φ0
        val Σφ = φ + φ0
        val n2 = n * n; val n3 = n * n * n

        val M = b * F0 * (
            (1 + n + 5.0 / 4 * n2 + 5.0 / 4 * n3) * Δφ
            - (3 * n + 3 * n2 + 21.0 / 8 * n3) * sin(Δφ) * cos(Σφ)
            + (15.0 / 8 * n2 + 15.0 / 8 * n3) * sin(2 * Δφ) * cos(2 * Σφ)
            - 35.0 / 24 * n3 * sin(3 * Δφ) * cos(3 * Σφ)
        )

        val I    = M + N0
        val II   = ν / 2 * sinφ * cosφ
        val III  = ν / 24 * sinφ * cosφ.pow(3) * (5 - tanφ * tanφ + 9 * η2)
        val IIIA = ν / 720 * sinφ * cosφ.pow(5) * (61 - 58 * tanφ * tanφ + tanφ.pow(4))
        val IV   = ν * cosφ
        val V    = ν / 6 * cosφ.pow(3) * (ν / ρ - tanφ * tanφ)
        val VI   = ν / 120 * cosφ.pow(5) * (5 - 18 * tanφ * tanφ + tanφ.pow(4) + 14 * η2 - 58 * tanφ * tanφ * η2)

        val Δλ = λ - λ0
        val N = I  + II * Δλ.pow(2) + III * Δλ.pow(4) + IIIA * Δλ.pow(6)
        val E = E0 + IV * Δλ         + V   * Δλ.pow(3) + VI   * Δλ.pow(5)

        return Pair(E, N)
    }

    // ── Grid reference letters & formatting ───────────────────────────────────

    // 25-letter alphabet (I omitted), indexed 0–24
    private const val LETTERS = "ABCDEFGHJKLMNOPQRSTUVWXYZ"

    private fun toGridRef(e: Double, n: Double): String {
        if (e < 0 || n < 0) return "—"

        val majorRow = (n / 500_000).toInt()
        val majorCol = (e / 500_000).toInt()
        val majorIdx = 17 - majorRow * 5 + majorCol
        if (majorIdx !in 0..24) return "—"

        val subRow = ((n % 500_000) / 100_000).toInt()
        val subCol = ((e % 500_000) / 100_000).toInt()
        val subIdx = subRow * 5 + subCol
        if (subIdx !in 0..24) return "—"

        val prefix = "${LETTERS[majorIdx]}${LETTERS[subIdx]}"

        // 6-figure (100 m precision): 3 easting digits + 3 northing digits
        val eIn = (e % 100_000).toInt() / 100
        val nIn = (n % 100_000).toInt() / 100
        return "%s %03d %03d".format(prefix, eIn, nIn)
    }
}
