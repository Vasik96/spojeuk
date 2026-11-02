package com.vasik96.spojeuk

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit

class AutoUpdatePolicy(private val context: Context) {
    private val prefs = context.getSharedPreferences("auto_update_prefs", Context.MODE_PRIVATE)

    var lastSuccessMs: Long
        get() = prefs.getLong("last_success_ms", 0L)
        set(v) = prefs.edit { putLong("last_success_ms", v) }

    fun isDue(now: Long = System.currentTimeMillis()): Boolean =
        now - lastSuccessMs >= INTERVAL_MS

    /** Volitelné: GTFS je velký, ať se netahá přes mobilní data. */
    fun isOnUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    var timetableStagedMs: Long
        get() = prefs.getLong("timetable_staged_ms", 0L)
        set(v) = prefs.edit { putLong("timetable_staged_ms", v) }

    fun timetableStagedRecently(now: Long = System.currentTimeMillis()): Boolean =
        now - timetableStagedMs < INTERVAL_MS

    companion object {
        const val INTERVAL_MS = 72L * 60 * 60 * 1000
    }
}