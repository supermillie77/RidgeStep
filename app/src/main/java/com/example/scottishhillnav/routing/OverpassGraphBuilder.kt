package com.example.scottishhillnav.routing

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*
import org.json.JSONObject

/**
 * Downloads walking footpath data from Overpass and builds an in-memory routing Graph.
 * Used as a fallback when the bundled graph has no coverage for the selected mountain area.
 */
object OverpassGraphBuilder {

    private val OVERPASS_ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    /**
     * Fetches ways tagged as footway/path/track/bridleway within [radiusMeters] of the centre
     * and builds a bidirectional routing Graph suitable for A* routing.
     * Tries the primary Overpass server then a mirror. Returns null if all fail.
     */
    fun buildForArea(centerLat: Double, centerLon: Double, radiusMeters: Int = 12_000): Graph? {
        // 20-second server-side timeout matches our HTTP read timeout — fail fast rather than
        // making the user wait 45 seconds only to see no route appear.
        val query = "[out:json][timeout:20];" +
            "(way[\"highway\"~\"^(footway|path|track|bridleway|steps|unclassified|service|residential|tertiary|living_street|pedestrian)$\"]" +
            "(around:$radiusMeters,$centerLat,$centerLon););" +
            "(._;>;);out;"
        val encoded = URLEncoder.encode(query, "UTF-8")

        for (endpoint in OVERPASS_ENDPOINTS) {
            val conn = try {
                URL("$endpoint?data=$encoded").openConnection() as HttpURLConnection
            } catch (_: Exception) { continue }
            conn.setRequestProperty("User-Agent", "ScottishHillNav/1.0 (android)")
            conn.connectTimeout = 20_000
            conn.readTimeout   = 20_000

            val text = try {
                val code = conn.responseCode
                if (code != 200) {
                    conn.errorStream?.bufferedReader()?.readText()
                    null
                } else {
                    conn.inputStream.bufferedReader().readText()
                }
            } catch (_: Exception) { null }
            finally { conn.disconnect() }

            if (text != null) {
                val graph = try { parseResponse(text) } catch (_: Exception) { null }
                if (graph != null) return graph
            }
        }
        return null
    }

    private fun parseResponse(response: String): Graph? {
        val root = JSONObject(response)
        val elements = root.optJSONArray("elements") ?: return null

        // First pass: collect OSM node coordinates and way node-ID sequences
        val osmNodes = HashMap<Long, Node>()
        val ways = mutableListOf<List<Long>>()

        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            when (el.optString("type")) {
                "node" -> {
                    val id  = el.getLong("id")
                    val lat = el.getDouble("lat")
                    val lon = el.getDouble("lon")
                    osmNodes[id] = Node(lat = lat, lon = lon, elevation = 0.0)
                }
                "way" -> {
                    val arr = el.optJSONArray("nodes") ?: continue
                    val ids = (0 until arr.length()).map { arr.getLong(it) }
                    if (ids.size >= 2) ways.add(ids)
                }
            }
        }

        if (osmNodes.isEmpty() || ways.isEmpty()) return null

        // Map OSM Long IDs → graph Int IDs, starting high to avoid collisions with bundled graph
        val idMap = HashMap<Long, Int>(osmNodes.size * 2)
        val nodes = HashMap<Int, Node>(osmNodes.size * 2)
        var nextId = 1_000_000
        for ((osmId, node) in osmNodes) {
            idMap[osmId] = nextId
            nodes[nextId] = node
            nextId++
        }

        // Build bidirectional edges using OSM way topology
        val edgeMap = HashMap<Int, MutableList<Edge>>()
        for (wayNodes in ways) {
            for (j in 0 until wayNodes.size - 1) {
                val aId = idMap[wayNodes[j]]     ?: continue
                val bId = idMap[wayNodes[j + 1]] ?: continue
                val a   = nodes[aId] ?: continue
                val b   = nodes[bId] ?: continue
                val dist = haversine(a.lat, a.lon, b.lat, b.lon)
                edgeMap.getOrPut(aId) { mutableListOf() }
                    .add(Edge(to = bId, cost = dist, requiredMask = Capability.WALKING))
                edgeMap.getOrPut(bId) { mutableListOf() }
                    .add(Edge(to = aId, cost = dist, requiredMask = Capability.WALKING))
            }
        }

        return Graph(nodes = nodes, edges = edgeMap.mapValues { it.value.toList() })
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
