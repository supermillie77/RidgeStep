// app/src/main/java/com/example/scottishhillnav/routing/RouteFamily.kt
package com.example.scottishhillnav.routing

/**
 * A RouteFamily represents a human-understandable route concept
 * (e.g. "CMD Arete", "Tourist Path", "North Face approach").
 *
 * It is NOT directly drawable.
 */
data class RouteFamily(
    val id: String,
    val mountainId: String,
    val name: String,
    val description: String,
    val intent: RouteIntent,
    val visibilityPolicy: VisibilityPolicy,
    val anchorStrategy: AnchorStrategy
)

enum class RouteIntent {
    WALKING,
    MOUNTAINEERING,
    CLIMBING
}

enum class VisibilityPolicy {
    DEFAULT,        // shown to all users
    EXPERIENCED,    // shown but clearly flagged
    HIDDEN          // not shown unless explicitly enabled
}