package com.vasik96.spojeuk

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Které odjezdy ikona ukazuje. */
enum class StopKind {
    /** Všechny (zastávka, přes kterou dnes nic nejede, takže směr nejde určit). */
    ALL,
    /** Autobusy / MHD jedním směrem ([MapStop.dir] podle [MapStop.dirs]): nástupiště na pravé straně silnice. */
    DIR,
    /** Jen vlaky (nádraží, vlakové záznamy jízdního řádu). */
    RAIL,
}

/**
 * Zastávka na mapě. Jízdní řád má pro jedno místo často víc záznamů (DÚK / MHD, vlakové stanice z jiných dat):
 * ty se stejným názvem blízko sebe jsou jedna zastávka ([ttStops]). Každé místo (stanoviště) zastávky se rozdělí
 * na nástupiště podle směru jízdy ([StopKind.DIR], [dir] = skupina v [dirs]) a vlaky zvlášť ([StopKind.RAIL]).
 */
class MapStop(
    val key: String,
    val lat: Double,
    val lng: Double,
    val name: String,
    val ttStops: IntArray,
    val kind: StopKind = StopKind.ALL,
    val dir: Int = 0,
    val dirs: DirGroups? = null,
)

/**
 * Směry jízdy na jednom místě zastávky: úhel (výseče po 10°) → skupina = nástupiště. Výseč, kterou žádný spoj
 * nepoužil, patří ke skupině s nejbližším průměrným směrem.
 */
class DirGroups(
    private val binTo: IntArray,
    private val means: DoubleArray,
    /** Pevné přiřazení podle (předchozí, další) zastávky; má přednost před směrem. */
    private val keyTo: Map<Long, Int>? = null,
) {
    val size get() = means.size

    fun groupOf(prev: Int, next: Int, d: Pair<Double, Double>?): Int {
        keyTo?.get(callKey(prev, next))?.let { return it }
        return if (d == null) 0 else groupOf(d)
    }

    fun groupOf(d: Pair<Double, Double>): Int {
        val a = atan2(d.second, d.first)
        binTo[bin(a)].let { if (it >= 0) return it }
        var best = 0
        var bestD = Double.MAX_VALUE
        for (g in means.indices) { val x = angleDiff(a, means[g]); if (x < bestD) { bestD = x; best = g } }
        return best
    }

    companion object {
        const val BINS = 36
        fun callKey(prev: Int, next: Int): Long = (prev.toLong() shl 32) or (next.toLong() and 0xFFFFFFFFL)
        fun bin(a: Double): Int = (((a + Math.PI) / (2 * Math.PI) * BINS).toInt()).coerceIn(0, BINS - 1)
        fun binCenter(b: Int): Double = -Math.PI + (b + 0.5) * 2 * Math.PI / BINS
        /** Úhel mezi dvěma směry (0..π). */
        fun angleDiff(a: Double, b: Double): Double {
            var d = kotlin.math.abs(a - b) % (2 * Math.PI)
            if (d > Math.PI) d = 2 * Math.PI - d
            return d
        }
    }
}

/** Jeden odjezd ze zastávky. [depSec] = sekundy od půlnoci dne [day] (může přesáhnout 24 h). */
class StopDeparture(
    val tid: Int,
    val depSec: Int,
    val day: LocalDate,
    val line: String,
    val headsign: String,
    val category: MapCategory,
)

/** Směr jízdy spoje v zastávce [s] (jednotkový, x = východ, y = sever): předchozí → další zastávka. */
private fun travelDir(tt: Timetable, s: Int, prev: Int, next: Int): Pair<Double, Double>? {
    val here = tt.stop(s)?.takeIf { it.hasCoords } ?: return null
    val a = tt.stop(prev)?.takeIf { it.hasCoords } ?: here
    val b = tt.stop(next)?.takeIf { it.hasCoords } ?: here
    val k = cos(Math.toRadians(here.lat))
    val dx = (b.lng - a.lng) * k
    val dy = b.lat - a.lat
    val len = hypot(dx, dy)
    return if (len < 1e-7) null else (dx / len) to (dy / len)
}

/** Zastávky pro mapu, seřazené podle zeměpisné šířky (výřez pohledu půlením). */
class StopIndex private constructor(
    private val tt: Timetable,
    val stops: Array<MapStop>,
    /** Zastávka jízdního řádu → nástupiště (DIR) její zastávky na mapě. */
    private val dirStopsOf: Map<Int, List<MapStop>>,
) {
    private val lats = DoubleArray(stops.size) { stops[it].lat }

    internal fun inView(view: ViewBox, limit: Int = 1500): List<MapStop> {
        var lo = 0
        var hi = lats.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (lats[mid] < view.s) lo = mid + 1 else hi = mid }
        val out = ArrayList<MapStop>()
        var i = lo
        while (i < stops.size && stops[i].lat <= view.n) {
            val st = stops[i++]
            if (st.lng in view.w..view.e) {
                out += st
                if (out.size >= limit) break
            }
        }
        return out
    }

    /** Nástupiště, u kterého staví spoj v zastávce [s] (předchozí [prev], další [next]); null = není rozdělená. */
    fun platformFor(s: Int, prev: Int, next: Int): MapStop? {
        val list = dirStopsOf[s] ?: return null
        if (list.size == 1) return list[0]
        val g = list[0].dirs?.groupOf(prev, next, travelDir(tt, s, prev, next)) ?: return null
        return list.firstOrNull { it.dir == g }
    }

    companion object {
        /** Záznamy stejného názvu do takové vzdálenosti od sebe jsou jedna zastávka. */
        private const val MERGE_M = 300.0
        /** Záznamy zastávky blíž než tohle jsou jedno místo (stanoviště); dál od sebe = samostatná nástupiště. */
        private const val PLACE_M = 10.0
        /** O kolik metrů vpravo od osy silnice (středu stanoviště) leží nástupiště. */
        private const val SIDE_M = 9.0
        /** Směry, které od sebe svírají méně než tohle, jsou jeden (šum: směr se bere z předchozí → další zastávky). */
        private const val SAME_DIR_DEG = 35.0
        /** Menšinový směr pod tímto podílem odjezdů… */
        private const val MINOR_SHARE = 0.25
        /** …a ne opravdu protisměrný (úhel od většího menší než tohle) se sloučí s ním (smyčky, odbočení). */
        private const val OPPOSITE_DEG = 150.0

        /** Skupina směrů při stavbě: výseče, jejich váha a součet jednotkových vektorů. */
        private class DirAcc { val bins = ArrayList<Int>(); var w = 0.0; var x = 0.0; var y = 0.0
            val mean get() = atan2(y, x)
            fun add(b: Int, bw: Double) { bins += b; w += bw; x += cos(DirGroups.binCenter(b)) * bw; y += sin(DirGroups.binCenter(b)) * bw }
            fun addAll(o: DirAcc) { bins += o.bins; w += o.w; x += o.x; y += o.y }
        }

        /** Rozdělí váhy výsečí na skupiny směrů (viz [SAME_DIR_DEG], [MINOR_SHARE], [OPPOSITE_DEG]). */
        private fun groupDirs(binW: DoubleArray): List<DirAcc> {
            val groups = ArrayList<DirAcc>()
            val sameRad = Math.toRadians(SAME_DIR_DEG)
            for (b in binW.indices.filter { binW[it] > 0.0 }.sortedByDescending { binW[it] }) {
                val c = DirGroups.binCenter(b)
                val g = groups.filter { DirGroups.angleDiff(it.mean, c) <= sameRad }
                    .minByOrNull { DirGroups.angleDiff(it.mean, c) }
                if (g != null) g.add(b, binW[b]) else groups += DirAcc().also { it.add(b, binW[b]) }
            }
            val total = groups.sumOf { it.w }
            val oppRad = Math.toRadians(OPPOSITE_DEG)
            while (true) {
                // nejmenší menšinová skupina → nejbližší větší, pokud k ní není protisměrná
                val minor = groups.filter { it.w / total < MINOR_SHARE }.sortedBy { it.w }.firstOrNull { m ->
                    groups.any { it !== m && it.w >= m.w && DirGroups.angleDiff(it.mean, m.mean) < oppRad }
                } ?: break
                val into = groups.filter { it !== minor && it.w >= minor.w }
                    .minBy { DirGroups.angleDiff(it.mean, minor.mean) }
                into.addAll(minor)
                groups.remove(minor)
            }
            return groups.sortedByDescending { it.w }
        }

        /** Místo s menším podílem odjezdů zastávky se sloučí s nejbližším jiným místem (občasné zastavení jinde). */
        private const val TINY_PLACE_SHARE = 0.05

        /** Průjezd spojů místem: předchozí → další zastávka, váha = počet jízd, součet směrů. */
        private class Call(val prev: Int, val next: Int, val prevName: String, val nextName: String) {
            var w = 0.0; var x = 0.0; var y = 0.0
        }

        /** Místo (stanoviště) zastávky: záznamy jízdního řádu blízko sebe. */
        private class Place(stops: List<Stop>) {
            val stops = stops.toMutableList()
            val ids get() = stops.map { it.id.toInt() }.sorted().toIntArray()
            val lat = stops.sumOf { it.lat } / stops.size
            val lng = stops.sumOf { it.lng } / stops.size
            val binW = DoubleArray(DirGroups.BINS)   // váha (počet jízd) směrů po výsečích
            val calls = HashMap<Long, Call>()
            var w = 0.0
            var bus = false
            fun absorb(o: Place) {
                stops += o.stops
                for (i in binW.indices) binW[i] += o.binW[i]
                for ((k, c) in o.calls) {
                    val t = calls.getOrPut(k) { Call(c.prev, c.next, c.prevName, c.nextName) }
                    t.w += c.w; t.x += c.x; t.y += c.y
                }
                w += o.w
                bus = bus || o.bus
            }
        }

        private class Cluster(val name: String, val stops: List<Stop>, val places: List<Place>) {
            val ids = stops.map { it.id.toInt() }.sorted().toIntArray()
            val railIds = HashSet<Int>()
        }

        /** Shluky podle vzdálenosti (jednoduché spojení: do [maxM] od kteréhokoli člena). */
        private fun <T> groupNear(items: List<T>, maxM: Double, lat: (T) -> Double, lng: (T) -> Double): List<List<T>> {
            val out = ArrayList<List<T>>()
            val left = items.toMutableList()
            while (left.isNotEmpty()) {
                val group = mutableListOf(left.removeAt(0))
                var grew = true
                while (grew) {
                    grew = false
                    val iter = left.iterator()
                    while (iter.hasNext()) {
                        val c = iter.next()
                        if (group.any { g -> Geo.distanceM(lat(g), lng(g), lat(c), lng(c)) <= maxM }) {
                            group += c; iter.remove(); grew = true
                        }
                    }
                }
                out += group
            }
            return out
        }

        /** Rozestup dvou nástupišť jednoho místa, když jedou podobným směrem (stojí za sebou). */
        private const val ALONG_M = 10.0

        /**
         * Místo zastávky s víc místy je jedno nástupiště. Rozdělí se, jen když ho obsluhují různé proudy spojů:
         * jejich protisměr je na různých jiných místech zastávky A přijíždějí z různých zastávek (Stříbrníky:
         * 84 / 87 od Poláčkovy s protisměrem nahoře, 74 / 75 od Kmochovy s protisměrem dole). Spoje bez protisměru
         * patří k největšímu proudu, proudy ze stejné předchozí zastávky se sloučí (Bělehradská od Hilarovy).
         * null = nedělit.
         */
        private fun splitByPartner(pl: Place, places: List<Place>): List<List<Call>>? {
            fun partnerOf(c: Call): Place? {
                var best: Place? = null
                var bestW = 0.0
                for (q in places) {
                    if (q === pl) continue
                    val w = q.calls.values.sumOf { o ->
                        if ((o.prevName == c.nextName && c.nextName.isNotEmpty()) ||
                            (o.nextName == c.prevName && c.prevName.isNotEmpty())) o.w else 0.0
                    }
                    if (w > bestW) { bestW = w; best = q }
                }
                return best
            }
            val byPartner = LinkedHashMap<Place?, MutableList<Call>>()
            for (c in pl.calls.values.sortedByDescending { it.w }) byPartner.getOrPut(partnerOf(c)) { mutableListOf() } += c
            val orphans = byPartner.remove(null).orEmpty()
            val groups = byPartner.values.sortedByDescending { g -> g.sumOf { it.w } }.toMutableList()
            if (groups.isEmpty()) return null
            groups[0].addAll(orphans)
            // stejná hlavní předchozí zastávka = jeden proud
            fun mainPrev(g: List<Call>) = g.groupBy { it.prevName }.maxBy { (_, cs) -> cs.sumOf { it.w } }.key
            var i = 1
            while (i < groups.size) {
                val into = groups.subList(0, i).firstOrNull { mainPrev(it) == mainPrev(groups[i]) }
                if (into != null) { into.addAll(groups[i]); groups.removeAt(i) } else i++
            }
            // slabý proud (pod MINOR_SHARE jízd místa) není samostatné nástupiště (Poliklinika: od Kampusu 12 %)
            val total = groups.sumOf { g -> g.sumOf { it.w } }
            i = 1
            while (i < groups.size) {
                if (groups[i].sumOf { it.w } / total < MINOR_SHARE) { groups[0].addAll(groups[i]); groups.removeAt(i) } else i++
            }
            return if (groups.size < 2) null else groups
        }

        /**
         * [net] = síť dne (vzory spojů): z nich směry jízdy v zastávkách a které záznamy jsou vlakové.
         * [railNames] = názvy zastávek s vlaky (záloha, kdyby vlakové záznamy ve vzorech nebyly).
         */
        fun build(tt: Timetable, net: Net, railNames: Set<String>): StopIndex {
            // 1. zastávka = stejný název do MERGE_M; uvnitř místa (stanoviště) do PLACE_M
            val clusters = ArrayList<Cluster>()
            val clusterOf = HashMap<Int, Cluster>()
            val placeOf = HashMap<Int, Place>()
            val byName = HashMap<String, MutableList<Stop>>()
            for (st in tt.stops.values) if (st.hasCoords) byName.getOrPut(st.name) { mutableListOf() } += st
            for ((name, list) in byName) {
                for (group in groupNear(list, MERGE_M, { it.lat }, { it.lng })) {
                    val places = groupNear(group, PLACE_M, { it.lat }, { it.lng }).map { Place(it) }
                    val cl = Cluster(name, group, places)
                    clusters += cl
                    for (id in cl.ids) clusterOf[id] = cl
                    for (pl in places) for (id in pl.ids) placeOf[id] = pl
                }
            }

            // 2. směry autobusů na každém místě (vzor × zastávka kromě konečné, váha = počet jízd)
            fun forEachBusCall(action: (Place, Int, Int, Pair<Double, Double>, Int) -> Unit) {
                for (pat in net.patterns) {
                    if (pat.m == 0) continue
                    val train = tt.trips.isTrain(pat.tid(0))
                    val st = pat.stops
                    for (pos in 0 until st.size - 1) {
                        val s = st[pos]
                        if (train) { clusterOf[s]?.railIds?.add(s); continue }
                        val pl = placeOf[s] ?: continue
                        pl.bus = true
                        val prev = if (pos > 0) st[pos - 1] else s
                        val d = travelDir(tt, s, prev, st[pos + 1]) ?: continue
                        action(pl, prev, st[pos + 1], d, pat.m)
                    }
                }
            }
            fun nameOf(id: Int) = tt.stop(id)?.name ?: ""
            forEachBusCall { pl, prev, next, d, w ->
                pl.binW[DirGroups.bin(atan2(d.second, d.first))] += w.toDouble()
                val c = pl.calls.getOrPut(DirGroups.callKey(prev, next)) { Call(prev, next, nameOf(prev), nameOf(next)) }
                c.w += w; c.x += d.first * w; c.y += d.second * w
                pl.w += w
            }

            // 3. ikony
            val all = ArrayList<MapStop>(clusters.size + clusters.size / 2)
            val dirStopsOf = HashMap<Int, List<MapStop>>()
            for (cl in clusters) {
                val pretty = prettyName(cl.name)
                val hasRail = cl.railIds.isNotEmpty() || cl.name in railNames
                var any = false
                // místa s autobusy; občasná zastavení jinde (pod TINY_PLACE_SHARE) k nejbližšímu většímu místu
                val busPlaces = cl.places.filter { it.w > 0.0 }.sortedByDescending { it.w }.toMutableList()
                val totalW = busPlaces.sumOf { it.w }
                for (pl in busPlaces.toList().asReversed()) {
                    if (busPlaces.size < 2 || pl.w / totalW >= TINY_PLACE_SHARE) continue
                    val into = busPlaces.filter { it !== pl && it.w >= pl.w }
                        .minByOrNull { Geo.distanceM(it.lat, it.lng, pl.lat, pl.lng) } ?: continue
                    into.absorb(pl)
                    busPlaces.remove(pl)
                }
                for (pl in busPlaces) {
                    val k = cos(Math.toRadians(pl.lat)) * 111_320.0
                    // víc míst: místo = jedno nástupiště, jen výjimečně rozdělené podle proudů spojů
                    val split: List<List<Call>>? =
                        if (busPlaces.size >= 2) splitByPartner(pl, busPlaces) ?: listOf(pl.calls.values.toList()) else null
                    val dirs: DirGroups
                    val means: List<Double>
                    if (split == null) {
                        // jediné místo zastávky: nástupiště podle směru jízdy
                        val groups = groupDirs(pl.binW)
                        if (groups.isEmpty()) continue
                        val binTo = IntArray(DirGroups.BINS) { -1 }
                        groups.forEachIndexed { g, acc -> for (b in acc.bins) binTo[b] = g }
                        means = groups.map { it.mean }
                        dirs = DirGroups(binTo, means.toDoubleArray())
                    } else {
                        // víc míst: místo = nástupiště, rozdělené jen podle proudů spojů (viz splitByPartner)
                        means = split.map { g -> atan2(g.sumOf { it.y }, g.sumOf { it.x }) }
                        val keyTo = HashMap<Long, Int>()
                        val binTo = IntArray(DirGroups.BINS) { -1 }
                        split.forEachIndexed { g, calls ->
                            for (c in calls) {
                                keyTo[DirGroups.callKey(c.prev, c.next)] = g
                                binTo[DirGroups.bin(atan2(c.y, c.x))] = g
                            }
                        }
                        dirs = DirGroups(binTo, means.toDoubleArray(), keyTo)
                    }
                    val list = ArrayList<MapStop>(means.size)
                    val ids = pl.ids
                    means.forEachIndexed { g, mean ->
                        val ux = cos(mean)
                        val uy = sin(mean)
                        // víc nástupišť na jednom místě: vpravo od směru jízdy (x = východ, y = sever): (uy, -ux),
                        // a když jedou podobným směrem (vedle sebe), ještě rozestup podél silnice
                        var la = pl.lat
                        var lo = pl.lng
                        if (means.size > 1) {
                            var along = 0.0
                            if (means.size == 2 && DirGroups.angleDiff(means[0], means[1]) < Math.PI / 2) {
                                along = if (g == 0) ALONG_M else -ALONG_M
                            }
                            la += (-ux * SIDE_M + uy * along) / 110_540.0
                            lo += (uy * SIDE_M + ux * along) / k
                        }
                        list += MapStop("d${ids[0]}_$g", la, lo, pretty, ids, StopKind.DIR, g, dirs)
                    }
                    if (list.isEmpty()) continue
                    any = true
                    all += list
                    for (id in ids) dirStopsOf[id] = list
                }
                if (hasRail) {
                    // vlaky: vlakové záznamy (leží jinde než autobusové), jinak záznamy bez autobusů, jinak střed
                    val src = cl.stops.filter { it.id.toInt() in cl.railIds }
                        .ifEmpty { cl.stops.filter { s -> placeOf[s.id.toInt()]?.bus != true } }
                        .ifEmpty { cl.stops }
                    all += MapStop(
                        "r${cl.ids[0]}", src.sumOf { it.lat } / src.size, src.sumOf { it.lng } / src.size,
                        pretty, cl.ids, StopKind.RAIL,
                    )
                    any = true
                }
                if (!any) {
                    val la = cl.stops.sumOf { it.lat } / cl.stops.size
                    val lo = cl.stops.sumOf { it.lng } / cl.stops.size
                    all += MapStop("t${cl.ids[0]}", la, lo, pretty, cl.ids)
                }
            }
            all.sortBy { it.lat }
            return StopIndex(tt, all.toTypedArray(), dirStopsOf)
        }
    }
}

/** Odjezdy ze zastávky od [from] na [hours] hodin (bez spojů, které tu končí). */
internal fun stopDepartures(planner: Planner, stop: MapStop, from: LocalDateTime, hours: Int = 3): List<StopDeparture> {
    val tt = planner.tt
    val day = from.toLocalDate()
    val net = planner.net(day)
    val t0 = from.toLocalTime().toSecondOfDay()
    val t1 = t0 + hours * 3600
    val out = ArrayList<StopDeparture>()
    val seen = HashSet<Long>()
    val sp = net.stopPats
    for (s in stop.ttStops) for (k in sp.from(s) until sp.to(s) step 2) {
        val pat = net.patterns[sp.data[k]]
        val pos = sp.data[k + 1]
        if (pos >= pat.stops.size - 1 || pat.m == 0) continue     // tady spoj končí
        val train = tt.trips.isTrain(pat.tid(0))
        when (stop.kind) {
            StopKind.RAIL -> if (!train) continue
            StopKind.DIR -> {
                if (train) continue
                val dirs = stop.dirs
                if (dirs != null && dirs.size > 1) {
                    val prev = if (pos > 0) pat.stops[pos - 1] else s
                    val next = pat.stops[pos + 1]
                    if (dirs.groupOf(prev, next, travelDir(tt, s, prev, next)) != stop.dir) continue
                }
            }
            StopKind.ALL -> {}
        }
        var j = pat.firstDepAtOrAfter(pos, t0)
        while (j < pat.m) {
            val dep = pat.dep(j, pos)
            if (dep > t1) break
            val tid = pat.tid(j)
            j++
            val line = tt.trips.lineOf(tid) ?: continue
            if (!seen.add((tid.toLong() shl 20) xor dep.toLong())) continue
            val head = tt.trips.head[tid]?.takeIf { it.isNotBlank() }
                ?: tt.stop(pat.stops.last())?.name ?: ""
            out += StopDeparture(tid, dep, day, line, prettyName(head), tt.trips.category(tid))
        }
    }
    out.sortBy { it.depSec }
    return out
}
