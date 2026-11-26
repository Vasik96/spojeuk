@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, com.google.maps.android.compose.MapsComposeExperimentalApi::class)

package com.vasik96.spojeuk

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.pow
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

// popis vozidla pro vykreslení: ikona + řazení, přepočítá se jen při změně vozidel / zvýraznění
internal class VehicleDraw(
    val key: String,
    val v: Vehicle,
    val iconPhase: Int
) {
    val style = vehicleStyle(v)
    val late = lateMinutes(v)

    // stejný klíč jako cache bitmap: obrázek závisí jen na tom, JESTLI je vůz zpožděný (okraj), ne o kolik minut.
    // Dřív byly minuty v ID => každá změna zpoždění = nový (stejný) obrázek ve stylu, počet rostl bez omezení.
    val imgId = "veh|" + labelKey(v.line, style, iconPhase, late, v.christmas)

    // předpočítané části GeoJSONu (draws se staví jen při změně vozidel, ne při každém pushi poloh)
    val layerIdx = zLayer(style, key)
    val imgJson = jsonQuote(imgId)

    val colorKey = style.name
}


private fun jsonQuote(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) when {
        c == '"' -> sb.append("\\\"")
        c == '\\' -> sb.append("\\\\")
        c < ' ' -> sb.append(' ')
        else -> sb.append(c)
    }
    sb.append('"')
    return sb.toString()
}

internal class VehicleJson(val json: String, val count: Int, val pending: Boolean)

/**
 * GeoJSON vozidel jako řetězec, sestavený MIMO hlavní vlákno. Dřív se na hlavním vlákně stavěly tisíce objektů
 * Feature (+ JsonObject) a setGeoJson(FeatureCollection) je pak v JNI procházel jeden po druhém; v Praze to
 * způsobovalo záseky při prvním pohybu mapy a při přepnutí štítky <-> tečky. Řetězec se v nativní části zpracuje
 * na pracovním vlákně.
 * Volat jen sekvenčně (mění [shownImg]); polohy jsou předem snímkem z hlavního vlákna.
 */
internal fun buildVehicleJson(
    draws: List<VehicleDraw>,
    lats: DoubleArray,
    lngs: DoubleArray,
    view: ViewBox?,
    labelsOn: Boolean,
    addedImages: Set<String>,
    shownImg: MutableMap<String, String>,
    zoom: Double,
    bearingDeg: Double,
): VehicleJson {
    val sb = StringBuilder(minOf(draws.size, 1500) * 170 + 64)
    sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
    var count = 0
    var pending = false
    val worldPx = 512.0 * 2.0.pow(zoom)
    val b = Math.toRadians(bearingDeg)
    val cosB = cos(b)
    val sinB = sin(b)
    val nowMs = System.currentTimeMillis()
    fun merc(lat: Double) = ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2))
    fun round2(x: Double) = Math.round(x * 100.0) / 100.0

    for (i in draws.indices) {
        val lat = lats[i]
        if (lat.isNaN()) continue
        val lng = lngs[i]
        if (view != null && !view.contains(lat, lng)) continue
        val d = draws[i]
        val hl = d.iconPhase != 0
        // štítek (img) jen když jsou štítky vidět nebo se prolínají; v pásmu před přepnutím se obrázky jen chystají
        var wantsImage = hl || labelsOn
        var imgJson = d.imgJson
        if (wantsImage && d.imgId !in addedImages) {
            pending = true
            if (hl) continue
            val prevImg = shownImg[d.key]
            if (prevImg != null && prevImg in addedImages) imgJson = jsonQuote(prevImg)   // starý štítek, než se nakreslí nový
            else wantsImage = false   // štítek ještě není => aspoň tečka (při prolínání nesmí vozidlo zmizet)
        }
        if (wantsImage) {
            if (imgJson === d.imgJson) shownImg[d.key] = d.imgId
        }

        // Štítek: kotva = nejbližší bod mřížky dlaždice (viz snappedAnchor), zbytek do přesné polohy nese posun ikony.
        // Posun je v pixelech pro zoom téhle dávky; během zoomu (data se zobrazí s malým zpožděním) se ikona odchýlí
        // o posun × změnu měřítka. Proto co nejmenší posun = jen zaokrouhlení na mřížku (do ~4 m), ne pevná kotva
        // s posunem v desítkách metrů (s 16–64 px vozy při zoomu „ujížděly“ o 10–30 m).
        var gLat = lat
        var gLng = lng
        var offX = 0.0
        var offY = 0.0
        if (wantsImage) {
            val a = snappedAnchor(lat, lng)
            val east = (lng - a[1]) / 360.0 * worldPx
            val north = (merc(lat) - a[2]) * worldPx / (2 * Math.PI)
            gLat = a[0]; gLng = a[1]
            offX = east * cosB - north * sinB
            offY = -(east * sinB + north * cosB)
        }

        if (count > 0) sb.append(',')
        count++
        sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            .append(gLng).append(',').append(gLat).append("]},\"properties\":{")
        val stale = d.v.ageSec(nowMs) >= 60
        var first = true
        if (stale) { sb.append("\"opacity\":").append(OLD_ALPHA); first = false }
        if (wantsImage) {
            if (!first) sb.append(',')
            sb.append("\"img\":").append(imgJson)
                .append(",\"g\":").append(d.layerIdx)
                .append(",\"o\":[").append(round2(offX)).append(',').append(round2(offY)).append(']')
            first = false
        }
        if (hl) {
            if (!first) sb.append(',')
            sb.append("\"hl\":1")
        } else {
            if (!first) sb.append(',')
            sb.append("\"k\":\"").append(d.colorKey).append(if (stale) "~\"" else "\"")
            if (d.late > 0) sb.append(",\"late\":1")
        }
        sb.append("}}")
    }
    sb.append("]}")
    return VehicleJson(sb.toString(), count, pending)
}

/**
 * Velké dlaždice zdroje vozidel (z9 ≈ 50 km, celá Praha v jedné: hranice z9 vedou po 14,06° a 14,77° v. d.).
 * MapLibre kreslí symboly PO DLAŽDICÍCH: vůz
 * v jiné dlaždici je vždy nad / pod sousedem bez ohledu na pořadí features, takže když tramvaj přejela hranici
 * dlaždice (u z13 ~3 km, v Praze pořád), prohodilo se překrytí. Méně dlaždic = i méně práce při každé dávce.
 * Hrubší mřížka souřadnic v dlaždici (~6 m) nevadí: kotvy štítků se přichytávají přímo na ni ([snappedAnchor])
 * a zbytek (do ~4 m) nese posun ikony; tečky (do zoomu 11) se posouvají nejvýš o čtvrt pixelu. Jemnější mřížka
 * (vyšší úroveň) by Prahu rozdělila hranicí 14,41° v. d. přímo přes centrum.
 */
private const val VEH_SRC_MAX_ZOOM = 9
private const val VEH_TILE_EXTENT = 8192.0   // MapLibre (geojson-vt) extent dlaždice

/**
 * Kotva [lat, lng, merc] přichycená na mřížku souřadnic dlaždice úrovně [VEH_SRC_MAX_ZOOM]: MapLibre ji
 * pak zobrazí přesně tam, kde ji počítáme, a posun ikony k ní je přesný. Nikdy přesně na hranici dlaždice
 * (bod na hranici by byl v obou dlaždicích => duplicita).
 */
private fun snappedAnchor(lat: Double, lng: Double): DoubleArray {
    val n = VEH_TILE_EXTENT * (1 shl VEH_SRC_MAX_ZOOM)
    fun off(v: Double) = if (v % VEH_TILE_EXTENT == 0.0) v + 1 else v
    val gx = off(Math.round((lng + 180.0) / 360.0 * n).toDouble())
    val m0 = ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2))
    val gy = off(Math.round((1.0 - m0 / Math.PI) / 2.0 * n).toDouble())
    val sLng = gx / n * 360.0 - 180.0
    val sMerc = (1.0 - 2.0 * gy / n) * Math.PI
    val sLat = Math.toDegrees(2.0 * kotlin.math.atan(kotlin.math.exp(sMerc)) - Math.PI / 2)
    return doubleArrayOf(sLat, sLng, sMerc)
}
internal const val VEH_DOT_LAYER = "vehicles-dots"
internal const val VEH_HL_LAYER = "vehicles-hl"
private const val VEH_OLD_LAYER = "vehicles-old"   // štítky vozidel se starou polohou (ztlumené)

/**
 * Z-order podle druhu dopravy řeší pořadí VRSTEV (ne data-driven symbolSortKey, který při 30 Hz GeoJSON
 * a tisících vozidel v Praze způsobuje blikání). Každá skupina má vlastní vrstvu (a vrstvu staré polohy);
 * výš ve výčtu = vykreslí se výš: autobusy < tramvaje < metro < vlaky < vánoční vozy.
 */
private const val Z_GROUPS = 5

/**
 * Každý druh dopravy je navíc rozdělený do [Z_SUB] podvrstev podle hashe klíče vozidla. MapLibre páruje symboly
 * mezi dávkami dat (CrossTileSymbolIndex) PO VRSTVÁCH podle ikony a polohy: stovky vozů stejné linky / stejného druhu
 * v jedné vrstvě se při 30 Hz zaměňují nebo nespárují (symbol na snímek zmizí = blikání). Čím méně vozů ve vrstvě,
 * tím jednoznačnější párování; pořadí překryvu stejného druhu je dané podvrstvou (stabilně podle klíče).
 * Cena: víc vrstev (každá je průchod přes všechny dlaždice). Při kotvách na mřížce dlaždice (snappedAnchor) stačí 1;
 * když by blikání mezi stejným druhem začalo znovu, zvyš na 2-4.
 */
private const val Z_SUB = 1
internal const val Z_LAYERS = Z_GROUPS * Z_SUB
private fun zLayer(style: VehicleStyle, key: String): Int =
    zGroup(style) * Z_SUB + (key.hashCode() and 0x7fffffff) % Z_SUB
private fun zGroup(style: VehicleStyle): Int = when (style) {
    VehicleStyle.TRAM -> 1
    VehicleStyle.METRO, VehicleStyle.METRO_A, VehicleStyle.METRO_B, VehicleStyle.METRO_C -> 2
    VehicleStyle.TRAIN -> 3
    VehicleStyle.CHRISTMAS -> 4
    else -> 0   // autobusy, trolejbusy, přívoz, lanovka
}
internal fun freshLayerId(g: Int) = if (g == 0) VEH_LAYER else "vehicles-g$g"
internal fun oldLayerId(g: Int) = if (g == 0) VEH_OLD_LAYER else "vehicles-g$g-old"
private fun isOldLayerId(id: String) = id == VEH_OLD_LAYER || (id.startsWith("vehicles-g") && id.endsWith("-old"))
private const val OLD_ALPHA = 0.65f

private fun vehicleSymbolProps() = arrayOf(
    PropertyFactory.iconImage(Expression.get("img")),
    PropertyFactory.iconAllowOverlap(true),
    PropertyFactory.iconIgnorePlacement(true),
    // průhlednost jen jako konstanta vrstvy (applyLayerAlpha): změna výrazu závislého na datech (get "opacity")
    // nutí MapLibre znovu postavit celý zdroj => při prolínání v Praze sekání v každém snímku
    PropertyFactory.iconOpacity(0f),   // zobrazí až smyčka vozidel (applyLayerAlpha), ať nic neproblikne
    // KLÍČOVÉ: výchozí symbolZOrder = "auto" řadí symboly podle Y na obrazovce (při iconAllowOverlap), takže se
    // překrývající vozy stejného druhu přehazují nad sebou při každém pohybu => blikání. "source" = pořadí features.
    PropertyFactory.symbolZOrder(Property.SYMBOL_Z_ORDER_SOURCE),
    // Kotva symbolu je na mřížce dlaždice (snappedAnchor), zbytek do přesné polohy je v pixelovém posunu ikony.
    PropertyFactory.iconOffset(Expression.get("o")),
    // Z-order: žádný symbolSortKey. Data-driven řazení s unikátním klíčem u každého vozidla rozdělí vrstvu na
    // stovky kreslicích dávek a při každém novém GeoJSON (30 Hz) se symboly přeskupují => markery blikají.
    // Vozidla se kreslí v pořadí features (stabilně podle klíče), vánoční a zvýrazněné mají vlastní vrstvy výš.
)

/**
 * Průhlednost řídí smyčka vozidel po snímcích, výchozí přechod stylu (300 ms) by se s ní pral: každá změna by
 * spustila novou animaci od aktuální hodnoty => zpožděné, nerovnoměrné prolínání a problikávání.
 */
private val NO_TRANSITION = TransitionOptions(0, 0, false)

private fun SymbolLayer.noOpacityTransition() = also { it.iconOpacityTransition = NO_TRANSITION }

/**
 * Vrstvy nad jedním zdrojem; průhlednost (prolínání tečky ↔ štítky a objevení) nastavuje smyčka vozidel
 * přes [applyLayerAlpha] jako konstantu vrstvy, vrstva s nulovou průhledností je skrytá:
 *  - [VEH_LAYER]: štítky s číslem linky od [DOT_ZOOM_ML] výš,
 *  - [VEH_OLD_LAYER]: totéž pro vozidla se starou polohou (ztlumená konstantou vrstvy),
 *  - [VEH_DOT_LAYER]: malé tečky pod [DOT_ZOOM_ML],
 *  - [VEH_HL_LAYER]: zvýrazněné vozidlo je vždy štítek (a nahoře).
 */
internal fun ensureVehicleLayer(style: Style) {
    if (style.getSource(VEH_SRC) == null) {
        // maxZoom viz VEH_SRC_MAX_ZOOM: nad ním MapLibre jen zvětšuje dlaždice z té úrovně.
        style.addSource(GeoJsonSource(
            VEH_SRC, FeatureCollection.fromFeatures(emptyList<Feature>()),
            org.maplibre.android.style.sources.GeoJsonOptions().withMaxZoom(VEH_SRC_MAX_ZOOM)
                // buffer 0: bod patří jen do JEDNÉ dlaždice. S výchozím bufferem (128) je vůz u hranice v obou
                // dlaždicích a CrossTileSymbolIndex duplicity páruje jen podle polohy (ikony nemají text => stejný
                // klíč) – překrývající se vozy (tramvaje v Praze) se při každé dávce zaměnily a jeden na snímek
                // zmizel / přeskočil nad druhý. Symboly se ořezávají jen podle kotvy, ne podle dlaždice, takže
                // ikona přes hranici se kreslí celá.
                .withBuffer(0),
        ))
        // bez animací umísťování symbolů: každá nová dávka poloh vozidel (GeoJSON) by štítky znovu „prolínala“
        // (300 ms) a mapa by se kvůli tomu překreslovala pořád dokola (render vlákno MapLibre ~80 % v Praze)
        style.transition = TransitionOptions(0, 0, false)
    }
    val notHl = Expression.neq(Expression.get("hl"), Expression.literal(1))
    val old = Expression.has("opacity")
    fun inGroup(g: Int) = Expression.eq(Expression.get("g"), Expression.literal(g))
    for (g in 0 until Z_LAYERS) {
        val freshId = freshLayerId(g)
        val oldId = oldLayerId(g)
        val below = if (g == 0) null else freshLayerId(g - 1)
        if (style.getLayer(oldId) == null) {
            val l = SymbolLayer(oldId, VEH_SRC).withProperties(*vehicleSymbolProps())
                .withFilter(Expression.all(notHl, inGroup(g), old)).noOpacityTransition()
            if (below == null) style.addLayer(l) else style.addLayerAbove(l, below)
        }
        if (style.getLayer(freshId) == null) {
            style.addLayerAbove(
                SymbolLayer(freshId, VEH_SRC).withProperties(*vehicleSymbolProps())
                    .withFilter(Expression.all(notHl, inGroup(g), Expression.not(old))).noOpacityTransition(),
                oldId
            )
        }
    }
    if (style.getLayer(VEH_DOT_LAYER) == null) {
        // tečky POD štítky (při prolínání nesmí prosvítat přes štítek)
        style.addLayerBelow(
            CircleLayer(VEH_DOT_LAYER, VEH_SRC).withProperties(
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(6, 2.0f),
                        Expression.stop(DOT_ZOOM_ML.toInt(), 3.4f)
                    )
                ),
                PropertyFactory.circleColor(
                    Expression.match(
                        Expression.get("k"), Expression.color(VehicleStyle.BUS.color),
                        *VehicleStyle.entries.flatMap {
                            // stará poloha: "<styl>~", ztlumená barvou (průhlednost vrstvy je konstanta, viz výše)
                            val dim = (it.color and 0xFFFFFF) or ((OLD_ALPHA * 255).toInt() shl 24)
                            listOf(Expression.stop(it.name, Expression.color(it.color)), Expression.stop(it.name + "~", Expression.color(dim)))
                        }.toTypedArray()
                    )
                ),
                PropertyFactory.circleOpacity(0f),
                // tramvaj / metro se zpožděním: červený okraj tečky
                PropertyFactory.circleStrokeColor(
                    Expression.match(Expression.get("late"), Expression.color(0xFFFFFFFF.toInt()),
                        Expression.stop(1, Expression.color(LATE_COLOR)))
                ),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circleStrokeOpacity(0f),
            ).withFilter(notHl).also {
                it.circleOpacityTransition = NO_TRANSITION
                it.circleStrokeOpacityTransition = NO_TRANSITION
            },
            VEH_LAYER
        )
    }
    if (style.getLayer(VEH_HL_LAYER) == null) {
        style.addLayer(
            SymbolLayer(VEH_HL_LAYER, VEH_SRC).withProperties(*vehicleSymbolProps())
                .withFilter(Expression.eq(Expression.get("hl"), Expression.literal(1))).noOpacityTransition()
        )
    }
}

/**
 * Průhlednost vrstvy vozidel (konstanta vrstvy, levná změna). Vrstvy se neskrývají přes visibility: to je layout
 * vlastnost a každé zobrazení by znovu rozmístilo všechny štítky (zásek na začátku každého přechodu). Skrytou
 * vrstvu štítků nic nestojí, protože vozidla v režimu teček štítek (img) nemají.
 */
internal fun applyLayerAlpha(style: Style, id: String, a: Float) {
    val layer = style.getLayer(id) ?: return
    when (id) {
        VEH_DOT_LAYER -> layer.setProperties(
            PropertyFactory.circleOpacity(a),
            PropertyFactory.circleStrokeOpacity(0.8f * a),
        )
        else -> layer.setProperties(PropertyFactory.iconOpacity(if (isOldLayerId(id)) OLD_ALPHA * a else a))
    }
}
