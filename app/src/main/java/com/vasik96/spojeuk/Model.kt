package com.vasik96.spojeuk

import java.time.LocalDate
import java.time.LocalDateTime

// ───────────────────────── jízdní řády ─────────────────────────

data class Stop(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val place: String = name,
    val inreg: Boolean = true,
) {
    val hasCoords: Boolean get() = lat != 0.0 || lng != 0.0
}

data class DbTrip(
    val id: Int,
    val line: String,
    val agency: String,
    val cis: String,
    val num: String,
    val head: String,
    val svc: Int,
    val wheel: Int,
    val t0: Int,
    val t1: Int,
    val gid: String = "", // GTFS trip_id, klíč do shapes.sqlite
)

data class SvcInfo(val id: Int, val dow: String, val d0: String, val d1: String)
data class SvcDateInfo(val svc: Int, val date: String, val type: Int)

/** Zastávky a časy jednoho spoje. */
data class TripTimes(val stops: IntArray, val arr: IntArray, val dep: IntArray)

// ───────────────────────── vyhledané spojení ─────────────────────────

data class Leg(
    val kind: String, // "ride" | "walk"
    val fromStop: Int,
    val toStop: Int,
    var dep: Int,
    var arr: Int,
    val tid: Int = 0,
    val stops: List<Triple<Int, Int, Int>> = emptyList(), // stopId, arr, dep
    val t0: Int = 0,
    val t1: Int = 0,
)

data class Journey(
    val departure: String,
    val arrival: String,
    val line: String,
    val line2: String? = null,
    val destination: String,
    val agency: String,
    var delay: Int = 0,
    var realtime: Boolean = false,
    var lowFloor: Boolean = false,
    val stopsCount: Int? = null,
    val transferAt: String? = null,
    val transferWait: Int? = null,
    val trip: String,
    val trip2: String? = null,
    val sortKey: Int = 0,
    val day: LocalDate = todayPrague(),
    val rawLegs: List<Leg> = emptyList(),
    val transfers: Int = if (line2 != null) 1 else 0,
    val cis: String = "",
) {
    val dep: Int get() = sortKey
    val arr: Int get() = sortKey + durationMinutes * 60
    val isTransfer get() = line2 != null
    val key get() = "$sortKey|$departure|$arrival|$line|$trip|$line2|$trip2|$transferAt|$day"
    val durationMinutes: Int
        get() {
            var duration = tm(arrival) - tm(departure)
            if (duration < 0) duration += 1440
            return duration
        }
    val arrivalKey get() = sortKey + durationMinutes
    val depDt: LocalDateTime get() = day.atStartOfDay().plusSeconds(sortKey.toLong())
}

data class VehicleTripInfo(val finalStop: String?, val nextStop: String?)

// ───────────────────────── živé polohy ─────────────────────────

/** Část dopravy, kterou zdroj pokrývá. Podle ní se rozhoduje, které zdroje si "konkurují". */
enum class Segment(val label: String) {
    CITY("MHD"),
    REGIONAL("regionální doprava"),
}

/** Jak spolehlivě je vůz přiřazen ke spoji z jízdního řádu. */
enum class TripMatch { NONE, HEURISTIC, EXACT }

/**
 * Jedna poloha vozu, nezávislá na zdroji. Zdroj vyplní jen to,     co zná;
 * [tid], [tripMatch] a (u heuristiky) [cis]/[trip] doplní později VehicleHub.
 */
data class Vehicle(
    val source: String,
    val lat: Double,
    val lng: Double,
    var line: String,
    val delay: Int,                 // minuty
    val observedAtMs: Long,         // kdy vůz tuto polohu skutečně vyslal
    val id: Int = 0,
    val trip: String = "",
    val cis: String = "",
    val destination: String = "",
    val agency: String = "",
    val segment: Segment = Segment.CITY,
    val state: Int = 0,
    val train: Boolean = false,
    val accessible: Boolean? = null,
    val airConditioned: Boolean? = null,
    val heading: Double? = null,
    val hasTimestamp: Boolean = true,
    val tid: Int = 0,
    val tripMatch: TripMatch = TripMatch.NONE,
) {
    init {
        if (id == 200 && (source == "duk" || source == "dpmul")) {
            line = "X"
        }
    }

    /** Stabilní klíč napříč zdroji (čísla vozů se mezi zdroji mohou překrývat). */
    val uid: String get() = "$source#" + if (id != 0) id.toString() else "$lat,$lng"

    fun ageSec(nowMs: Long = System.currentTimeMillis()): Long = maxOf(0L, (nowMs - observedAtMs) / 1000)
}
