package com.vasik96.spojeuk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Hledání spojení a stránkování výsledků (nové hledání, pull-to-refresh, další a dřívější spoje).
 * Stav je v [SpojeAppState]; scope dodává obrazovka hledání (zrušením obrazovky se zruší i rozběhnuté hledání).
 */

private const val FIRST_PAGE = 8
private const val MORE_PAGE = 6

/** Nové hledání z polí Odkud / Kam (zapíše použití zastávek a historii). */
internal fun SpojeAppState.startSearch(api: TransitRepository, scope: CoroutineScope, record: Boolean = true) {
    val a = from ?: return
    val b = to ?: return
    searchJob?.cancel()
    restoredSearchPending = false

    // obnovené hledání (po ukončení procesu) se do používání ani historie znovu nepočítá
    if (record) {
        usageManager.recordUsage(a.name)
        usageManager.recordUsage(b.name)
        history.record(a.name, b.name)
        scope.launch { refreshUsage() }
    }

    val s = api.newSearch(a.name, b.name, modeFilter = modeFilter, departAt = departAt,
        arriveBy = arriveBy && departAt != null)
    search = s
    journeys = emptyList()
    searched = true
    searchLoading = true
    loadingMore = false
    refreshing = false
    loadMoreFailed = false
    hasMore = true
    hasEarlier = true
    loadingEarlier = false
    searchError = null
    searchedFrom = a
    searchedTo = b
    searchedArriveBy = arriveBy && departAt != null
    historyRev++
    detailJourney = null
    showResults = true

    shownKeys.clear()
    listState.requestScrollToItem(0)

    searchJob = scope.launch {
        try {
            val r = withContext(Dispatchers.Default) { s.loadMore(FIRST_PAGE) }
            if (search === s) {
                journeys = r
                hasMore = s.hasMore
                // podle příjezdu: nejlepší (nejpozdější odjezd, který ještě stihne) je poslední => ukázat ho
                if (searchedArriveBy && r.isNotEmpty()) listState.requestScrollToItem(r.lastIndex)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (search === s) searchError = tr(R.string.search_failed, e.message.orEmpty())
        } finally {
            withContext(NonCancellable) {
                if (search === s) searchLoading = false
            }
        }
    }
}

// ───── pull-to-refresh (nahoře) ─────
/** Pull-to-refresh: stejné hledání znovu od teď. */
internal fun SpojeAppState.refreshSearch(scope: CoroutineScope) {
    val old = search ?: return
    searchJob?.cancel()
    val s = old.fresh()
    search = s
    refreshing = true
    loadingMore = false
    loadMoreFailed = false
    hasEarlier = true
    loadingEarlier = false
    searchError = null

    shownKeys.clear()

    searchJob = scope.launch {
        // Refresh poběží minimálně 700 ms.
        val refreshStartedAt = System.currentTimeMillis()
        var wasCancelled = false

        try {
            val r = withContext(Dispatchers.Default) {
                s.loadMore(FIRST_PAGE)
            }

            if (search === s) {
                journeys = r
                hasMore = s.hasMore
            }
        } catch (e: CancellationException) {
            wasCancelled = true
            throw e
        } catch (e: Exception) {
            if (search === s) {
                searchError = tr(R.string.search_refresh_failed, e.message.orEmpty())
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    val elapsed = System.currentTimeMillis() - refreshStartedAt
                    val remaining = 700L - elapsed
                    if (remaining > 0 && !wasCancelled) {
                        delay(remaining)
                    }
                } catch (_: CancellationException) {
                }
                if (search === s) {
                    refreshing = false
                }
            }
        }
    }
}

// ───── doplnění dalších spojů (dole) ─────
/** Další (pozdější) spoje na konec seznamu. */
internal fun SpojeAppState.loadMoreJourneys(scope: CoroutineScope) {
    val s = search ?: return
    if (loadingMore || searchLoading || refreshing ||
        loadMoreFailed || !s.hasMore
    ) return
    loadingMore = true

    searchJob = scope.launch {
        try {
            val r = withContext(Dispatchers.Default) { s.loadMore(MORE_PAGE) }
            if (search === s) {
                journeys = r
                hasMore = s.hasMore
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (search === s) {
                searchError = tr(R.string.search_more_failed, e.message.orEmpty())
                loadMoreFailed = true
            }
        } finally {
            withContext(NonCancellable) {
                if (search === s) loadingMore = false
            }
        }
    }
}

/** Dřívější spoje na začátek seznamu; viditelný spoj zůstane na místě. */
internal fun SpojeAppState.loadEarlierJourneys(scope: CoroutineScope) {
    val s = search ?: return

    if (loadingEarlier || loadingMore ||
        searchLoading || refreshing
    ) return

    // Zapamatuj si spoj, který je právě vidět, a jeho pozici.
    val visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull()
    val anchorKey = visibleItem?.key
    val anchorOffset = listState.firstVisibleItemScrollOffset

    loadingEarlier = true

    scope.launch {
        try {
            val r = withContext(Dispatchers.Default) {
                s.loadEarlier()
            }

            if (search === s) {
                val newIndex = r.indexOfFirst {
                    it.key == anchorKey
                }

                // Nastav pozici ještě před změnou seznamu.
                if (newIndex >= 0) {
                    listState.requestScrollToItem(
                        index = newIndex,
                        scrollOffset = anchorOffset
                    )
                }

                journeys = r
                hasEarlier = s.hasEarlier
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (search === s) {
                searchError =
                    tr(R.string.search_earlier_failed, e.message.orEmpty())
            }
        } finally {
            withContext(NonCancellable) {
                if (search === s) {
                    loadingEarlier = false
                }
            }
        }
    }
}
