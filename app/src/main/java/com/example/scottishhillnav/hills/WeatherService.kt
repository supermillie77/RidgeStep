package com.example.scottishhillnav.hills

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Fetches current weather from the Open-Meteo API (free, no API key).
 * Used to warn users when conditions at their location are poor and to
 * suggest nearby Scottish hill areas where weather is clear.
 */
object WeatherService {

    data class AreaWeather(
        val areaName: String,
        val lat: Double,
        val lon: Double,
        val code: Int,          // WMO weather code
        val windKmh: Double
    ) {
        /** Weather is suitable for hillwalking: clear/partly cloudy and not too windy. */
        val isGood: Boolean get() = code <= 3 && windKmh < 40.0
        val isPoor: Boolean get() = !isGood

        val description: String get() = when {
            code == 0          -> "clear sky"
            code in 1..3       -> "partly cloudy"
            code in 45..48     -> "fog"
            code in 51..55     -> "drizzle"
            code in 61..65     -> "rain"
            code in 71..77     -> "snow"
            code in 80..82     -> "rain showers"
            code >= 95         -> "thunderstorm"
            else               -> "overcast"
        }
    }

    /** Preset Scottish hill areas checked when looking for clear conditions. */
    private val SCOTTISH_AREAS = listOf(
        Triple("Cairngorms",  57.12, -3.73),
        Triple("Glencoe",     56.67, -4.96),
        Triple("Torridon",    57.56, -5.47),
        Triple("Skye",        57.21, -6.17),
        Triple("Loch Lomond", 56.19, -4.63),
        Triple("Trossachs",   56.27, -4.33),
        Triple("Moray",       57.65, -3.32),
        Triple("Sutherland",  58.30, -4.56)
    )

    /**
     * Fetches current weather for [lat]/[lon]. Returns null on network failure.
     * Call from a background thread.
     */
    fun fetchCurrent(lat: Double, lon: Double): AreaWeather? {
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=weather_code,wind_speed_10m" +
                "&timezone=Europe%2FLondon&forecast_days=1"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "ScottishHillNav/1.0 (android)")
            conn.connectTimeout = 8_000
            conn.readTimeout    = 8_000
            val json = try {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } finally { conn.disconnect() }
            val current = json.optJSONObject("current") ?: return null
            val code = current.optInt("weather_code", -1)
            val wind = current.optDouble("wind_speed_10m", 0.0)
            if (code < 0) null else AreaWeather("Your location", lat, lon, code, wind)
        } catch (_: Exception) { null }
    }

    /**
     * Checks weather at each preset Scottish hill area and returns those with good conditions.
     * Call from a background thread — makes up to 8 HTTP requests in parallel.
     */
    fun findClearAreas(): List<AreaWeather> {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        val futures = SCOTTISH_AREAS.map { (name, lat, lon) ->
            executor.submit<AreaWeather?> {
                try {
                    val url = URL(
                        "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=$lat&longitude=$lon" +
                        "&current=weather_code,wind_speed_10m" +
                        "&timezone=Europe%2FLondon&forecast_days=1"
                    )
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "ScottishHillNav/1.0 (android)")
                    conn.connectTimeout = 5_000
                    conn.readTimeout    = 5_000
                    val json = try {
                        JSONObject(conn.inputStream.bufferedReader().readText())
                    } finally { conn.disconnect() }
                    val cur  = json.optJSONObject("current") ?: return@submit null
                    val code = cur.optInt("weather_code", -1)
                    val wind = cur.optDouble("wind_speed_10m", 0.0)
                    if (code >= 0) AreaWeather(name, lat, lon, code, wind) else null
                } catch (_: Exception) { null }
            }
        }
        executor.shutdown()
        return try {
            futures.mapNotNull { f ->
                try { f.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                catch (_: Exception) { null }
            }.filter { it.isGood }
        } finally {
            if (!executor.isTerminated) executor.shutdownNow()
        }
    }
}
