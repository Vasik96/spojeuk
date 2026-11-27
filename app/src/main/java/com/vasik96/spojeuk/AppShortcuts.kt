package com.vasik96.spojeuk

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Zkratky launcheru (dlouhé podržení ikony): statická "Mapa" (res/xml/shortcuts.xml) a dynamické spojení
 * "Odkud → Kam" podle posledního předvyplnění po spuštění. Klepnutí na spojení vyplní pole a rovnou hledá.
 */
object AppShortcuts {
    const val ACTION_OPEN_MAP = "com.vasik96.spojeuk.OPEN_MAP"
    const val ACTION_SEARCH = "com.vasik96.spojeuk.SEARCH"
    private const val EXTRA_FROM = "from"
    private const val EXTRA_TO = "to"
    private const val ID_MAP = "map"
    private const val ID_ROUTE = "prefilled_route"
    private const val PREFS = "app_shortcuts"

    sealed interface Request {
        data object OpenMap : Request
        class Search(val fromName: String, val toName: String) : Request
    }

    /** Požadavek ze zkratky, který si vyzvedne UI (SpojeApp). */
    val pending = MutableStateFlow<Request?>(null)

    /** Intent ze zkratky => [pending]. Vrací true, když šlo o zkratku. */
    fun handle(intent: Intent?): Boolean {
        val req = when (intent?.action) {
            ACTION_OPEN_MAP -> Request.OpenMap
            ACTION_SEARCH -> {
                val from = intent.getStringExtra(EXTRA_FROM)
                val to = intent.getStringExtra(EXTRA_TO)
                if (from.isNullOrBlank() || to.isNullOrBlank()) return false
                Request.Search(from, to)
            }
            else -> return false
        }
        pending.value = req
        return true
    }

    /** Launcher se učí, které zkratky se používají (řazení nabídky). */
    fun reportUsed(ctx: Context, req: Request) {
        val id = if (req is Request.OpenMap) ID_MAP else ID_ROUTE
        runCatching { ShortcutManagerCompat.reportShortcutUsed(ctx, id) }
    }

    /** Zkratka spojení podle předvyplnění; stejná dvojice se znovu neposílá (zbytečné volání při každém startu). */
    fun publishRoute(ctx: Context, fromName: String, toName: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "$fromName|$toName|${ctx.resources.configuration.locales[0].language}"
        if (prefs.getString("route", null) == key) return
        val info = ShortcutInfoCompat.Builder(ctx, ID_ROUTE)
            .setShortLabel(stopLabel(toName))
            .setLongLabel(tr(R.string.shortcut_route_long, stopLabel(fromName), stopLabel(toName)))
            .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_shortcut_route))
            .setIntent(
                Intent(ctx, MainActivity::class.java)
                    .setAction(ACTION_SEARCH)
                    .putExtra(EXTRA_FROM, fromName)
                    .putExtra(EXTRA_TO, toName)
            )
            .build()
        val ok = runCatching { ShortcutManagerCompat.pushDynamicShortcut(ctx, info) }.isSuccess
        if (ok) prefs.edit { putString("route", key) }
    }
}
