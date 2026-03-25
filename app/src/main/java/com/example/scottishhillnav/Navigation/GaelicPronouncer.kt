package com.example.scottishhillnav.navigation

/**
 * Converts Scottish Gaelic hill names into phonetic strings that Android TTS
 * (British English voice) will render intelligibly.
 *
 * Strategy:
 *  1. Check full-name overrides first (exact-match, case-insensitive).
 *  2. Apply word-level substitution rules in order (longest match wins within each word).
 *
 * Sources: WalkHighlands pronunciation guides, Ordnance Survey Gaelic name guidance,
 * and standard Scottish Gaelic mutation rules.
 */
object GaelicPronouncer {

    // ── Full-name overrides ───────────────────────────────────────────────────
    // These take priority over word-level rules.

    private val fullNameOverrides: Map<String, String> = mapOf(
        // Ben Nevis group
        "Ben Nevis"               to "Ben Neevish",
        "Carn Mòr Dearg"          to "Karn More Jerrak",
        "Aonach Mòr"              to "Oo-nach More",
        "Aonach Beag"             to "Oo-nach Bek",
        "Carn Dearg"              to "Karn Jerrak",

        // Ben Lomond
        "Ben Lomond"              to "Ben Lomond",   // English enough; keep as-is

        // Cairngorms
        "Braeriach"               to "Bray-ree-ach",
        "Cairn Toul"              to "Karn Tool",
        "Sgor an Lochain Uaine"   to "Skor an Lochan Oo-anya",
        "Cairn Gorm"              to "Karn Gorm",
        "Ben Macdui"              to "Ben Mak-doo-ee",
        "Beinn a' Bhuird"         to "Ben a Voord",
        "Beinn Bhreac"            to "Ben Vrechk",
        "Bynack More"             to "Bye-nak More",
        "Beinn a' Chaorainn"      to "Ben a Churn",
        "Beinn Bhrotain"          to "Ben Vroten",

        // Glen Coe / Bidean
        "Bidean nam Bian"         to "BEE-jen nam BEE-an",
        "Buachaille Etive Mòr"    to "BOO-achkh-la EH-tiv More",
        "Buachaille Etive Beag"   to "BOO-achkh-la EH-tiv Bek",
        "Stob Coire Sgreamhach"   to "Stop Corr-a Skreev-ach",
        "Stob na Doire"           to "Stop na Doy-ra",
        "Binnein Mòr"             to "Bin-yan More",
        "Na Gruagaichean"         to "Na Groo-a-gee-chen",

        // Glenshee / Angus Glens
        "Glas Maol"               to "Glas Mool",
        "Cairn of Claise"         to "Karn of Clesh",
        "Carn an Tuirc"           to "Karn an Toork",
        "Tolmount"                to "Toll-mount",
        "Tom Buidhe"              to "Tom Boo-ya",

        // Torridon
        "Beinn Eighe"             to "Ben Ay",
        "Liathach"                to "LEE-uh-ach",
        "Beinn Alligin"           to "Ben Al-ee-gin",
        "Tom na Gruagaich"        to "Tom na Groo-a-geech",
        "Sgùrr Mòr"               to "Skoor More",
        "Sgurr Mòr"               to "Skoor More",

        // Knoydart / remote west
        "Ladhar Bheinn"           to "LAR-uh Ven",
        "Luinne Bheinn"           to "LOON-ya Ven",
        "Meall Buidhe"            to "Myowl Boo-ya",
        "Sgurr na Ciche"          to "Skoor na KEE-cheh",
        "Sgurr Mor"               to "Skoor More",
        "Gairich"                 to "Garr-ich",

        // Assynt / far north
        "Quinag"                  to "KYOO-nyak",
        "Suilven"                 to "SOO-il-ven",
        "Canisp"                  to "KAN-isp",
        "Ben More Assynt"         to "Ben More Ah-sint",
        "Conival"                 to "CON-ih-val",

        // Schiehallion / Breadalbane
        "Schiehallion"            to "Shee-hal-ee-un",
        "Ben Lawers"              to "Ben Law-erz",
        "Meall Corranaich"        to "Myowl Korr-an-ich",
        "Meall a' Choire Leith"   to "Myowl a Chorr-a Lay",
        "Beinn Ghlas"             to "Ben Glas",
        "Ben nan Eachainn"        to "Ben nan Yechan",
        "An Stuc"                 to "An Stoochk",

        // An Teallach
        "An Teallach"             to "An Chyallach",
        "Sgurr Fiona"             to "Skoor FEE-uh-na",
        "Bidein a' Ghlas Thuill"  to "BEE-jen a Glas Hooll",

        // Skye
        "Sgurr Dearg"             to "Skoor Jerrak",
        "Inaccessible Pinnacle"   to "Inaccessible Pinnacle",
        "Sgurr na Banachdaich"    to "Skoor na Ban-ach-teech",
        "Sgurr Alasdair"          to "Skoor Al-as-ter",
        "Sgurr Dubh Mòr"          to "Skoor Doo More",
        "Bruach na Frithe"        to "Broo-ach na FREE-uh",

        // Trossachs / Loch Lomond
        "Ben More"                to "Ben More",
        "Stob Binnein"            to "Stop Bin-yan",
        "Cruach Ardrain"          to "KROO-ach Ard-ren",
        "An Caisteal"             to "An CASH-chul",
        "Beinn a' Chroin"         to "Ben a Chron",
        "Beinn Chabhair"          to "Ben Chav-ur",
        "Ben Oss"                 to "Ben Oss",
        "Beinn Dubhchraig"        to "Ben Doo-chraik",
        "Beinn Narnain"           to "Ben Narn-an",
        "Beinn Ime"               to "Ben Ee-ma",
        "Ben Vorlich"             to "Ben Vor-lich",  // two hills of this name — OK
        "Stuc a' Chroin"          to "Stoochk a Chron",

        // Glen Lyon / Rannoch
        "Creag Mhor"              to "Krayk Vor",
        "Beinn Heasgarnich"       to "Ben Hes-gar-nich",
        "Meall Ghaordaidh"        to "Myowl Geurd-ee",
        "Carn Mairg"              to "Karn Marak",
        "Meall Garbh"             to "Myowl Garuv",

        // Monadhliath
        "Carn Dearg"              to "Karn Jerrak",
        "Carn Sgulain"            to "Karn Skoo-lan",
        "A' Chailleach"           to "A Challyach",
        "Carn a' Chuilinn"        to "Karn a Choolin"
    )

    // ── Word-level substitution table ─────────────────────────────────────────
    // Applied left-to-right; each word of the hill name is checked against these
    // patterns (case-insensitive). Order matters — longer/more specific first.

    private data class Sub(val pattern: Regex, val replacement: String)

    private val wordSubs: List<Sub> = listOf(

        // Articles / wee words
        sub("^A'$",    "a"),
        sub("^An$",    "an"),
        sub("^Na$",    "na"),
        sub("^Nam$",   "nam"),
        sub("^nan$",   "nan"),
        sub("^A$",     "a"),

        // Generic mountain / landform words
        sub("^Beinn$",      "Ben"),
        sub("^Bheinn$",     "Ven"),
        sub("^Bheine$",     "Ven-a"),
        sub("^Ben$",        "Ben"),
        sub("^Binnein$",    "Bin-yan"),
        sub("^Sgùrr$",      "Skoor"),
        sub("^Sgurr$",      "Skoor"),
        sub("^Sgorr$",      "Skor"),
        sub("^Sgor$",       "Skor"),
        sub("^Stob$",       "Stop"),
        sub("^Stuc$",       "Stoochk"),
        sub("^Meall$",      "Myowl"),
        sub("^Mheall$",     "Vyowl"),
        sub("^Creag$",      "Krayk"),
        sub("^Carn$",       "Karn"),
        sub("^Càrn$",       "Karn"),
        sub("^Cruach$",     "KROO-ach"),
        sub("^Cnoc$",       "Krock"),
        sub("^Druim$",      "Droo-im"),
        sub("^Tom$",        "Tom"),
        sub("^Aonach$",     "Oo-nach"),
        sub("^Bidean$",     "BEE-jen"),
        sub("^Bidein$",     "BEE-jen"),
        sub("^Bruach$",     "Broo-ach"),
        sub("^Buachaille$", "BOO-achkh-la"),
        sub("^Maol$",       "Mool"),
        sub("^Glas$",       "Glas"),
        sub("^Sron$",       "Strawn"),
        sub("^Sròn$",       "Strawn"),
        sub("^Mam$",        "Mam"),

        // Valley / pass
        sub("^Gleann$",  "Glyown"),
        sub("^Glen$",    "Glen"),
        sub("^Bealach$", "Byal-ach"),
        sub("^Lairig$",  "LAR-ig"),

        // Corrie / water
        sub("^Coire$",  "Corr-a"),
        sub("^Choire$", "Chorr-a"),
        sub("^Allt$",   "Owlt"),
        sub("^Lochan$", "Loch-an"),

        // Descriptors — colours / size / texture
        sub("^Mòr$",   "More"),
        sub("^Mor$",   "More"),
        sub("^Mhòr$",  "Wore"),
        sub("^Mhor$",  "Wore"),
        sub("^Beag$",  "Bek"),
        sub("^Bheag$", "Vek"),
        sub("^Dearg$", "Jerrak"),
        sub("^Dhearg$","Yerrak"),
        sub("^Dubh$",  "Doo"),
        sub("^Dhubh$", "Goo"),
        sub("^Liath$", "LEE-uh"),
        sub("^Ruadh$", "ROO-ugh"),
        sub("^Garbh$", "Garuv"),
        sub("^Gharbh$","Aruv"),
        sub("^Odhar$", "OO-ur"),
        sub("^Fionn$", "Fyown"),
        sub("^Geal$",  "Gyal"),
        sub("^Buidhe$","Boo-ya"),
        sub("^Bhuidhe$","Voo-ya"),
        sub("^Riabhach$","REE-a-vach"),

        // Common suffixes / genitive particles
        sub("^a'$",  "a"),
        sub("^nam$", "nam"),
        sub("^nan$", "nan"),
        sub("^na$",  "na"),

        // Specific place roots (partial words caught at word boundary)
        sub("^Etive$",   "EH-tiv"),
        sub("^Nevis$",   "Neevish"),
        sub("^Lomond$",  "Lomond"),
        sub("^Lawers$",  "Law-erz"),
        sub("^Torridon$","Torr-ih-dun"),
        sub("^Rannoch$", "Ran-ach"),
        sub("^Cairn$",   "Karn"),
        sub("^Caisteal$","CASH-chul"),
        sub("^Fionn$",   "Fyown"),
        sub("^Assynt$",  "Ah-sint")
    )

    private fun sub(pattern: String, replacement: String) =
        Sub(Regex(pattern, RegexOption.IGNORE_CASE), replacement)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a phonetic version of [name] suitable for Android TTS (British English).
     * Falls back to the original string if no substitution applies.
     */
    fun phonetic(name: String): String {
        // 1. Full-name override (case-insensitive lookup)
        val lower = name.trim()
        for ((key, value) in fullNameOverrides) {
            if (key.equals(lower, ignoreCase = true)) return value
        }

        // 2. Word-by-word substitution
        val words = lower.split(Regex("\\s+"))
        val result = words.joinToString(" ") { word ->
            var out = word
            for (sub in wordSubs) {
                val replaced = sub.pattern.replace(out, sub.replacement)
                if (replaced != out) { out = replaced; break }
            }
            out
        }

        // Return substituted form only if it actually changed something
        return if (result.equals(lower, ignoreCase = false)) name else result
    }
}
