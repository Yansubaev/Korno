package com.ians.korno.core.map

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** Как карта позиционирует камеру относительно трека. */
enum class TrackMapCamera {
    /** Вписать весь трек в видимую область — для просмотра готовой активности. */
    FitTrack,

    /** Следовать за последней точкой — для экрана записи. */
    FollowLatest,
}

private const val FOLLOW_ZOOM = 16.5
private const val SINGLE_POINT_ZOOM = 15.5
private const val MIN_BOUNDS_SPAN_DEGREES = 0.0015
private const val CAMERA_ANIMATION_MILLIS = 700
private const val TOUCH_TARGET_DP = 20
private const val MAX_INSET_FRACTION = 0.75f

private class LoadedMap(val map: MapLibreMap, val style: Style)

/**
 * Общая реализация карты Korno: линия трека, маркеры, точки для коррекции и
 * управление камерой. Публичные обёртки — [TrackMap] и [EditableTrackMap].
 */
@Composable
internal fun KornoMapCanvas(
    route: List<MapPoint>,
    modifier: Modifier = Modifier,
    editablePoints: List<EditableMapPoint> = emptyList(),
    currentPoint: MapPoint? = null,
    overlay: TrackOverlay = TrackOverlay.None,
    showEndpoints: Boolean = true,
    camera: TrackMapCamera = TrackMapCamera.FitTrack,
    interactive: Boolean = true,
    bottomInset: Dp = 0.dp,
    onPointClick: ((Long) -> Unit)? = null,
) {
    val colors = trackMapColors()

    if (LocalInspectionMode.current) {
        MapCanvasPreviewStub(
            points = route.ifEmpty { editablePoints.map(EditableMapPoint::point) },
            modifier = modifier,
        )
        return
    }

    val styleUrl = currentMapStyleUrl()
    val mapView = rememberMapViewWithLifecycle()
    val density = LocalDensity.current
    val touchSlopPx = remember(density) { with(density) { TOUCH_TARGET_DP.dp.toPx() } }
    val latestOnPointClick by rememberUpdatedState(onPointClick)

    var loaded by remember { mutableStateOf<LoadedMap?>(null) }
    var styleFailed by remember { mutableStateOf(false) }
    var retryToken by remember { mutableIntStateOf(0) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var userMovedCamera by remember { mutableStateOf(false) }
    var cameraInitialized by remember { mutableStateOf(false) }

    val cameraTargets = remember(route, editablePoints) {
        route.ifEmpty { editablePoints.map(EditableMapPoint::point) }
    }

    // Ошибка загрузки приходит отдельным колбэком MapLibre, а не результатом setStyle:
    // при недоступной сети корутина ниже просто не возобновляется, и без этого
    // слушателя экран показывал бы пустой прямоугольник без объяснений.
    DisposableEffect(mapView) {
        val failListener = MapView.OnDidFailLoadingMapListener { styleFailed = true }
        val styleListener = MapView.OnDidFinishLoadingStyleListener { styleFailed = false }
        mapView.addOnDidFailLoadingMapListener(failListener)
        mapView.addOnDidFinishLoadingStyleListener(styleListener)
        onDispose {
            mapView.removeOnDidFailLoadingMapListener(failListener)
            mapView.removeOnDidFinishLoadingStyleListener(styleListener)
        }
    }

    val isOnline by rememberNetworkAvailability()

    // Вернувшаяся сеть — повод перезагрузить стиль самому: иначе пользователю
    // пришлось бы догадаться переоткрыть экран.
    LaunchedEffect(isOnline) {
        if (isOnline && styleFailed) retryToken++
    }

    LaunchedEffect(mapView, styleUrl, retryToken) {
        loaded = null
        val map = mapView.awaitMap()
        val style = map.awaitStyle(styleUrl)
        style.installTrackLayers(colors)
        loaded = LoadedMap(map, style)
    }

    LaunchedEffect(loaded, interactive) {
        val map = loaded?.map ?: return@LaunchedEffect
        map.uiSettings.apply {
            isScrollGesturesEnabled = interactive
            isZoomGesturesEnabled = interactive
            isDoubleTapGesturesEnabled = interactive
            isQuickZoomGesturesEnabled = interactive
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
            isCompassEnabled = false
            isLogoEnabled = false
            isAttributionEnabled = true
            setAttributionMargins(8, 0, 0, 8)
        }
    }

    LaunchedEffect(loaded, route, currentPoint, overlay, showEndpoints) {
        val style = loaded?.style ?: return@LaunchedEffect
        style.setRoute(route, defaultColor = colors.route)
        style.setOverlay(overlay)
        style.setMarkers(
            start = route.firstOrNull().takeIf { showEndpoints },
            finish = route.lastOrNull().takeIf { showEndpoints && currentPoint == null },
            current = currentPoint,
            highlight = overlay.highlight,
        )
    }

    LaunchedEffect(loaded, editablePoints) {
        val style = loaded?.style ?: return@LaunchedEffect
        style.setEditablePoints(editablePoints)
    }

    // Отступ снизу не должен съесть всю карту: даже под высокой шторкой
    // камере оставляем видимую полосу, иначе центр уезжает за экран.
    val bottomInsetPx = with(density) { bottomInset.toPx() }
        .coerceAtMost(viewportSize.height * MAX_INSET_FRACTION)
        .toInt()

    // Смена высоты перекрывающей панели — явный запрос нового кадра,
    // поэтому ручное смещение камеры сбрасывается.
    LaunchedEffect(bottomInsetPx) { userMovedCamera = false }

    LaunchedEffect(loaded, camera, cameraTargets, currentPoint, viewportSize, userMovedCamera, bottomInsetPx) {
        val map = loaded?.map ?: return@LaunchedEffect
        if (viewportSize == IntSize.Zero) return@LaunchedEffect

        val update = when (camera) {
            TrackMapCamera.FollowLatest -> {
                // Текущая позиция приходит из сессии раньше, чем сервис сбросит
                // точки в БД, поэтому следование не ждёт непустого маршрута.
                val target = currentPoint ?: cameraTargets.lastOrNull()
                if (userMovedCamera || target == null) return@LaunchedEffect
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(target.toLatLng())
                        .zoom(FOLLOW_ZOOM)
                        .padding(0.0, 0.0, 0.0, bottomInsetPx.toDouble())
                        .build(),
                )
            }

            TrackMapCamera.FitTrack -> {
                if (cameraTargets.isEmpty()) return@LaunchedEffect
                cameraTargets.fitUpdate(
                    paddingPx = with(density) { 40.dp.toPx() }.toInt(),
                    bottomPaddingPx = bottomInsetPx,
                )
            }
        }

        if (cameraInitialized) {
            map.easeCamera(update, CAMERA_ANIMATION_MILLIS)
        } else {
            map.moveCamera(update)
            cameraInitialized = true
        }
    }

    DisposableEffect(loaded) {
        val map = loaded?.map
        val listener = MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                userMovedCamera = true
            }
        }
        map?.addOnCameraMoveStartedListener(listener)
        onDispose { map?.removeOnCameraMoveStartedListener(listener) }
    }

    DisposableEffect(loaded, onPointClick != null) {
        val map = loaded?.map
        val listener = MapLibreMap.OnMapClickListener { latLng ->
            val callback = latestOnPointClick
            if (map == null || callback == null) {
                false
            } else {
                val id = map.pointIdAt(latLng, touchSlopPx)
                if (id != null) callback(id)
                id != null
            }
        }
        if (onPointClick != null) map?.addOnMapClickListener(listener)
        onDispose { if (onPointClick != null) map?.removeOnMapClickListener(listener) }
    }

    Box(modifier = modifier.onSizeChanged { viewportSize = it }) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.matchParentSize(),
        )
        // Без сети карта не обязательно пуста: ранее открытые участки MapLibre рисует
        // из кэша. Поэтому отсутствие сети — полоска поверх карты, а не заглушка на
        // весь экран, и только полный отказ стиля закрывает карту целиком.
        when {
            styleFailed && loaded == null -> MapStyleFailedOverlay(
                onRetry = { retryToken++ },
                modifier = Modifier.matchParentSize(),
            )
            // Снизу по центру: сверху слева живёт плашка даты активности, снизу
            // слева — атрибуция, а перекрывающая панель учтена отступом.
            !isOnline -> OfflineBanner(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomInset + 8.dp, start = 8.dp, end = 8.dp),
            )
        }
        if (camera == TrackMapCamera.FollowLatest && userMovedCamera) {
            FilledTonalIconButton(
                onClick = { userMovedCamera = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MyLocation,
                    contentDescription = stringResource(R.string.map_recenter),
                )
            }
        }
    }
}

/** Стиль карты не загрузился: показывать нечего, и есть смысл повторить попытку. */
@Composable
private fun MapStyleFailedOverlay(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.map_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.map_retry)) }
        }
    }
}

/**
 * Сети нет. Карта под полоской остаётся видимой: то, что уже побывало в кэше,
 * рисуется и офлайн, и закрывать это заглушкой было бы хуже, чем объяснить.
 */
@Composable
private fun OfflineBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(100))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.map_offline),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Ищет точку трека под нажатием, с запасом в палец вокруг тапа. */
private fun MapLibreMap.pointIdAt(latLng: LatLng, touchSlopPx: Float): Long? {
    val screenPoint = projection.toScreenLocation(latLng)
    val tapArea = RectF(
        screenPoint.x - touchSlopPx,
        screenPoint.y - touchSlopPx,
        screenPoint.x + touchSlopPx,
        screenPoint.y + touchSlopPx,
    )
    return queryRenderedFeatures(
        tapArea,
        POINTS_SELECTED_LAYER,
        POINTS_OUTLIER_LAYER,
        POINTS_PAUSED_LAYER,
        POINTS_LAYER,
    ).firstNotNullOfOrNull { feature -> feature.getNumberProperty(PROPERTY_POINT_ID)?.toLong() }
}

private fun List<MapPoint>.fitUpdate(paddingPx: Int, bottomPaddingPx: Int) = if (size < 2) {
    CameraUpdateFactory.newCameraPosition(
        CameraPosition.Builder()
            .target(first().toLatLng())
            .zoom(SINGLE_POINT_ZOOM)
            .padding(0.0, 0.0, 0.0, bottomPaddingPx.toDouble())
            .build(),
    )
} else {
    val north = maxOf { it.latitude }
    val south = minOf { it.latitude }
    val east = maxOf { it.longitude }
    val west = minOf { it.longitude }
    if (north - south < MIN_BOUNDS_SPAN_DEGREES && east - west < MIN_BOUNDS_SPAN_DEGREES) {
        // Трек короче ~150 м: границы вырождаются, камера ушла бы в бесконечный зум.
        CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(LatLng((north + south) / 2, (east + west) / 2))
                .zoom(SINGLE_POINT_ZOOM)
                .padding(0.0, 0.0, 0.0, bottomPaddingPx.toDouble())
                .build(),
        )
    } else {
        CameraUpdateFactory.newLatLngBounds(
            LatLngBounds.from(north, east, south, west),
            paddingPx,
            paddingPx,
            paddingPx,
            paddingPx + bottomPaddingPx,
        )
    }
}

private fun MapPoint.toLatLng(): LatLng = LatLng(latitude, longitude)

@Composable
private fun trackMapColors(): TrackMapColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        TrackMapColors(
            route = scheme.primary.toArgb(),
            pausedRoute = scheme.tertiary.toArgb(),
            routeCasing = scheme.surface.toArgb(),
            start = scheme.tertiary.toArgb(),
            finish = scheme.primary.toArgb(),
            current = scheme.primary.toArgb(),
            markerStroke = scheme.surface.toArgb(),
            point = scheme.primary.toArgb(),
            outlier = scheme.error.toArgb(),
            selected = scheme.tertiary.toArgb(),
            stop = scheme.onSurfaceVariant.toArgb(),
            highlight = scheme.tertiary.toArgb(),
            label = scheme.onSurface.toArgb(),
        )
    }
}

/**
 * В @Preview нативный рендерер MapLibre недоступен, поэтому вместо карты
 * рисуется тот же силуэт трека, что и в миниатюрах списка.
 */
@Composable
private fun MapCanvasPreviewStub(points: List<MapPoint>, modifier: Modifier = Modifier) {
    RouteSketch(
        points = points,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        strokeWidth = 6f,
    )
}
