package com.celica.dash.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.celica.dash.location.Fix
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory

/**
 * A small flat map that just follows the car, heading-up, with no controls - for the corner
 * of another screen. Same tiles and style as the big one.
 */
@Composable
fun MiniMap(fix: Fix?, modifier: Modifier = Modifier, zoom: Double = 15.0) {
    val ctx = LocalContext.current
    val tiles = remember { tilesFile(ctx) }
    val have = remember(tiles) { tiles.exists() && tiles.length() > 0 }
    Box(modifier) {
        if (!have) {
            Text("NO MAP DATA", style = Label, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        val lifecycle = LocalLifecycleOwner.current
        val mapView = remember { MapLibre.getInstance(ctx); MapView(ctx) }
        var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
        DisposableEffect(lifecycle) {
            val obs = LifecycleEventObserver { _, e ->
                when (e) {
                    Lifecycle.Event.ON_START -> mapView.onStart(); Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause(); Lifecycle.Event.ON_STOP -> mapView.onStop()
                    Lifecycle.Event.ON_DESTROY -> mapView.onDestroy(); else -> {}
                }
            }
            lifecycle.lifecycle.addObserver(obs)
            onDispose { lifecycle.lifecycle.removeObserver(obs); mapView.onDestroy() }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    getMapAsync { map ->
                        mapRef = map
                        val style = MapLayers.styleJson(ctx, tiles)
                        // the raised buildings pop in and out as the tiles arrive, which on a map
                        // this small and always flat reads as a flicker; it is looking down anyway
                        map.setStyle(Style.Builder().fromJson(style)) { st ->
                            st.getLayer("buildings-3d")?.setProperties(PropertyFactory.visibility(Property.NONE))
                        }
                        map.uiSettings.apply { isAttributionEnabled = false; isLogoEnabled = false; isCompassEnabled = false; setAllGesturesEnabled(false) }
                        map.cameraPosition = CameraPosition.Builder().target(LatLng(com.celica.dash.data.Region.homeLat, com.celica.dash.data.Region.homeLon)).zoom(com.celica.dash.data.Region.homeZoom - 0.5).build()
                    }
                }
            },
            update = {
                val map = mapRef ?: return@AndroidView
                val f = fix ?: return@AndroidView
                map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(f.lat, f.lon)).zoom(zoom).bearing(f.bearing.toDouble()).tilt(0.0).build()), 900)
            },
        )
        Canvas(Modifier.align(Alignment.Center).size(18.dp)) {
            val c = center
            val p = Path().apply {
                moveTo(c.x, c.y - size.height * 0.46f); lineTo(c.x + size.width * 0.38f, c.y + size.height * 0.42f)
                lineTo(c.x, c.y + size.height * 0.18f); lineTo(c.x - size.width * 0.38f, c.y + size.height * 0.42f); close()
            }
            drawPath(p, Ink.bg, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            drawPath(p, Ink.red, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        }
        if (fix == null) Text("NO FIX", style = Label, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
    }
}
