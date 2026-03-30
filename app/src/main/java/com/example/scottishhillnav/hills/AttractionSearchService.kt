package com.example.scottishhillnav.hills

import android.util.JsonReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Queries OpenStreetMap (Overpass API) for walk-worthy destinations near a location:
 * waterfalls, castles, historic ruins, viewpoints, beaches, caves and nature reserves.
 *
 * Uses streaming JSON parsing so large responses never cause OOM.
 * Call from a background thread.
 */
object AttractionSearchService {

    private val OVERPASS_ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    /**
     * Returns attractions within [radiusMeters] of ([lat], [lon]).
     * Results are sorted by distance (nearest first).
     * Returns an empty list on network failure rather than throwing.
     */
    fun findNearby(lat: Double, lon: Double, radiusMeters: Int): List<Attraction> {
        val r = radiusMeters
        // "out center;" gives centroid lat/lon for ways (e.g. castle polygons)
        val query = """
            [out:json][timeout:20];
            (
              node["waterway"="waterfall"](around:$r,$lat,$lon);
              node["natural"="waterfall"](around:$r,$lat,$lon);
              node["historic"~"^(castle|ruins|monument|abbey|battlefield|fort|tower)$"](around:$r,$lat,$lon);
              way["historic"~"^(castle|ruins|monument|abbey|battlefield|fort|tower)$"](around:$r,$lat,$lon);
              node["tourism"="viewpoint"](around:$r,$lat,$lon);
              node["tourism"="attraction"]["name"](around:$r,$lat,$lon);
              way["tourism"="attraction"]["name"](around:$r,$lat,$lon);
              node["natural"="beach"]["name"](around:$r,$lat,$lon);
              node["natural"="cave_entrance"](around:$r,$lat,$lon);
              node["leisure"="nature_reserve"]["name"](around:$r,$lat,$lon);
              way["leisure"="nature_reserve"]["name"](around:$r,$lat,$lon);
              node["amenity"="place_of_worship"]["name"]["heritage"](around:$r,$lat,$lon);
            );
            out center;
        """.trimIndent()

        val encoded = URLEncoder.encode(query, "UTF-8")

        for (endpoint in OVERPASS_ENDPOINTS) {
            val conn = try {
                URL("$endpoint?data=$encoded").openConnection() as HttpURLConnection
            } catch (_: Exception) { continue }
            conn.setRequestProperty("User-Agent", "ScottishHillNav/1.0 (android)")
            conn.connectTimeout = 20_000
            conn.readTimeout   = 30_000

            try {
                if (conn.responseCode != 200) { conn.errorStream?.close(); continue }
                val results = parseStream(conn.inputStream.bufferedReader(), lat, lon)
                if (results.isNotEmpty()) return results
            } catch (_: Exception) {
                // Try mirror
            } finally {
                conn.disconnect()
            }
        }
        return emptyList()
    }

    private fun parseStream(
        reader: java.io.Reader,
        refLat: Double,
        refLon: Double
    ): List<Attraction> {
        val attractions = mutableListOf<Attraction>()
        JsonReader(reader).use { jr ->
            jr.beginObject()
            while (jr.hasNext()) {
                if (jr.nextName() == "elements") {
                    jr.beginArray()
                    while (jr.hasNext()) {
                        parseElement(jr)?.let { attractions.add(it) }
                    }
                    jr.endArray()
                } else {
                    jr.skipValue()
                }
            }
        }
        return attractions
            .sortedBy { haversine(refLat, refLon, it.lat, it.lon) }
    }

    private fun parseElement(jr: JsonReader): Attraction? {
        var name:     String? = null
        var lat:      Double? = null
        var lon:      Double? = null
        var osmTag:   String? = null   // OSM tag value driving category mapping

        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "lat"    -> lat = jr.nextDouble()
                "lon"    -> lon = jr.nextDouble()
                "center" -> {
                    jr.beginObject()
                    while (jr.hasNext()) {
                        when (jr.nextName()) {
                            "lat" -> lat = jr.nextDouble()
                            "lon" -> lon = jr.nextDouble()
                            else  -> jr.skipValue()
                        }
                    }
                    jr.endObject()
                }
                "tags"   -> {
                    jr.beginObject()
                    while (jr.hasNext()) {
                        when (jr.nextName()) {
                            "name"    -> name = jr.nextString()
                            "waterway", "natural", "historic", "tourism", "leisure" -> {
                                val v = jr.nextString()
                                if (osmTag == null) osmTag = v
                            }
                            else      -> jr.skipValue()
                        }
                    }
                    jr.endObject()
                }
                else     -> jr.skipValue()
            }
        }
        jr.endObject()

        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lt = lat ?: return null
        val ln = lon ?: return null
        val category = mapCategory(osmTag ?: return null) ?: return null

        return Attraction(name = n, category = category, lat = lt, lon = ln)
    }

    private fun mapCategory(osmValue: String): String? = when (osmValue) {
        "waterfall"        -> "Waterfall"
        "beach"            -> "Beach"
        "cave_entrance"    -> "Cave"
        "castle"           -> "Castle"
        "ruins"            -> "Ruins"
        "monument"         -> "Monument"
        "abbey"            -> "Abbey"
        "battlefield"      -> "Battlefield"
        "fort", "tower"    -> "Fort"
        "viewpoint"        -> "Viewpoint"
        "attraction"       -> "Attraction"
        "nature_reserve"   -> "Nature Reserve"
        else               -> null
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r    = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sinDLat = Math.sin(dLat / 2)
        val sinDLon = Math.sin(dLon / 2)
        val a = sinDLat * sinDLat +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinDLon * sinDLon
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}
