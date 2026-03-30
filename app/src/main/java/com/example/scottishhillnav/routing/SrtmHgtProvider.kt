package com.example.scottishhillnav.routing

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Offline SRTM .hgt reader.
 *
 * Put tiles in:
 *   /storage/emulated/0/Android/data/<your.package>/files/dem/
 *
 * Expected names:
 *   N56W006.hgt, N56W005.hgt, ...
 *   (lat, lon are the SW corner integer degrees)
 *
 * Supports 1201x1201 (3 arc-sec) and 3601x3601 (1 arc-sec) tiles automatically by file size.
 */
class SrtmHgtProvider(
    context: Context,
    demFolderName: String = "dem"
) : ElevationProvider {

    companion object {
        private const val TAG = "SRTM"
        private const val VOID = -32768
    }

    private val demDir: File = File(context.getExternalFilesDir(null), demFolderName).apply {
        if (!exists()) mkdirs()
    }

    private data class TileKey(val latDeg: Int, val lonDeg: Int)

    private data class Tile(
        val file: File,
        val size: Int,      // 1201 or 3601
        val raf: RandomAccessFile
    )

    // Tracks tile keys known to be absent so the "missing tile" warning fires only once per key.
    private val missingTiles = HashSet<TileKey>()

    private val cache = object : LinkedHashMap<TileKey, Tile>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, Tile>?): Boolean {
            if (size <= 6) return false
            try {
                eldest?.value?.raf?.close()
            } catch (_: Throwable) {}
            return true
        }
    }

    override fun elevationMeters(lat: Double, lon: Double): Double? {
        val latDeg = floor(lat).toInt()
        val lonDeg = floor(lon).toInt()

        val tile = loadTile(latDeg, lonDeg) ?: return null

        // Position inside tile: SRTM tiles are 1°x1°.
        // Sample coordinate mapping:
        //   top row is north edge, left column is west edge.
        //   lat increases northwards, so we invert row.
        val size = tile.size
        val latFrac = lat - latDeg
        val lonFrac = lon - lonDeg

        // Convert to grid coordinates (0..size-1)
        val x = lonFrac * (size - 1)
        val y = (1.0 - latFrac) * (size - 1) // invert so y=0 is north edge

        val x0 = clamp(floor(x).toInt(), 0, size - 1)
        val y0 = clamp(floor(y).toInt(), 0, size - 1)
        val x1 = clamp(x0 + 1, 0, size - 1)
        val y1 = clamp(y0 + 1, 0, size - 1)

        val fx = x - x0
        val fy = y - y0

        val e00 = readSample(tile, x0, y0) ?: return null
        val e10 = readSample(tile, x1, y0) ?: return null
        val e01 = readSample(tile, x0, y1) ?: return null
        val e11 = readSample(tile, x1, y1) ?: return null

        // Bilinear interpolation
        val e0 = e00 * (1.0 - fx) + e10 * fx
        val e1 = e01 * (1.0 - fx) + e11 * fx
        return e0 * (1.0 - fy) + e1 * fy
    }

    private fun loadTile(latDeg: Int, lonDeg: Int): Tile? {
        val key = TileKey(latDeg, lonDeg)
        cache[key]?.let { return it }

        val fileName = buildTileName(latDeg, lonDeg)
        val file = File(demDir, fileName)
        if (!file.exists()) {
            if (missingTiles.add(key)) {   // log only the first time we discover this tile is absent
                Log.w(TAG, "Missing DEM tile: ${file.absolutePath}")
            }
            return null
        }

        val bytes = file.length()
        val size = when (bytes) {
            1201L * 1201L * 2L -> 1201
            3601L * 3601L * 2L -> 3601
            else -> {
                Log.e(TAG, "Unknown HGT size ($bytes bytes): ${file.absolutePath}")
                return null
            }
        }

        val raf = try {
            RandomAccessFile(file, "r")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open HGT: ${file.absolutePath}", t)
            return null
        }

        val tile = Tile(file, size, raf)
        cache[key] = tile
        return tile
    }

    private fun readSample(tile: Tile, x: Int, y: Int): Double? {
        // HGT is big-endian signed 16-bit.
        val idx = y.toLong() * tile.size.toLong() + x.toLong()
        val offset = idx * 2L

        return try {
            tile.raf.seek(offset)
            val hi = tile.raf.read()
            val lo = tile.raf.read()
            if (hi < 0 || lo < 0) return null

            val v = ((hi shl 8) or lo)
            val signed = if (v and 0x8000 != 0) v - 0x10000 else v
            if (signed == VOID) null else signed.toDouble()
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildTileName(latDeg: Int, lonDeg: Int): String {
        val ns = if (latDeg >= 0) "N" else "S"
        val ew = if (lonDeg >= 0) "E" else "W"
        val latAbs = kotlin.math.abs(latDeg)
        val lonAbs = kotlin.math.abs(lonDeg)
        return "%s%02d%s%03d.hgt".format(ns, latAbs, ew, lonAbs)
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(hi, v))
}
