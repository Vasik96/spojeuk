package com.vasik96.spojeuk

import android.content.Context
import android.content.SharedPreferences

class StopUsageManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("stop_usage_prefs", Context.MODE_PRIVATE)

    fun recordUsage(stopName: String) {
        val trimmed = stopName.trim()
        if (trimmed.isBlank()) return
        val current = prefs.getInt(trimmed, 0)
        prefs.edit().putInt(trimmed, current + 1).apply()
    }

    fun getUsageCount(stopName: String): Int {
        return prefs.getInt(stopName.trim(), 0)
    }
}
