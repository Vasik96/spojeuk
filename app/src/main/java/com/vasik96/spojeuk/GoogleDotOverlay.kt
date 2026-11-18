package com.vasik96.spojeuk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    Spacer(
        modifier.drawBehind {
            layer.version.intValue                     // odběr změn vozidel
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
    private var lastBmp: Bitmap? = null

    private fun dotFor(color: Int, late: Boolean): Bitmap {
        if (color == lastColor && late == lastLate) lastBmp?.let { return it }
        return dotBitmap(context, color, late).also { lastColor = color; lastLate = late; lastBmp = it }
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
        if (n == 0) return
        val wx = layer.wx
        val wy = layer.wy
        val colors = layer.color
        val lates = layer.late
        val labels = layer.label
        val hl = layer.hl

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

        atlas.beginFrame()
        for (b in batches) b.reset()

        for (i in 0 until n) {
            if (i == hl) continue
            val dx = (wx[i] - cwx) * world
            val dy = (wy[i] - cwy) * world
            // otočení mapy (bearing): bod na východě je při bearing 90° nahoře
            val x = (dx * c + dy * s + cx).toFloat()
            val y = (-dx * s + dy * c + cy).toFloat()
            if (x < minX || x > maxX || y < minY || y > maxY) continue
            val bmp = labels[i] ?: dotFor(colors[i], lates[i])
            // celé pixely => bitmapa 1:1, ostrá
            val left = Math.round(x - bmp.width / 2f).toFloat()
            val top = Math.round(y - bmp.height / 2f).toFloat()
            val sp = atlas.get(bmp)
            if (sp == null) { canvas.drawBitmap(bmp, left, top, bmpPaint); continue }   // (atlas plný) výjimečně zvlášť
            batch(sp.page).add(left, top, sp)
        }
        for (p in batches.indices) {
            val b = batches[p]
            if (b.quads > 0) b.draw(canvas, atlas.paint(p))
        }

        // zvýrazněné vozidlo navrch (pulzující štítek, nebo větší tečka)
        if (hl in 0 until n) {
            val dx = (wx[hl] - cwx) * world
            val dy = (wy[hl] - cwy) * world
            val x = (dx * c + dy * s + cx).toFloat()
            val y = (-dx * s + dy * c + cy).toFloat()
            labels[hl]?.let { bmp ->
                canvas.drawBitmap(bmp, x - bmp.width / 2f, y - bmp.height / 2f, bmpPaint)
                return
            }
            val rr = HL_DP / 2 * density
            hlPaint.color = RING_COLOR
            canvas.drawCircle(x, y, rr, hlPaint)
            hlPaint.color = colors[hl]
            canvas.drawCircle(x, y, rr - RING_DP * density, hlPaint)
        }
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
