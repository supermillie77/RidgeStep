package com.example.scottishhillnav.hills

import android.content.Context

/**
 * Persists completed hill IDs in SharedPreferences.
 * Used by [WalkProgressActivity] and the "Mark as climbed" action in the summit sheet.
 */
object WalkLogManager {

    private const val PREFS = "walk_log"
    private const val KEY   = "completed_ids"

    fun getCompleted(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
               .getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun markCompleted(context: Context, hillId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set   = prefs.getStringSet(KEY, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (set.add(hillId)) prefs.edit().putStringSet(KEY, set).apply()
    }

    fun removeCompleted(context: Context, hillId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set   = prefs.getStringSet(KEY, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (set.remove(hillId)) prefs.edit().putStringSet(KEY, set).apply()
    }

    fun isCompleted(context: Context, hillId: String): Boolean =
        hillId in getCompleted(context)

    /** Toggle; returns true if the hill is now marked as completed. */
    fun toggle(context: Context, hillId: String): Boolean =
        if (isCompleted(context, hillId)) {
            removeCompleted(context, hillId); false
        } else {
            markCompleted(context, hillId); true
        }

    /**
     * Returns a map of category → count of completed hills in that category.
     * [allHills] should be [HillRepository.hills].
     */
    fun countByCategory(context: Context, allHills: List<Hill>): Map<String, Int> {
        val completed = getCompleted(context)
        return allHills.filter { it.id in completed }
                       .groupingBy { it.category }
                       .eachCount()
    }
}
