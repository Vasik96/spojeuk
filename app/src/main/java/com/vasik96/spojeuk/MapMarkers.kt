package com.vasik96.spojeuk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.ConcurrentHashMap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import androidx.core.graphics.drawable.toDrawable

// Ikony vozů a šipky na trase pro Google Maps. Až přibudou další mapové providery,
// bitmapy se dají použít beze změny, jen BitmapDescriptor je specifický pro Google.

private val markerIconCache = ConcurrentHashMap<String, BitmapDrawable>()

// ── štítky vozidel: bitmapa se kreslí na pozadí (prerenderMarker), overlay na mapě si bere hotové (cachedMarkerBitmap) ──

internal fun labelKey(line: String, style: VehicleStyle, phase: Int, late: Int = 0): String =
    if (late > 0) "$line:${style.name}:$phase:late" else "$line:${style.name}:$phase"

/** Tramvaje a metro mají vlastní barvu (zpoždění barvou nejde): zpoždění > 3 min (pro červený rámeček), jinak 0. */
internal fun lateMinutes(v: Vehicle): Int {
    val s = vehicleStyle(v)
    val own = s == VehicleStyle.TRAM || s == VehicleStyle.METRO || s == VehicleStyle.METRO_A ||
            s == VehicleStyle.METRO_B || s == VehicleStyle.METRO_C
    return if (own && v.delay > 3) v.delay.coerceAtMost(99) else 0
}

/** Hotová bitmapa štítku, nebo null (nic nekreslí). */
internal fun cachedMarkerBitmap(key: String): Bitmap? = markerIconCache[key]?.bitmap

/** Nakreslí bitmapu štítku do cache. Bezpečné volat mimo hlavní vlákno. */
internal fun prerenderMarker(ctx: Context, line: String, style: VehicleStyle, phase: Int, late: Int = 0) {
    createLineMarkerDrawable(ctx, line, style, phase, late)
}

internal fun markerBitmap(ctx: Context, line: String, style: VehicleStyle, phase: Int, late: Int = 0): Bitmap =
    createLineMarkerDrawable(ctx, line, style, phase, late).bitmap

/**
 * Barevná skupina vozu (štítek i tečka). Autobusy podle zpoždění, ostatní druhy dopravy vlastní barvou
 * (zpoždění vlaku / tramvaje / metra je vidět v detailu), metro v barvách linek A / B / C.
 */
internal enum class VehicleStyle(
    val color: Int,
    /** Barva trasy spoje na světlé mapě: tmavší odstín barvy vozu (stejně jako na webu). */
    val route: Int,
    /** Barva trasy na tmavé mapě: světlejší odstín. */
    val routeDark: Int,
    val darkText: Boolean = false,
) {
    BUS(0xFF2E7D32.toInt(), 0xFF1B5E20.toInt(), 0xFF66BB6A.toInt()),
    BUS_LATE(0xFFC62828.toInt(), 0xFF1B5E20.toInt(), 0xFF66BB6A.toInt()),   // trasa zpožděného autobusu je autobusová
    TRAIN(0xFF0277BD.toInt(), 0xFF01579B.toInt(), 0xFF4FC3F7.toInt()),
    TRAM(0xFFEF6C00.toInt(), 0xFFC25400.toInt(), 0xFFFFB74D.toInt()),       // oranžová (dřív fialová)
    METRO_A(0xFF00A562.toInt(), 0xFF007A48.toInt(), 0xFF4CD08F.toInt()),
    METRO_B(0xFFF8B322.toInt(), 0xFFC68A00.toInt(), 0xFFFFD36B.toInt(), darkText = true),
    METRO_C(0xFFD6006F.toInt(), 0xFFA10054.toInt(), 0xFFF0609F.toInt()),     // malinová: červená C splývala se zpožděnými autobusy
    METRO(0xFF37474F.toInt(), 0xFF263238.toInt(), 0xFF90A4AE.toInt()),
    FERRY(0xFF1565C0.toInt(), 0xFF0D47A1.toInt(), 0xFF64B5F6.toInt()),
    FUNICULAR(0xFF6D4C41.toInt(), 0xFF4E342E.toInt(), 0xFFA1887F.toInt()),
}

/** Druh dopravy spoje z jízdního řádu (kategorie + linka metra) jako styl vozu. */
internal fun tripStyle(category: MapCategory, line: String): VehicleStyle = when (category) {
    MapCategory.TRAIN -> VehicleStyle.TRAIN
    MapCategory.TRAM -> VehicleStyle.TRAM
    MapCategory.METRO -> when (line.trim().uppercase()) {
        "A" -> VehicleStyle.METRO_A; "B" -> VehicleStyle.METRO_B; "C" -> VehicleStyle.METRO_C
        else -> VehicleStyle.METRO
    }
    MapCategory.BUS -> VehicleStyle.BUS
}

/** Druh dopravy: ze serveru (`mode`), u starších odpovědí odvozený z PID uid ("g0:…" tramvaj) a linek metra. */
internal fun vehicleStyle(v: Vehicle): VehicleStyle {
    val mode = v.mode.ifEmpty {
        val rt = if (v.source == SourceIds.PID && v.serverUid.startsWith("g")) v.serverUid.substring(1).substringBefore(':') else ""
        when {
            v.train -> "train"
            rt == "0" -> "tram"
            rt == "4" -> "ferry"
            rt == "7" -> "funicular"
            v.source == SourceIds.PID && isMetroLine(v.line) -> "metro"
            else -> "bus"
        }
    }
    return when (mode) {
        "train" -> VehicleStyle.TRAIN
        "tram" -> VehicleStyle.TRAM
        "ferry" -> VehicleStyle.FERRY
        "funicular" -> VehicleStyle.FUNICULAR
        "metro" -> when (v.line.trim().uppercase()) {
            "A" -> VehicleStyle.METRO_A
            "B" -> VehicleStyle.METRO_B
            "C" -> VehicleStyle.METRO_C
            else -> VehicleStyle.METRO
        }
        else -> if (v.train) VehicleStyle.TRAIN else if (v.delay > 3) VehicleStyle.BUS_LATE else VehicleStyle.BUS
    }
}

/** Druh dopravy pro filtr mapy. Trolejbusy (a přívozy, lanovka) patří k autobusům. */
enum class MapCategory(@androidx.annotation.StringRes internal val labelRes: Int, internal val style: VehicleStyle) {
    BUS(R.string.mode_bus, VehicleStyle.BUS),
    TRAM(R.string.mode_tram, VehicleStyle.TRAM),
    METRO(R.string.mode_metro, VehicleStyle.METRO_A),
    TRAIN(R.string.mode_train, VehicleStyle.TRAIN),
}

internal fun mapCategory(v: Vehicle): MapCategory = when (vehicleStyle(v)) {
    VehicleStyle.TRAIN -> MapCategory.TRAIN
    VehicleStyle.TRAM -> MapCategory.TRAM
    VehicleStyle.METRO, VehicleStyle.METRO_A, VehicleStyle.METRO_B, VehicleStyle.METRO_C -> MapCategory.METRO
    else -> MapCategory.BUS
}

/** Barva markeru vozidla (stejná pro štítek i pro tečku při oddálení); fáze zvýraznění mají vlastní barvy. */
internal fun vehicleColor(style: VehicleStyle, phase: Int = 0): Int = when (phase) {
    1 -> 0xFFFFD54F.toInt()
    2 -> 0xFFFF6D00.toInt()
    else -> style.color
}

// ───────────── malé tečky pro oddálenou mapu (bez čísla linky) ─────────────

private val dotBitmapCache = ConcurrentHashMap<Long, Bitmap>()

/** Červená zpoždění (stejná jako u zpožděných autobusů), pro okraj tečky a odznak tramvají / metra. */
internal const val LATE_COLOR = 0xFFD32F2F.toInt()

/**
 * Malá tečka (cca 9 dp) v barvě vozidla s jemným světlým okrajem, aby šla vidět na tmavé i světlé mapě.
 * [late] (tramvaj / metro se zpožděním): okraj červený a silnější.
 */
internal fun dotBitmap(ctx: Context, color: Int, late: Boolean = false): Bitmap =
    dotBitmapCache.getOrPut((color.toLong() shl 1) or (if (late) 1L else 0L)) {
        val density = ctx.resources.displayMetrics.density
        val size = ((if (late) 11 else 9) * density).toInt().coerceAtLeast(9)
        val bmp = createBitmap(size, size)
        val c = Canvas(bmp)
        val r = size / 2f
        c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = if (late) LATE_COLOR else 0xCCFFFFFF.toInt() })
        c.drawCircle(r, r, r - (if (late) 2.2f else 1.2f) * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        bmp
    }

/** phase: 0 = normální, 1 = zvýraznění A (žlutá, velká), 2 = zvýraznění B (oranžová, menší). */
private fun createLineMarkerDrawable(
    context: Context, line: String, vs: VehicleStyle, phase: Int, late: Int = 0
): BitmapDrawable {
    val key = labelKey(line, vs, phase, late)
    markerIconCache[key]?.let { return it }

    val density = context.resources.displayMetrics.density
    val highlighted = phase != 0

    val textSizeDp = when (phase) { 1 -> 17; 2 -> 15; else -> 13 }
    val heightDp = when (phase) { 1 -> 38; 2 -> 32; else -> 26 }
    val padDp = if (highlighted) 26 else 18
    val strokePx = if (highlighted) 2.5f * density else 0f

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (phase == 1 || (phase == 0 && vs.darkText)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        textSize = textSizeDp * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    // Linka se nikdy neořezává. Marker se nejdřív roztáhne do šířky (až po maxWidthDp)
    // a písmo se zmenšuje jen o tolik, o kolik je to nutné, aby se text vešel.
    val displayLine = line
    val maxWidthDp = if (highlighted) 84 else 72
    val longPadDp = if (highlighted) 20 else 12      // u dlouhých linek menší okraje = větší písmo
    var effectivePadDp = padDp
    if (displayLine.length > 3) {
        effectivePadDp = longPadDp
        val maxTextWidthPx = (maxWidthDp - effectivePadDp) * density
        val fullWidthPx = textPaint.measureText(displayLine)
        if (fullWidthPx > maxTextWidthPx) {
            val minSizePx = 8f * density   // spodní mez čitelnosti
            textPaint.textSize = maxOf(minSizePx, textPaint.textSize * maxTextWidthPx / fullWidthPx)
        }
    }
    val textWidthPx = textPaint.measureText(displayLine)

    val pillWidthPx = maxOf((36 * density).toInt(), (textWidthPx + effectivePadDp * density).toInt())
    val pillHeightPx = (heightDp * density).toInt()

    // zpožděná tramvaj / metro: červený rámeček KOLEM štítku (bitmapa se o něj zvětší, barevná část zůstane stejně velká)
    val lateRing = if (late > 0 && !highlighted) (4f * density).toInt() else 0
    val widthPx = pillWidthPx + 2 * lateRing
    val heightPx = pillHeightPx + 2 * lateRing

    val bitmap = createBitmap(widthPx, heightPx)
    val canvas = Canvas(bitmap)

    val bgColor = vehicleColor(vs, phase)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        style = Paint.Style.FILL
    }
    val inset = strokePx / 2f
    val rect = RectF(lateRing + inset, lateRing + inset, lateRing + pillWidthPx - inset, lateRing + pillHeightPx - inset)
    val radius = rect.height() / 2f
    if (lateRing > 0) {
        val outer = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
        canvas.drawRoundRect(outer, outer.height() / 2f, outer.height() / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LATE_COLOR })
    }
    canvas.drawRoundRect(rect, radius, radius, bgPaint)

    if (highlighted) {
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = strokePx
        }
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    val textY = (heightPx / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(displayLine, widthPx / 2f, textY, textPaint)

    val drawable = bitmap.toDrawable(context.resources)
    markerIconCache[key] = drawable
    return drawable
}

// ───────────────────────── šipky na trase ─────────────────────────

internal data class RouteArrow(
    val position: LatLng,
    val bearing: Float
)

private fun bearingBetween(start: LatLng, end: LatLng): Float {
    val lat1 = Math.toRadians(start.latitude)
    val lat2 = Math.toRadians(end.latitude)
    val dLon = Math.toRadians(end.longitude - start.longitude)

    val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2)
    val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
            kotlin.math.sin(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.cos(dLon)

    return (Math.toDegrees(kotlin.math.atan2(y, x)).toFloat() + 360f) % 360f
}

private val arrowDescCache = ConcurrentHashMap<String, BitmapDescriptor>()
private val arrowBitmapCache = ConcurrentHashMap<String, Bitmap>()

internal fun routeArrowDescriptor(primaryColor: Int, scale: Float): BitmapDescriptor {
    val size = (96 * scale).toInt().coerceAtLeast(18)
    return arrowDescCache.getOrPut("$primaryColor:$size") {
        BitmapDescriptorFactory.fromBitmap(routeArrowBitmap(primaryColor, scale))
    }
}

/** Šipka na trase jako holá bitmapa (míří nahoru), pro kreslení do Canvasu nad Google mapou. */
internal fun routeArrowBitmap(primaryColor: Int, scale: Float): Bitmap {
    val size = (96 * scale).toInt().coerceAtLeast(18)
    return arrowBitmapCache.getOrPut("$primaryColor:$size") {
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)

        // Souřadnice se škálují společně s bitmapou.
        canvas.withScale(size / 72f, size / 72f) {
            val path = android.graphics.Path().apply {
                moveTo(13f, 43f)
                lineTo(36f, 15f)
                lineTo(59f, 43f)
            }

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 18f
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            drawPath(path, borderPaint)

            val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = primaryColor
                style = Paint.Style.STROKE
                strokeWidth = 11f
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            drawPath(path, arrowPaint)

        }

        bitmap
    }
}

internal fun arrowSizeForZoom(zoom: Float): Float = when {
    zoom >= 18f -> 1.4f
    zoom >= 16f -> 1.15f
    zoom >= 14f -> 0.9f
    zoom >= 12f -> 0.65f
    zoom >= 10f -> 0.45f
    else -> 0.3f
}

/**
 * Rozestup šipek na trase v metrech tak, aby byly na obrazovce zhruba [ARROW_GAP_DP] od sebe při jakémkoli zoomu
 * (dřív pevné metry: při oddálení se šipky slévaly, na zoomu 9 byly ~8 dp od sebe). Počítá se z celé úrovně zoomu,
 * takže se mění jen na hranicích úrovní (žádná rekompozice při plynulém zoomu).
 */
internal fun arrowSpacingForZoom(zoom: Float): Float {
    val metersPerDp = 156_543.03 * kotlin.math.cos(Math.toRadians(50.0)) / Math.pow(2.0, kotlin.math.floor(zoom.toDouble()))
    return (ARROW_GAP_DP * metersPerDp).toFloat().coerceAtLeast(150f)
}

private const val ARROW_GAP_DP = 90.0

/** Šipky podél trasy v lineárním čase (kumulativní vzdálenosti + binární vyhledávání). */
internal fun createRouteArrows(route: List<LatLng>, spacingMeters: Float = 300f): List<RouteArrow> {
    val n = route.size
    if (n < 2) return emptyList()

    val cum = DoubleArray(n)
    for (i in 1 until n) cum[i] = cum[i - 1] + distanceMeters(route[i - 1], route[i]).toDouble()
    val total = cum[n - 1]
    if (total < 100.0) return emptyList()

    fun at(d: Double): LatLng {
        val dd = d.coerceIn(0.0, total)
        var idx = java.util.Arrays.binarySearch(cum, dd)
        if (idx < 0) idx = -idx - 2
        idx = idx.coerceIn(0, n - 2)
        val seg = cum[idx + 1] - cum[idx]
        val t = if (seg <= 1e-9) 0.0 else (dd - cum[idx]) / seg
        return LatLng(
            route[idx].latitude + (route[idx + 1].latitude - route[idx].latitude) * t,
            route[idx].longitude + (route[idx + 1].longitude - route[idx].longitude) * t
        )
    }

    val out = ArrayList<RouteArrow>()
    // První šipka přibližně uprostřed prvního rozestupu.
    var d = spacingMeters / 2.0
    while (d < total - 20.0) {
        // Směr z bodů těsně před a za šipkou, omezí chybné natočení v ostrých zatáčkách.
        out += RouteArrow(at(d), bearingBetween(at(d - 15.0), at(d + 15.0)))
        d += spacingMeters
    }
    return out
}

// ───────────────────────── kruhy pro začátek, konec a zastávky ─────────────────────────

private var startDesc: BitmapDescriptor? = null
private var endDesc: BitmapDescriptor? = null
private var stopDesc: BitmapDescriptor? = null
private var cachedStartBitmap: Bitmap? = null
private var cachedEndBitmap: Bitmap? = null

/** Kruh pro začátek trasy (zelený s bílým okrajem a bodem). */
internal fun routeStartDescriptor(ctx: Context): BitmapDescriptor {
    startDesc?.let { return it }
    val desc = BitmapDescriptorFactory.fromBitmap(routeStartBitmap(ctx))
    startDesc = desc
    return desc
}

internal fun routeStartBitmap(ctx: Context): Bitmap {
    cachedStartBitmap?.let { return it }
    val density = ctx.resources.displayMetrics.density
    val sizePx = (24 * density).toInt()
    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)

    val strokePx = 3f * density
    val center = sizePx / 2f
    val radius = (sizePx - strokePx) / 2f

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = strokePx + 2f
    }
    canvas.drawCircle(center, center, radius, shadowPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = strokePx
    }
    canvas.drawCircle(center, center, radius, borderPaint)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E7D32.toInt() // Zelená (start)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radius - strokePx / 2f, fillPaint)

    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 3.5f * density, dotPaint)

    cachedStartBitmap = bitmap
    return bitmap
}

/** Kruh pro konec trasy (červený s bílým okrajem a bodem). */
internal fun routeEndDescriptor(ctx: Context): BitmapDescriptor {
    endDesc?.let { return it }
    val desc = BitmapDescriptorFactory.fromBitmap(routeEndBitmap(ctx))
    endDesc = desc
    return desc
}

internal fun routeEndBitmap(ctx: Context): Bitmap {
    cachedEndBitmap?.let { return it }
    val density = ctx.resources.displayMetrics.density
    val sizePx = (24 * density).toInt()
    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)

    val strokePx = 3f * density
    val center = sizePx / 2f
    val radius = (sizePx - strokePx) / 2f

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = strokePx + 2f
    }
    canvas.drawCircle(center, center, radius, shadowPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = strokePx
    }
    canvas.drawCircle(center, center, radius, borderPaint)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC62828.toInt() // Červená (konec)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radius - strokePx / 2f, fillPaint)

    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 3.5f * density, dotPaint)

    cachedEndBitmap = bitmap
    return bitmap
}

/** Kruh pro mezilehlé zastávky na trase (bílý s modrým okrajem a bodem). */
internal fun routeStopDescriptor(ctx: Context): BitmapDescriptor {
    stopDesc?.let { return it }
    val desc = BitmapDescriptorFactory.fromBitmap(routeStopBitmap(ctx))
    stopDesc = desc
    return desc
}

private var cachedStopBitmap: Bitmap? = null

internal fun routeStopBitmap(ctx: Context): Bitmap {
    cachedStopBitmap?.let { return it }
    val density = ctx.resources.displayMetrics.density
    val sizePx = (16 * density).toInt()
    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)

    val strokePx = 2.5f * density
    val center = sizePx / 2f
    val radius = (sizePx - strokePx) / 2f

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radius, bgPaint)

    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0277BD.toInt() // Modrá pro zastávky
        style = Paint.Style.STROKE
        strokeWidth = strokePx
    }
    canvas.drawCircle(center, center, radius, strokePaint)

    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0277BD.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, 2.5f * density, dotPaint)

    cachedStopBitmap = bitmap
    return bitmap
}