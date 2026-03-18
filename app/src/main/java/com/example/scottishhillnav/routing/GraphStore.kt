// app/src/main/java/com/example/scottishhillnav/routing/GraphStore.kt
package com.example.scottishhillnav.routing

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.EOFException

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

    private fun readNodesFromAssetsAuto(
        context: Context,
        assetPath: String
    ): Map<Int, Node> {

        context.assets.open(assetPath).use { stream ->
            DataInputStream(stream).use { input ->

                val count = input.readInt()
                require(count > 0) { "nodes.bin invalid count=$count" }

                val payloadBytes = input.available().toLong()
                val bytesPerNode = payloadBytes / count.toLong()

                val format = when (bytesPerNode.toInt()) {
                    NodeFormat.V0_ID_LAT_LON.bytesPerNode -> NodeFormat.V0_ID_LAT_LON
                    NodeFormat.V1_ID_META_LAT_LON.bytesPerNode -> NodeFormat.V1_ID_META_LAT_LON
                    NodeFormat.V2_ID_META_LAT_LON_ELE.bytesPerNode -> NodeFormat.V2_ID_META_LAT_LON_ELE
                    else -> {
                        throw IllegalStateException(
                            "Unsupported nodes.bin record size in assets: bytesPerNode=$bytesPerNode (expected 12, 16, or 20)."
                        )
                    }
                }

                Log.e(TAG, "nodes.bin assets format=$format count=$count bytesPerNode=$bytesPerNode")

                val nodes = HashMap<Int, Node>(count * 2)

                try {
                    repeat(count) {
                        when (format) {

                            NodeFormat.V0_ID_LAT_LON -> {
                                val id = input.readInt()
                                val lat = input.readFloat().toDouble()
                                val lon = input.readFloat().toDouble()
                                nodes[id] = Node(lat = lat, lon = lon, elevation = 0.0)
                            }

                            NodeFormat.V1_ID_META_LAT_LON -> {
                                val id = input.readInt()
                                val meta = input.readInt()
                                val lat = input.readFloat().toDouble()
                                val lon = input.readFloat().toDouble()
                                nodes[id] = Node(lat = lat, lon = lon, elevation = 0.0)
                            }

                            NodeFormat.V2_ID_META_LAT_LON_ELE -> {
                                val id = input.readInt()
                                val meta = input.readInt()
                                val lat = input.readFloat().toDouble()
                                val lon = input.readFloat().toDouble()
                                val ele = input.readFloat().toDouble()
                                nodes[id] = Node(lat = lat, lon = lon, elevation = ele)
                            }
                        }
                    }
                } catch (e: EOFException) {
                    throw IllegalStateException(
                        "nodes.bin assets ended unexpectedly (EOF). count=$count format=$format",
                        e
                    )
                }

                return nodes
            }
        }
    }

    private fun readEdgesFromAssets(
        context: Context,
        assetPath: String
    ): Map<Int, List<Edge>> {

        context.assets.open(assetPath).use { stream ->
            DataInputStream(stream).use { input ->

                val edges = HashMap<Int, MutableList<Edge>>()

                val count = input.readInt()
                repeat(count) {
                    val from = input.readInt()
                    val to = input.readInt()
                    val cost = input.readFloat().toDouble()

                    edges.getOrPut(from) { mutableListOf() }
                        .add(Edge(to = to, cost = cost))
                }

                return edges.mapValues { it.value.toList() }
            }
        }
    }
}
