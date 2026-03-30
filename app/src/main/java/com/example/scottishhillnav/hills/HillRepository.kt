package com.example.scottishhillnav.hills

import android.content.Context
import android.util.Log

object HillRepository {

    private const val TAG = "HillRepository"

    /**
     * All hills loaded from hills.csv.
     * Populated by [initialize] on a background thread before the map is shown.
     * Falls back to the legacy hardcoded list until initialize() completes.
     */
    @Volatile
    var hills: List<Hill> = legacyHills()
        private set

    // Precomputed lookup structures — rebuilt whenever [hills] is replaced.
    // Avoids repeated O(n) scans for common operations.
    @Volatile private var byIdMap:       Map<String, Hill>         = emptyMap()
    @Volatile private var byNameMap:     Map<String, List<Hill>>   = emptyMap()
    @Volatile private var duplicateNames: Set<String>              = emptySet()
    @Volatile private var sortedUniqueNames: List<String>          = emptyList()

    init { rebuildIndexes(hills) }

    private fun rebuildIndexes(list: List<Hill>) {
        byIdMap          = list.associateBy { it.id }
        byNameMap        = list.groupBy { it.name }
        duplicateNames   = byNameMap.filter { it.value.size > 1 }.keys.toHashSet()
        sortedUniqueNames = byNameMap.keys.sorted()
    }

    /**
     * Parses assets/hills.csv and populates [hills].
     * CSV format: id,name,category,area,lat,lon,elevation
     * Call once from a background thread (e.g. alongside GraphStore.load).
     */
    fun initialize(context: Context) {
        try {
            val loaded = mutableListOf<Hill>()
            context.assets.open("hills.csv").bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val cols = line.split(",", limit = 7)
                    if (cols.size < 7) return@forEach
                    val id       = cols[0].trim()
                    val name     = cols[1].trim()
                    val category = cols[2].trim()
                    val area     = cols[3].trim()
                    val lat      = cols[4].trim().toDoubleOrNull() ?: return@forEach
                    val lon      = cols[5].trim().toDoubleOrNull() ?: return@forEach
                    val ele      = cols[6].trim().toIntOrNull() ?: 0
                    loaded += Hill(
                        id         = id,
                        name       = name,
                        area       = area,
                        summitLat  = lat,
                        summitLon  = lon,
                        category   = category,
                        elevationM = ele
                    )
                }
            }
            if (loaded.isNotEmpty()) {
                hills = loaded
                rebuildIndexes(loaded)
                Log.i(TAG, "Loaded ${loaded.size} hills from hills.csv")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load hills.csv — using legacy list", e)
        }
    }

    /**
     * Returns the display label for a hill.
     * Unique names show just the name; duplicate names include the area in brackets.
     */
    fun displayLabel(hill: Hill): String =
        if (hill.name in duplicateNames) "${hill.name} (${hill.area})" else hill.name

    /** All unique hill names in alphabetical order (for first-level selection). */
    fun uniqueNames(): List<String> = sortedUniqueNames

    /** All hills with a given name (for disambiguation). */
    fun byName(name: String): List<Hill> = byNameMap[name] ?: emptyList()

    fun byId(id: String): Hill? = byIdMap[id]

    /** Hardcoded fallback list (used before CSV loads). */
    private fun legacyHills(): List<Hill> = listOf(
        Hill(
            id = "ben_nevis",
            name = "Ben Nevis",
            area = "Lochaber",
            summitLat = 56.7969,
            summitLon = -5.0036,
            carParks = listOf(
                CarPark("Achintee / Ben Nevis Visitor Centre", 56.7872, -5.0145),
                CarPark("North Face Car Park, Torlundy", 56.8105, -4.9969)
            ),
            category = "Munro",
            elevationM = 1345
        ),
        Hill(
            id = "aonach_mor",
            name = "Aonach Mòr",
            area = "Lochaber",
            summitLat = 56.8256,
            summitLon = -4.9819,
            carParks = listOf(
                CarPark("Nevis Range Gondola Base", 56.8083, -4.9867)
            ),
            category = "Munro",
            elevationM = 1221
        ),
        Hill(
            id = "carn_mor_dearg",
            name = "Carn Mòr Dearg",
            area = "Lochaber",
            summitLat = 56.7972,
            summitLon = -4.9700,
            carParks = listOf(
                CarPark("North Face Car Park, Torlundy", 56.8105, -4.9969)
            ),
            category = "Munro",
            elevationM = 1220
        ),
        Hill(
            id = "ben_macdui",
            name = "Ben Macdui",
            area = "Cairngorms",
            summitLat = 57.0697,
            summitLon = -3.6694,
            carParks = listOf(
                CarPark("Cairngorm Ski Centre Car Park", 57.1347, -3.6628)
            ),
            category = "Munro",
            elevationM = 1309
        ),
        Hill(
            id = "braeriach",
            name = "Braeriach",
            area = "Cairngorms",
            summitLat = 57.0787,
            summitLon = -3.7283,
            carParks = listOf(
                CarPark("Sugar Bowl Car Park, Cairngorm", 57.1186, -3.6911)
            ),
            category = "Munro",
            elevationM = 1296
        ),
        Hill(
            id = "cairn_toul",
            name = "Cairn Toul",
            area = "Cairngorms",
            summitLat = 57.0542,
            summitLon = -3.7111,
            carParks = listOf(
                CarPark("Linn of Dee Car Park, Braemar", 56.9989, -3.5569)
            ),
            category = "Munro",
            elevationM = 1291
        ),
        Hill(
            id = "ben_more_crianlarich",
            name = "Ben More",
            area = "Crianlarich",
            summitLat = 56.3870,
            summitLon = -4.5580,
            carParks = listOf(
                CarPark("Ben More Car Park, Benmore Farm", 56.3900, -4.5972)
            ),
            category = "Munro",
            elevationM = 1174
        ),
        Hill(
            id = "ben_more_mull",
            name = "Ben More",
            area = "Isle of Mull",
            summitLat = 56.4320,
            summitLon = -5.9050,
            carParks = listOf(
                CarPark("Dhiseig Layby, Loch na Keal", 56.4298, -5.8864)
            ),
            category = "Munro",
            elevationM = 966
        ),
        Hill(
            id = "ben_lomond",
            name = "Ben Lomond",
            area = "Loch Lomond",
            summitLat = 56.1900,
            summitLon = -4.6375,
            carParks = listOf(
                CarPark("Rowardennan Car Park", 56.1532, -4.6303)
            ),
            category = "Munro",
            elevationM = 974
        ),
        Hill(
            id = "ben_lawers",
            name = "Ben Lawers",
            area = "Breadalbane",
            summitLat = 56.5496,
            summitLon = -4.2225,
            carParks = listOf(
                CarPark("Ben Lawers NTS Car Park", 56.5469, -4.2622)
            ),
            category = "Munro",
            elevationM = 1214
        ),
        Hill(
            id = "schiehallion",
            name = "Schiehallion",
            area = "Perthshire",
            summitLat = 56.6581,
            summitLon = -4.0972,
            carParks = listOf(
                CarPark("Braes of Foss Car Park", 56.6394, -4.1367)
            ),
            category = "Munro",
            elevationM = 1083
        ),
        Hill(
            id = "the_cairnwell",
            name = "The Cairnwell",
            area = "Cairngorms",
            summitLat = 56.8792,
            summitLon = -3.4194,
            carParks = listOf(
                CarPark("Glenshee Ski Centre Car Park", 56.8789, -3.4158)
            ),
            category = "Munro",
            elevationM = 933
        ),
        Hill(
            id = "ben_vorlich_lochlomond",
            name = "Ben Vorlich",
            area = "Loch Lomond",
            summitLat = 56.2097,
            summitLon = -4.7297,
            carParks = listOf(
                CarPark("Ardlui Car Park", 56.2128, -4.7272)
            ),
            category = "Munro",
            elevationM = 943
        ),
        Hill(
            id = "ben_vorlich_lochearn",
            name = "Ben Vorlich",
            area = "Loch Earn",
            summitLat = 56.3886,
            summitLon = -4.2422,
            carParks = listOf(
                CarPark("Ardvorlich Car Park, Loch Earn", 56.3881, -4.2561)
            ),
            category = "Munro",
            elevationM = 985
        )
    )
}
