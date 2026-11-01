package com.vasik96.spojeuk

import android.content.Context
import java.io.File

/**
 * Jediné místo, kde se zdroje poloh zapojují. Nový zdroj:
 *  1. napiš třídu implementující [VehicleSource],
 *  2. přidej ji do seznamu níž (a nastav jí `priority`).
 * Slučování, štítek "Zdroj:", varování i párování se spoji se přizpůsobí samo.
 */
object VehicleSources {
    fun create(context: Context?): List<VehicleSource> = listOf(
        DpmulSource(),
        PortaboSource(snapshotFile = File(context?.filesDir ?: File("/tmp"), "last_seen.json")),
    )
}
