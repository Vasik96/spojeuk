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

/** Čárka hned následovaná nemezerou (předkompilováno: prettyName se volá pro každou zastávku, odjezd a spojení). */
private val COMMA_NO_SPACE = Regex(",(?=\\S)")

fun prettyName(name: String): String {
    // bez čárky není co doplňovat: regex se nespouští (výsledek stejný)
    val spaced = if (name.indexOf(',') >= 0) name.replace(COMMA_NO_SPACE, ", ") else name
    return (if (spaced.contains("null")) spaced.replace("null", "") else spaced).trim()
}

fun pluralTransfers(n: Int): String =
    if (n == 0) tr(R.string.transfers_none) else trPlural(R.plurals.transfers, n, n)

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


// ───────────────────────── hlavní vlákno ─────────────────────────

/**
 * Pozastaví korutinu, dokud hlavní vlákno nemá co dělat (fronta zpráv je prázdná). Volat z hlavního vlákna.
 * Na rozdíl od pevného `delay` se nečeká na čas, ale na to, že práce navíc už nikoho nebrzdí.
 */
suspend fun awaitMainThreadIdle() = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
    val queue = android.os.Looper.myQueue()
    val handler = android.os.MessageQueue.IdleHandler {
        if (cont.isActive) cont.resumeWith(Result.success(Unit))
        false // jednorázový
    }
    queue.addIdleHandler(handler)
    cont.invokeOnCancellation { queue.removeIdleHandler(handler) }
}
/**
 * Sdílené instance opakujících se textů z DB (linky, dopravci, cíle u statisíců spojů), čtené bez alokace:
 * Cursor.getString by pro každý řádek vytvořil nový String jen kvůli porovnání (~1,5 milionu dočasných řetězců při
 * načtení JŘ, GC kvůli nim zastavoval UI). Text se zkopíruje do znovupoužívaného bufferu a hledá se v tabulce.
 */
class CursorStringPool {
    private val buf = android.database.CharArrayBuffer(64)
    private var table = arrayOfNulls<String>(16384)
    private var count = 0

    fun get(c: android.database.Cursor, col: Int): String? {
        if (c.isNull(col)) return null
        c.copyStringToBuffer(col, buf)
        val data = buf.data
        val len = buf.sizeCopied
        var h = 0
        for (i in 0 until len) h = 31 * h + data[i].code
        var idx = (h xor (h ushr 16)) and (table.size - 1)
        while (true) {
            val s = table[idx]
            if (s == null) {
                val n = String(data, 0, len)
                table[idx] = n
                if (++count * 2 > table.size) grow()
                return n
            }
            if (s.length == len && s.hashCode() == h && matches(s, data, len)) return s
            idx = (idx + 1) and (table.size - 1)
        }
    }

    private fun matches(s: String, data: CharArray, len: Int): Boolean {
        for (i in 0 until len) if (s[i] != data[i]) return false
        return true
    }

    private fun grow() {
        val old = table
        table = arrayOfNulls(old.size * 2)
        for (s in old) {
            if (s == null) continue
            val h = s.hashCode()
            var idx = (h xor (h ushr 16)) and (table.size - 1)
            while (table[idx] != null) idx = (idx + 1) and (table.size - 1)
            table[idx] = s
        }
    }
}
