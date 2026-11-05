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

// Ikony vozů a šipky na trase pro Google Maps. Až přibudou další mapové providery,
// bitmapy se dají použít beze změny, jen BitmapDescriptor je specifický pro Google.

private val markerIconCache = ConcurrentHashMap<String, BitmapDrawable>()
private val descCache = ConcurrentHashMap<String, BitmapDescriptor>()

internal fun markerDescriptor(ctx: Context, line: String, train: Boolean, delay: Int, phase: Int): BitmapDescriptor =
    descCache.getOrPut("$line:$train:${delay > 3}:$phase") {
        BitmapDescriptorFactory.fromBitmap(
            createLineMarkerDrawable(ctx, line, train, delay, phase).bitmap
        )
    }

internal fun markerBitmap(ctx: Context, line: String, train: Boolean, delay: Int, phase: Int): Bitmap =
    createLineMarkerDrawable(ctx, line, train, delay, phase).bitmap

/** phase: 0 = normální, 1 = zvýraznění A (žlutá, velká), 2 = zvýraznění B (oranžová, menší). */
private fun createLineMarkerDrawable(
    context: Context, line: String, isTrain: Boolean, delay: Int, phase: Int
): BitmapDrawable {
    val key = "$line:$isTrain:${delay > 3}:$phase"
    markerIconCache[key]?.let { return it }

    val density = context.resources.displayMetrics.density
    val highlighted = phase != 0

    val textSizeDp = when (phase) { 1 -> 17; 2 -> 15; else -> 13 }
    val heightDp = when (phase) { 1 -> 38; 2 -> 32; else -> 26 }
    val padDp = if (highlighted) 26 else 18
    val strokePx = if (highlighted) 2.5f * density else 0f

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (phase == 1) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
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

    val widthPx = maxOf((36 * density).toInt(), (textWidthPx + effectivePadDp * density).toInt())
    val heightPx = (heightDp * density).toInt()

    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bgColor = when {
        phase == 1 -> 0xFFFFD54F.toInt()
        phase == 2 -> 0xFFFF6D00.toInt()
        isTrain -> 0xFF0277BD.toInt()
        delay > 3 -> 0xFFC62828.toInt()
        else -> 0xFF2E7D32.toInt()
    }

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        style = Paint.Style.FILL
    }
    val inset = strokePx / 2f
    val rect = RectF(inset, inset, widthPx - inset, heightPx - inset)
    val radius = rect.height() / 2f
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

    val drawable = BitmapDrawable(context.resources, bitmap)
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

internal fun routeArrowDescriptor(primaryColor: Int, scale: Float): BitmapDescriptor {
    val size = (96 * scale).toInt().coerceAtLeast(18)
    return arrowDescCache.getOrPut("$primaryColor:$size") {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Souřadnice se škálují společně s bitmapou.
        canvas.save()
        canvas.scale(size / 72f, size / 72f)

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
        canvas.drawPath(path, borderPaint)

        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            style = Paint.Style.STROKE
            strokeWidth = 11f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(path, arrowPaint)

        canvas.restore()

        BitmapDescriptorFactory.fromBitmap(bitmap)
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

internal fun arrowSpacingForZoom(zoom: Float): Float = when {
    zoom >= 17f -> 180f
    zoom >= 15f -> 300f
    zoom >= 13f -> 500f
    zoom >= 11f -> 900f
    zoom >= 9f -> 1600f
    else -> 3000f
}

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
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
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
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
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
    val density = ctx.resources.displayMetrics.density
    val sizePx = (16 * density).toInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
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

    val desc = BitmapDescriptorFactory.fromBitmap(bitmap)
    stopDesc = desc
    return desc
}