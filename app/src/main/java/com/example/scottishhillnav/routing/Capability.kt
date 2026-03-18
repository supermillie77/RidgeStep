// app/src/main/java/com/example/scottishhillnav/routing/Capability.kt
package com.example.scottishhillnav.routing

/**
 * Capability requirements are stored as a bitmask Int on each edge.
 * This keeps routing fast and avoids Kotlin/serialization friction.
 */
object Capability {
    const val WALKING: Int = 1 shl 0
    const val SCRAMBLING: Int = 1 shl 1
    const val MOUNTAINEERING: Int = 1 shl 2

    const val ALL: Int = WALKING or SCRAMBLING or MOUNTAINEERING

    fun hasAll(allowedMask: Int, requiredMask: Int): Boolean {
        return (allowedMask and requiredMask) == requiredMask
    }
}
