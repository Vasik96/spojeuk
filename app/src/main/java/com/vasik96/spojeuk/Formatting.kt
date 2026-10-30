package com.vasik96.spojeuk

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

private fun fmtMinutes(m: Long): String = when {
    m < 60 -> "$m\u00A0min"
    m % 60 == 0L -> "${m / 60}\u00A0h"
    else -> "${m / 60}\u00A0h\u00A0${m % 60}\u00A0min"
}

internal fun departsIn(j: Journey, now: LocalDateTime): String {
    val secs = ChronoUnit.SECONDS.between(now, j.depDt)
    return when {
        secs > 0 -> "za ${fmtMinutes((secs + 59) / 60)}"   // zaokrouhlení nahoru
        secs > -60 -> "právě odjíždí"
        else -> "odjel před ${fmtMinutes(-secs / 60)}"
    }
}

/** "Ústí nad Labem,Divadlo" → ("Divadlo", "Ústí nad Labem") */
internal fun splitStopName(name: String): Pair<String, String> {
    val i = name.indexOf(',')
    return if (i < 0) name.trim() to "" else name.substring(i + 1).trim() to name.substring(0, i).trim()
}

internal fun fmtDist(m: Double): String =
    if (m < 1000) "${m.toInt()} m" else String.format("%.1f km", m / 1000)

private fun fmtAge(s: Long): String = when {
    s < 60 -> "$s s"
    s < 3600 -> "${s / 60} min ${s % 60} s"
    else -> "${s / 3600} h ${(s % 3600) / 60} min"
}

internal fun ageLabel(s: Long): String = when {
    s <= 10 -> "Aktuální poloha"
    s <= 40 -> "Poloha před ${fmtAge(s)}"
    else -> "Poloha stará ${fmtAge(s)}. Zdroj teď vozidlo nedodává."
}

internal fun stopLabel(name: String): String = splitStopName(name).first.ifBlank { name }

internal fun myLocLabel(name: String) = "Moje poloha (${stopLabel(name)})"