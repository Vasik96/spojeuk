package com.vasik96.spojeuk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

object TouchDiag { @Volatile var lastUpAt = 0L }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { SpojeApp() }
    }

    // diagnostika zpoždění: jak dlouho událost čekala ve frontě hlavního vlákna a kdy přesně byl prst zvednut
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
            val now = android.os.SystemClock.uptimeMillis()
            TouchDiag.lastUpAt = android.os.SystemClock.elapsedRealtime()
            android.util.Log.d("SheetLag", "UP: fronta hlavního vlákna ${now - ev.eventTime} ms")
        }
        return super.dispatchTouchEvent(ev)
    }
}