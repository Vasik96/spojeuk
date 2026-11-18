package com.vasik96.spojeuk

/** Od kolika "držených" poloh se feed DÚK považuje za výpadek (stejná hranice má server). */
const val OUTAGE_MIN = 18

enum class Health {
    /** Zdroj je aktuální. */
    LIVE,

    /** Zdroj už něco dodal, ale data jsou stará. */
    STALE,

    /** Zdroj ještě nikdy neodpověděl. */
    DOWN,
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
    val vehicleCount: Int = 0,
)

/** Skládá text "Zdroj: …" ze stavu zdrojů. */
object SourceLabel {
    /** Texty v jazyce appky; JVM testy (bez Android resources) si je nastaví samy. */
    internal var noneText: () -> String = { tr(R.string.source_none) }
    internal var staleSuffixText: () -> String = { tr(R.string.source_stale_suffix) }

    val NONE: String get() = noneText()

    fun build(reports: List<SourceReport>): String {
        val ordered = reports.sortedBy { it.priority }
        val active = ordered.filter { it.vehicleCount > 0 }
        if (active.isEmpty()) return NONE

        val live = active.filter { it.health == Health.LIVE }
        return active.joinToString(", ") { r ->
            val isStale = when (r.health) {
                Health.DOWN -> true
                Health.STALE -> true
                Health.LIVE -> {
                    // zastaralý segment se hlásí jen tehdy, když ho čerstvě nepokrývá jiný živý zdroj
                    val uncovered = r.staleSegments.filter { seg ->
                        live.none { o -> (o !== r) && (seg in o.segments) && (seg !in o.staleSegments) }
                    }
                    uncovered.isNotEmpty()
                }
            }
            r.name + staleNote(isStale)
        }
    }

    private fun staleNote(isStale: Boolean): String = if (isStale) staleSuffixText() else ""
}
