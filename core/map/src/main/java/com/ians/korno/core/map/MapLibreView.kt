package com.ians.korno.core.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * [MapView] — обычный Android View с собственным жизненным циклом, который
 * обязательно прокидывать вручную, иначе рендерер течёт и не освобождает
 * нативные ресурсы. Здесь View привязывается к жизненному циклу композиции.
 */
@Composable
internal fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        // textureMode: карта живёт в карточках со скруглениями и внутри
        // прокручиваемых экранов, где SurfaceView не обрезается по форме.
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .logoEnabled(false)
            .compassEnabled(false)
        MapView(context, options).apply { onCreate(null) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    started = true
                    mapView.onStart()
                }

                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    mapView.onResume()
                }

                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    mapView.onPause()
                }

                Lifecycle.Event.ON_STOP -> {
                    started = false
                    mapView.onStop()
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Композиция может уйти, пока экран ещё RESUMED: доводим View
            // до STOPPED руками, чтобы onDestroy отработал из валидного состояния.
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    return mapView
}

/** Ждёт инициализации рендерера карты. */
internal suspend fun MapView.awaitMap(): MapLibreMap = suspendCancellableCoroutine { continuation ->
    getMapAsync { map ->
        if (continuation.isActive) continuation.resume(map)
    }
}

/**
 * Ждёт загрузки стиля. Колбэк MapLibre вызывается только при успехе, поэтому
 * при недоступной сети корутина просто не возобновится — карта останется
 * пустой, но экран продолжит работать.
 */
internal suspend fun MapLibreMap.awaitStyle(styleUrl: String): Style =
    suspendCancellableCoroutine { continuation ->
        setStyle(Style.Builder().fromUri(styleUrl)) { style ->
            if (continuation.isActive) continuation.resume(style)
        }
    }
