package com.example.scottishhillnav.routing

object RouteWarningPolicy {

    // Route IDs that are always graded SCRAMBLE regardless of slope statistics.
    // These are named technical routes where the nature of the terrain is well-known.
    private val SCRAMBLE_ROUTE_IDS = setOf("cmd", "lomond_ptarmigan")

    // Route IDs that require mountaineering equipment (ropes, harness, helmet).
    private val TECHNICAL_ROUTE_IDS = setOf("ledge")

    enum class DifficultyGrade {
        MODERATE_WALK,    // Straightforward hillwalk on clear paths
        STRENUOUS_WALK,   // Steep, demanding — good fitness and hillwalk experience needed
        SCRAMBLE,         // Hands required on rock — experienced hillwalkers with good head for heights
        TECHNICAL_CLIMB   // Ropes, harness and mountaineering skills essential
    }

    fun grade(route: RouteCandidate): DifficultyGrade = when {
        route.id in TECHNICAL_ROUTE_IDS -> DifficultyGrade.TECHNICAL_CLIMB
        route.id in SCRAMBLE_ROUTE_IDS  -> DifficultyGrade.SCRAMBLE
        route.difficultyProfile.p95Slope >= 55.0 -> DifficultyGrade.SCRAMBLE
        route.difficultyProfile.p95Slope >= 25.0 -> DifficultyGrade.STRENUOUS_WALK
        else -> DifficultyGrade.MODERATE_WALK
    }

    fun gradeLabel(grade: DifficultyGrade): String = when (grade) {
        DifficultyGrade.MODERATE_WALK   -> "🟢 Moderate hillwalk"
        DifficultyGrade.STRENUOUS_WALK  -> "🟠 Strenuous hillwalk"
        DifficultyGrade.SCRAMBLE        -> "⚠️ Scramble — experienced hillwalkers only"
        DifficultyGrade.TECHNICAL_CLIMB -> "🔴 Technical climb — specialist gear required"
    }

    /** Routes at this grade or above must show the liability acknowledgement dialog. */
    fun requiresAcknowledgement(grade: DifficultyGrade): Boolean =
        grade == DifficultyGrade.SCRAMBLE || grade == DifficultyGrade.TECHNICAL_CLIMB

    fun evaluate(route: RouteCandidate): List<RouteWarning> {
        val warnings = mutableListOf<RouteWarning>()
        when (grade(route)) {
            DifficultyGrade.TECHNICAL_CLIMB -> warnings += RouteWarning(
                code = "TECHNICAL_CLIMB",
                message = "Requires technical climbing skills and equipment (ropes, harness, helmet). " +
                          "Do not attempt without mountaineering experience and appropriate gear."
            )
            DifficultyGrade.SCRAMBLE -> warnings += RouteWarning(
                code = "SCRAMBLE",
                message = "Involves scrambling on steep, rocky terrain. Requires experience on " +
                          "exposed ground and a good head for heights. Not suitable for beginners."
            )
            DifficultyGrade.STRENUOUS_WALK -> warnings += RouteWarning(
                code = "STEEP",
                message = "Very steep sections. Requires a good level of fitness and previous " +
                          "hillwalking experience."
            )
            DifficultyGrade.MODERATE_WALK -> { /* no warning needed */ }
        }
        return warnings
    }

    /**
     * Returns a grade based solely on hill category, for use where no route slope data
     * is available (e.g. the "Hills near me" discovery list).
     *
     * Munros, Corbetts, Fionas and Islands are always at least Strenuous — they are
     * high, remote or on rough terrain. Grahams, Donalds, Wainwrights, Hewitts and
     * Sub 2000s are graded Moderate as a conservative starting estimate.
     */
    fun gradeForCategory(category: String): DifficultyGrade = when (category.lowercase().trim()) {
        "munro", "corbett", "fiona", "island" -> DifficultyGrade.STRENUOUS_WALK
        else                                   -> DifficultyGrade.MODERATE_WALK
    }

    /** Full liability disclaimer text shown in the acknowledgement dialog. */
    fun liabilityText(grade: DifficultyGrade): String = when (grade) {
        DifficultyGrade.TECHNICAL_CLIMB ->
            "⚠️ TECHNICAL CLIMB WARNING\n\n" +
            "This route requires technical mountaineering skills, ropes, a harness, a helmet, " +
            "and experience of leading on rock or mixed terrain.\n\n" +
            "It is NOT a hillwalk. Attempting it without the correct skills and equipment " +
            "risks serious injury or death.\n\n" +
            "By continuing you confirm that you have the skills, equipment and experience " +
            "required, and that you accept full personal responsibility for your safety. " +
            "The app and its developer provide navigation assistance only and accept no " +
            "liability whatsoever for incidents on this route."
        DifficultyGrade.SCRAMBLE ->
            "⚠️ SCRAMBLE WARNING\n\n" +
            "This route involves scrambling — using hands on steep, rocky ground in exposed " +
            "positions. It is NOT a straightforward hillwalk.\n\n" +
            "You should be experienced on rough hill terrain, have a good head for heights, " +
            "and be comfortable on steep rock. Consider a helmet. Turn back if conditions " +
            "are wet, icy or visibility is poor.\n\n" +
            "By continuing you confirm that you have assessed your own ability and the day's " +
            "conditions, and that you accept full personal responsibility for your safety. " +
            "The app and its developer provide navigation assistance only and accept no " +
            "liability whatsoever for incidents on this route."
        else -> ""
    }
}
