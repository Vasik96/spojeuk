package com.vasik96.spojeuk

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.android.gms.maps.MapsInitializer
import org.maplibre.android.MapLibre

class SpojeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        MapLibre.getInstance(this)
        // Google Maps: načtení modulu a rendereru z Play Services trvá stovky ms; uděláme to na pozadí hned při startu,
        // ať první GoogleMap v UI nic takového nepočítá (MapLibre se předehřívá řádkem výše)
        Thread {
            runCatching { MapsInitializer.initialize(applicationContext, MapsInitializer.Renderer.LATEST) { } }
        }.apply { name = "gmaps-init"; isDaemon = true; start() }
        val repo = TransitRepository.get(this)
        // Okamžitě při startu aplikace inicializovat repozitář a začít stahovat polohy vozidel
        repo.prewarmVehiclesAsync()
        registerNetworkCallback(repo)
    }

    /**
     * Když appka nastartuje bez (hotového) připojení nebo se přepne síť, první dotaz selže a polohy zůstanou
     * zastaralé. Jakmile je internet k dispozici, zahodíme stará spojení a stáhneme polohy hned.
     */
    private fun registerNetworkCallback(repo: TransitRepository) {
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    repo.onNetworkAvailable()
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    // síť teprve teď prošla ověřením internetu (captive portal / pomalé připojení po startu)
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        if (!validated) { validated = true; repo.onNetworkAvailable() }
                    } else validated = false
                }

                override fun onLost(network: Network) {
                    validated = false
                }

                @Volatile private var validated = false
            })
        }
    }
}