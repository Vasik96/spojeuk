package com.vasik96.spojeuk

import android.app.Application
import org.maplibre.android.MapLibre

class SpojeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}