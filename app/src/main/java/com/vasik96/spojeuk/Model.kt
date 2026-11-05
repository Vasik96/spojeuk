package com.vasik96.spojeuk

import java.time.LocalDate
import java.time.LocalDateTime

// ───────────────────────── jízdní řády ─────────────────────────

enum class ModeFilter(val label: String) {
    ALL("Vše"),
    BUS("Autobusy"),
    TRAIN("Vlaky");

    fun accepts(isTrain: Boolean): Boolean = when (this) {
        ALL -> true
        BUS -> !isTrain
        TRAIN -> isTrain
    }
}

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
) {
    /** Vlak z CZPTT (trip_id začíná "czptt:"); num je u něj číslo vlaku, cis prázdné. */
    val isTrain: Boolean get() = gid.startsWith("czptt:")
}

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

/** Jeden přestup mezi dvěma jízdami spojení. */
data class TransferInfo(
    val at: String,            // "Zastávka" nebo "Zastávka A → Zastávka B" (při přesunu pěšky)
    val waitMin: Int,          // čekání v min
    val walkMin: Int? = null,  // chůze mezi zastávkami v min
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
    val lines: List<String> = listOf(line),               // všechny linky v pořadí jízd
    val transferList: List<TransferInfo> = emptyList(),   // všechny přestupy v pořadí
    val cis: String = "",
    val boardWalkMin: Int? = null,      // chůze před prvním spojem
    val boardStopName: String? = null,  // kam se jde pěšky
    val transferWalkMin: Int? = null,   // chůze mezi spoji
    var routeStops: List<String> = emptyList(),
) {
    val dep: Int get() = sortKey
    val arr: Int get() = sortKey + durationMinutes * 60
    val isTransfer get() = transfers > 0
    val key get() = "$sortKey|$departure|$arrival|$line|$trip|$line2|$trip2|$transferAt|${lines.joinToString(",")}|$day"
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
    val station: Int = 0,
    val finalStation: Int = 0,
) {
    init {
        if (id == 200 && (source == "duk" || source == "dpmul")) {
            line = "X"
        }
    }

    /** Stabilní klíč napříč zdroji (čísla vozů se mezi zdroji mohou překrývat). */
    val uid: String get() = "$source:" + if (id != 0) id.toString() else "$lat,$lng"

    fun ageSec(nowMs: Long = System.currentTimeMillis()): Long = maxOf(0L, (nowMs - observedAtMs) / 1000)
}

/** Vrací základní číslo ústeckého vozu bez předpony '30' a nul (např. 300451 -> 451, 301212 -> 1212, 451 -> 451). */
fun baseVehicleId(id: Int): Int {
    if (id <= 0) return 0
    val s = id.toString()
    if (s.length >= 4 && s[0] == '3' && s[1] == '0') {
        val rest = s.substring(2).dropWhile { it == '0' } // substring(2): 3 -> 30
        if (rest.isNotEmpty()) {
            val base = rest.toIntOrNull()
            if (base != null && base > 0) return base
        }
    }
    return id
}

/** Porovná dvě ID vozidel s ohledem na možný kód ústeckého MHD DÚK (předpona 30). */
fun sameVehicleId(id1: Int, id2: Int): Boolean {
    if (id1 <= 0 || id2 <= 0) return false
    if (id1 == id2) return true
    return baseVehicleId(id1) == baseVehicleId(id2)
}