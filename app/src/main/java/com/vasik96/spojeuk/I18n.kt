package com.vasik96.spojeuk

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Texty mimo Compose (stav, toasty, hlášky z vláken na pozadí). V UI se používá `stringResource`.
 *
 * Jazyk appky se volí v systémovém nastavení (per-app language, Android 13+); systém ho promítne do konfigurace
 * celého procesu, takže kontext aplikace vrací texty ve zvoleném jazyce. Výchozí jazyk (values/) je čeština.
 */
object I18n {
    @Volatile private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    internal fun ctx(): Context = app ?: error("I18n.init nebyl zavolán")

    /** Jazyk, ve kterém appka právě zobrazuje texty (pro formátování dat). */
    fun locale(): Locale = app?.resources?.configuration?.locales?.get(0) ?: Locale.forLanguageTag("cs")
}

fun tr(@StringRes id: Int): String = I18n.ctx().getString(id)

fun tr(@StringRes id: Int, vararg args: Any): String = I18n.ctx().getString(id, *args)

fun trPlural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    I18n.ctx().resources.getQuantityString(id, count, *args)
