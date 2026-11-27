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
        handleIntent(intent)
        // zkratka launcheru jen při skutečném spuštění: po obnově (ukončený proces) by se jinak provedla znovu
        if (savedInstanceState == null) AppShortcuts.handle(intent)
        setContent { SpojeApp() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        AppShortcuts.handle(intent)
    }

    /** Ťuknutí na notifikaci sledované cesty: otevřít její detail. */
    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(JourneyTrackingService.EXTRA_OPEN_TRACKED, false) == true) {
            intent.removeExtra(JourneyTrackingService.EXTRA_OPEN_TRACKED)
            JourneyTracker.openRequested.value = true
        }
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