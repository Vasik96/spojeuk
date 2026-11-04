package com.vasik96.spojeuk

import kotlin.math.floor

// Párování vlakových stanic (CZPTT) s autobusovými zastávkami před nádražím.
//
// Nic se neslučuje v DB: vlakovým zastávkám se jen nastaví stejný `place` jako autobusovému nádraží,
// takže RAPTOR startuje z obou sad stanovišť (tt.places[place]) a UI může zobrazit jednu položku.
// ID zastávek zůstávají oddělená.
//
// Pravidlo (pro každé vlakové místo = rodičovská stanice nebo název zastávky):
//  1. kandidáti = autobusová místa do PAIR_MAX_M (vzdálenost k nejbližšímu stanovišti),
//  2. kandidát musí být ze stejného města (první slovo názvu bez "hl. n.", "žel. st." atd.)
//     a musí se shodovat v rozlišujících slovech (jih, sever, zastávka, …),
//  3. přednost mají kandidáti, jejichž název říká, že jde o nádraží; jinak nejbližší do PAIR_FALLBACK_M
//     (jen když vlakové místo samo nemá rozlišující slovo),
//  4. když jsou dva nejlepší kandidáti blíž než PAIR_AMBIGUOUS_M, nehádá se → nepáruje se (a zaloguje se).

/** Zastávka předaná párování. [rail] = přišla z vlakového feedu. */
class PairStop(
    val id: String,
    val name: String,
    val place: String,
    val lat: Double?,
    val lon: Double?,
    val rail: Boolean,
)

private const val PAIR_MAX_M = 400.0
private const val PAIR_FALLBACK_M = 250.0
private const val PAIR_AMBIGUOUS_M = 40.0
private const val CELL_DEG = 0.01   // ≈1,1 km ve směru lat, ≈0,7 km ve směru lon → 3×3 buňky pokryjí 400 m

/**
 * Ruční výjimky. Klíč = název vlakového místa po normalizaci (bez diakritiky, malá písmena, slova oddělená
 * mezerou, např. "usti nad labem hlavni nadrazi jih"), hodnota = `place` autobusového místa, se kterým
 * se má spárovat, nebo null = nechat samostatně.
 */
val STATION_PAIR_OVERRIDES: Map<String, String?> = mapOf(
    // jiné místo než hlavní nádraží, nespojovat
    "usti nad labem hlavni nadrazi jih" to null,
)

private val NON_ALNUM = Regex("[^a-z0-9]+")

/** Slova, kterými název říká „tohle je nádraží“ (po foldText; delší první, aby se odstranily celé fráze). */
private val STATION_WORDS = listOf(
    "hlavni nadrazi", "vlakove nadrazi", "zeleznicni stanice", "zeleznicni zastavka",
    "hl nadrazi", "hl nadr", "hl n",
    "zel stanice", "zel zastavka", "zel st", "zel zast",
    "nadrazi", "nadr", "zst",
).sortedByDescending { it.length }

/** Slova, která odlišují jiné místo ve stejném městě (musí se shodovat na obou stranách). */
private val QUALIFIERS = setOf(
    "jih", "sever", "zapad", "vychod", "stred",
    "zastavka", "predmesti", "mesto", "dolni", "horni",
    "odstavne", "nakladove", "seradovaci", "vlecka", "depo", "dilny",
)

private fun pairTokens(s: String): List<String> =
    foldText(s).split(NON_ALNUM).filter { it.isNotEmpty() }

private fun hasStationWord(tokens: List<String>): Boolean {
    val s = " ${tokens.joinToString(" ")} "
    return STATION_WORDS.any { s.contains(" $it ") }
}

/** Tokeny názvu bez slov „nádraží“; zbyde město + případné rozlišující slovo. */
private fun coreTokens(tokens: List<String>): List<String> {
    var s = " ${tokens.joinToString(" ")} "
    for (w in STATION_WORDS) s = s.replace(" $w ", " ")
    return s.split(' ').filter { it.isNotEmpty() }
}

private class BusPlace(
    val place: String,
    val core: List<String>,
    val named: Boolean,
    val qual: Set<String>,
)

private class BusPost(val bus: Int, val lat: Double, val lon: Double)

private fun cellKey(i: Long, j: Long): Long = (i shl 32) xor (j and 0xffffffffL)

/**
 * Vrací mapu stop_id vlakové zastávky → `place` autobusového místa, se kterým se má seskupit.
 * Zastávky, které se nepárují, v mapě nejsou (zůstává jim jejich vlastní `place`).
 */
fun pairRailStations(
    stops: List<PairStop>,
    overrides: Map<String, String?> = STATION_PAIR_OVERRIDES,
    log: (String) -> Unit = {},
): Map<String, String> {
    // ───── autobusová místa + mřížka jejich stanovišť ─────
    val busIdx = HashMap<String, Int>()
    val buses = ArrayList<BusPlace>()
    val grid = HashMap<Long, MutableList<BusPost>>()
    for (s in stops) {
        val la = s.lat
        val lo = s.lon
        if (s.rail || la == null || lo == null) continue
        val i = busIdx.getOrPut(s.place) {
            val t = pairTokens(s.place)
            val c = coreTokens(t)
            buses.add(BusPlace(s.place, c, hasStationWord(t), c.filter { it in QUALIFIERS }.toSet()))
            buses.size - 1
        }
        val ci = floor(la / CELL_DEG).toLong()
        val cj = floor(lo / CELL_DEG).toLong()
        grid.getOrPut(cellKey(ci, cj)) { mutableListOf() }.add(BusPost(i, la, lo))
    }

    // ───── vlaková místa ─────
    val out = HashMap<String, String>()
    var paired = 0
    var ambiguous = 0
    val railByPlace = stops.filter { it.rail }.groupBy { it.place }

    for ((rPlace, members) in railByPlace) {
        val rt = pairTokens(rPlace)
        val railKey = rt.joinToString(" ")

        if (overrides.containsKey(railKey)) {
            val target = overrides[railKey]
            if (target != null) {
                for (m in members) out[m.id] = target
                paired++
            }
            continue
        }

        val rCore = coreTokens(rt)
        if (rCore.isEmpty()) continue
        val rQual = rCore.filter { it in QUALIFIERS }.toSet()

        // vzdálenost k nejbližšímu stanovišti každého autobusového místa
        val best = HashMap<Int, Double>()
        for (m in members) {
            val la = m.lat ?: continue
            val lo = m.lon ?: continue
            val ci = floor(la / CELL_DEG).toLong()
            val cj = floor(lo / CELL_DEG).toLong()
            for (dy in -1L..1L) for (dx in -1L..1L) {
                val list = grid[cellKey(ci + dy, cj + dx)] ?: continue
                for (p in list) {
                    val d = Geo.distanceM(la, lo, p.lat, p.lon)
                    if (d > PAIR_MAX_M) continue
                    val old = best[p.bus]
                    if (old == null || d < old) best[p.bus] = d
                }
            }
        }

        val cands = best.entries
            .map { buses[it.key] to it.value }
            .filter { (b, _) -> b.core.firstOrNull() == rCore.first() && b.qual == rQual }

        val named = cands.filter { it.first.named }.sortedBy { it.second }
        val pool = when {
            named.isNotEmpty() -> named
            rQual.isEmpty() -> cands.filter { it.second <= PAIR_FALLBACK_M }.sortedBy { it.second }
            else -> emptyList()
        }
        if (pool.isEmpty()) continue

        if (pool.size > 1 && pool[1].second - pool[0].second < PAIR_AMBIGUOUS_M) {
            ambiguous++
            log("Párování nádraží: nejednoznačné \"$rPlace\" ↔ \"${pool[0].first.place}\" / \"${pool[1].first.place}\" (přidej výjimku)")
            continue
        }

        val target = pool[0].first.place
        for (m in members) out[m.id] = target
        paired++
    }

    val summary = "Párování nádraží: spárováno $paired, nejednoznačných $ambiguous, vlakových míst ${railByPlace.size}"
    log(summary)
    android.util.Log.i("StationPairing", summary)
    return out
}