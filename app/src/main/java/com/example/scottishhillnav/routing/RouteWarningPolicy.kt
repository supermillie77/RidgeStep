package com.example.scottishhillnav.routing

object RouteWarningPolicy {

    fun evaluate(route: RouteCandidate): List<RouteWarning> {
        val warnings = mutableListOf<RouteWarning>()

        if (route.difficultyProfile.p95Slope > 0.25) {
            warnings += RouteWarning(
                code = "STEEP",
                message = "Very steep sections on this route"
            )
        }

        return warnings
    }
}