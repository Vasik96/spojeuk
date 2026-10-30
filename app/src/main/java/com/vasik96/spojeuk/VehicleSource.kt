package com.vasik96.spojeuk

enum class Health {
    /** Poslední dotaz proběhl nedávno a data vypadají aktuálně. */
    LIVE,

    /** Zdroj už něco dodal, ale data jsou stará. */
    STALE,

    /** Zdroj ještě nikdy neodpověděl. */
    DOWN,
}

/** Kdy zdroj považovat za aktuální. Vyhodnocuje to VehicleHub, zdroj jen deklaruje pravidla. */
data class Freshness(
    /** Jak dlouho po posledním úspěšném dotazu je zdroj ještě "živý". */
    val maxFetchAgeMs: Long,
    /** Medián stáří poloh (jen z vozů s časovou značkou) v době dotazu; null = nehlídat. */
    val maxMedianAgeSec: Long? = null,
    /** Poloha starší než tohle je "zastaralá". */
    val staleVehicleSec: Long = 40,
    /** Segmenty, u kterých se hlídá, že většina poloh není zastaralá (nebo že zdroj není ve výpadku). */
    val watchedSegments: Set<Segment> = emptySet(),
)

/**
 * Zdroj živých poloh. Nový zdroj = jedna třída, která tohle implementuje, plus řádek ve [VehicleSources].
 * Zbytek aplikace (slučování, štítek "Zdroj:", varování, párování se spoji) funguje sám.
 */
interface VehicleSource {
    /** Stabilní identifikátor, ukládá se do [Vehicle.source]. */
    val id: String

    /** Název pro uživatele ("DPMÚL"). */
    val name: String

    /** Nižší číslo = důvěryhodnější. Při rozporu a při mazání "duchů" vyhrává zdroj s nižším číslem. */
    val priority: Int

    /** Co zdroj pokrývá. Vozy ve stejném segmentu z více zdrojů se slučují. */
    val segments: Set<Segment>

    val freshness: Freshness

    /** Jak přiřadit vozy ke spojům z jízdního řádu. */
    val tripMatcher: TripMatcher

    /** Má se linka vozu přepsat linkou ze spoje (u zdrojů, které dávají jen interní označení)? */
    val adoptsTimetableLine: Boolean get() = false

    /** true = hub počká na odpověď; false = dotaz běží na pozadí (pomalé API) a použije se poslední hotová odpověď. */
    val awaitResult: Boolean get() = true

    /** Nejkratší odstup mezi dotazy. */
    val minIntervalMs: Long get() = 0

    /** Počet poloh, které zdroj drží z minula, protože je server přestal dodávat. */
    val heldCount: Int get() = 0

    /** Zdroj hlásí výpadek (většina vozů přestala chodit). */
    val inOutage: Boolean get() = false

    /** Jeden dotaz na zdroj. Smí trvat dlouho a smí vyhodit výjimku. */
    suspend fun fetch(): List<Vehicle>
}

/** Stav jednoho zdroje v daném okamžiku. */
data class SourceReport(
    val id: String,
    val name: String,
    val priority: Int,
    val segments: Set<Segment>,
    val health: Health,
    /** Segmenty, u kterých jsou data zastaralá. */
    val staleSegments: Set<Segment> = emptySet(),
    val heldCount: Int = 0,
)
