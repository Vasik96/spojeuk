package com.vasik96.spojeuk

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit

/** [refined] = nejbližší po silnici; null když OSRM selhal (pak jen vzdušná čára v [cands]). */
class NearestResult(val cands: List<Pair<Stop, Double>>, val refined: Pair<Stop, Double>?)

internal val UstiCenter = LatLng(50.6606, 14.0323)

data class IndexedStop(
    val stop: Stop,
    val normalizedName: String,
    val normalizedWords: List<String>,
    val usageCount: Int
)

/** Odkaz na konkrétní spoj (každá instance je unikátní → opakovaný klik znovu spustí zvýraznění). */
class TripRef(
    val cis: String,
    val trip: String,
    val line: String,
    val boardLat: Double?,
    val boardLng: Double?,
    val destination: String = "",
    val isPast: Boolean = false,
    val departureTimeMillis: Long? = null,
    val tid: Int = 0            // spoj z JŘ (první jízda spojení); 0 = neznámý
)

enum class AppTheme(@androidx.annotation.StringRes private val labelRes: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark);

    val label: String get() = tr(labelRes)
}

enum class MapTheme(@androidx.annotation.StringRes private val labelRes: Int) {
    APP(R.string.map_theme_app),
    LIGHT(R.string.map_theme_light),
    DARK(R.string.map_theme_dark);

    val label: String get() = tr(labelRes)
}

enum class MapProvider(val label: String) {
    OPENSTREETMAP("OpenStreetMap"),
    MAPYCZ("Mapy.com"),
    GOOGLE("Google Maps")

}

enum class SettingsSubscreen {
    MAIN,
    DATA_UPDATES,
    ABOUT
}

/** Zoom (Google) mapy otevřené poprvé na poloze uživatele: okolí se štítky vozů. */
private const val USER_LOC_MAP_ZOOM = 14f
private const val AUTOFILL_SNAPSHOT_RADIUS_M = 150.0

class SpojeAppState(
    val context: Context,
    val usageManager: StopUsageManager = StopUsageManager(context),
    val history: TripHistory = TripHistory(context),
    val baseline: UsageBaseline = UsageBaseline(context)
) {
    var selectedTab by mutableIntStateOf(0)
    var stops by mutableStateOf<List<Stop>>(emptyList())
    var indexedStops by mutableStateOf<List<IndexedStop>>(emptyList())
    var stopsLoading by mutableStateOf(false)

    var from by mutableStateOf<Stop?>(null)
    var to by mutableStateOf<Stop?>(null)
    var fromQuery by mutableStateOf("")
    var toQuery by mutableStateOf("")

    val listState = LazyListState()
    val shownKeys = HashSet<String>()

    var fullRoute by mutableStateOf<List<LatLng>>(emptyList())

    var showSetup by mutableStateOf(false)
    var setupMessage by mutableStateOf("")
    var setupProgress by mutableFloatStateOf(0f)
    var setupDismissed by mutableStateOf(false)

    var journeys by mutableStateOf<List<Journey>>(emptyList())
    var departAt by mutableStateOf<java.time.LocalDateTime?>(null)   // zvolený čas odjezdu; null = teď
    /** [departAt] je čas příjezdu ("chci tam být do"), ne odjezdu. */
    var arriveBy by mutableStateOf(false)
    /** Zobrazené výsledky jsou podle příjezdu (nejpozdější vyhovující dole). */
    var searchedArriveBy by mutableStateOf(false)
    var searchLoading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var hasMore by mutableStateOf(true)
    var loadMoreFailed by mutableStateOf(false)
    var searchError by mutableStateOf<String?>(null)
    var searched by mutableStateOf(false)

    var search: JourneySearch? = null
    var searchJob: Job? = null

    /** Spojení otevřené v detailu (podobrazovka hledání); null = seznam výsledků. */
    var detailJourney by mutableStateOf<Journey?>(null)

    /** Obrazovka výsledků hledání (přes hlavní obrazovku s formulářem a historií). */
    var showResults by mutableStateOf(false)

    /** Zastávka "kam" posledního hledání (do hlavičky výsledků). */
    var searchedTo by mutableStateOf<Stop?>(null)

    /** Změna historie hledání (seznam na hlavní obrazovce se podle toho načte znovu). */
    var historyRev by mutableIntStateOf(0)

    /** Probíhá gesto zpět z jiné záložky na Spoje: záložka Spoje se pod ní ukáže jako náhled. */
    var backPreviewSearch by mutableStateOf(false)

    /** Na mapě neukazovat pěší trasu od uživatele k výchozí zastávce (zobrazená je až další jízda spojení). */
    var hideUserWalk by mutableStateOf(false)

    /** Bottom sheet jedné jízdy spojení: (spojení, index úseku v [Journey.rawLegs]). */
    var legSheet by mutableStateOf<Pair<Journey, Int>?>(null)

    var loadingEarlier by mutableStateOf(false)
    var hasEarlier by mutableStateOf(true)

    // Mapa
    var vehicles by mutableStateOf<List<Vehicle>>(emptyList())
    var vehiclesLoaded by mutableStateOf(false)
    var mapError by mutableStateOf<String?>(null)
    var selectedVehicle by mutableStateOf<Vehicle?>(null)

    /** Zastávka / nástupiště vybrané na mapě (panel s odjezdy). */
    var selectedStop by mutableStateOf<MapStop?>(null)

    /** Víc vozů pod jedním ťuknutím (štítky přes sebe, např. dva vlaky v jedné stanici): mapa nabídne výběr. */
    var vehicleChoices by mutableStateOf<List<Vehicle>>(emptyList())

    /** Čas (elapsedRealtime) posledního kliknutí na vozidlo; smyčky markerů po tu dobu nic nepočítají, ať sheet naběhne hned. */
    @Volatile var vehicleSelectedAtMs: Long = 0L

    fun selectVehicle(v: Vehicle) {
        // stejné vozidlo znovu (např. opakované ťuknutí) => nic
        if (selectedVehicle?.uid == v.uid) return
        vehicleSelectedAtMs = android.os.SystemClock.elapsedRealtime()
        android.util.Log.d("SheetLag", "selectVehicle: +${vehicleSelectedAtMs - TouchDiag.lastUpAt} ms od zvednutí prstu")
        selectedVehicle = v
        selectedStop = null
        stopOpenSeq++   // čekající otevření zastávky (openStop) už neplatí
    }

    /** Odjezdy spočítané před otevřením panelu (panel naběhne rovnou s nimi), null = panel si je načte sám. */
    var selectedStopDeps: List<StopDeparture>? = null
    private var stopOpenSeq = 0

    fun selectStop(s: MapStop, deps: List<StopDeparture>? = null) {
        stopOpenSeq++
        showStop(s, deps)
    }

    private fun showStop(s: MapStop, deps: List<StopDeparture>?) {
        selectedVehicle = null
        selectedStopDeps = deps
        selectedStop = s
    }

    /**
     * Ťuknutí na zastávku: odjezdy se spočítají ještě před otevřením panelu, ať naběhne hotový (dřív se otevřel
     * prázdný a odjezdy do něj naskočily až po chvíli). Trvá-li výpočet déle, panel se otevře a načte je sám.
     */
    fun openStop(api: TransitRepository, s: MapStop, scope: CoroutineScope) {
        val seq = ++stopOpenSeq
        val tab = selectedTab
        scope.launch {
            // výpočet běží zvlášť (async): withTimeoutOrNull nepřeruší výpočet bez bodů přerušení, ale await ano,
            // takže panel se po 1,2 s opravdu otevře a odjezdy si dočte sám
            val calc = scope.async(Dispatchers.Default) {
                runCatching { api.stopDepartures(s, STOP_DEPARTURE_HOURS) }.getOrNull()
            }
            val deps = kotlinx.coroutines.withTimeoutOrNull(1_200) { calc.await() }
            if (deps == null) calc.cancel()
            // mezitím uživatel vybral něco jiného (vůz, jinou zastávku, trasu, záložku, vypnul zastávky): nic nepřebíjet
            if (seq == stopOpenSeq && selectedTab == tab && showMapStops) showStop(s, deps)
        }
    }
    var tripRoute by mutableStateOf<List<LatLng>>(emptyList())
    /** Druh dopravy zobrazené trasy (barva čáry podle motivu mapy), null = barva motivu. */
    internal var routeStyle by mutableStateOf<VehicleStyle?>(null)

    /**
     * Ostatní jízdy zobrazeného spojení (tid, bez té, ke které se hledá vůz v [focusTrip]). Při "Skrýt ostatní
     * vozidla" zůstávají na mapě i jejich vozy, ale jen spárované přesně podle čísla spoje (odhad jen u aktuální jízdy).
     */
    internal var journeyOtherRideTids by mutableStateOf<Set<Int>>(emptySet())
    var tripRouteSegments by mutableStateOf<List<List<LatLng>>>(emptyList())
    var tripStops by mutableStateOf<List<LatLng>>(emptyList())

    /** Zastávky vyhledaného spojení (bez začátku a konce), null = trasa není ze spojení. */
    var journeyStops by mutableStateOf<List<LatLng>?>(null)

    /**
     * Mezilehlé zastávky, které se kreslí jako tečky na jejich přesné poloze (ne na trase). Začátek a konec trasy
     * tu nejsou, ty mají vlastní zelenou / červenou značku.
     */
    fun stopDots(showFullRoute: Boolean): List<LatLng> {
        if (!(showFullRoute && fullRoute.size >= 2)) journeyStops?.let { return it }
        return if (tripStops.size > 2) tripStops.subList(1, tripStops.size - 1) else emptyList()
    }
    var routeLoading by mutableStateOf(false)

    /** Místo, na které má mapa jednorázově přiblížit (zastávka otevřená z hledání), null = nic. */
    var focusPoint by mutableStateOf<LatLng?>(null)

    /** Spoj, na který má mapa přiblížit (jednorázově). */
    var focusTrip by mutableStateOf<TripRef?>(null)

    /** Spoj, jehož marker se má na chvíli zvýraznit. */
    var highlightedTrip by mutableStateOf<TripRef?>(null)
    var pulsePhase by mutableIntStateOf(0) // 0 = nic, 1 / 2 = střídání zvýraznění

    // Poslední pozice kamery (aby mapa po přepnutí tabu neskákala zpět). Než ji mapa poprvé uloží, otevře se
    // na poloze uživatele (pokud je známá), jinak na Ústí.
    private var savedMapCenter: LatLng? = null
    private var savedMapZoom: Float? = null

    var mapCenter: LatLng
        get() = savedMapCenter ?: userLoc?.let { LatLng(it.first, it.second) } ?: UstiCenter
        set(v) { savedMapCenter = v }
    var mapZoom: Float
        get() = savedMapZoom ?: if (savedMapCenter == null && userLoc != null) USER_LOC_MAP_ZOOM else 11f
        set(v) { savedMapZoom = v }

    var heldCount by mutableIntStateOf(0)

    /** Text "Zdroj: …" z VehicleHub. */
    var vehicleSource by mutableStateOf("")

    var userLoc by mutableStateOf<Pair<Double, Double>?>(null)
    /** Kdy (System.currentTimeMillis) byla [userLoc] naposledy zjištěna; 0 = neznámo. */
    var userLocTime: Long = 0L
    /** Druhy dopravy na zastávkách pro filtr ve výběru zastávky; null dokud se nespočítají. */
    var stopModes by mutableStateOf<StopModes?>(null)

    /** Předpočítaná nejbližší zastávka (kandidáti + pořadí po silnici), ať předvyplnění nečeká na OSRM. */
    var nearestKey: Pair<Double, Double>? = null
    var nearestKeyTime: Long = 0L
    var nearestJob: Deferred<NearestResult>? = null

    var autoFillDone = false      // předvyplnění proběhlo

    /**
     * Poslední předvyplnění "Odkud" / "Kam" k poloze (do [AUTOFILL_SNAPSHOT_RADIUS_M]): ukáže se hned po zjištění polohy,
     * bez čekání na načtení jízdního řádu, hledání nejbližší zastávky a OSRM (stejně jako uložená karta odjezdů).
     * Skutečné předvyplnění ho pak potvrdí nebo opraví.
     */
    class AutoFillPreview(val fromName: String, val toName: String?)
    private val autoFillPrefs = context.getSharedPreferences("autofill_snapshot", Context.MODE_PRIVATE)

    /** Co se zobrazilo jako náhled; podle toho se pozná, že je uživatel od té doby nezměnil. */
    var autoFillPreview: AutoFillPreview? = null

    fun loadAutoFillPreview(lat: Double, lng: Double): AutoFillPreview? {
        val p = autoFillPrefs
        if (!p.contains("uLat")) return null
        val d = Geo.distanceM(lat, lng, java.lang.Double.longBitsToDouble(p.getLong("uLat", 0)), java.lang.Double.longBitsToDouble(p.getLong("uLng", 0)))
        if (d > AUTOFILL_SNAPSHOT_RADIUS_M) return null
        val from = p.getString("from", null)?.takeIf { it.isNotBlank() } ?: return null
        return AutoFillPreview(from, p.getString("to", null)?.takeIf { it.isNotBlank() })
    }

    fun saveAutoFillPreview(lat: Double, lng: Double, fromName: String, toName: String?) {
        autoFillPrefs.edit {
            putLong("uLat", java.lang.Double.doubleToRawLongBits(lat))
            putLong("uLng", java.lang.Double.doubleToRawLongBits(lng))
            putString("from", fromName)
            if (toName != null) putString("to", toName) else remove("to")
        }
    }
    var autoFillAsked = false     // o oprávnění jsme už žádali

    var walkRoute by mutableStateOf<WalkRoute?>(null)

    var searchedFrom by mutableStateOf<Stop?>(null)

    var journeyWalks by mutableStateOf<List<WalkRoute>>(emptyList())

    var journeyWalk: WalkRoute?
        get() = journeyWalks.firstOrNull() ?: walkRoute?.takeUnless { hideUserWalk }
        set(value) {
            journeyWalks = if (value != null) listOf(value) else emptyList()
        }

    @Volatile
    private var routeJob: Job? = null

    val hasRoute: Boolean
        get() = tripRoute.isNotEmpty() || fullRoute.isNotEmpty() || tripRouteSegments.isNotEmpty() || (walkRoute != null && !hideUserWalk) || journeyWalks.isNotEmpty()

    fun clearRoute() {
        routeJob?.cancel()
        routeJob = null
        routeStyle = null
        journeyOtherRideTids = emptySet()
        tripRoute = emptyList()
        tripRouteSegments = emptyList()
        fullRoute = emptyList()
        tripStops = emptyList()
        journeyStops = null
        focusedTripVehicle = null
        focusedTripRef = null
        highlightedTrip = null
        focusTrip = null
        walkRoute = null
        journeyWalks = emptyList()
        routeLoading = false
    }

    fun loadJourneyRoute(api: TransitRepository, journey: Journey, scope: CoroutineScope, ref: TripRef) {
        routeJob?.cancel()
        selectedVehicle = null
        focusedTripVehicle = null
        focusedTripRef = ref
        tripRoute = emptyList()
        tripRouteSegments = emptyList()
        fullRoute = emptyList()
        tripStops = emptyList()
        journeyStops = null
        journeyWalks = emptyList()
        routeLoading = true
        vehiclesLoaded = vehicles.isNotEmpty()
        focusTrip = ref
        highlightedTrip = ref
        selectedTab = 1

        journeyOtherRideTids = journey.rawLegs.filter { it.kind == "ride" && it.tid != 0 && it.tid != ref.tid }.mapTo(HashSet()) { it.tid }

        // barva trasy podle první jízdy spojení (autobus, vlak, tramvaj, metro)
        routeStyle = journey.rawLegs.firstOrNull { it.kind == "ride" }?.let { api.tripStyleOf(it.tid) }

        val job = scope.launch(Dispatchers.Default) {
            try {
                val segments = api.journeySegments(journey)
                val points = api.journeyGeometry(journey)
                val full = api.firstLegFullRoute(journey)
                val stops = api.firstLegStopLocations(journey)
                val jStops = api.journeyStopLocations(journey)
                val walks = api.journeyWalks(journey)
                val segLatLng = segments.map { seg -> seg.map { LatLng(it.first, it.second) } }
                val ptsLatLng = points.map { LatLng(it.first, it.second) }
                val fullLatLng = full.map { LatLng(it.first, it.second) }
                val stopsLatLng = stops.map { LatLng(it.first, it.second) }
                val jStopsLatLng = jStops.map { LatLng(it.first, it.second) }
                if (!isActive) return@launch
                withContext(Dispatchers.Main) {
                    if (!isActive) return@withContext
                    tripRouteSegments = segLatLng
                    tripRoute = ptsLatLng
                    fullRoute = fullLatLng
                    tripStops = stopsLatLng
                    journeyStops = jStopsLatLng
                    journeyWalks = walks
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("SpojeAppState", "Chyba při načítání trasy spojení: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    routeLoading = false
                }
            }
        }
        routeJob = job
    }

    /** Odjezd ze zastávky na mapě: celá trasa spoje, nalezení vozu (přiblížení / zvýraznění) jako u spojení. */
    fun loadStopDepartureRoute(api: TransitRepository, d: StopDeparture, stop: MapStop, scope: CoroutineScope) {
        routeJob?.cancel()
        stopOpenSeq++
        journeyOtherRideTids = emptySet()
        // panel zastávky zavírá volající s animací (selectedStop = null až po ní)
        selectedVehicle = null
        focusedTripVehicle = null
        tripRoute = emptyList()
        tripRouteSegments = emptyList()
        fullRoute = emptyList()
        tripStops = emptyList()
        journeyStops = null
        journeyWalks = emptyList()
        walkRoute = null
        routeLoading = true
        val job = scope.launch {
            try {
                val ref = api.stopDepartureRef(d, stop)
                focusedTripRef = ref
                focusTrip = ref
                highlightedTrip = ref
                val (points, stops) = api.tripRouteById(d.tid)
                if (!isActive) return@launch
                routeStyle = tripStyle(d.category, d.line, d.agency)
                tripRoute = points.map { LatLng(it.first, it.second) }
                fullRoute = emptyList()
                tripStops = stops.map { LatLng(it.first, it.second) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("SpojeAppState", "Chyba při načítání trasy odjezdu: ${e.message}")
            } finally {
                routeLoading = false
            }
        }
        routeJob = job
    }

    fun loadVehicleRoute(api: TransitRepository, vehicle: Vehicle, scope: CoroutineScope, ctx: Context, onDismiss: () -> Unit) {
        routeJob?.cancel()
        routeLoading = true
        journeyOtherRideTids = emptySet()
        val job = scope.launch {
            try {
                walkRoute = null
                val points = api.tripGeometry(vehicle)
                val stops = api.tripStopLocations(vehicle)
                if (!isActive) return@launch
                if (points.isNotEmpty()) {
                    tripRouteSegments = emptyList()
                    routeStyle = vehicleStyle(vehicle)
                    tripRoute = points.map { LatLng(it.first, it.second) }
                    tripStops = stops.map { LatLng(it.first, it.second) }
                    journeyStops = null
                    onDismiss()
                } else {
                    android.widget.Toast.makeText(ctx, tr(R.string.route_not_found), android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("SpojeAppState", "Chyba při načítání trasy spoje: ${e.message}")
            } finally {
                routeLoading = false
            }
        }
        routeJob = job
    }

    // Settings & Preferences
    private val settingsPrefs = context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)

    var settingsSubscreen by mutableStateOf(SettingsSubscreen.MAIN)

    var hideOtherVehiclesOnMap by mutableStateOf(
        settingsPrefs.getBoolean("hide_other_vehicles_on_map", false)
    )

    var predictVehiclePositions by mutableStateOf(
        settingsPrefs.getBoolean("predict_vehicle_positions", false)
    )

    /** Druhy dopravy zobrazené na mapě (filtr v banneru mapy); pamatuje se. */
    internal var mapCategories by mutableStateOf(
        settingsPrefs.getStringSet("map_categories", null)
            ?.mapNotNull { n -> MapCategory.entries.firstOrNull { it.name == n } }?.toSet()
            ?: MapCategory.entries.toSet()
    )
        private set

    internal fun updateMapCategories(set: Set<MapCategory>) {
        mapCategories = set
        settingsPrefs.edit { putStringSet("map_categories", set.map { it.name }.toSet()) }
    }

    /** Druhy dopravy pro hledání spojení (stejný dialog jako filtr mapy); pamatuje se. */
    internal var searchCategories by mutableStateOf(
        settingsPrefs.getStringSet("search_categories", null)
            ?.mapNotNull { n -> MapCategory.entries.firstOrNull { it.name == n } }?.toSet()
            ?.takeIf { it.isNotEmpty() }   // prázdný výběr hledání nedává smysl
            ?: MapCategory.entries.toSet()
    )
        private set

    internal fun updateSearchCategories(set: Set<MapCategory>) {
        searchCategories = set
        settingsPrefs.edit { putStringSet("search_categories", set.map { it.name }.toSet()) }
    }

    val modeFilter: ModeFilter get() = ModeFilter(searchCategories)

    var showOnlyMapFilters by mutableStateOf(
        settingsPrefs.getBoolean("show_only_map_filters", false)
    )

    /** Ikony zastávek na mapě (ťuknutím odjezdy); jde vypnout v nastavení. */
    var showMapStops by mutableStateOf(settingsPrefs.getBoolean("show_map_stops", true))

    fun updateShowMapStops(show: Boolean) {
        showMapStops = show
        settingsPrefs.edit().putBoolean("show_map_stops", show).apply()
        if (!show) { selectedStop = null; stopOpenSeq++ }
    }

    /** Po spuštění upozornit na novou verzi aplikace (GitHub release); jde vypnout v nastavení. */
    var notifyAppUpdates by mutableStateOf(settingsPrefs.getBoolean("notify_app_updates", true))
        private set

    fun updateNotifyAppUpdates(on: Boolean) {
        notifyAppUpdates = on
        settingsPrefs.edit().putBoolean("notify_app_updates", on).apply()
        if (!on) appUpdateOffer = null
    }

    /** Novější verze nalezená po spuštění: zobrazí se dialog (null = nic). */
    internal var appUpdateOffer by mutableStateOf<AppRelease?>(null)

    fun updateShowOnlyMapFilters(showOnly: Boolean) {
        showOnlyMapFilters = showOnly
        settingsPrefs.edit().putBoolean("show_only_map_filters", showOnly).apply()
    }

    /**
     * Vypnuté zdroje poloh. Ukládají se vypnuté, ne zapnuté: zdroj, který server nově přidá, je tak rovnou zapnutý.
     * Dřívější uložení zapnutých ("enabled_vehicle_sources", jen pět tehdejších zdrojů) se jednou převede.
     */
    var disabledVehicleSources: Set<String> by mutableStateOf(loadDisabledSources())
        private set

    private fun loadDisabledSources(): Set<String> {
        settingsPrefs.getStringSet("disabled_vehicle_sources", null)?.let { return it.toSet() }
        val oldEnabled = settingsPrefs.getStringSet("enabled_vehicle_sources", null)?.toSet() ?: return emptySet()
        val disabled = setOf("dpmul", "duk", "dpmd", "train", "pid") - oldEnabled
        settingsPrefs.edit {
            remove("enabled_vehicle_sources")
            putStringSet("disabled_vehicle_sources", disabled)
        }
        return disabled
    }

    fun updateDisabledVehicleSources(ids: Set<String>) {
        disabledVehicleSources = ids.toSet()
        settingsPrefs.edit { putStringSet("disabled_vehicle_sources", ids.toSet()) }
    }


    var appTheme by mutableStateOf(
        runCatching { AppTheme.valueOf(settingsPrefs.getString("app_theme", "SYSTEM") ?: "SYSTEM") }.getOrDefault(AppTheme.SYSTEM)
    )

    fun updateAppTheme(theme: AppTheme) {
        appTheme = theme
        settingsPrefs.edit { putString("app_theme", theme.name) }
    }

    var mapTheme by mutableStateOf(
        runCatching { MapTheme.valueOf(settingsPrefs.getString("map_theme", "APP") ?: "APP") }
            .getOrDefault(MapTheme.APP)
    )

    fun updateMapTheme(theme: MapTheme) {
        mapTheme = theme
        settingsPrefs.edit { putString("map_theme", theme.name) }
    }

    var mapProvider by mutableStateOf(
        // "MAPLIBRE" je stará uložená hodnota (před přejmenováním na OpenStreetMap)
        runCatching {
            when (val saved = settingsPrefs.getString("map_provider", null)) {
                null, "MAPLIBRE" -> MapProvider.OPENSTREETMAP
                else -> MapProvider.valueOf(saved)
            }
        }.getOrDefault(MapProvider.OPENSTREETMAP)
    )

    fun updateMapProvider(provider: MapProvider) {
        mapProvider = provider
        settingsPrefs.edit { putString("map_provider", provider.name) }
    }

    private var focusedTripVehicleState by mutableStateOf<Vehicle?>(null)

    /**
     * Vůz, který právě sledujeme na trase spoje. Barva trasy z jízdního řádu ([tripStyle]) nemusí znát druh vozu
     * (trolejbus, vánoční vůz), takže jakmile se vůz s druhem ze serveru najde, přebije barvu trasy jeho stylem.
     */
    var focusedTripVehicle: Vehicle?
        get() = focusedTripVehicleState
        set(value) {
            focusedTripVehicleState = value
            if (value != null && (value.christmas || value.mode.isNotEmpty()) && routeStyle != null) routeStyle = vehicleStyle(value)
        }
    var focusedTripRef by mutableStateOf<TripRef?>(null)

    /** Najde v novém seznamu stejný vůz jako [cur]. Primárně podle uid ze serveru, pak source + id nebo sameVehicleId. */
    private fun rematch(list: List<Vehicle>, cur: Vehicle): Vehicle? {
        list.firstOrNull { it.serverUid.isNotEmpty() && it.uid == cur.uid }?.let { return it }
        if (cur.id != 0) {
            return list.firstOrNull { it.source == cur.source && it.id == cur.id }
                ?: list.firstOrNull { it.id != 0 && sameVehicleId(it.id, cur.id) }
        }
        // jen záloha pro zdroje bez id: nejbližší vůz stejné linky a zdroje do 300 m
        return list
            .filter { it.id == 0 && it.source == cur.source && it.line == cur.line }
            .minByOrNull { Geo.distanceM(it.lat, it.lng, cur.lat, cur.lng) }
            ?.takeIf { Geo.distanceM(it.lat, it.lng, cur.lat, cur.lng) < 300.0 }
    }

    fun updateVehicles(newVehicles: List<Vehicle>) {
        vehicles = newVehicles
        val ref = focusedTripRef
        val curFocused = focusedTripVehicle
        if (curFocused != null) {
            // už sledujeme konkrétní vůz: jen ho aktualizujeme, nikdy nevybíráme jiný
            rematch(newVehicles, curFocused)?.let { focusedTripVehicle = it }
        } else if (ref != null && tripRoute.isNotEmpty()) {
            findVehicle(newVehicles, ref, fullRoute.ifEmpty { tripRoute })?.vehicle
                ?.let { focusedTripVehicle = it }
        }

        selectedVehicle?.let { cur ->
            rematch(newVehicles, cur)?.let { selectedVehicle = it }
        }
    }


    fun setHideOtherVehicles(hide: Boolean) {
        hideOtherVehiclesOnMap = hide
        settingsPrefs.edit().putBoolean("hide_other_vehicles_on_map", hide).apply()
    }

    fun updatePredictVehiclePositions(predict: Boolean) {
        predictVehiclePositions = predict
        settingsPrefs.edit().putBoolean("predict_vehicle_positions", predict).apply()
    }

    // ───────── aktualizace dat (obrazovka Nastavení) ─────────

    var isUpdatingData by mutableStateOf(false)
    var updateStatusMessage by mutableStateOf<String?>(null)
    var updateProgress by mutableFloatStateOf(0f)
    var updateError by mutableStateOf<String?>(null)
    var updateSuccess by mutableStateOf<String?>(null)

    private fun beginUpdate(status: String): Boolean {
        if (isUpdatingData) return false
        isUpdatingData = true
        updateError = null
        updateSuccess = null
        updateStatusMessage = status
        updateProgress = 0f
        return true
    }

    fun performUpdateAll(
        api: TransitRepository,
        scope: CoroutineScope,
        onSuccess: () -> Unit = {},
    ) {
        if (!beginUpdate(tr(R.string.upd_starting))) return

        scope.launch {
            runCatching {
                api.updateAllData(
                    log = { updateStatusMessage = it },
                    progress = { updateProgress = it },
                    forceShapes = false,   // při automatice stahovat shapes jen když se změnily
                )
                stopsLoading = true
                val loaded = api.loadStops(
                    log = { updateStatusMessage = it },
                    progress = { updateProgress = it }
                )
                updateIndexedStops(loaded)
                stopsLoading = false
            }.onSuccess {
                updateSuccess = tr(R.string.upd_all_ok)
                updateStatusMessage = null
                onSuccess()
            }.onFailure {
                stopsLoading = false
                if (it is CancellationException) throw it
                updateError = tr(R.string.upd_failed, it.message.orEmpty())
                updateStatusMessage = null
            }
            isUpdatingData = false
        }
    }


    fun performUpdateTimetable(api: TransitRepository, scope: CoroutineScope) {
        if (!beginUpdate(tr(R.string.upd_tt_running))) return

        scope.launch {
            runCatching {
                api.updateTimetable(
                    log = { updateStatusMessage = it },
                    progress = { updateProgress = it }
                )
                stopsLoading = true
                val loaded = api.loadStops(
                    log = { updateStatusMessage = it },
                    progress = { updateProgress = it }
                )
                updateIndexedStops(loaded)
                stopsLoading = false
            }.onSuccess {
                updateSuccess = tr(R.string.upd_tt_ok)
                updateStatusMessage = null
            }.onFailure {
                if (it is CancellationException) throw it
                updateError = tr(R.string.upd_tt_failed, it.message.orEmpty())
                updateStatusMessage = null
            }
            isUpdatingData = false
        }
    }

    fun performUpdateShapes(api: TransitRepository, scope: CoroutineScope) {
        if (!beginUpdate(tr(R.string.upd_shapes_running))) return

        scope.launch {
            runCatching {
                api.updateShapes(
                    log = { updateStatusMessage = it },
                    forceDownload = true
                )
            }.onSuccess {
                updateSuccess = tr(R.string.upd_shapes_ok)
                updateStatusMessage = null
            }.onFailure {
                if (it is CancellationException) throw it
                updateError = tr(R.string.upd_shapes_failed, it.message.orEmpty())
                updateStatusMessage = null
            }
            isUpdatingData = false
        }
    }

    fun performUpdateRealtime(api: TransitRepository, scope: CoroutineScope) {
        if (!beginUpdate(tr(R.string.upd_rt_running))) return

        scope.launch {
            runCatching {
                api.updateRealtime(log = { updateStatusMessage = it })
            }.onSuccess { vehiclesList ->
                updateVehicles(vehiclesList)
                updateSuccess = trPlural(R.plurals.upd_rt_ok, vehiclesList.size, vehiclesList.size)
                updateStatusMessage = null
            }.onFailure {
                if (it is CancellationException) throw it
                updateError = tr(R.string.upd_rt_failed, it.message.orEmpty())
                updateStatusMessage = null
            }
            isUpdatingData = false
        }
    }

    fun performClearOsrmCache(api: TransitRepository, scope: CoroutineScope) {
        if (!beginUpdate(tr(R.string.upd_osrm_running))) return

        scope.launch {
            runCatching {
                api.clearOsrmCache(log = { updateStatusMessage = it })
            }.onSuccess { count ->
                updateSuccess = trPlural(R.plurals.upd_osrm_ok, count, count)
                updateStatusMessage = null
            }.onFailure {
                if (it is CancellationException) throw it
                updateError = tr(R.string.upd_osrm_failed, it.message.orEmpty())
                updateStatusMessage = null
            }
            isUpdatingData = false
        }
    }

    // ───────── používané zastávky ─────────

    private fun effectiveUsage(name: String): Int =
        (usageManager.getUsageCount(name) - baseline.get(name)).coerceAtLeast(0)

    suspend fun forgetStop(name: String) {
        baseline.set(name, usageManager.getUsageCount(name))
        history.forget(name)
        refreshUsage()
    }

    suspend fun updateIndexedStops(newStops: List<Stop>) {
        val idx = withContext(Dispatchers.Default) {
            val ws = Regex("\\s+")   // jednou, ne pro každou zastávku
            newStops.distinctBy { it.name }.map { s ->
                val norm = normalize(s.name)
                val words = norm.split(ws).filter { it.isNotBlank() }
                IndexedStop(s, norm, words, effectiveUsage(s.name))
            }
        }
        stops = newStops
        indexedStops = idx
    }

    suspend fun refreshUsage() {
        val idx = withContext(Dispatchers.Default) {
            indexedStops.map { it.copy(usageCount = effectiveUsage(it.stop.name)) }
        }
        indexedStops = idx
    }
}