package com.vasik96.spojeuk

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection

/*
 * Vozidla na MapLibre kreslí overlay nad mapou (VehicleCanvasOverlay, stejný jako u Google mapy), mapa o nich neví.
 * Ve stylu zůstává jen prázdná kotva: trasy a zastávky se vkládají pod ni, takže leží nad podkladem mapy
 * ve stálém pořadí bez ohledu na to, kdy se přidaly.
 */

internal const val ROUTE_ANCHOR_LAYER = "route-anchor"
private const val ROUTE_ANCHOR_SRC = "route-anchor-src"

internal fun ensureRouteAnchor(style: Style) {
    if (style.getLayer(ROUTE_ANCHOR_LAYER) != null) return
    if (style.getSource(ROUTE_ANCHOR_SRC) == null) {
        style.addSource(GeoJsonSource(ROUTE_ANCHOR_SRC, FeatureCollection.fromFeatures(emptyList<Feature>())))
    }
    // bez výchozích přechodů stylu (prolínání trasy a zastávek si vrstvy nastavují samy)
    style.transition = TransitionOptions(0, 0, false)
    style.addLayer(CircleLayer(ROUTE_ANCHOR_LAYER, ROUTE_ANCHOR_SRC))
}
