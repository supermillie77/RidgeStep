package com.example.scottishhillnav.hills

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

object HillSearchService {

    // Cache car park results keyed on rounded lat/lon so repeated selections of the
    // same mountain always produce an identical list (Nominatim probes are non-deterministic).
    // Call clearCache() to force a fresh fetch (e.g. after settings change).
    private val carParkCache = HashMap<String, List<CarPark>>()

    // Gaelic/Scots words that identify natural features (peaks, corries, glens, lochs).
    // Includes lenited (mutated) forms common in compound names (e.g. "Torc-choire").
    // ANY word in the name matching this set means it is a natural feature, not a settlement.
    private val naturalFeatureWords = setOf(
        "coire", "choire", "corrie", "stob", "sgurr", "sgorr", "beinn", "bheinn",
        "meall", "mheall", "creag", "chreag", "allt", "loch", "lochan", "gleann",
        "ghleann", "sron", "garbh", "bealach", "bhealach", "druim", "cnoc", "torr",
        "aonach", "bidean", "rannoch", "cairn", "stac", "mam", "caisteal", "ruadh",
        "dearg", "dubh", "mor", "beag", "liath"
    )

    // Only include place types that represent driveable destinations. Excludes locality,
    // isolated_dwelling, and farm — these are vague or off-road named areas (e.g. Folach)
    // that cannot be used as car-park starting points.
    private const val PLACE_REGEX =
        "^(city|town|village|hamlet|suburb|neighbourhood|quarter)$"

    fun clearCache() = carParkCache.clear()

    data class HillResult(
        val name: String,
        val area: String,
        val lat: Double,
        val lon: Double,
        val isRoute: Boolean = false,   // true for named walking routes (not summits)
        val elevationM: Int? = null
    ) {
        val displayLabel: String get() = buildString {
            append(name)
            if (area.isNotEmpty()) append(", $area")
            if (isRoute) append("  (walk)")
        }
    }

    /** Query Nominatim for natural=peak/hill matching [query], restricted to GB. */
    fun search(query: String): List<HillResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL(
            "https://nominatim.openstreetmap.org/search" +
                "?q=$encoded&countrycodes=gb&format=json&limit=30&addressdetails=1&extratags=1"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "ScottishHillNav/1.0 (android)")
        conn.connectTimeout = 8_000
        conn.readTimeout   = 8_000
        return try {
            val array = JSONArray(conn.inputStream.bufferedReader().readText())
            val results = mutableListOf<HillResult>()
            for (i in 0 until array.length()) {
                val obj  = array.getJSONObject(i)
                val cls  = obj.optString("class", "")
                val type = obj.optString("type", "")
                val isPeak  = cls == "natural" && type in setOf("peak", "hill", "ridge")
                val isRoute = cls == "route"   && type in setOf("hiking", "foot", "walking")
                if (!isPeak && !isRoute) continue
                val lat  = obj.getString("lat").toDoubleOrNull() ?: continue
                val lon  = obj.getString("lon").toDoubleOrNull() ?: continue
                val displayName = obj.getString("display_name")
                val displayParts = displayName.split(",").map { it.trim() }
                val addr = obj.optJSONObject("address")
                val name = addr?.optString("peak")?.takeIf { it.isNotEmpty() }
                    ?: displayParts.firstOrNull().orEmpty()
                // Prefer the most specific locality (hamlet/village/town) for a human-friendly
                // area label (e.g. "Crianlarich" or "Isle of Mull") rather than a county name.
                val ignored = setOf("Scotland", "Wales", "England", "United Kingdom",
                                    "Northern Ireland", "GB")
                val area = addr?.run {
                    optString("hamlet").ifEmpty {
                        optString("village").ifEmpty {
                            optString("town").ifEmpty {
                                optString("municipality")
                            }
                        }
                    }
                }.orEmpty().ifEmpty {
                    // Second component of display_name is usually the nearest settlement
                    displayParts.getOrNull(1)?.takeIf { it !in ignored }.orEmpty()
                }.ifEmpty {
                    addr?.optString("county").orEmpty()
                        .ifEmpty { addr?.optString("state_district").orEmpty() }
                }
                val ele = obj.optJSONObject("extratags")?.optString("ele")
                    ?.toDoubleOrNull()?.toInt()
                // Deduplicate by name+area
                if (results.none { it.name == name && it.area == area }) {
                    results.add(HillResult(name, area, lat, lon, isRoute, ele))
                }
            }
            results
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Returns named car parks within 25 km of the mountain and ferry access points.
     * Settlements are NOT included — they can be across water barriers (lochs, sounds)
     * with no road connection. The only reliable walk-start indicators are:
     *  1. amenity=parking nodes/ways — actual car parks
     *  2. route=ferry relations — authoritative ferry pairs via from/to tags
     */
    fun findNearbyCarParks(lat: Double, lon: Double): List<CarPark> {
        val cacheKey = "%.4f,%.4f".format(lat, lon)
        carParkCache[cacheKey]?.let { return it }

        fun isNaturalFeature(name: String): Boolean {
            val words = name.lowercase().split(" ", "-", "'", "_")
            return words.any { it in naturalFeatureWords }
        }

        val carParks       = mutableListOf<CarPark>()
        // Settlements collected only for ferry from/to name resolution — NOT for output.
        val settlements    = mutableListOf<CarPark>()
        val ferryTerms     = mutableListOf<CarPark>()
        val ferryRouteNames= mutableListOf<Pair<String, String>>()

        // ── Query 1: car parks within 30 km ──────────────────────────────────
        // 30 km catches remote road-end car parks (e.g. Kinlochhourn, ~23 km from
        // Ladhar Bheinn) that a smaller radius would miss.
        try {
            val q = URLEncoder.encode(
                "[out:json][timeout:45];" +
                    "(node[\"amenity\"=\"parking\"](around:30000,$lat,$lon);" +
                    "way[\"amenity\"=\"parking\"](around:30000,$lat,$lon););" +
                    "out center tags;",
                "UTF-8"
            )
            val conn = URL("https://overpass-api.de/api/interpreter?data=$q")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "ScottishHillNav/1.0 (android)")
            conn.connectTimeout = 45_000; conn.readTimeout = 45_000
            val elements = try {
                JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("elements")
            } finally { conn.disconnect() }
            for (i in 0 until elements.length()) {
                val el   = elements.getJSONObject(i)
                val tags = el.optJSONObject("tags")
                val eLat = if (el.has("center")) el.getJSONObject("center").getDouble("lat")
                           else el.optDouble("lat", 0.0)
                val eLon = if (el.has("center")) el.getJSONObject("center").getDouble("lon")
                           else el.optDouble("lon", 0.0)
                if (eLat == 0.0 && eLon == 0.0) continue
                val name = tags?.optString("name", "").orEmpty()
                if (name.isEmpty() || !isNaturalFeature(name))
                    carParks.add(CarPark(name.ifEmpty { "Car park" }, eLat, eLon))
            }
        } catch (e: Exception) { /* continue */ }

        // ── Query 2: ferry routes + terminals + nearby settlements ────────────
        // Ferry route relations are included in the output so their from/to tags can be
        // read directly — the authoritative source for the ferry pair regardless of how
        // individual terminal nodes happen to be tagged.
        // Settlements are fetched only to supply coordinates when resolving from/to names.
        try {
            val q = URLEncoder.encode(
                "[out:json][timeout:60];" +
                    "relation[\"route\"=\"ferry\"](around:35000,$lat,$lon)->.fr;" +
                    "(.fr;" +
                    "node[\"place\"~\"$PLACE_REGEX\"](around:30000,$lat,$lon);" +
                    "way[\"place\"~\"$PLACE_REGEX\"](around:30000,$lat,$lon);" +
                    "node[\"amenity\"=\"ferry_terminal\"](around:35000,$lat,$lon);" +
                    "node[\"man_made\"~\"^(pier|jetty)$\"][\"name\"](around:35000,$lat,$lon);" +
                    "node[\"ferry\"=\"yes\"][\"name\"](around:35000,$lat,$lon);" +
                    "node(r.fr)[\"name\"];" +
                    ");" +
                    "out center tags;",
                "UTF-8"
            )
            val conn = URL("https://overpass-api.de/api/interpreter?data=$q")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "ScottishHillNav/1.0 (android)")
            conn.connectTimeout = 60_000; conn.readTimeout = 60_000
            val elements = try {
                JSONObject(conn.inputStream.bufferedReader().readText()).getJSONArray("elements")
            } finally { conn.disconnect() }
            for (i in 0 until elements.length()) {
                val el   = elements.getJSONObject(i)
                val tags = el.optJSONObject("tags")
                if (el.optString("type") == "relation") {
                    // Try from/to tags first, then parse the name "Mallaig – Inverie"
                    val from = tags?.optString("from", "").orEmpty().trim()
                    val to   = tags?.optString("to",   "").orEmpty().trim()
                    if (from.isNotEmpty() && to.isNotEmpty()) {
                        ferryRouteNames.add(from to to)
                    } else {
                        val rName = tags?.optString("name", "").orEmpty().trim()
                        val parts = rName.split(Regex("\\s*[–—-]\\s*|\\bto\\b"), limit = 2)
                        if (parts.size == 2) {
                            val p0 = parts[0].trim(); val p1 = parts[1].trim()
                            if (p0.isNotEmpty() && p1.isNotEmpty()) ferryRouteNames.add(p0 to p1)
                        }
                    }
                    continue
                }
                val eLat = if (el.has("center")) el.getJSONObject("center").getDouble("lat")
                           else el.optDouble("lat", 0.0)
                val eLon = if (el.has("center")) el.getJSONObject("center").getDouble("lon")
                           else el.optDouble("lon", 0.0)
                if (eLat == 0.0 && eLon == 0.0) continue
                val amenity     = tags?.optString("amenity",          "").orEmpty()
                val manMade     = tags?.optString("man_made",         "").orEmpty()
                val ferry       = tags?.optString("ferry",            "").orEmpty()
                val pubTransport= tags?.optString("public_transport", "").orEmpty()
                val place       = tags?.optString("place",            "").orEmpty()
                val name        = tags?.optString("name",             "").orEmpty()
                when {
                    (amenity == "ferry_terminal" || manMade == "pier" || manMade == "jetty"
                            || ferry == "yes" || pubTransport == "stop_position") && name.isNotEmpty() ->
                        ferryTerms.add(CarPark(name, eLat, eLon))
                    place.isNotEmpty() && name.isNotEmpty() && !isNaturalFeature(name) ->
                        settlements.add(CarPark(name, eLat, eLon))
                    place.isEmpty() && amenity.isEmpty() && manMade.isEmpty()
                            && ferry.isEmpty() && name.isNotEmpty() && !isNaturalFeature(name) ->
                        ferryTerms.add(CarPark(name, eLat, eLon))
                }
            }
        } catch (e: Exception) { /* continue */ }

        // ── Build ferry pairs ─────────────────────────────────────────────────
        val ferryAccess = mutableListOf<CarPark>()

        // Pass 1: pair explicitly tagged terminals
        for (i in ferryTerms.indices) {
            for (j in i + 1 until ferryTerms.size) {
                val a = ferryTerms[i]; val b = ferryTerms[j]
                val (closer, farther) =
                    if (haversine(lat, lon, a.lat, a.lon) <= haversine(lat, lon, b.lat, b.lon))
                        a to b else b to a
                if (haversine(lat, lon, closer.lat, closer.lon) > 25_000) continue
                ferryAccess.add(CarPark(
                    name   = "${closer.name} (ferry from ${farther.name})",
                    lat    = closer.lat, lon    = closer.lon,
                    navLat = farther.lat, navLon = farther.lon
                ))
            }
        }

        // Pass 2: use authoritative from/to tags from route=ferry relations.
        // Resolves place names against settlements + ferryTerms, falling back to a
        // Nominatim name search so Inverie is found even without an explicit OSM terminal node.
        val allPlaces = settlements + ferryTerms
        for ((fromName, toName) in ferryRouteNames) {
            fun findPlace(target: String): CarPark? =
                allPlaces.firstOrNull { it.name.equals(target, ignoreCase = true) }
                    ?: allPlaces.firstOrNull { target.contains(it.name, ignoreCase = true) && it.name.length >= 4 }
                    ?: nominatimSearchByName(target, lat, lon)

            val fromPlace = findPlace(fromName) ?: continue
            val toPlace   = findPlace(toName)   ?: continue
            val (closer, farther) =
                if (haversine(lat, lon, fromPlace.lat, fromPlace.lon) <=
                    haversine(lat, lon, toPlace.lat,   toPlace.lon))
                    fromPlace to toPlace else toPlace to fromPlace
            if (haversine(lat, lon, closer.lat, closer.lon) > 25_000) continue
            val closerKey = closer.name.lowercase()
            if (ferryAccess.any { it.name.substringBefore(" (ferry").lowercase() == closerKey }) continue
            ferryAccess.add(CarPark(
                name   = "${closer.name} (ferry from ${farther.name})",
                lat    = closer.lat, lon    = closer.lon,
                navLat = farther.lat, navLon = farther.lon
            ))
        }

        // ── Enrich car park area labels ───────────────────────────────────────
        // Use the settlements fetched in Query 2 (used for ferry name resolution) to add
        // a location label to each car park so the user sees e.g. "Car park, Kinlochhourn"
        // instead of a bare "Car park".
        val enrichedParks = carParks.map { cp ->
            if (cp.area.isNotEmpty()) return@map cp   // already labelled
            val near = settlements.minByOrNull { haversine(cp.lat, cp.lon, it.lat, it.lon) }
            if (near != null && haversine(cp.lat, cp.lon, near.lat, near.lon) < 5_000.0)
                cp.copy(area = near.name)
            else cp
        }

        // ── Deduplicate unnamed car parks within 100 m ────────────────────────
        val dedupedParks = mutableListOf<CarPark>()
        for (cp in enrichedParks) {
            if (cp.name != "Car park" ||
                dedupedParks.none { it.name == "Car park" && haversine(it.lat, it.lon, cp.lat, cp.lon) < 100.0 }
            ) dedupedParks.add(cp)
        }

        // ── Road-end settlement fallback ──────────────────────────────────────
        // Settlements 20–35 km from the mountain are likely road-end access points
        // (e.g. Kinlochhourn at ~23 km for Ladhar Bheinn). Settlements closer than 20 km
        // are excluded because they may be across a loch (e.g. Arnisdale, Corran).
        // Only included when no car park or ferry entry already covers the same spot.
        val roadEnds = settlements
            .filter { s ->
                val d = haversine(lat, lon, s.lat, s.lon)
                d in 20_000.0..35_000.0 &&
                dedupedParks.none  { haversine(it.lat, it.lon, s.lat, s.lon) < 2_000.0 } &&
                ferryAccess.none   { haversine(it.lat, it.lon, s.lat, s.lon) < 2_000.0 }
            }
            .sortedBy { haversine(lat, lon, it.lat, it.lon) }
            .take(3)

        // Only include ferry access if no road-accessible car park exists within 15 km.
        // This prevents Clyde/Firth ferries from appearing for mainland mountains like
        // Ben Lomond, Ben Nevis, etc., while still showing ferry options for genuinely
        // car-inaccessible mountains like Ladhar Bheinn (Knoydart).
        val hasNearbyParking = dedupedParks.any { haversine(lat, lon, it.lat, it.lon) < 15_000.0 }
        val effectiveFerry   = if (hasNearbyParking) emptyList() else ferryAccess

        // Enrich unnamed car parks in the top-10 nearest with a reverse-geocoded locality
        // label so users see "Car park, Rowardennan" instead of a bare "Car park".
        // Settlement enrichment above covers the common case; this handles mountains where
        // the ferry query returned no settlements (typical for road-accessible hills).
        val sortedForLabel = dedupedParks.sortedBy { haversine(lat, lon, it.lat, it.lon) }
        val labelledParks  = sortedForLabel.take(10).map { cp ->
            when {
                cp.area.isNotEmpty()  -> cp          // already has label
                cp.name != "Car park" -> cp          // named parks don't need area label
                else -> reverseGeocodeLocality(cp.lat, cp.lon)
                            ?.let { cp.copy(area = it) } ?: cp
            }
        } + sortedForLabel.drop(10)

        // Output: car parks + ferry access (if needed) + road-end settlements.
        val result = (labelledParks + effectiveFerry + roadEnds)
            .sortedBy { haversine(lat, lon, it.lat, it.lon) }
        if (result.isNotEmpty()) carParkCache[cacheKey] = result
        return result
    }

    /**
     * Nominatim place-name search near [nearLat]/[nearLon]. Used as a fallback to resolve
     * ferry route from/to place names (e.g. "Inverie") when Overpass didn't return a node.
     * Returns the result closest to the reference point, or null on failure.
     */
    private fun nominatimSearchByName(name: String, nearLat: Double, nearLon: Double): CarPark? {
        return try {
            val encoded = URLEncoder.encode(name, "UTF-8")
            val url = URL(
                "https://nominatim.openstreetmap.org/search" +
                    "?q=$encoded&format=json&limit=5&countrycodes=gb"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "ScottishHillNav/1.0 (android)")
            conn.connectTimeout = 6_000; conn.readTimeout = 6_000
            val array = try {
                JSONArray(conn.inputStream.bufferedReader().readText())
            } finally { conn.disconnect() }
            var best: CarPark? = null
            var bestDist = Double.MAX_VALUE
            for (i in 0 until array.length()) {
                val obj  = array.getJSONObject(i)
                val rLat = obj.getString("lat").toDoubleOrNull() ?: continue
                val rLon = obj.getString("lon").toDoubleOrNull() ?: continue
                val dist = haversine(nearLat, nearLon, rLat, rLon)
                if (dist < bestDist) { bestDist = dist; best = CarPark(name, rLat, rLon) }
            }
            best
        } catch (e: Exception) { null }
    }

    /**
     * Nominatim reverse geocode to get the nearest human-readable locality for a coordinate.
     * Returns the most specific available place name (village > hamlet > suburb > town > city),
     * or null if the call fails or returns nothing useful.
     */
    private fun reverseGeocodeLocality(lat: Double, lon: Double): String? {
        return try {
            val url = URL(
                "https://nominatim.openstreetmap.org/reverse" +
                    "?lat=$lat&lon=$lon&format=json&zoom=14&addressdetails=1"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "ScottishHillNav/1.0 (android)")
            conn.connectTimeout = 4_000; conn.readTimeout = 4_000
            val obj = try {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } finally { conn.disconnect() }
            val addr = obj.optJSONObject("address") ?: return null
            addr.optString("village").takeIf { it.isNotEmpty() }
                ?: addr.optString("hamlet").takeIf  { it.isNotEmpty() }
                ?: addr.optString("suburb").takeIf  { it.isNotEmpty() }
                ?: addr.optString("town").takeIf    { it.isNotEmpty() }
                ?: addr.optString("city").takeIf    { it.isNotEmpty() }
                ?: addr.optString("county").takeIf  { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r    = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
