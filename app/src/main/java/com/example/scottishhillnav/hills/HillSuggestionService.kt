package com.example.scottishhillnav.hills

/**
 * Fuzzy name suggestions using Levenshtein distance + Gaelic phonetic normalisation.
 *
 * Gaelic phonetics: "larven" → phoneticNorm → "larven";
 *                   "Ladhar Bheinn" → phoneticNorm → "laarven"  (distance = 1)
 *
 * Also strips common Gaelic/English prefixes before comparing so bare-word
 * searches like "nevis" or "macdooey" still surface the correct entry.
 */
object HillSuggestionService {

    private val KNOWN = listOf(
        // Lochaber / Ben Nevis range
        "Ben Nevis", "Carn Mor Dearg", "Aonach Mor", "Aonach Beag",
        "Stob Coire Easain", "Stob a Choire Mheadhoin",
        // Knoydart
        "Ladhar Bheinn", "Luinne Bheinn", "Meall Buidhe", "Sgurr Mor",
        "Sgurr na Ciche", "Sgurr nan Coireachan", "Sgurr Thuilm",
        "Sgurr nan Eugallt", "Beinn na h-Eaglaise",
        // Cairngorms
        "Ben Macdui", "Braeriach", "Cairn Toul", "Cairn Gorm",
        "Sgor an Lochain Uaine", "Beinn a Chaorainn", "Beinn Bhreac",
        "Ben Avon", "Beinn a Bhuird", "Derry Cairngorm",
        "Bynack More", "Beinn Bhrotain", "Monadh Mor",
        // Breadalbane / Lawers
        "Ben Lawers", "Beinn Ghlas", "An Stuc", "Meall Greigh", "Meall Garbh",
        "Meall Corranaich", "Meall a Choire Leith", "Meall nan Tarmachan",
        "Ben Challum", "Ben More", "Stob Binnein", "Cruach Ardrain",
        "An Caisteal", "Beinn a Chroin", "Ben Chonzie",
        // Crianlarich / Trossachs
        "Ben Lomond", "Ben Vorlich", "Stuc a Chroin",
        "Ben Ledi", "Ben Venue", "The Cobbler", "Ben Vane",
        // Perthshire
        "Schiehallion", "Ben Lui", "Ben Oss", "Beinn Dubhchraig",
        // Rannoch / Etive
        "Stob Ghabhar", "Clach Leathad", "Creise", "Meall a Bhuiridh",
        "Stob Coir an Albannaich", "Glas Bheinn Mhor", "Ben Starav",
        // Grey Corries
        "Sgurr Choinnich Mor", "Stob Choire Claurigh", "Stob Ban",
        // Monadh Liath / Drumochter
        "Geal Charn", "A Bhuidheanach Bheag", "Carn na Caim",
        "Beinn Udlamain", "Sgairneach Mhor",
        // Ben Alder / Loch Ossian
        "Ben Alder", "Beinn Bheoil", "Carn Dearg", "Sgor Gaibhre",
        "Beinn na Lap", "Chno Dearg", "Stob Coire Sgriodain",
        // Mamores
        "Binnein Mor", "Na Gruagaichean", "An Gearanach",
        "Am Bodach", "Sgurr Eilde Mor", "Binnein Beag",
        // Glen Shiel / Kintail
        "The Saddle", "Druim Shionnach", "Creag a Mhaim",
        "Aonach air Chrith", "Maol Chinn-dearg",
        "Sgurr an Doire Leathain", "Sgurr an Lochain", "Sgurr Fhuaran",
        "Sgurr a Mhaoraich", "Gleouraich", "Spidean Mialach",
        // Affric / Glen Cannich
        "Sgurr na Lapaich", "An Riabhachan", "An Socach", "Maoile Lunndaidh",
        "Toll Creagach", "Tom a Choinich", "Beinn Fhionnlaidh",
        "Carn Eige", "Mam Sodhail", "Sgurr nan Ceathreamhnan",
        // Torridon / Wester Ross
        "Beinn Eighe", "Liathach", "Beinn Alligin", "Beinn Dearg",
        "Seana Bhraigh", "Am Faochagach", "Cona Mheall",
        "Beinn Liath Mhor Fannaich", "Sgurr Choinnich", "Moruisg",
        "Bidein a Choire Sheasgaich", "Lurg Mhor",
        // Northern Highlands
        "Ben Wyvis", "Ben Klibreck", "Ben Hope", "Ben More Assynt",
        "Conival", "Beinn Dearg", "Sgurr Fiona",
        // Skye
        "Sgurr Alasdair", "Sgurr Dearg", "Sgurr na Banachdich",
        "Sgurr a Ghreadaidh", "Sgurr Mhic Choinnich",
        "Bruach na Frithe", "Am Bastier", "Sgurr nan Gillean", "Blaven",
        // Outer Hebrides
        "Clisham",
        // Angus / Glenshee
        "Glas Maol", "Cairn of Claise", "Lochnagar", "The Cairnwell",
        // Named Scottish long-distance walks
        "West Highland Way", "Rob Roy Way", "Southern Upland Way",
        "John Muir Way", "Great Glen Way", "Fife Coastal Path",
        "Speyside Way", "Cateran Trail", "Cape Wrath Trail",
        "Forth and Clyde Canal", "Union Canal",
        "St Cuthberts Way", "Pilgrims Way"
    )

    private val PREFIXES = listOf(
        "ben ", "beinn ", "carn ", "cairn ", "sgurr ", "sgor ",
        "stob ", "meall ", "creag ", "an ", "a ", "na ", "the ",
        "am ", "aonach ", "ladhar ", "luinne "
    )

    fun suggest(query: String, maxResults: Int = 5): List<String> {
        val q = query.lowercase().trim()
        val qNorm = phoneticNorm(q)
        val threshold = (q.length / 2).coerceIn(2, 5)

        return KNOWN.mapNotNull { hill ->
            val h = hill.lowercase()
            val hNorm = phoneticNorm(h)

            // 1. Raw Levenshtein on the full names
            val d1 = levenshtein(q, h)
            // 2. Levenshtein after stripping common prefixes
            val qBare = stripPrefix(q); val hBare = stripPrefix(h)
            val d2 = if (qBare.length >= 3 && hBare.length >= 3)
                levenshtein(qBare, hBare) else Int.MAX_VALUE
            // 3. Phonetic Levenshtein (space-stripped) — key for Gaelic
            val d3 = levenshtein(qNorm, hNorm)
            // 4. Phonetic bare-word
            val qNormBare = stripPrefix(qNorm); val hNormBare = stripPrefix(hNorm)
            val d4 = if (qNormBare.length >= 3 && hNormBare.length >= 3)
                levenshtein(qNormBare, hNormBare) else Int.MAX_VALUE

            val best = minOf(d1, d2, d3, d4)
            if (best <= threshold) hill to best else null
        }
            .sortedBy { (_, d) -> d }
            .take(maxResults)
            .map { (name, _) -> name }
    }

    /**
     * Converts Gaelic orthography to an approximate phonetic string so that
     * "larven" ≈ "ladhar bheinn", "skoor" ≈ "sgurr", etc.
     * Spaces are stripped at the end to allow whole-name phonetic comparison.
     */
    private fun phoneticNorm(s: String): String {
        var r = s.lowercase()
        // Long patterns first (prevent partial replacement)
        r = r.replace("bheinn", "ven")
        r = r.replace("mheinn", "ven")
        r = r.replace("bhein",  "ven")
        r = r.replace("mhein",  "ven")
        r = r.replace("bhuird", "voord")
        // Lenited digraphs
        r = r.replace("bh", "v")
        r = r.replace("mh", "v")
        r = r.replace("fh", "")        // fh is silent
        r = r.replace("dh", "")        // often silent (makes "ladhar" → "laar")
        r = r.replace("gh", "")        // often silent
        r = r.replace("th", "h")
        r = r.replace("ch", "k")       // guttural → k approx
        r = r.replace("sg", "sk")      // "sgurr" → "skurr"
        // Vowel clusters
        r = r.replace("aoi", "ee")
        r = r.replace("ao",  "oo")
        r = r.replace("ai",  "a")
        r = r.replace("ei",  "e")
        r = r.replace("ui",  "oo")
        r = r.replace("ia",  "ee")
        r = r.replace("ea",  "e")
        r = r.replace("einn","en")
        // Doubled consonants → single
        r = r.replace("nn",  "n")
        r = r.replace("ll",  "l")
        r = r.replace("rr",  "r")
        // Strip spaces for whole-name comparison
        return r.replace(" ", "")
    }

    private fun stripPrefix(s: String): String {
        for (p in PREFIXES) if (s.startsWith(p)) return s.removePrefix(p)
        return s
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length; val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                       else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
        return dp[m][n]
    }
}
