package com.vasik96.spojeuk

/**
 * Spoje jízdního řádu po sloupcích, indexované id spoje (id v DB jsou souvislá 1..max).
 *
 * Dřív HashMap<Int, DbTrip>: ~300 tisíc spojů = ~900 tisíc malých objektů (spoj, zabalené číslo, uzel mapy).
 * GC je musel při každém úklidu projít a přesunout (stovky ms na pomalém telefonu), a když přitom hlavní vlákno
 * alokovalo, čekalo na něj => zásek UI hned po startu. Sloupce jsou pár velkých polí (GC je nepřesouvá
 * a primitivní pole neprochází), řetězce jsou sdílené z [CursorStringPool].
 *
 * [get] vytvoří [DbTrip] na požádání (krátce žijící objekt); horké smyčky přes všechny spoje čtou sloupce přímo.
 */
class TripTable(val maxId: Int) {
    private val n = maxId + 1
    internal val line = arrayOfNulls<String>(n)       // null = spoj s tímto id neexistuje
    internal val agency = arrayOfNulls<String>(n)
    internal val cis = arrayOfNulls<String>(n)
    internal val num = arrayOfNulls<String>(n)
    internal val head = arrayOfNulls<String>(n)
    internal val svc = IntArray(n)
    internal val wheel = IntArray(n)
    internal val t0 = IntArray(n)
    internal val t1 = IntArray(n)
    internal val train = BooleanArray(n)

    /** Počet existujících spojů (nastaví loader po naplnění). */
    var size = 0
        internal set

    fun has(id: Int): Boolean = id in 0 until n && line[id] != null

    operator fun get(id: Int): DbTrip? {
        if (!has(id)) return null
        return DbTrip(
            id = id, line = line[id]!!, agency = agency[id]!!, cis = cis[id]!!, num = num[id]!!, head = head[id]!!,
            svc = svc[id], wheel = wheel[id], t0 = t0[id], t1 = t1[id], isTrain = train[id],
        )
    }

    fun isTrain(id: Int): Boolean = has(id) && train[id]

    /**
     * Druh dopravy spoje pro filtr hledání. Jízdní řád druh (route_type) nemá: vlak podle čísla vlaku, metro = linky
     * A/B/C Dopravního podniku hl. m. Prahy, tramvaj = jeho číselné linky pod 100 (autobusy DPP mají 100+), jinak bus
     * (i trolejbusy, jako na mapě). Počítá se jednou pro všechny spoje, hledání se ptá v horké smyčce.
     */
    fun category(id: Int): MapCategory = if (has(id)) MapCategory.entries[categories[id].toInt()] else MapCategory.BUS

    private val categories: ByteArray by lazy {
        ByteArray(n) { id ->
            val c = when {
                line[id] == null -> MapCategory.BUS
                train[id] -> MapCategory.TRAIN
                agency[id]!!.contains("Prahy") -> {
                    val l = line[id]!!.trim()
                    when {
                        l == "A" || l == "B" || l == "C" -> MapCategory.METRO
                        (l.toIntOrNull() ?: 999) < 100 -> MapCategory.TRAM
                        else -> MapCategory.BUS
                    }
                }
                else -> MapCategory.BUS
            }
            c.ordinal.toByte()
        }
    }
    fun lineOf(id: Int): String? = if (has(id)) line[id] else null

    /** Id všech existujících spojů vzestupně. */
    inline fun forEachId(action: (Int) -> Unit) {
        for (id in 0..maxId) if (has(id)) action(id)
    }

    internal fun set(
        id: Int, line: String, agency: String, cis: String, num: String, head: String,
        svc: Int, wheel: Int, t0: Int, t1: Int, isTrain: Boolean,
    ) {
        this.line[id] = line; this.agency[id] = agency; this.cis[id] = cis; this.num[id] = num; this.head[id] = head
        this.svc[id] = svc; this.wheel[id] = wheel; this.t0[id] = t0; this.t1[id] = t1; this.train[id] = isTrain
    }
}
