# Project-specific ProGuard rules for ScottishHillNav

# ── osmdroid ──────────────────────────────────────────────────────────────────
# osmdroid uses reflection to instantiate tile sources and overlays.
-keep class org.osmdroid.** { *; }

# ── Google Play Services Location ─────────────────────────────────────────────
-keep class com.google.android.gms.location.** { *; }

# ── App data classes used with JSON parsing ───────────────────────────────────
# Hill, CarPark, Node, Edge and related routing data classes are accessed by name
# in some paths; keep their public API intact.
-keep class com.example.scottishhillnav.hills.** { *; }
-keep class com.example.scottishhillnav.routing.Node { *; }
-keep class com.example.scottishhillnav.routing.Edge { *; }

# ── Kotlin coroutine internals (if ever added) ────────────────────────────────
-keepnames class kotlinx.coroutines.** { *; }

# ── Standard Android keep rules ───────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
