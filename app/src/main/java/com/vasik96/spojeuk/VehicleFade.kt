package com.vasik96.spojeuk

import kotlin.math.min

/**
 * Prolínání vozidel na mapě (Google i MapLibre): plynulé objevení po otevření mapy a přechod mezi tečkami
 * a štítky s číslem linky.
 *
 * Průběh se posouvá po snímcích ([tick]) a jeden snímek posune animaci nejvýš o [MAX_STEP_MS]. Při lagu (Praha,
 * tisíce vozidel, kreslení štítků) se tak animace jen zpomalí, ale je vidět. S časem podle hodin by proběhla
 * celá mezi dvěma pomalými snímky. Přechod na štítky navíc chvíli počká, než jsou štítky v záběru nakreslené.
 */
internal class VehicleFade {
    private var appearT = -1f       // <0 = ještě nezačalo (vozidla nejsou vidět), 0..1 průběh
    private var switchT = 1f        // 0 = právě přepnuto, 1 = přechod dokončen
    private var holdMs = 0L
    private var lastTick = 0L
    var dotMode = true
        private set

    /** První vozidla na mapě: začne objevování (jen jednou). */
    fun startAppear(): Boolean {
        if (appearT >= 0f) return false
        appearT = 0f
        return true
    }

    fun resetAppear() { appearT = -1f }

    val appeared get() = appearT > 0f

    /** Změna režimu; uprostřed přechodu se plynule otočí zpět (bez skoku průhlednosti). */
    fun setDotMode(dot: Boolean): Boolean {
        if (dot == dotMode) return false
        dotMode = dot
        switchT = 1f - switchT
        holdMs = 0L
        return true
    }

    /** Snap bez animace (první nastavení podle zoomu, před zobrazením vozidel). */
    fun initDotMode(dot: Boolean) {
        dotMode = dot
        switchT = 1f
    }

    /**
     * Jednou za snímek. [labelsPending] = některé štítky v záběru ještě nejsou nakreslené: přechod na štítky
     * počká (nejvýš [HOLD_MAX_MS]), aby se štítky prolnuly a neobjevovaly se až po animaci.
     */
    fun tick(now: Long, labelsPending: Boolean = false) {
        val dt = if (lastTick == 0L) 16L else (now - lastTick).coerceIn(0L, MAX_STEP_MS)
        lastTick = now
        if (appearT >= 0f && appearT < 1f) appearT = min(1f, appearT + dt.toFloat() / APPEAR_MS)
        if (switchT < 1f) {
            if (labelsPending && !dotMode && holdMs < HOLD_MAX_MS) holdMs += dt
            else switchT = min(1f, switchT + dt.toFloat() / SWITCH_MS)
        }
    }

    fun appearAlpha(): Float = if (appearT < 0f) 0f else ease(appearT)

    fun switching() = switchT < 1f

    fun dotAlpha(): Float = ease(if (dotMode) switchT else 1f - switchT)
    fun labelAlpha(): Float = ease(if (dotMode) 1f - switchT else switchT)

    fun animating() = switching() || (appearT >= 0f && appearT < 1f)

    private fun ease(t: Float) = t * t * (3f - 2f * t)   // smoothstep

    companion object {
        const val APPEAR_MS = 600L
        const val SWITCH_MS = 350L
        const val MAX_STEP_MS = 34L     // nejvýš ~2 snímky na jeden (pomalý) snímek
        const val HOLD_MAX_MS = 600L
    }
}
