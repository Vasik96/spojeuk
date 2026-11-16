package com.vasik96.spojeuk

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.withFrameNanos
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos

/**
 * Markery vozidel pro Google Maps přímo přes nativní [GoogleMap], mimo Compose.
 *
 * - Pozice se zapisuje rovnou do `Marker.position` a jen když se marker na obrazovce posune aspoň o pixel.
 * - Ikony se připravují mimo hlavní vlákno ([sync]).
 * - Přidávání markerů i zapisování pozic má časový rozpočet na snímek, takže se nikdy neudělá všechno naráz
 *   (otevření mapy, návrat na záložku, velká dávka nových vozidel) a animace UI nepřeskakují.
 *
 * Klik na marker řeší vlastní tap v MapScreen (googleTapModifier), takže marker sám nemusí nic dělat.
 */
internal class GoogleVehicleMarkers {
    private class Slot(
        val key: String,
        val marker: Marker,
        var iconKey: String,
        var alpha: Float,
        var z: Float,
        var pos: LatLng,
    )

    private var map: GoogleMap? = null
    private val slots = HashMap<String, Slot>()

    // pole pro rychlé procházení po snímcích; přestaví se po každém přidání / odebrání
    private var snapshot: Array<Slot> = emptyArray()
    private var snapshotDirty = true
    private var cursor = 0

    fun attach(m: GoogleMap) {
        if (map === m) return
        slots.clear()
        snapshotDirty = true
        map = m
        // Klik na marker by jinak posunul kameru k markeru (výchozí chování Google Maps). Výběr vozidla řeší vlastní tap,
        // trasové markery (začátek, konec, zastávky, šipky) už dřív vracely onClick = true, takže pro ně se nic nemění.
        m.setOnMarkerClickListener { true }
    }

    /** Mapa zanikla (přepnutí poskytovatele / odchod z obrazovky); markery zanikly s ní. */
    fun detach() {
        slots.clear()
        snapshotDirty = true
        map = null
    }

    /**
     * Sladí markery se seznamem vozidel: ikony se nejdřív připraví na pozadí, pak se na hlavním vlákně přidává /
     * upravuje po dávkách a mezi dávkami se čeká na další snímek. Lze kdykoli zrušit (další volání naváže).
     */
    suspend fun sync(
        ctx: Context,
        hub: MotionHub,
        vehicles: List<Pair<String, Vehicle>>,
        targetUid: String?,
        phase: Int,
    ) {
        val m = map ?: return

        // 1) bitmapy a BitmapDescriptory mimo hlavní vlákno (cache v MapMarkers je thread-safe)
        withContext(Dispatchers.Default) {
            val seen = HashSet<String>()
            for ((_, v) in vehicles) {
                val iconPhase = if (targetUid != null && v.uid == targetUid) phase else 0
                if (seen.add("${v.line}:${v.train}:${v.delay > 3}:$iconPhase")) {
                    // když SDK ještě není inicializované, vyrobí se ikona až na hlavním vlákně
                    runCatching { markerDescriptor(ctx, v.line, v.train, v.delay, iconPhase) }
                }
            }
        }
        if (map !== m) return

        // 2) zmizelá vozidla pryč (levné)
        val alive = HashSet<String>(vehicles.size * 2)
        for ((k, _) in vehicles) alive += k
        val it = slots.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in alive) { e.value.marker.remove(); it.remove(); snapshotDirty = true }
        }

        // 3) nová a změněná po dávkách
        var batchStart = SystemClock.uptimeMillis()
        for ((k, v) in vehicles) {
            val iconPhase = if (targetUid != null && v.uid == targetUid) phase else 0
            val alpha = if (v.ageSec() >= 60) 0.65f else 1.0f
            val z = if (iconPhase != 0) 3f else 2f
            val iconKey = "${v.line}:${v.train}:${v.delay > 3}:$iconPhase"
            val slot = slots[k]
            if (slot == null) {
                val pos = hub.positionOf(k) ?: LatLng(v.lat, v.lng)
                val marker = m.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .icon(markerDescriptor(ctx, v.line, v.train, v.delay, iconPhase))
                        .anchor(0.5f, 0.5f)
                        .alpha(alpha)
                        .zIndex(z)
                ) ?: continue
                slots[k] = Slot(k, marker, iconKey, alpha, z, pos)
                snapshotDirty = true
            } else {
                if (slot.iconKey != iconKey) {
                    slot.marker.setIcon(markerDescriptor(ctx, v.line, v.train, v.delay, iconPhase))
                    slot.iconKey = iconKey
                }
                if (slot.alpha != alpha) { slot.marker.alpha = alpha; slot.alpha = alpha }
                if (slot.z != z) { slot.marker.zIndex = z; slot.z = z }
            }

            if (SystemClock.uptimeMillis() - batchStart >= SYNC_BUDGET_MS) {
                withFrameNanos { }           // zbytek v dalším snímku, UI mezitím dýchá
                if (map !== m) return
                batchStart = SystemClock.uptimeMillis()
            }
        }
    }

    /**
     * Každý snímek: zapíše pozice z [hub] do markerů. Přeskočí vozidla mimo [view] a posuny menší než ~1 px
     * ([metersPerPx]). Práce za snímek je časově omezená; co se nestihne, dokončí se v dalších snímcích
     * (po návratu na záložku se tak nepřesune stovka markerů v jednom snímku).
     */
    fun applyPositions(hub: MotionHub, view: ViewBox?, metersPerPx: Double) {
        if (snapshotDirty) {
            snapshot = slots.values.toTypedArray()
            snapshotDirty = false
            cursor = 0
        }
        val n = snapshot.size
        if (n == 0) return
        val t0 = System.nanoTime()
        var i = 0
        var c = cursor % n
        while (i < n) {
            val s = snapshot[c]
            val p = hub.positionOf(s.key)
            if (p != null && p != s.pos && (view == null || view.contains(p.latitude, p.longitude))) {
                val dLat = (p.latitude - s.pos.latitude) * 111_320.0
                val dLng = (p.longitude - s.pos.longitude) * 111_320.0 * cos(Math.toRadians(p.latitude))
                if (abs(dLat) + abs(dLng) >= metersPerPx) {
                    s.pos = p
                    s.marker.position = p
                }
            }
            c = (c + 1) % n
            i++
            if ((i and 7) == 0 && System.nanoTime() - t0 > APPLY_BUDGET_NS) break
        }
        cursor = c
    }

    private companion object {
        const val SYNC_BUDGET_MS = 4L
        const val APPLY_BUDGET_NS = 5_000_000L
    }
}