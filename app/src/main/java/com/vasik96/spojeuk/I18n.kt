package com.vasik96.spojeuk

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Texty mimo Compose (stav, toasty, notifikace sledování, hlášky z vláken na pozadí). V UI se používá `stringResource`.
 *
 * Jazyk appky se volí v systémovém nastavení (per-app language, Android 13+). Kontext aplikace ale po změně jazyka
 * (a u služby spuštěné bez aktivity) může dál vracet texty v jazyce systému, proto se jazyk čte vždy z [LocaleManager]
 * a texty berou z kontextu s touto konfigurací. Výchozí jazyk (values/) je čeština.
 */
object I18n {
    @Volatile private var app: Context? = null
    @Volatile private var cached: Pair<LocaleList, Context>? = null

    fun init(context: Context) { app = context.applicationContext }

    private fun appLocales(base: Context): LocaleList? =
        base.getSystemService(LocaleManager::class.java)?.applicationLocales?.takeUnless { it.isEmpty }

    internal fun ctx(): Context {
        val base = app ?: error("I18n.init nebyl zavolán")
        val wanted = appLocales(base) ?: return base   // jazyk podle systému: kontext aplikace sedí
        cached?.let { (l, c) -> if (l == wanted) return c }
        val conf = Configuration(base.resources.configuration).apply { setLocales(wanted) }
        val c = base.createConfigurationContext(conf)
        cached = wanted to c
        return c
    }

    /** Jazyk, ve kterém appka právě zobrazuje texty (pro formátování dat). */
    fun locale(): Locale = app?.let { ctx().resources.configuration.locales[0] } ?: Locale.forLanguageTag("cs")
}

fun tr(@StringRes id: Int): String = I18n.ctx().getString(id)

fun tr(@StringRes id: Int, vararg args: Any): String = I18n.ctx().getString(id, *args)

fun trPlural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    I18n.ctx().resources.getQuantityString(id, count, *args)
