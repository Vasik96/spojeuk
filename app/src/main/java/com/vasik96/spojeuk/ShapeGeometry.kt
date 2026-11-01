package com.vasik96.spojeuk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Přesné trasy spojů z GTFS `shapes.txt` (generuje je obehy map-matchingem na OSM).
 * Žádný Android kód, jen čistá geometrie, takže jde otestovat i mimo telefon.
 */
object ShapeGeometry {
    private const val M_LAT = 110540.0
    private const val M_LNG = 111320.0

    /** Zastávka dál než tohle od tvaru se nepovažuje za ležící na trase. */
    const val MAX_OFF_M = 120.0

    // ───────── načtení tvaru ze shapes.sqlite ─────────

    /** Tvar: lat/lng + vzdálenost od začátku tvaru v metrech (stejná jednotka jako offsety zastávek). */
    class Shape(val pts: List<Pair<Double, Double>>, val dist: DoubleArray)

    /** int32 LE: na bod lat, lng (1e-5 °) a dist (m); první bod absolutně, další jako delty. */
    fun decode(bytes: ByteArray): Shape {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = bytes.size / 12
        val pts = ArrayList<Pair<Double, Double>>(n)
        val dist = DoubleArray(n)
        var la = 0
        var lo = 0
        var d = 0
        for (i in 0 until n) {
            val a = bb.getInt()
            val b = bb.getInt()
            val c = bb.getInt()
            if (i == 0) { la = a; lo = b; d = c } else { la += a; lo += b; d += c }
            pts.add(la / 1e5 to lo / 1e5)
            dist[i] = d.toDouble()
        }
        return Shape(pts, dist)
    }

    /** int32 LE pole (offsety zastávek v metrech). */
    fun decodeInts(bytes: ByteArray): IntArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(bytes.size / 4) { bb.getInt() }
    }

    // ───────── poloha zastávek na tvaru ─────────

    /** Tvar v lokálních metrech + kumulativní délka, aby šly zastávky promítnout na trasu. */
    class Path(val pts: List<Pair<Double, Double>>, dist: DoubleArray? = null) {
        private val lat0 = pts[0].first
        private val lng0 = pts[0].second
        private val cosLat = cos(Math.toRadians(lat0))
        val x = DoubleArray(pts.size) { (pts[it].second - lng0) * M_LNG * cosLat }
        val y = DoubleArray(pts.size) { (pts[it].first - lat0) * M_LAT }
        // vzdálenosti z GTFS (přesné i po zjednodušení tvaru); jinak dopočítané z geometrie
        val cum: DoubleArray = if (dist != null && dist.size == pts.size) dist else DoubleArray(pts.size).also {
            for (i in 1 until pts.size) it[i] = it[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        }
        val length: Double get() = cum.last()

        fun toX(lng: Double) = (lng - lng0) * M_LNG * cosLat
        fun toY(lat: Double) = (lat - lat0) * M_LAT

        /** Pozice (m od začátku) a vzdálenost od tvaru pro každou „skupinu“ blízkých segmentů. */
        fun candidates(lat: Double, lng: Double, maxOff: Double = MAX_OFF_M): List<Pair<Double, Double>> {
            val px = toX(lng)
            val py = toY(lat)
            val out = ArrayList<Pair<Double, Double>>()
            var bestD = Double.MAX_VALUE
            var bestS = 0.0
            var inGroup = false
            for (i in 0 until pts.size - 1) {
                val dx = x[i + 1] - x[i]
                val dy = y[i + 1] - y[i]
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (((px - x[i]) * dx + (py - y[i]) * dy) / l2).coerceIn(0.0, 1.0)
                val d = hypot(px - (x[i] + t * dx), py - (y[i] + t * dy))
                if (d <= maxOff) {
                    if (!inGroup || d < bestD) { bestD = d; bestS = cum[i] + t * (cum[i + 1] - cum[i]) }
                    inGroup = true
                } else if (inGroup) {
                    out.add(bestS to bestD)
                    inGroup = false
                    bestD = Double.MAX_VALUE
                }
            }
            if (inGroup) out.add(bestS to bestD)
            return out
        }

        /** Bod na tvaru ve vzdálenosti [s] metrů od začátku. */
        fun pointAt(s: Double): Pair<Double, Double> {
            if (s <= 0.0) return pts.first()
            if (s >= length) return pts.last()
            var lo = 0
            var hi = cum.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (cum[mid] <= s) lo = mid else hi = mid
            }
            val seg = cum[lo + 1] - cum[lo]
            val t = if (seg == 0.0) 0.0 else (s - cum[lo]) / seg
            return (pts[lo].first + t * (pts[lo + 1].first - pts[lo].first)) to
                    (pts[lo].second + t * (pts[lo + 1].second - pts[lo].second))
        }

        /** Úsek tvaru mezi [from] a [to] metrů. */
        fun slice(from: Double, to: Double): List<Pair<Double, Double>> {
            val a = max(0.0, min(from, to))
            val b = min(length, max(from, to))
            val out = ArrayList<Pair<Double, Double>>()
            out.add(pointAt(a))
            for (i in pts.indices) if (cum[i] > a && cum[i] < b) out.add(pts[i])
            out.add(pointAt(b))
            return out
        }
    }

    /**
     * Pozice všech zastávek spoje na tvaru (m od začátku tvaru), v pořadí jízdy.
     * Hledá takové přiřazení, které je neklesající podél trasy a má nejmenší součet odchylek,
     * takže spoj jedoucí tam a zpátky po stejné silnici nebo smyčka dopadne správně.
     * Zastávka, která na tvar nedosáhne, dostane NaN.
     */
    fun locateStops(path: Path, stops: List<Pair<Double, Double>>): DoubleArray {
        val res = DoubleArray(stops.size) { Double.NaN }
        val layers = ArrayList<Pair<Int, List<Pair<Double, Double>>>>() // (index zastávky, kandidáti)
        for ((i, s) in stops.withIndex()) {
            val c = path.candidates(s.first, s.second)
            if (c.isNotEmpty()) layers.add(i to c)
        }
        if (layers.isEmpty()) return res

        val tol = 30.0                // šum při zpětném skoku (zastávky na protilehlých stranách)
        val backPenalty = 2000.0      // zpětný skok je možný, ale drahý
        val cost = ArrayList<DoubleArray>()
        val from = ArrayList<IntArray>()
        for ((li, layer) in layers.withIndex()) {
            val cands = layer.second
            val c = DoubleArray(cands.size)
            val f = IntArray(cands.size)
            for ((ci, cand) in cands.withIndex()) {
                if (li == 0) { c[ci] = cand.second; f[ci] = -1; continue }
                val prev = layers[li - 1].second
                var best = Double.MAX_VALUE
                var bi = 0
                for ((pi, pc) in prev.withIndex()) {
                    val back = pc.first - cand.first
                    val step = cost[li - 1][pi] + if (back <= tol) 0.0 else backPenalty + back
                    if (step < best) { best = step; bi = pi }
                }
                c[ci] = best + cand.second
                f[ci] = bi
            }
            cost.add(c)
            from.add(f)
        }
        var ci = cost.last().indices.minByOrNull { cost.last()[it] } ?: return res
        for (li in layers.indices.reversed()) {
            res[layers[li].first] = layers[li].second[ci].first
            ci = from[li][ci]
            if (ci < 0) break
        }
        return res
    }

    /**
     * Trasa mezi zastávkami [boardIdx]..[alightIdx] (indexy do [stops]) po tvaru, nebo null,
     * když se zastávky na tvar nedají spolehlivě položit.
     */
    fun sliceBetween(
        shape: Shape,
        stops: List<Pair<Double, Double>>,
        boardIdx: Int,
        alightIdx: Int
    ): List<Pair<Double, Double>>? {
        if (shape.pts.size < 2 || boardIdx !in stops.indices || alightIdx !in stops.indices) return null
        val path = Path(shape.pts, shape.dist)
        val pos = locateStops(path, stops)
        val a = pos[boardIdx]
        val b = pos[alightIdx]
        if (a.isNaN() || b.isNaN() || b + 30.0 < a) return null
        return path.slice(a, b)
    }

    fun sliceBetween(
        pts: List<Pair<Double, Double>>,
        stops: List<Pair<Double, Double>>,
        boardIdx: Int,
        alightIdx: Int
    ): List<Pair<Double, Double>>? = sliceBetween(Shape(pts, DoubleArray(0)), stops, boardIdx, alightIdx)

    /** Pro testy: délka lomené čáry v metrech. */
    fun lengthM(pts: List<Pair<Double, Double>>): Double {
        if (pts.size < 2) return 0.0
        val cosLat = cos(Math.toRadians(pts[0].first))
        var s = 0.0
        for (i in 1 until pts.size) {
            s += hypot(
                (pts[i].second - pts[i - 1].second) * M_LNG * cosLat,
                (pts[i].first - pts[i - 1].first) * M_LAT
            )
        }
        return s
    }
}