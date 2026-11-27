package com.vasik96.spojeuk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Poslední vydaná verze aplikace (GitHub release). */
internal data class AppRelease(
    val version: String,
    /** Stránka releasu (odkaz na stažení APK). */
    val url: String,
    val publishedMs: Long,
    /** Přímý odkaz na APK z assetů releasu (stažení v aplikaci), null = release APK nemá. */
    val apkUrl: String? = null,
    /** Velikost APK v B (0 = neznámá). */
    val apkSize: Long = 0L,
)

/**
 * Kontrola nové verze aplikace přes GitHub Releases (veřejné API, bez přihlášení).
 *
 * Upozornění po spuštění ([shouldNotify]) se ukáže jen na verzi, která vyšla PO sestavení nainstalovaného APK:
 * vlastní testovací build (debug i release) je vždy novější než poslední release, takže neotravuje, i když má
 * v gradle.properties ještě starší číslo verze. Debug build automaticky nekontroluje vůbec.
 */
internal object UpdateChecker {
    private const val LATEST_URL = "https://api.github.com/repos/Vasik96/spojeuk/releases/latest"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Čas sestavení nainstalovaného APK (versionCode = sekundy od epochy při buildu). */
    val buildTimeMs: Long get() = BuildConfig.VERSION_CODE.toLong() * 1000L

    suspend fun fetchLatest(): AppRelease = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val o = JSONObject(resp.body.string())
            val assets = o.optJSONArray("assets")
            val apk = assets?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                ?.firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            AppRelease(
                version = o.getString("tag_name").removePrefix("v").trim(),
                url = o.optString("html_url").ifEmpty { "https://github.com/Vasik96/spojeuk/releases/latest" },
                publishedMs = runCatching { Instant.parse(o.getString("published_at")).toEpochMilli() }.getOrDefault(0L),
                apkUrl = apk?.optString("browser_download_url")?.takeIf { it.startsWith("https://") },
                apkSize = apk?.optLong("size", 0L) ?: 0L,
            )
        }
    }

    /**
     * Porovná verze po číselných částech ("6.0.10" > "6.0.9", "6.1" > "6.0.4"); nečíselné přípony se ignorují.
     * Kladné = [a] je novější.
     */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-', '+')
            .map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(release: AppRelease, installedVersion: String): Boolean =
        compareVersions(release.version, installedVersion) > 0

    /** Upozornit po spuštění: novější verze, která vyšla až po sestavení tohoto APK. */
    fun shouldNotify(release: AppRelease, installedVersion: String, buildTimeMs: Long): Boolean =
        isNewer(release, installedVersion) && release.publishedMs > buildTimeMs
}
