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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit

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

enum class AppTheme(val label: String) {
    SYSTEM("Podle systému"),
    LIGHT("Světlý"),
    DARK("Tmavý")
}

enum class MapTheme(val label: String) {
    APP("Podle motivu aplikace"),
    LIGHT("Světlá"),
    DARK("Tmavá")
}

enum class MapProvider(val label: String) {
    MAPLIBRE("MapLibre (OpenStreetMap)"),
    GOOGLE("Google Maps")

}

enum class SettingsSubscreen {
    MAIN,
    DATA_UPDATES
}

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
    var modeFilter by mutableStateOf(ModeFilter.ALL)
    var mapModeFilter by mutableStateOf(ModeFilter.ALL)   // filtr vozidel na mapě (Vše / Autobusy / Vlaky)
    var searchLoading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var hasMore by mutableStateOf(true)
    var loadMoreFailed by mutableStateOf(false)
    var searchError by mutableStateOf<String?>(null)
    var searched by mutableStateOf(false)

    var search: JourneySearch? = null
    var searchJob: Job? = null

    var loadingEarlier by mutableStateOf(false)
    var hasEarlier by mutableStateOf(true)

    // Mapa
    var vehicles by mutableStateOf<List<Vehicle>>(emptyList())
    var vehiclesLoaded by mutableStateOf(false)
    var mapError by mutableStateOf<String?>(null)
    var selectedVehicle by mutableStateOf<Vehicle?>(null)
    var tripRoute by mutableStateOf<List<LatLng>>(emptyList())
    var tripRouteSegments by mutableStateOf<List<List<LatLng>>>(emptyList())
    var tripStops by mutableStateOf<List<LatLng>>(emptyList())
    var routeLoading by mutableStateOf(false)

    /** Spoj, na který má mapa přiblížit (jednorázově). */
    var focusTrip by mutableStateOf<TripRef?>(null)

    /** Spoj, jehož marker se má na chvíli zvýraznit. */
    var highlightedTrip by mutableStateOf<TripRef?>(null)
    var pulsePhase by mutableIntStateOf(0) // 0 = nic, 1 / 2 = střídání zvýraznění

    // Poslední pozice kamery (aby mapa po přepnutí tabu neskákala zpět)
    var mapCenter: LatLng = UstiCenter
    var mapZoom: Float = 11f

    var heldCount by mutableIntStateOf(0)

    /** Text "Zdroj: …" z VehicleHub. */
    var vehicleSource by mutableStateOf("")

    var userLoc by mutableStateOf<Pair<Double, Double>?>(null)
    /** Kdy (System.currentTimeMillis) byla [userLoc] naposledy zjištěna; 0 = neznámo. */
    var userLocTime: Long = 0L
    /** Druhy dopravy na zastávkách pro filtr ve výběru zastávky; null dokud se nespočítají. */
    var stopModes by mutableStateOf<StopModes?>(null)

    var autoFillDone = false      // předvyplnění proběhlo
    var autoFillAsked = false     // o oprávnění jsme už žádali

    var walkRoute by mutableStateOf<WalkRoute?>(null)

    var searchedFrom by mutableStateOf<Stop?>(null)

    var journeyWalks by mutableStateOf<List<WalkRoute>>(emptyList())

    var journeyWalk: WalkRoute?
        get() = journeyWalks.firstOrNull() ?: walkRoute
        set(value) {
            journeyWalks = if (value != null) listOf(value) else emptyList()
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

    var showOnlyMapFilters by mutableStateOf(
        settingsPrefs.getBoolean("show_only_map_filters", false)
    )

    fun updateShowOnlyMapFilters(showOnly: Boolean) {
        showOnlyMapFilters = showOnly
        settingsPrefs.edit().putBoolean("show_only_map_filters", showOnly).apply()
    }

    var enabledVehicleSources: Set<String>? by mutableStateOf(
        settingsPrefs.getStringSet("enabled_vehicle_sources", null)?.toSet()
    )
        private set

    fun updateEnabledVehicleSources(ids: Set<String>?) {
        enabledVehicleSources = ids

        settingsPrefs.edit {
            if (ids == null) {
                remove("enabled_vehicle_sources")
            } else {
                putStringSet("enabled_vehicle_sources", ids.toSet())
            }
        }
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
        runCatching { MapProvider.valueOf(settingsPrefs.getString("map_provider", "MAPLIBRE") ?: "MAPLIBRE") }.getOrDefault(MapProvider.MAPLIBRE)
    )

    fun updateMapProvider(provider: MapProvider) {
        mapProvider = provider
        settingsPrefs.edit { putString("map_provider", provider.name) }
    }

    var focusedTripVehicle by mutableStateOf<Vehicle?>(null)
    var focusedTripRef by mutableStateOf<TripRef?>(null)

    /** Najde v novém seznamu stejný vůz jako [cur]. Primárně podle source + id (vehicleNumber) nebo sameVehicleId. */
    private fun rematch(list: List<Vehicle>, cur: Vehicle): Vehicle? {
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
        if (!beginUpdate("Zahajuji aktualizaci…")) return

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
                updateSuccess = "Všechna data byla úspěšně stažena a aktualizována! Změny se projeví po restartu."
                updateStatusMessage = null
                onSuccess()
            }.onFailure {
                stopsLoading = false
                if (it is CancellationException) throw it
                updateError = "Aktualizace selhala: ${it.message}"
                updateStatusMessage = null
            }
            isUpdatingData = false
        }
    }


    fun performUpdateTimetable(api: TransitRepository, scope: CoroutineScope) {
        if (!beginUpdate("Aktualizuji jízdní řády…")) return

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
                updateSuccess = "Jízdní řády byly úspěšně aktualizovány! Změny se projeví po restartu."
                updateStatusMessage = null
            }.onFailure {
                if (it is CancellationException) throw it
                updateError = "Aktualizace JŘ selhala: ${it.message}"
                updateStatusMessage = null
            }
            isUpdatingData = false
        }
    }

    fun performUpdateShapes(api: TransitRepository, scope: CoroutineScope) {
        if (!beginUpdate("Aktualizuji trasy spojů (Shapes)…")) return

        scope.launch {
            runCatching {
                api.updateShapes(
                    log = { updateStatusMessage = it },
                    forceDownload = true
                )
            }.onSuccess {
                updateSuccess = "Trasy spojů (Shapes) byly úspěšně aktualizovány! Změny se projeví po restartu."
                updateStatusMessage = null
            }.onFailure {
                if (it is CancellationException) throw it
                updateError = "Aktualizace tras selhala: ${it.message}"
                updateStatusMessage = null
            }
            isUpdatingData = false
        }
    }

    fun performUpdateRealtime(api: TransitRepository, scope: CoroutineScope) {
        if (!beginUpdate("Obnovuji živá data vozidel…")) return

        scope.launch {
            runCatching {
                api.updateRealtime(log = { updateStatusMessage = it })
            }.onSuccess { vehiclesList ->
                updateVehicles(vehiclesList)
                updateSuccess = "Živá data obnovena (${vehiclesList.size} vozidel)!"
                updateStatusMessage = null
            }.onFailure {
                if (it is CancellationException) throw it
                updateError = "Obnovení živých dat selhalo: ${it.message}"
                updateStatusMessage = null
            }
            isUpdatingData = false
        }
    }

    fun performClearOsrmCache(api: TransitRepository, scope: CoroutineScope) {
        if (!beginUpdate("Čistím cache tras OSRM…")) return

        scope.launch {
            runCatching {
                api.clearOsrmCache(log = { updateStatusMessage = it })
            }.onSuccess { count ->
                updateSuccess = "Cache OSRM byla vyčištěna ($count souborů smazáno)."
                updateStatusMessage = null
            }.onFailure {
                if (it is CancellationException) throw it
                updateError = "Vyčištění cache selhalo: ${it.message}"
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
            newStops.distinctBy { it.name }.map { s ->
                val norm = normalize(s.name)
                val words = norm.split(Regex("\\s+")).filter { it.isNotBlank() }
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