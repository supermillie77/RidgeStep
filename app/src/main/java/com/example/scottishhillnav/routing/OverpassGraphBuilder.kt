package com.example.scottishhillnav.routing

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

/**
 * Downloads walking footpath data from Overpass and builds an in-memory routing Graph.
 * Used as a fallback when the bundled graph has no coverage for the selected mountain area.
 *
 * Response is stream-parsed with [JsonReader] so the full JSON body is never held in memory —
 * previously [readText] + [JSONObject] tried to allocate a single String for the entire response
 * (~130 MB for busy lowland areas), causing an OutOfMemoryError.
 */
object OverpassGraphBuilder {

    private val OVERPASS_ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    /**
     * Fetches ways tagged as footway/path/track etc. within [radiusMeters] of the centre
     * and builds a bidirectional routing Graph suitable for A* routing.
     * Tries the primary Overpass server then a mirror. Returns null if all fail.
     */
    fun buildForArea(centerLat: Double, centerLon: Double, radiusMeters: Int = 12_000): Graph? {
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
            conn.readTimeout   = 60_000   // large areas can take longer to transfer

            try {
                val code = conn.responseCode
                if (code != 200) {
                    conn.errorStream?.close()
                    continue
                }
                val graph = parseStream(conn.inputStream)
                if (graph != null) return graph
            } catch (_: Exception) {
                // Try next endpoint
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /**
     * Stream-parses the Overpass JSON response directly from [input] without buffering
     * the full body into a String. This keeps peak memory usage proportional to the
     * number of graph nodes/edges rather than the raw text size.
     *
     * Expected format:
     * ```
     * { "elements": [
     *   { "type":"node", "id":123, "lat":56.0, "lon":-3.0 },
     *   { "type":"way",  "id":456, "nodes":[123,124,125]  },
     *   ...
     * ]}
     * ```
     */
    private fun parseStream(input: InputStream): Graph? {
        val osmNodes = HashMap<Long, Node>()
        val ways     = mutableListOf<LongArray>()

        JsonReader(input.bufferedReader()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "elements") {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        parseElement(reader, osmNodes, ways)
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
            }
            // Don't endObject() — we may stop reading early; JsonReader.close() handles cleanup
        }

        return buildGraph(osmNodes, ways)
    }

    private fun parseElement(
        reader: JsonReader,
        osmNodes: HashMap<Long, Node>,
        ways: MutableList<LongArray>
    ) {
        var type: String? = null
        var id:   Long    = 0L
        var lat:  Double  = 0.0
        var lon:  Double  = 0.0
        var nodeRefs: LongArray? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "type"  -> type = reader.nextString()
                "id"    -> id   = reader.nextLong()
                "lat"   -> lat  = reader.nextDouble()
                "lon"   -> lon  = reader.nextDouble()
                "nodes" -> {
                    val refs = mutableListOf<Long>()
                    reader.beginArray()
                    while (reader.hasNext()) refs.add(reader.nextLong())
                    reader.endArray()
                    nodeRefs = refs.toLongArray()
                }
                else    -> reader.skipValue()
            }
        }
        reader.endObject()

        when (type) {
            "node" -> osmNodes[id] = Node(lat = lat, lon = lon, elevation = 0.0)
            "way"  -> nodeRefs?.takeIf { it.size >= 2 }?.let { ways.add(it) }
        }
    }

    private fun buildGraph(osmNodes: HashMap<Long, Node>, ways: List<LongArray>): Graph? {
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
