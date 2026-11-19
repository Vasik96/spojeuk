package com.vasik96.spojeuk

/**
 * Časové prolínání vozidel na mapě (Google i MapLibre): plynulé objevení po otevření mapy a přechod mezi tečkami
 * a štítky s číslem linky. Čas v ms (SystemClock.elapsedRealtime).
 */
internal class VehicleFade {
    private var appearAt = Long.MIN_VALUE   // MIN = ještě nezačalo (vozidla nejsou vidět)
    private var switchAt = Long.MIN_VALUE / 2
    var dotMode = true
        private set

    /** První vozidla na mapě: začne objevování (jen jednou). */
    fun startAppear(now: Long) {
        if (appearAt == Long.MIN_VALUE) appearAt = now
    }

    fun resetAppear() { appearAt = Long.MIN_VALUE }

    /** Změna režimu; uprostřed přechodu se plynule otočí zpět (bez skoku průhlednosti). */
    fun setDotMode(dot: Boolean, now: Long): Boolean {
        if (dot == dotMode) return false
        val t = switchProgress(now)
        dotMode = dot
        switchAt = now - ((1f - t) * SWITCH_MS).toLong()
        return true
    }

    /** Snap bez animace (první nastavení podle zoomu, před zobrazením vozidel). */
    fun initDotMode(dot: Boolean) {
        dotMode = dot
        switchAt = Long.MIN_VALUE / 2
    }

    fun appearAlpha(now: Long): Float =
        if (appearAt == Long.MIN_VALUE) 0f else ease(((now - appearAt).toFloat() / APPEAR_MS).coerceIn(0f, 1f))

    /** 0 = právě přepnuto, 1 = přechod dokončen. */
    fun switchProgress(now: Long): Float = ((now - switchAt).toFloat() / SWITCH_MS).coerceIn(0f, 1f)

    fun switching(now: Long) = now - switchAt < SWITCH_MS

    fun dotAlpha(now: Long): Float = ease(if (dotMode) switchProgress(now) else 1f - switchProgress(now))
    fun labelAlpha(now: Long): Float = ease(if (dotMode) 1f - switchProgress(now) else switchProgress(now))

    fun animating(now: Long) = switching(now) || (appearAt != Long.MIN_VALUE && now - appearAt < APPEAR_MS)

    private fun ease(t: Float) = t * t * (3f - 2f * t)   // smoothstep

    companion object {
        const val APPEAR_MS = 600L
        const val SWITCH_MS = 350L
    }
}
