package com.vasik96.spojeuk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Tmavá varianta dlaždic Mapy.com. API žádnou tmavou sadu nemá a rastrová vrstva MapLibre umí jen jas / kontrast /
 * sytost / otočení odstínu: dřívější inverze jasu dělala ze zelených lesů se stínováním terénu „satelitní“ vzhled.
 * Dlaždice se proto přebarví při stažení vlastní tmavou paletou (barvy jako tmavá Google mapa v aplikaci).
 *
 * Tmavé dlaždice mají v URL navíc [DARK_PARAM]: interceptor ho před odesláním odebere a odpověď přebarví. MapLibre si
 * tak do své cache ukládá už tmavé dlaždice pod jiným klíčem než světlé.
 *
 * Mapy.com posílá PNG s paletou (max. 256 barev): stačí přepsat chunk PLTE (+ CRC), obraz se nedekóduje ani nekóduje.
 */
internal object MapyDarkTiles {
    const val DARK_PARAM = "spojeDark"

    /** HTTP klient pro MapLibre: jako výchozí klient MapLibre (20 požadavků na host) + přebarvení tmavých dlaždic. */
    fun mapLibreHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 20 })
        .addInterceptor(Interceptor { chain -> intercept(chain) })
        .build()

    private fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.queryParameter(DARK_PARAM) == null) return chain.proceed(req)
        val url = req.url.newBuilder().removeAllQueryParameters(DARK_PARAM).build()
        val resp = chain.proceed(req.newBuilder().url(url).build())
        val body = resp.body
        // 304 (MapLibre si ověřuje platnost dlaždice v cache) nebo chyba: beze změny
        if (resp.code != 200) return resp
        val type = body.contentType()
        val bytes = body.bytes()
        val dark = runCatching { recolor(bytes) }.getOrNull() ?: bytes
        return resp.newBuilder()
            .removeHeader("Content-Length")
            .body(dark.toResponseBody(type))
            .build()
    }

    /** Tmavá verze obrázku dlaždice; null = neznámý formát (pošle se původní). */
    fun recolor(png: ByteArray): ByteArray? = recolorPalette(png) ?: recolorPixels(png)

    // ── PNG s paletou: přepis PLTE ──

    private val PNG_SIG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    private fun recolorPalette(png: ByteArray): ByteArray? {
        if (png.size < 8 || !png.copyOfRange(0, 8).contentEquals(PNG_SIG)) return null
        val out = png.copyOf()
        var i = 8
        while (i + 12 <= out.size) {
            val len = readInt(out, i)
            if (len < 0 || i + 12 + len > out.size) return null
            val t = i + 4
            if (out[t] == 'P'.code.toByte() && out[t + 1] == 'L'.code.toByte() &&
                out[t + 2] == 'T'.code.toByte() && out[t + 3] == 'E'.code.toByte()
            ) {
                val d = i + 8
                var p = 0
                while (p + 2 < len) {
                    val rgb = ((out[d + p].toInt() and 0xFF) shl 16) or ((out[d + p + 1].toInt() and 0xFF) shl 8) or
                        (out[d + p + 2].toInt() and 0xFF)
                    val dk = darkRgb(rgb)
                    out[d + p] = (dk shr 16).toByte(); out[d + p + 1] = (dk shr 8).toByte(); out[d + p + 2] = dk.toByte()
                    p += 3
                }
                val crc = CRC32().apply { update(out, t, 4 + len) }.value
                writeInt(out, d + len, crc.toInt())
                return out
            }
            if (out[t] == 'I'.code.toByte() && out[t + 1] == 'D'.code.toByte() &&
                out[t + 2] == 'A'.code.toByte() && out[t + 3] == 'T'.code.toByte()
            ) return null   // data bez palety (truecolor PNG)
            i += 12 + len
        }
        return null
    }

    private fun readInt(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun writeInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }

    // ── pojistka: jiný formát (PNG bez palety, JPEG) => po pixelech ──

    private fun recolorPixels(img: ByteArray): ByteArray? {
        val src = BitmapFactory.decodeByteArray(img, 0, img.size) ?: return null
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        src.recycle()
        for (k in px.indices) {
            val c = px[k]
            px[k] = (c and 0xFF000000.toInt()) or darkRgb(c and 0xFFFFFF)
        }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream(img.size)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    // ── barevná transformace ──

    /**
     * Kotvy (světlá barva Mapy.com -> tmavá). Ostatní barvy (vyhlazené hrany, stínování terénu) se posunou o vážený
     * průměr posunů kotev v OKLab (váha 1/vzdálenost³, rozdíly odstínu váží víc než jas): přechody zůstanou plynulé
     * a stín v lese zůstane tmavší než les. Bílá je zároveň silnice i lem popisků => jen o kousek světlejší než pozadí.
     */
    private val ANCHORS = arrayOf(
        0xf1f0e5 to 0x1d2430,   // pozadí
        0xffffff to 0x343c4b,   // silnice, lem popisků
        0xe2d5b7 to 0x232a35,   // zástavba
        0xcdc3b1 to 0x2c3341,   // budovy
        0xb5aa91 to 0x363e4d,   // budovy (tmavší)
        0xcee4b2 to 0x1b2a26,   // les, zeleň
        0xa9c58c to 0x16231f,   // les ve stínu
        0x9fc4e2 to 0x0f1a2b,   // voda
        0xffcc78 to 0x7a5a33,   // hlavní silnice
        0xe8a541 to 0x5a3f22,   // okraj hlavní silnice
        0xfaef75 to 0x6d6533,   // silnice II. třídy
        0x000000 to 0xe4e8ee,   // text
        0x333333 to 0xcdd2da,
        0x555555 to 0xb3b9c3,
        0x717171 to 0x7d8593,   // koleje, šedé popisky
        0x999999 to 0x5a6270,
        0xd5d3cd to 0x2a2f39,   // nádraží, železniční plochy
        0xe0e5f2 to 0x252d3e,   // průmysl, parkoviště
        0x7fbf5a to 0x3d6b3a,   // dálnice
        0xd94a3c to 0xa8433a,   // červené štítky
        0x3b7fd0 to 0x3b6fb0,   // modré štítky, popisky vod
    )
    private val SRC = ANCHORS.map { oklab(it.first) }
    private val DELTA = ANCHORS.map { (s, d) -> oklab(d).let { o -> val a = oklab(s); doubleArrayOf(o[0] - a[0], o[1] - a[1], o[2] - a[2]) } }

    private val cache = ConcurrentHashMap<Int, Int>()

    fun darkRgb(rgb: Int): Int {
        cache[rgb]?.let { return it }
        if (cache.size > MAX_CACHE) cache.clear()   // palety dlaždic se liší jen málo, ale za dlouhý běh nekonečně
        return compute(rgb).also { cache[rgb] = it }
    }

    private const val MAX_CACHE = 65_536

    private fun compute(rgb: Int): Int {
        val lab = oklab(rgb)
        var sw = 0.0
        var dl = 0.0; var da = 0.0; var db = 0.0
        for (k in SRC.indices) {
            val s = SRC[k]
            val x = lab[0] - s[0]
            val y = (lab[1] - s[1]) * CHROMA_WEIGHT
            val z = (lab[2] - s[2]) * CHROMA_WEIGHT
            val d = sqrt(x * x + y * y + z * z) + 1e-4
            val w = 1.0 / (d * d * d)
            val dd = DELTA[k]
            sw += w; dl += w * dd[0]; da += w * dd[1]; db += w * dd[2]
        }
        return srgb(lab[0] + dl / sw, lab[1] + da / sw, lab[2] + db / sw)
    }

    private const val CHROMA_WEIGHT = 2.0

    private fun toLin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
    private fun fromLin(v: Double): Int {
        val c = v.coerceIn(0.0, 1.0)
        val s = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
        return (s * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    private fun oklab(rgb: Int): DoubleArray {
        val r = toLin((rgb shr 16) and 0xFF); val g = toLin((rgb shr 8) and 0xFF); val b = toLin(rgb and 0xFF)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    private fun srgb(L: Double, a: Double, b: Double): Int {
        val l = (L + 0.3963377774 * a + 0.2158037573 * b).let { it * it * it }
        val m = (L - 0.1055613458 * a - 0.0638541728 * b).let { it * it * it }
        val s = (L - 0.0894841775 * a - 1.2914855480 * b).let { it * it * it }
        val r = fromLin(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s)
        val g = fromLin(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s)
        val bb = fromLin(-0.0041960771 * l - 0.7034186167 * m + 1.7076127010 * s)
        return (r shl 16) or (g shl 8) or bb
    }
}
