package com.vasik96.spojeuk

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

private fun fmtMinutes(m: Long): String = when {
    m < 60 -> tr(R.string.fmt_min, m.toInt())
    m % 60 == 0L -> tr(R.string.fmt_h, (m / 60).toInt())
    else -> tr(R.string.fmt_h_min, (m / 60).toInt(), (m % 60).toInt())
}

internal fun departsIn(j: Journey, now: LocalDateTime): String {
    val secs = ChronoUnit.SECONDS.between(now, j.depDt)
    return when {
        secs > 0 -> tr(R.string.departs_in, fmtMinutes((secs + 59) / 60))   // zaokrouhlení nahoru
        secs > -60 -> tr(R.string.departs_now)
        else -> tr(R.string.departed_ago, fmtMinutes(-secs / 60))
    }
}

/** Štítek dne, když spoj nejede dnes ("zítra", "pá 20. 11."); jinak null. */
internal fun depDayLabel(j: Journey, now: LocalDateTime): String? {
    val d = j.depDt.toLocalDate()
    val today = now.toLocalDate()
    return when (d) {
        today -> null
        today.plusDays(1) -> tr(R.string.day_tomorrow_lower)
        else -> d.format(java.time.format.DateTimeFormatter.ofPattern(tr(R.string.date_short_pattern), I18n.locale()))
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
    s < 60 -> tr(R.string.age_s, s.toInt())
    s < 3600 -> tr(R.string.age_min_s, (s / 60).toInt(), (s % 60).toInt())
    else -> tr(R.string.age_h_min, (s / 3600).toInt(), ((s % 3600) / 60).toInt())
}

internal fun ageLabel(s: Long): String = when {
    s <= 10 -> tr(R.string.age_current)
    s <= 40 -> tr(R.string.age_before, fmtAge(s))
    else -> tr(R.string.age_stale, fmtAge(s))
}

internal fun stopLabel(name: String): String = splitStopName(name).first.ifBlank { name }

/**
 * Název zastávky u vozidla (detail, výběr z více vozidel): autobus / MHD jen zastávka bez obce ("Divadlo"),
 * vlak celý název, protože jezdí na delší vzdálenosti ("Ústí nad Labem hlavní nádraží", "Praha, Hlavní nádraží").
 */
internal fun vehicleStopName(name: String, train: Boolean): String = if (train) name.trim() else stopLabel(name)

internal fun myLocLabel(name: String) = tr(R.string.my_location_with_stop, stopLabel(name))

internal fun formatLastUpdate(ms: Long): String {
    if (ms <= 0L) return tr(R.string.never)
    val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), PRAGUE_ZONE)
    val formatter = java.time.format.DateTimeFormatter.ofPattern(tr(R.string.date_time_pattern), I18n.locale())
    return dt.format(formatter)
}