// app/src/main/java/com/example/scottishhillnav/routing/GraphStore.kt
package com.example.scottishhillnav.routing

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object GraphStore {

    private const val TAG = "GraphStore"

    @Volatile
    private var cached: Graph? = null

    fun load(context: Context): Graph {

        cached?.let {
            Log.e(TAG, "GRAPHSTORE_RETURNING_CACHED")
            return it
        }

        synchronized(this) {
            cached?.let {
                Log.e(TAG, "GRAPHSTORE_RETURNING_CACHED_SYNC")
                return it
            }

            Log.e(TAG, "GRAPHSTORE_ASSETS_ONLY_RUNNING_20260219")

            val nodes = readNodesFromAssetsAuto(context, "scotland_pack/nodes.bin")
            Log.e(TAG, "Loaded ${nodes.size} nodes (assets auto format)")

            val edges = readEdgesFromAssets(context, "scotland_pack/edges.bin")
            Log.e(TAG, "Loaded ${edges.size} edge lists")

            var graph = Graph(
                nodes = nodes,
                edges = edges
            )

            // Import curated GPX routes into the graph (deterministic)
            val importer = GpxRouteImporter(
                snapMeters = 20.0,
                attachMeters = 120.0
            )

            // Ledge Route
            graph = importer.importFromAssets(
                context,
                graph,
                GpxRouteImporter.ImportSpec(
                    assetName = "ledge-route-ben-nevis.gpx",
                    startLandmarkKey = "ledge_route_start",
                    endLandmarkKey = "ledge_route_end",
                    requiredMask = Capability.MOUNTAINEERING
                )
            )

            // CMD Arête
            graph = importer.importFromAssets(
                context,
                graph,
                GpxRouteImporter.ImportSpec(
                    assetName = "carnmordeargarete.gpx",
                    startLandmarkKey = "cmd_arete_start",
                    endLandmarkKey = "cmd_arete_end",
                    requiredMask = Capability.SCRAMBLING
                )
            )

            // Ben Lomond — Tourist Route (South Ridge path, Rowardennan → summit)
            graph = importer.importFromAssets(
                context,
                graph,
                GpxRouteImporter.ImportSpec(
                    assetName = "ben-lomond-tourist-route.gpx",
                    startLandmarkKey = "ben_lomond_tourist_start",
                    endLandmarkKey = "ben_lomond_tourist_end",
                    requiredMask = Capability.WALKING,
                    routeSequenceKey = "lomond_tourist"
                )
            )

            // Ben Lomond — Ptarmigan Route (Ptarmigan Ridge, Rowardennan → summit)
            graph = importer.importFromAssets(
                context,
                graph,
                GpxRouteImporter.ImportSpec(
                    assetName = "ben-lomond-ptarmigan-route.gpx",
                    startLandmarkKey = "ben_lomond_ptarmigan_start",
                    endLandmarkKey = "ben_lomond_ptarmigan_end",
                    requiredMask = Capability.WALKING,
                    routeSequenceKey = "lomond_ptarmigan"
                )
            )

            Log.e(TAG, "Landmarks: ${graph.landmarks.keys.sorted().joinToString()}")

            cached = graph
            return graph
        }
    }

    private enum class NodeFormat(val bytesPerNode: Int) {
        V0_ID_LAT_LON(12),
        V1_ID_META_LAT_LON(16),
        V2_ID_META_LAT_LON_ELE(20)
    }

    /**
     * Reads the entire asset file into a ByteArray in one call, then parses
     * via ByteBuffer. This is ~5-10× faster than reading field-by-field with
     * DataInputStream, which issues a separate native I/O call per readInt/readFloat
     * — ~2.5 M calls for the bundled Scotland pack.
     *
     * Also avoids the AvailableInputStream.available() unreliability on some Android
     * versions (may return fewer bytes than the actual file size, breaking format detection).
     */
    private fun readNodesFromAssetsAuto(
        context: Context,
        assetPath: String
    ): Map<Int, Node> {
        val bytes = context.assets.open(assetPath).use { BufferedInputStream(it).readBytes() }
        val buf   = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        val count = buf.int
        require(count > 0) { "nodes.bin invalid count=$count" }

        // Derive bytes-per-node from actual file size rather than available() heuristic
        val bytesPerNode = (bytes.size - 4) / count

        val format = when (bytesPerNode) {
            NodeFormat.V0_ID_LAT_LON.bytesPerNode       -> NodeFormat.V0_ID_LAT_LON
            NodeFormat.V1_ID_META_LAT_LON.bytesPerNode  -> NodeFormat.V1_ID_META_LAT_LON
            NodeFormat.V2_ID_META_LAT_LON_ELE.bytesPerNode -> NodeFormat.V2_ID_META_LAT_LON_ELE
            else -> throw IllegalStateException(
                "Unsupported nodes.bin record size: bytesPerNode=$bytesPerNode (expected 12, 16, or 20)"
            )
        }

        Log.e(TAG, "nodes.bin format=$format count=$count bytesPerNode=$bytesPerNode")

        // Size for 0.75 load factor — avoids any rehash during population
        val nodes = HashMap<Int, Node>((count * 4 + 2) / 3)

        repeat(count) {
            when (format) {
                NodeFormat.V0_ID_LAT_LON -> {
                    val id  = buf.int
                    val lat = buf.float.toDouble()
                    val lon = buf.float.toDouble()
                    nodes[id] = Node(lat = lat, lon = lon, elevation = 0.0)
                }
                NodeFormat.V1_ID_META_LAT_LON -> {
                    val id  = buf.int
                    buf.int  // meta field — unused
                    val lat = buf.float.toDouble()
                    val lon = buf.float.toDouble()
                    nodes[id] = Node(lat = lat, lon = lon, elevation = 0.0)
                }
                NodeFormat.V2_ID_META_LAT_LON_ELE -> {
                    val id  = buf.int
                    buf.int  // meta field — unused
                    val lat = buf.float.toDouble()
                    val lon = buf.float.toDouble()
                    val ele = buf.float.toDouble()
                    nodes[id] = Node(lat = lat, lon = lon, elevation = ele)
                }
            }
        }

        return nodes
    }

    private fun readEdgesFromAssets(
        context: Context,
        assetPath: String
    ): Map<Int, List<Edge>> {
        val bytes = context.assets.open(assetPath).use { BufferedInputStream(it).readBytes() }
        val buf   = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        val count = buf.int
        // Pre-size assuming ~2 edges per node on average; avoids repeated rehashing
        val edges = HashMap<Int, MutableList<Edge>>(count / 2 + 16)

        repeat(count) {
            val from = buf.int
            val to   = buf.int
            val cost = buf.float.toDouble()
            edges.getOrPut(from) { mutableListOf() }.add(Edge(to = to, cost = cost))
        }

        return edges.mapValues { it.value.toList() }
    }
}
