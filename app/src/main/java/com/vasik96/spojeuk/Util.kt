package com.vasik96.spojeuk

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

// ───────────────────────── geometrie ─────────────────────────

object Geo {
    const val M_PER_DEG_LAT = 110540.0
    const val M_PER_DEG_LNG = 111320.0

    /** Vzdálenost dvou bodů v metrech (ploché přiblížení, na škále kraje bohatě stačí). */
    fun distanceM(la1: Double, lo1: Double, la2: Double, lo2: Double): Double =
        hypot((lo2 - lo1) * M_PER_DEG_LNG * cos(Math.toRadians(la1)), (la2 - la1) * M_PER_DEG_LAT)

    fun bearingDeg(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val y = (lo2 - lo1) * cos(Math.toRadians(la1))
        val x = la2 - la1
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}

// ───────────────────────── čas ─────────────────────────

val PRAGUE_ZONE: ZoneId = ZoneId.of("Europe/Prague")

fun nowPrague(): LocalDateTime = LocalDateTime.now(PRAGUE_ZONE)
fun todayPrague(): LocalDate = LocalDate.now(PRAGUE_ZONE)

/** Sekundy od půlnoci dne [day] (může být > 86400 nebo záporné, když je "teď" v jiném dni). */
fun secondsSinceMidnight(day: LocalDate, now: LocalDateTime = nowPrague()): Int =
    Duration.between(day.atStartOfDay(), now).seconds.toInt()

fun hhmm(sec: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", (sec / 3600) % 24, (sec % 3600) / 60)

fun parseTsec(s: String?): Int? {
    if (s.isNullOrBlank()) return null
    val p = s.split(":")
    if (p.size < 2) return null
    val h = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    val x = if (p.size >= 3) p[2].toIntOrNull() ?: 0 else 0
    return h * 3600 + m * 60 + x
}

/** "HH:MM..." → minuty od půlnoci. */
fun tm(value: String): Int {
    val p = value.take(5).split(":")
    return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
}

// ───────────────────────── text ─────────────────────────

fun foldText(s: String): String {
    val norm = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    val sb = StringBuilder(norm.length)
    for (ch in norm) if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) sb.append(ch)
    return sb.toString()
}

fun normalize(text: String): String = foldText(text)

fun prettyName(name: String): String = name.replace(Regex(",(?=\\S)"), ", ").replace("null", "").trim()

fun pluralTransfers(n: Int): String =
    if (n == 0) "přímo" else "$n " + if (n == 1) "přestup" else if (n in 2..4) "přestupy" else "přestupů"

/** Linka pro porovnávání: "070" == "70", bez diakritiky a velkých písmen. */
fun normLine(s: String): String {
    val t = s.trim()
    return if (t.isNotEmpty() && t.all { it.isDigit() }) (t.toIntOrNull()?.toString() ?: t) else foldText(t)
}

fun JSONObject.optStringClean(key: String, fallback: String = ""): String {
    if (isNull(key)) return fallback
    val s = optString(key, fallback)
    return if (s == "null" || s.isBlank()) fallback else s
}

// ───────────────────────── binární pole (BLOBy v SQLite) ─────────────────────────

fun bytesToIntArray(bytes: ByteArray): IntArray {
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return IntArray(bytes.size / 4) { bb.getInt() }
}

fun intArrayToBytes(arr: IntArray): ByteArray {
    val bb = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (v in arr) bb.putInt(v)
    return bb.array()
}
