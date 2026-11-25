package com.vasik96.spojeuk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.google.maps.android.compose.CameraPositionState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Vozidla nad Google mapou (tečky i štítky s číslem linky), kreslená vlastním canvasem (sprite batch z atlasu).
 *
 * Mapové jádro o nich neví => žádná cena na GL vlákně mapy (markery: ~6 µs na kus a snímek v labelovacím enginu),
 * pevná velikost v dp při jakémkoli zoomu a nic nebliká. Pozice na obrazovce se počítá z kamery (Web Mercator,
 * svět = 256 dp * 2^zoom, střed kamery = střed mapy, náklon je vypnutý), takže se nic nevolá do mapy.
 * Kreslí se znovu jen při pohybu kamery nebo změně vozidel ([GoogleVehicleLayer.version]).
 */
@Composable
internal fun GoogleDotOverlay(
    layer: GoogleVehicleLayer,
    cameraPositionState: CameraPositionState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val painter = remember(context) { DotPainter(context) }
    // prolínání: překreslení každý snímek jen po dobu animace
    val frame = remember { mutableIntStateOf(0) }
    // vozidla v pohybu: překreslit každý snímek, polohu dopočítá painter z rychlosti k času snímku
    LaunchedEffect(layer) {
        snapshotFlow { layer.moving.value }.collectLatest { mv ->
            while (mv) {
                layer.frameNanos = withFrameNanos { it }
                frame.intValue++
            }
        }
    }
    // prolínání zastávek: překreslení každý snímek, dokud doznívá
    LaunchedEffect(layer) {
        snapshotFlow { layer.stopFadeKick.intValue }.collectLatest {
            while (true) {
                withFrameNanos { }
                frame.intValue++
                if (!layer.stopsFading(SystemClock.elapsedRealtime())) break
            }
        }
    }
    LaunchedEffect(layer) {
        snapshotFlow { layer.fadeKick.intValue }.collectLatest {
            while (true) {
                withFrameNanos { }
                frame.intValue++
                if (!layer.fade.animating()) break
            }
        }
    }
    Spacer(
        modifier.drawBehind {
            layer.version.intValue                     // odběr změn vozidel
            frame.intValue                             // odběr snímků animace
            val cam = cameraPositionState.position     // odběr pohybu kamery (jen kreslení, žádná rekompozice)
            painter.draw(
                drawContext.canvas.nativeCanvas, layer,
                cam.target.latitude, cam.target.longitude, cam.zoom, cam.bearing,
                size.width, size.height, density
            )
        }
    )
}

/**
 * Sprite batch: všechny bitmapy (tečky po barvách, štítky linek) leží v atlasu a celý snímek je jedno
 * `drawVertices` na stránku atlasu. Perfetto: tisíce samostatných drawBitmap = 10–120 ms na RenderThreadu
 * (Skia každé převádí a slučuje zvlášť), batch = pár operací bez ohledu na počet vozidel.
 * Pořadí v batchi = pořadí kreslení => okraj horní tečky leží přes výplň spodní (i dvě stejné tečky jdou rozlišit).
 */
private class DotPainter(private val context: Context) {
    private val atlas = SpriteAtlas()
    private val batches = ArrayList<Batch>()
    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)   // bez barvy (alfa paintu by se propsala do bitmapy)
    private var lastColor = 0
    private var lastLate = false
    private var lastChristmas = false
    private var lastBmp: Bitmap? = null
    private val stopPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Tečky podle (barva, zpoždění, Vánoce): různých kombinací je jen pár desítek. Malá tabulka bez alokací místo
    // dotBitmap() pro každé vozidlo v každém snímku (ConcurrentHashMap s klíčem zabaleným do Long).
    private var dotKeys = LongArray(32)
    private var dotBmps = arrayOfNulls<Bitmap>(32)
    private var dotN = 0

    private fun dotFor(color: Int, late: Boolean, christmas: Boolean): Bitmap {
        if (color == lastColor && late == lastLate && christmas == lastChristmas) lastBmp?.let { return it }
        val key = (color.toLong() shl 2) or (if (late) 1L else 0L) or (if (christmas) 2L else 0L)
        var bmp: Bitmap? = null
        for (i in 0 until dotN) if (dotKeys[i] == key) { bmp = dotBmps[i]; break }
        if (bmp == null) {
            bmp = dotBitmap(context, color, late, christmas)
            if (dotN == dotKeys.size) { dotKeys = dotKeys.copyOf(dotN * 2); dotBmps = dotBmps.copyOf(dotN * 2) }
            dotKeys[dotN] = key; dotBmps[dotN] = bmp; dotN++
        }
        lastColor = color; lastLate = late; lastChristmas = christmas; lastBmp = bmp
        return bmp
    }

    private fun batch(page: Int): Batch {
        while (batches.size <= page) batches += Batch()
        return batches[page]
    }

    fun draw(
        canvas: android.graphics.Canvas, layer: GoogleVehicleLayer,
        lat: Double, lng: Double, zoom: Float, bearing: Float,
        w: Float, h: Float, density: Float,
    ) {
        val n = layer.count
        if (n == 0 && layer.stops.isEmpty()) return
        val wx = layer.wx
        val wy = layer.wy
        val colors = layer.color
        val labels = layer.label
        val hl = layer.hl
        val ext = extrapolationMs(layer)

        val world = 256.0 * 2.0.pow(zoom.toDouble()) * density   // šířka světa v px
        val cwx = (lng + 180.0) / 360.0
        val sn = sin(Math.toRadians(lat)).coerceIn(-0.9999, 0.9999)
        val cwy = 0.5 - ln((1 + sn) / (1 - sn)) / (4 * PI)
        val th = Math.toRadians(bearing.toDouble())
        val c = cos(th)
        val s = sin(th)
        val cx = w / 2.0
        val cy = h / 2.0
        val r = MAX_HALF_DP * density   // nejširší štítek / 2
        val minX = -r; val maxX = w + r; val minY = -r; val maxY = h + r

        // zastávky nad vozidly, ale pod zvýrazněným vozidlem (vlak u nástupiště nesmí zakrýt zastávku)
        val stopA = layer.stopAlpha(SystemClock.elapsedRealtime())
        val drawStops = {
            val icons = layer.stopIcons
            val sts = layer.stops
            if (sts.isNotEmpty() && icons != null && stopA > 0.004f) {
                stopPaint.alpha = (stopA * 255f).toInt().coerceIn(0, 255)
                val sx = layer.stopWx
                val sy = layer.stopWy
                val iconDp = stopIconDpForZoom(zoom)
                val scale = iconDp / STOP_ICON_MAX_DP
                val bw = icons.bus.width * scale
                val bh = icons.bus.height * scale
                val hw = bw / 2f
                val hh = bh / 2f
                val dstRect = android.graphics.RectF()
                for (i in sts.indices) {
                    val dx = (sx[i] - cwx) * world
                    val dy = (sy[i] - cwy) * world
                    val x = (dx * c + dy * s + cx).toFloat()
                    val y = (-dx * s + dy * c + cy).toFloat()
                    if (x < -hw || x > w + hw || y < -hh || y > h + hh) continue
                    val bmp = if (sts[i].kind == StopKind.RAIL) icons.rail else icons.bus
                    dstRect.set(x - hw, y - hh, x + hw, y + hh)
                    canvas.drawBitmap(bmp, null, dstRect, stopPaint)
                }
            }
        }
        if (n == 0) { drawStops(); return }
        val fade = layer.fade
        fade.tick(SystemClock.elapsedRealtime(), layer.labelsPending)
        val appear = fade.appearAlpha()
        if (appear <= 0f) { drawStops(); return }

        atlas.beginFrame()
        drawStops()
        if (!fade.switching()) {
            // běžný snímek: jeden průchod; v režimu teček se (doznívající) štítky ignorují
            pass(canvas, if (fade.dotMode) PASS_DOTS else PASS_ALL, appear, layer, cwx, cwy, world, c, s, cx, cy, minX, maxX, minY, maxY)
        } else {
            // přechod tečky ↔ štítky: vozidla bez štítku zůstávají tečkou, ostatní se prolínají
            pass(canvas, PASS_DOTS_UNLABELED, appear, layer, cwx, cwy, world, c, s, cx, cy, minX, maxX, minY, maxY)
            pass(canvas, PASS_DOTS_LABELED, appear * fade.dotAlpha(), layer, cwx, cwy, world, c, s, cx, cy, minX, maxX, minY, maxY)
            pass(canvas, PASS_LABELS, appear * fade.labelAlpha(), layer, cwx, cwy, world, c, s, cx, cy, minX, maxX, minY, maxY)
        }

        val hlLayer = appear < 0.996f && hl in 0 until n
        if (hlLayer) canvas.saveLayerAlpha(null, (appear * 255f).toInt())
        // zvýrazněné vozidlo navrch (pulzující štítek, nebo větší tečka)
        if (hl in 0 until n) {
            val dx = (wx[hl] + layer.vwx[hl] * ext - cwx) * world
            val dy = (wy[hl] + layer.vwy[hl] * ext - cwy) * world
            val x = (dx * c + dy * s + cx).toFloat()
            val y = (-dx * s + dy * c + cy).toFloat()
            val bmp = labels[hl]
            if (bmp != null) {
                canvas.drawBitmap(bmp, x - bmp.width / 2f, y - bmp.height / 2f, bmpPaint)
            } else {
                val rr = HL_DP / 2 * density
                hlPaint.color = RING_COLOR
                canvas.drawCircle(x, y, rr, hlPaint)
                hlPaint.color = colors[hl]
                canvas.drawCircle(x, y, rr - RING_DP * density, hlPaint)
            }
        }
        if (hlLayer) canvas.restore()
    }

    /**
     * Jeden průchod vozidel (bez zvýrazněného) jako sprite batch. Průhledné štítky přes vrstvu (saveLayerAlpha),
     * aby se přes sebe neprosvítaly; tečky jen alfou paintu (malé, skoro se nepřekrývají, offscreen vrstva přes
     * celou obrazovku je zbytečná práce navíc). Obojí jen během animace.
     */
    private fun pass(
        canvas: android.graphics.Canvas, mode: Int, alpha: Float, layer: GoogleVehicleLayer,
        cwx: Double, cwy: Double, world: Double, c: Double, s: Double, cx: Double, cy: Double,
        minX: Float, maxX: Float, minY: Float, maxY: Float,
    ) {
        if (alpha <= 0.004f) return
        val n = layer.count
        val wx = layer.wx
        val wy = layer.wy
        val vwx = layer.vwx
        val vwy = layer.vwy
        val ext = extrapolationMs(layer)
        val colors = layer.color
        val lates = layer.late
        val christmases = layer.christmas
        val labels = layer.label
        val hl = layer.hl
        for (b in batches) b.reset()
        val a255 = (alpha * 255f).toInt().coerceIn(0, 255)
        val translucent = a255 < 254
        val saved = translucent && (mode == PASS_LABELS || mode == PASS_ALL)
        val paintAlpha = if (translucent && !saved) a255 else 255
        if (saved) canvas.saveLayerAlpha(null, a255)
        bmpPaint.alpha = paintAlpha
        for (i in 0 until n) {
            if (i == hl) continue
            val lb = labels[i]
            // průchod, do kterého vozidlo nepatří, nic nepočítá
            when (mode) {
                PASS_DOTS_UNLABELED -> if (lb != null) continue
                PASS_DOTS_LABELED, PASS_LABELS -> if (lb == null) continue
            }
            val mv = vwx[i] != 0.0 || vwy[i] != 0.0
            val dx = (wx[i] + vwx[i] * ext - cwx) * world
            val dy = (wy[i] + vwy[i] * ext - cwy) * world
            // otočení mapy (bearing): bod na východě je při bearing 90° nahoře
            val x = (dx * c + dy * s + cx).toFloat()
            val y = (-dx * s + dy * c + cy).toFloat()
            // ořez před výběrem bitmapy (hranice nezávisí na bitmapě): tečka se hledá v cache jen pro viditelná vozidla
            if (x < minX || x > maxX || y < minY || y > maxY) continue
            val bmp = when (mode) {
                PASS_ALL -> lb ?: dotFor(colors[i], lates[i], christmases[i])
                PASS_LABELS -> lb!!
                else -> dotFor(colors[i], lates[i], christmases[i])
            }
            // stojící vozidlo na celé pixely => bitmapa 1:1, ostrá; jedoucí sub-pixelově (zaokrouhlení by pomalý vůz
            // posouvalo po skocích o pixel, viditelné trhání)
            val left = if (mv) x - bmp.width / 2f else Math.round(x - bmp.width / 2f).toFloat()
            val top = if (mv) y - bmp.height / 2f else Math.round(y - bmp.height / 2f).toFloat()
            val sp = atlas.get(bmp)
            if (sp == null) { canvas.drawBitmap(bmp, left, top, bmpPaint); continue }   // (atlas plný) výjimečně zvlášť
            batch(sp.page).add(left, top, sp)
        }
        for (p in batches.indices) {
            val b = batches[p]
            if (b.quads > 0) {
                val paint = atlas.paint(p)
                paint.alpha = paintAlpha
                b.draw(canvas, paint)
            }
        }
        bmpPaint.alpha = 255
        if (saved) canvas.restore()
    }

    /** Čtyřúhelníky jedné stránky atlasu (pozice, souřadnice v atlasu, indexy trojúhelníků). */
    private class Batch {
        var quads = 0
        private var verts = FloatArray(0)
        private var texs = FloatArray(0)
        private var idx = ShortArray(0)

        fun reset() { quads = 0 }

        fun add(left: Float, top: Float, sp: SpriteAtlas.Sprite) {
            if (quads >= MAX_QUADS) return
            if (verts.size < (quads + 1) * 8) grow()
            val v = quads * 8
            val right = left + sp.w
            val bottom = top + sp.h
            verts[v] = left; verts[v + 1] = top
            verts[v + 2] = right; verts[v + 3] = top
            verts[v + 4] = right; verts[v + 5] = bottom
            verts[v + 6] = left; verts[v + 7] = bottom
            texs[v] = sp.u; texs[v + 1] = sp.v
            texs[v + 2] = sp.u + sp.w; texs[v + 3] = sp.v
            texs[v + 4] = sp.u + sp.w; texs[v + 5] = sp.v + sp.h
            texs[v + 6] = sp.u; texs[v + 7] = sp.v + sp.h
            quads++
        }

        private fun grow() {
            val q = maxOf(256, quads * 2)
            verts = verts.copyOf(q * 8)
            texs = texs.copyOf(q * 8)
            val old = idx.size / 6
            idx = idx.copyOf(q * 6)
            for (i in old until q) {   // indexy jsou pro daný čtyřúhelník pořád stejné
                val b = i * 4; val o = i * 6
                idx[o] = b.toShort(); idx[o + 1] = (b + 1).toShort(); idx[o + 2] = (b + 2).toShort()
                idx[o + 3] = b.toShort(); idx[o + 4] = (b + 2).toShort(); idx[o + 5] = (b + 3).toShort()
            }
        }

        fun draw(canvas: android.graphics.Canvas, paint: Paint) {
            canvas.drawVertices(
                android.graphics.Canvas.VertexMode.TRIANGLES, quads * 8, verts, 0, texs, 0,
                null, 0, idx, 0, quads * 6, paint
            )
        }

        private companion object {
            const val MAX_QUADS = 16_000   // 4 vrcholy na čtyřúhelník, indexy jsou 16bitové
        }
    }

    private companion object {
        const val PASS_ALL = 0
        const val PASS_DOTS = 1
        const val PASS_DOTS_UNLABELED = 2
        const val PASS_DOTS_LABELED = 3
        const val PASS_LABELS = 4
        const val MAX_HALF_DP = 48f      // polovina nejširšího štítku (zvýrazněný až 84 dp) + rezerva
        const val HL_DP = 13f
        const val RING_DP = 1.2f
        const val RING_COLOR = 0xCCFFFFFF.toInt()
    }
}

/**
 * Atlas bitmap pro [DotPainter]: stránky 1024×1024, řádkové skládání. Bitmapy (štítky, tečky) jsou z cache a nemění se,
 * takže se do atlasu kopírují jen jednou; HWUI pak nahraje jen změněnou stránku (~4 MB, jednou za čas).
 */
private class SpriteAtlas {
    class Sprite(val page: Int, val u: Float, val v: Float, val w: Int, val h: Int)

    private val pages = ArrayList<Bitmap>()
    private val canvases = ArrayList<android.graphics.Canvas>()
    private val paints = ArrayList<Paint>()
    private val sprites = java.util.IdentityHashMap<Bitmap, Sprite>()
    private var x = 0
    private var y = 0
    private var rowH = 0

    fun paint(page: Int): Paint = paints[page]

    /** Před snímkem: skoro plný atlas (hodně nových štítků za dlouhý běh) se začne plnit znovu. */
    fun beginFrame() {
        if (pages.size >= MAX_PAGES && y > PAGE / 2) reset()
    }

    fun get(b: Bitmap): Sprite? {
        sprites[b]?.let { return it }
        if (b.width + PAD > PAGE || b.height + PAD > PAGE) return null
        if (x + b.width + PAD > PAGE) { x = 0; y += rowH + PAD; rowH = 0 }
        if (pages.isEmpty() || y + b.height + PAD > PAGE) {
            if (pages.size >= MAX_PAGES) return null
            val bmp = Bitmap.createBitmap(PAGE, PAGE, Bitmap.Config.ARGB_8888)
            pages += bmp
            canvases += android.graphics.Canvas(bmp)
            paints += Paint(Paint.FILTER_BITMAP_FLAG).apply {
                shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            }
            x = 0; y = 0; rowH = 0
        }
        val page = pages.size - 1
        canvases[page].drawBitmap(b, x.toFloat(), y.toFloat(), null)
        val sp = Sprite(page, x.toFloat(), y.toFloat(), b.width, b.height)
        sprites[b] = sp
        x += b.width + PAD
        if (b.height > rowH) rowH = b.height
        return sp
    }

    private fun reset() {
        sprites.clear()
        for (p in pages) p.eraseColor(0)
        // stránky zůstávají (jen se přepíšou), plní se znovu od první
        pages.subList(1, pages.size).clear(); canvases.subList(1, canvases.size).clear(); paints.subList(1, paints.size).clear()
        x = 0; y = 0; rowH = 0
    }

    private companion object {
        const val PAGE = 1024
        const val PAD = 2                // proti prosakování sousedů při filtrování
        const val MAX_PAGES = 12         // ~48 MB; víc unikátních štítků naráz se nestává
    }
}

/**
 * O kolik ms dopočítat polohy od posledního snímku poloh ([GoogleVehicleLayer.stampNanos]) k právě kreslenému snímku.
 * Polohy se počítají jen ~30× za vteřinu, kreslí se každý snímek (60–120 Hz): bez dopočtu by vozidla poskakovala.
 */
private fun extrapolationMs(layer: GoogleVehicleLayer): Double {
    if (layer.stampNanos == 0L) return 0.0
    return ((layer.frameNanos - layer.stampNanos) / 1e6).coerceIn(0.0, MAX_EXTRAPOLATION_MS)
}

/** Delší mezeru mezi snímky poloh (tečky po 250 ms, zaseknutí) nedopočítávat, ať vůz nepřejede. */
private const val MAX_EXTRAPOLATION_MS = 300.0
