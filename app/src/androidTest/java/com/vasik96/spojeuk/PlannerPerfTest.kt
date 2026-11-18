package com.vasik96.spojeuk

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Měření rychlosti plánovače nad skutečným jízdním řádem v telefonu (bez UI, běží na pozadí):
 *   adb shell am instrument -w -e class com.vasik96.spojeuk.PlannerPerfTest \
 *       com.vasik96.spojeuk.test/androidx.test.runner.AndroidJUnitRunner
 * Výsledky v logcatu pod značkou PlannerPerf.
 */
@RunWith(AndroidJUnit4::class)
class PlannerPerfTest {
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    @Test
    fun measure() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = File(File(ctx.filesDir, "spojeuk"), "static.sqlite")
        assumeTrue("jízdní řád v telefonu není", db.exists())

        var t0 = System.nanoTime()
        val tt = Timetable(db)
        Log.i("PlannerPerf", "Timetable: ${ms(t0)} ms (${tt.trips.size} spojů)")

        // rozbor načítání JŘ: tabulka trip s / bez gid
        android.database.sqlite.SQLiteDatabase.openDatabase(db.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { d ->
            for (cols in listOf("id, line, agency, cis, num, head, svc, wheel, t0, t1", "id, line, agency, cis, num, head, svc, wheel, t0, t1, gid", "id, svc, t0, t1")) {
                t0 = System.nanoTime()
                var n = 0
                d.rawQuery("SELECT $cols FROM trip", null).use { c -> while (c.moveToNext()) { for (i in 0 until c.columnCount) if (c.getType(i) == android.database.Cursor.FIELD_TYPE_STRING) c.getString(i) else c.getLong(i); n++ } }
                Log.i("PlannerPerf", "trip [$cols]: ${ms(t0)} ms")
            }
            t0 = System.nanoTime()
            d.rawQuery("SELECT id, name, place, lat, lon, inreg FROM stop", null).use { c -> while (c.moveToNext()) { c.getString(1); c.getString(2) } }
            Log.i("PlannerPerf", "stop: ${ms(t0)} ms")
            t0 = System.nanoTime()
            d.rawQuery("SELECT svc, d, type FROM svc_date", null).use { c -> while (c.moveToNext()) { c.getString(1) } }
            Log.i("PlannerPerf", "svc_date: ${ms(t0)} ms")
        }

        // rozbor stavby sítě: čtení z SQLite / převod blobů / seskupení
        android.database.sqlite.SQLiteDatabase.openDatabase(db.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { d ->
            t0 = System.nanoTime()
            var n = 0
            d.rawQuery("SELECT trip FROM trip_times", null).use { c -> while (c.moveToNext()) { c.getInt(0); n++ } }
            Log.i("PlannerPerf", "jen trip id: ${ms(t0)} ms ($n)")
            t0 = System.nanoTime()
            var bytes = 0L
            d.rawQuery("SELECT trip, stops, arr, dep FROM trip_times", null).use { c ->
                while (c.moveToNext()) { bytes += c.getBlob(1).size + c.getBlob(2).size + c.getBlob(3).size }
            }
            Log.i("PlannerPerf", "všechny bloby: ${ms(t0)} ms (${bytes / 1048576} MB)")
            t0 = System.nanoTime()
            val keys = HashMap<List<Int>, Int>()
            d.rawQuery("SELECT stops FROM trip_times", null).use { c ->
                while (c.moveToNext()) { val k = bytesToIntArray(c.getBlob(0)).toList(); keys[k] = (keys[k] ?: 0) + 1 }
            }
            Log.i("PlannerPerf", "stops->List<Int> klíče: ${ms(t0)} ms (${keys.size} vzorů)")
        }

        val today = nowPrague().toLocalDate()
        run {
            val maxTid = tt.trips.keys.max()
            fun scan(lo: Int, hi: Int, join: Boolean): Long {
                var b = 0L
                android.database.sqlite.SQLiteDatabase.openDatabase(db.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { d ->
                    val svcs = tt.activeSvcs(today).joinToString(",")
                    val sql = if (join) "SELECT tt.trip, tt.stops, tt.arr, tt.dep FROM trip_times tt JOIN trip t ON t.id = tt.trip WHERE tt.trip BETWEEN $lo AND $hi AND t.svc IN ($svcs)"
                    else "SELECT trip, stops, arr, dep FROM trip_times WHERE trip BETWEEN $lo AND $hi"
                    d.rawQuery(sql, null).use { c -> while (c.moveToNext()) { b += c.getBlob(1).size + c.getBlob(2).size + c.getBlob(3).size } }
                }
                return b
            }
            for ((name, join, threads) in listOf(Triple("1 vlákno bez filtru", false, 1), Triple("4 vlákna bez filtru", false, 4), Triple("1 vlákno s filtrem", true, 1), Triple("4 vlákna s filtrem", true, 4))) {
                t0 = System.nanoTime()
                val step = maxTid / threads + 1
                val th = (0 until threads).map { i -> Thread { scan(i * step, (i + 1) * step - 1, join) }.also { it.start() } }
                th.forEach { it.join() }
                Log.i("PlannerPerf", "čtení $name: ${ms(t0)} ms")
            }
        }
        run {
            val p1 = Planner(tt)
            t0 = System.nanoTime()
            p1.net(today)
            Log.i("PlannerPerf", "síť jen dnes: ${ms(t0)} ms")
        }
        System.gc()
        val planner = Planner(tt)
        t0 = System.nanoTime()
        planner.prepare(listOf(today, today.plusDays(1)))
        Log.i("PlannerPerf", "síť dnes + zítra (jeden průchod): ${ms(t0)} ms")

        t0 = System.nanoTime()
        val footN = tt.foot.size
        Log.i("PlannerPerf", "pěší přestupy (foot): ${ms(t0)} ms ($footN)")
        val from = tt.places.keys.firstOrNull { it.contains("Na Rybárně") } ?: tt.places.keys.first()
        val to = tt.places.keys.firstOrNull { it.contains("Mírové náměstí") && it.startsWith("Ústí") } ?: tt.places.keys.last()
        t0 = System.nanoTime()
        val j = planner.find(from, to, nowPrague(), 8)
        Log.i("PlannerPerf", "hledání $from → $to: ${ms(t0)} ms (${j.size} spojení)")
    }
}
