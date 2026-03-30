package com.example.scottishhillnav.hills

/**
 * Community-recommended / WalkHighlands-preferred car parks for named hills.
 * Keyed by [Hill.id]. The [CarPark] entry has [CarPark.isPreferred] = true and is
 * injected at the top of the car park selection dialog even when Overpass doesn't
 * return the exact node.
 */
object PreferredParking {

    private val data: Map<String, CarPark> = mapOf(

        // ── Lochaber / Ben Nevis group ────────────────────────────────────────

        "ben_nevis" to CarPark(
            name = "Achintee / Ben Nevis Visitor Centre",
            lat = 56.79799, lon = -5.07042,
            area = "Fort William",
            isPreferred = true
        ),
        "aonach_mor" to CarPark(
            name = "Nevis Range Gondola Base Station",
            lat = 56.84970, lon = -4.97730,
            area = "Lochaber",
            isPreferred = true
        ),
        "carn_mor_dearg" to CarPark(
            name = "Torlundy / North Face Car Park",
            lat = 56.81050, lon = -4.99690,
            area = "Lochaber",
            isPreferred = true
        ),
        "stob_coire_easain" to CarPark(
            name = "Fersit Car Park",
            lat = 56.83750, lon = -4.73500,
            area = "Lochaber",
            isPreferred = true
        ),
        "stob_coire_an_laoigh" to CarPark(
            name = "Corriechoille Track End",
            lat = 56.88590, lon = -4.94350,
            area = "Lochaber",
            isPreferred = true
        ),

        // ── Cairngorms ────────────────────────────────────────────────────────

        "ben_macdui" to CarPark(
            name = "Cairngorm Coire Cas Car Park",
            lat = 57.13480, lon = -3.67860,
            area = "Cairngorms",
            isPreferred = true
        ),
        "braeriach" to CarPark(
            name = "Cairngorm Coire Cas Car Park",
            lat = 57.13480, lon = -3.67860,
            area = "Cairngorms",
            isPreferred = true
        ),
        "cairn_toul" to CarPark(
            name = "Linn of Dee Car Park",
            lat = 56.99470, lon = -3.55660,
            area = "Braemar",
            isPreferred = true
        ),
        "cairn_gorm" to CarPark(
            name = "Cairngorm Coire Cas Car Park",
            lat = 57.13480, lon = -3.67860,
            area = "Cairngorms",
            isPreferred = true
        ),
        "the_cairnwell" to CarPark(
            name = "Glenshee Ski Centre Car Park",
            lat = 56.88050, lon = -3.41830,
            area = "Glenshee",
            isPreferred = true
        ),

        // ── Breadalbane / Perthshire ──────────────────────────────────────────

        "ben_lawers" to CarPark(
            name = "Ben Lawers NTS Visitor Centre",
            lat = 56.54530, lon = -4.22000,
            area = "Breadalbane",
            isPreferred = true
        ),
        "beinn_ghlas" to CarPark(
            name = "Ben Lawers NTS Visitor Centre",
            lat = 56.54530, lon = -4.22000,
            area = "Breadalbane",
            isPreferred = true
        ),
        "meall_nan_tarmachan" to CarPark(
            name = "Tarmachan Car Park, Loch Tay",
            lat = 56.56110, lon = -4.25270,
            area = "Breadalbane",
            isPreferred = true
        ),
        "schiehallion" to CarPark(
            name = "Braes of Foss Car Park (John Muir Trust)",
            lat = 56.65970, lon = -4.08650,
            area = "Perthshire",
            isPreferred = true
        ),
        "beinn_a_ghlo" to CarPark(
            name = "Loch Moraig Track End",
            lat = 56.79860, lon = -3.73900,
            area = "Atholl",
            isPreferred = true
        ),

        // ── Loch Lomond & Crianlarich ─────────────────────────────────────────

        "ben_lomond" to CarPark(
            name = "Rowardennan NTS Car Park",
            lat = 56.15640, lon = -4.61570,
            area = "Loch Lomond",
            isPreferred = true
        ),
        "ben_more_crianlarich" to CarPark(
            name = "Benmore Farm Lay-by",
            lat = 56.38610, lon = -4.55990,
            area = "Crianlarich",
            isPreferred = true
        ),
        "ben_vorlich_lochlomond" to CarPark(
            name = "Ardlui Lay-by",
            lat = 56.29640, lon = -4.72050,
            area = "Loch Lomond",
            isPreferred = true
        ),
        "ben_vorlich_lochearn" to CarPark(
            name = "Ardvorlich Farm Car Park",
            lat = 56.38910, lon = -4.21640,
            area = "Loch Earn",
            isPreferred = true
        ),

        // ── Trossachs / Strathearn ────────────────────────────────────────────

        "ben_lui" to CarPark(
            name = "Dalrigh / Cononish Track End",
            lat = 56.43780, lon = -4.72800,
            area = "Tyndrum",
            isPreferred = true
        ),
        "beinn_dorain" to CarPark(
            name = "Bridge of Orchy Station Car Park",
            lat = 56.50870, lon = -4.76490,
            area = "Bridge of Orchy",
            isPreferred = true
        ),

        // ── Glen Coe / Etive ──────────────────────────────────────────────────

        "buachaille_etive_mor" to CarPark(
            name = "Altnafeadh Lay-by (A82)",
            lat = 56.66700, lon = -4.89280,
            area = "Glen Coe",
            isPreferred = true
        ),
        "buachaille_etive_beag" to CarPark(
            name = "Lairig Eilde Forestry Car Park",
            lat = 56.64360, lon = -4.93810,
            area = "Glen Coe",
            isPreferred = true
        ),
        "bidean_nam_bian" to CarPark(
            name = "Three Sisters NTS Lay-by (A82)",
            lat = 56.65760, lon = -5.01380,
            area = "Glen Coe",
            isPreferred = true
        ),

        // ── Isle of Mull ──────────────────────────────────────────────────────

        "ben_more_mull" to CarPark(
            name = "Dhiseig Lay-by, Loch na Keal",
            lat = 56.42130, lon = -5.95270,
            area = "Isle of Mull",
            isPreferred = true
        ),

        // ── North-West Highlands ──────────────────────────────────────────────

        "an_teallach" to CarPark(
            name = "Corrie Hallie Lay-by",
            lat = 57.88130, lon = -5.18060,
            area = "Dundonnell",
            isPreferred = true
        ),
        "ben_wyvis" to CarPark(
            name = "Garbat Forestry Car Park (A835)",
            lat = 57.64800, lon = -4.58100,
            area = "Easter Ross",
            isPreferred = true
        ),

        // ── Torridon ─────────────────────────────────────────────────────────

        "liathach" to CarPark(
            name = "Glen Torridon NTS Car Park (A896)",
            lat = 57.55140, lon = -5.48830,
            area = "Torridon",
            isPreferred = true
        ),
        "beinn_eighe" to CarPark(
            name = "Beinn Eighe NNR Visitor Centre, Kinlochewe",
            lat = 57.60500, lon = -5.35800,
            area = "Kinlochewe",
            isPreferred = true
        ),
        "beinn_alligin" to CarPark(
            name = "NTS Car Park, Torridon",
            lat = 57.56050, lon = -5.56630,
            area = "Torridon",
            isPreferred = true
        )
    )

    /**
     * Returns the preferred [CarPark] for the given [Hill.id], or null if none is registered.
     */
    fun forHillId(hillId: String): CarPark? = data[hillId]
}
