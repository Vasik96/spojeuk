package com.vasik96.spojeuk

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.channels.FileChannel

/**
 * Časy jízd (tabulka trip_times) v binárním souboru vedle DB, už rozdělené podle vzoru zastávek. Soubor se namapuje
 * do paměti (mmap), takže stavba sítě dne ([Net.build]) nealokuje nic kromě výsledných polí.
 *
 * Dřív se síť stavěla přímo z SQLite: bloby, jejich převody na IntArray a kopie dělaly ~150 MB dočasných dat za necelou
 * sekundu. ART po úklidu nechá volných jen ~32 MB (dalvik.vm.heapmaxfree), takže GC nestíhal a zastavoval všechna vlákna
 * včetně UI (~300 ms zásek animace načítání, když uživatel hned po startu hledal spojení).
 *
 * Soubor se vytvoří jednou pro každou verzi DB (pozná se podle velikosti a času změny souboru DB).
 *
 * Formát (vše Int, little endian): hlavička [HEADER] intů, pak data jízd (pro každý spoj příjezdy a za nimi odjezdy;
 * když jsou stejné, jen jednou a v indexu je příznak [SAME]), pak tabulky spoj → vzor, spoj → index dat,
 * začátky vzorů a zastávky vzorů.
 */
class NetCache private constructor(private val ib: IntBuffer) {
    val maxTid = ib.get(6)
    val nPat = ib.get(7)
    private val offPat = ib.get(8)
    private val offTrip = ib.get(9)
    private val offPatStart = ib.get(10)
    private val offPatStops = ib.get(11)

    /** Vzor spoje, nebo -1. */
    fun patOf(tid: Int): Int = if (tid < 0 || tid > maxTid) -1 else ib.get(offPat + tid)

    fun patLen(p: Int): Int = ib.get(offPatStart + p + 1) - ib.get(offPatStart + p)

    fun patStops(p: Int): IntArray {
        val s = ib.get(offPatStart + p)
        return IntArray(ib.get(offPatStart + p + 1) - s) { ib.get(offPatStops + s + it) }
    }

    /** Index příjezdů spoje v souboru. */
    fun arrBase(tid: Int): Int = ib.get(offTrip + tid) and SAME.inv()

    /** Index odjezdů spoje (stejný jako příjezdů, když se neliší). */
    fun depBase(tid: Int, n: Int): Int {
        val d = ib.get(offTrip + tid)
        return if (d and SAME != 0) d and SAME.inv() else d + n
    }

    operator fun get(i: Int): Int = ib.get(i)

    companion object {
        private const val TAG = "NetCache"
        private const val MAGIC = 0x534E4331 // "SNC1"
        private const val VERSION = 1
        private const val HEADER = 16
        private const val SAME = Int.MIN_VALUE

        fun fileFor(dbFile: File) = File(dbFile.parentFile, "net_cache.bin")

        fun open(dbFile: File): NetCache {
            val f = fileFor(dbFile)
            tryOpen(f, dbFile)?.let { return it }
            val t0 = System.nanoTime()
            write(dbFile, f)
            Log.d("SearchPerf", "mezipaměť sítě vytvořena za ${(System.nanoTime() - t0) / 1_000_000} ms (${f.length() / 1048576} MB)")
            return tryOpen(f, dbFile) ?: error("mezipaměť sítě nejde otevřít")
        }

        private fun tryOpen(f: File, dbFile: File): NetCache? {
            if (!f.exists()) return null
            return try {
                val bb = RandomAccessFile(f, "r").use { raf -> raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()) }
                val ib = bb.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
                val ok = ib.limit() >= HEADER && ib.get(0) == MAGIC && ib.get(1) == VERSION &&
                    long(ib, 2) == dbFile.length() && long(ib, 4) == dbFile.lastModified() && ib.get(12).toLong() == ib.limit().toLong()
                if (ok) NetCache(ib) else { f.delete(); null }
            } catch (e: Exception) {
                Log.w(TAG, "mezipaměť sítě je vadná: ${e.message}")
                f.delete()
                null
            }
        }

        private fun long(ib: IntBuffer, i: Int) = (ib.get(i).toLong() shl 32) or (ib.get(i + 1).toLong() and 0xFFFFFFFFL)

        private fun write(dbFile: File, f: File) {
            val tmp = File(f.path + ".tmp")
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                RandomAccessFile(tmp, "rw").use { raf ->
                    raf.setLength(0)
                    val ch = raf.channel
                    val buf = ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.LITTLE_ENDIAN)
                    var pos = HEADER
                    fun flush() {
                        buf.flip()
                        while (buf.hasRemaining()) ch.write(buf)
                        buf.clear()
                    }
                    fun put(v: Int) {
                        if (!buf.hasRemaining()) flush()
                        buf.putInt(v)
                        pos++
                    }
                    ch.position(HEADER * 4L)

                    val maxTid = db.rawQuery("SELECT max(trip) FROM trip_times", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                    val tripPat = IntArray(maxTid + 1) { -1 }
                    val tripOff = IntArray(maxTid + 1)
                    val keys = HashMap<StopsKey, Int>()
                    val patStops = ArrayList<IntArray>()
                    db.rawQuery("SELECT trip, stops, arr, dep FROM trip_times", null).use { c ->
                        while (c.moveToNext()) {
                            val tid = c.getInt(0)
                            val sb = c.getBlob(1) ?: continue
                            val ab = c.getBlob(2) ?: continue
                            val db2 = c.getBlob(3) ?: continue
                            val n = sb.size / 4
                            if (tid !in 0..maxTid || n == 0 || ab.size != n * 4 || db2.size != n * 4) continue
                            tripPat[tid] = keys.getOrPut(StopsKey(sb)) { patStops.add(bytesToIntArray(sb)); patStops.size - 1 }
                            val same = ab.contentEquals(db2)
                            tripOff[tid] = if (same) pos or SAME else pos
                            val a = ByteBuffer.wrap(ab).order(ByteOrder.LITTLE_ENDIAN)
                            repeat(n) { put(a.getInt()) }
                            if (!same) {
                                val d = ByteBuffer.wrap(db2).order(ByteOrder.LITTLE_ENDIAN)
                                repeat(n) { put(d.getInt()) }
                            }
                        }
                    }
                    val offPat = pos
                    for (v in tripPat) put(v)
                    val offTrip = pos
                    for (v in tripOff) put(v)
                    val offPatStart = pos
                    var acc = 0
                    put(0)
                    for (s in patStops) { acc += s.size; put(acc) }
                    val offPatStops = pos
                    for (s in patStops) for (v in s) put(v)
                    flush()

                    val h = ByteBuffer.allocate(HEADER * 4).order(ByteOrder.LITTLE_ENDIAN)
                    val len = dbFile.length()
                    val mt = dbFile.lastModified()
                    for (v in intArrayOf(
                        MAGIC, VERSION, (len ushr 32).toInt(), len.toInt(), (mt ushr 32).toInt(), mt.toInt(),
                        maxTid, patStops.size, offPat, offTrip, offPatStart, offPatStops, pos
                    )) h.putInt(v)
                    h.position(HEADER * 4)
                    h.flip()
                    while (h.hasRemaining()) ch.write(h, h.position().toLong())
                    ch.force(false)
                }
            } finally {
                db.close()
            }
            if (!tmp.renameTo(f)) { tmp.delete(); error("mezipaměť sítě nejde uložit") }
        }
    }
}

/** Klíč vzoru = pořadí zastávek jako surové bajty z DB. */
internal class StopsKey(val bytes: ByteArray) {
    private val h = bytes.contentHashCode()
    override fun hashCode() = h
    override fun equals(other: Any?) = other is StopsKey && other.h == h && other.bytes.contentEquals(bytes)
}
