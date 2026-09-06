package com.ians.korno.core.map

import androidx.compose.runtime.Immutable
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE = "korno-route-source"
private const val ROUTE_CASING_LAYER = "korno-route-casing-layer"
private const val ROUTE_LAYER = "korno-route-layer"
private const val PAUSED_ROUTE_SOURCE = "korno-paused-route-source"
private const val PAUSED_ROUTE_CASING_LAYER = "korno-paused-route-casing-layer"
private const val PAUSED_ROUTE_LAYER = "korno-paused-route-layer"

private const val POINTS_SOURCE = "korno-points-source"
internal const val POINTS_LAYER = "korno-points-layer"
internal const val POINTS_OUTLIER_LAYER = "korno-points-outlier-layer"
internal const val POINTS_PAUSED_LAYER = "korno-points-paused-layer"
internal const val POINTS_SELECTED_LAYER = "korno-points-selected-layer"

private const val MARKERS_SOURCE = "korno-markers-source"
private const val START_LAYER = "korno-start-layer"
private const val FINISH_LAYER = "korno-finish-layer"
private const val CURRENT_LAYER = "korno-current-layer"
private const val CURRENT_PAUSED_LAYER = "korno-current-paused-layer"

private const val PROPERTY_KIND = "kind"
private const val PROPERTY_STATE = "state"
internal const val PROPERTY_POINT_ID = "pointId"

private const val KIND_START = "start"
private const val KIND_FINISH = "finish"
private const val KIND_CURRENT = "current"
private const val KIND_CURRENT_PAUSED = "current_paused"

private const val STATE_NORMAL = "normal"
private const val STATE_OUTLIER = "outlier"
private const val STATE_PAUSED = "paused"
private const val STATE_SELECTED = "selected"

/** ARGB-цвета оформления трека, приходят из Material-темы приложения. */
@Immutable
internal data class TrackMapColors(
    val route: Int,
    val pausedRoute: Int,
    val routeCasing: Int,
    val start: Int,
    val finish: Int,
    val current: Int,
    val markerStroke: Int,
    val point: Int,
    val outlier: Int,
    val selected: Int,
)

/**
 * Добавляет в стиль слои Korno: линию трека, точки для ручной коррекции и
 * маркеры старта/финиша/текущей позиции. Вызывается один раз на каждый
 * загруженный [Style] (стиль пересоздаётся при смене темы).
 */
internal fun Style.installTrackLayers(colors: TrackMapColors) {
    if (getSource(ROUTE_SOURCE) != null) return

    addSource(GeoJsonSource(ROUTE_SOURCE))
    addSource(GeoJsonSource(PAUSED_ROUTE_SOURCE))
    addSource(GeoJsonSource(POINTS_SOURCE))
    addSource(GeoJsonSource(MARKERS_SOURCE))

    addLayer(
        LineLayer(ROUTE_CASING_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(colors.routeCasing),
            PropertyFactory.lineWidth(9f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    addLayer(
        LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(colors.route),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    addLayer(
        LineLayer(PAUSED_ROUTE_CASING_LAYER, PAUSED_ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(colors.routeCasing),
            PropertyFactory.lineWidth(8f),
            PropertyFactory.lineOpacity(0.75f),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
            PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    addLayer(
        LineLayer(PAUSED_ROUTE_LAYER, PAUSED_ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(colors.pausedRoute),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineOpacity(0.65f),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
            PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )

    addLayer(
        CircleLayer(POINTS_LAYER, POINTS_SOURCE).withProperties(
            PropertyFactory.circleColor(colors.point),
            PropertyFactory.circleRadius(4.5f),
            PropertyFactory.circleStrokeColor(colors.markerStroke),
            PropertyFactory.circleStrokeWidth(1.5f),
        ).apply { setFilter(stateFilter(STATE_NORMAL)) },
    )
    addLayer(
        CircleLayer(POINTS_OUTLIER_LAYER, POINTS_SOURCE).withProperties(
            PropertyFactory.circleColor(colors.outlier),
            PropertyFactory.circleRadius(5.5f),
            PropertyFactory.circleStrokeColor(colors.markerStroke),
            PropertyFactory.circleStrokeWidth(1.5f),
        ).apply { setFilter(stateFilter(STATE_OUTLIER)) },
    )
    addLayer(
        CircleLayer(POINTS_PAUSED_LAYER, POINTS_SOURCE).withProperties(
            PropertyFactory.circleColor(colors.pausedRoute),
            PropertyFactory.circleOpacity(0.65f),
            PropertyFactory.circleRadius(4.5f),
            PropertyFactory.circleStrokeColor(colors.markerStroke),
            PropertyFactory.circleStrokeWidth(1.5f),
        ).apply { setFilter(stateFilter(STATE_PAUSED)) },
    )
    addLayer(
        CircleLayer(POINTS_SELECTED_LAYER, POINTS_SOURCE).withProperties(
            PropertyFactory.circleColor(colors.selected),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor(colors.markerStroke),
            PropertyFactory.circleStrokeWidth(2.5f),
        ).apply { setFilter(stateFilter(STATE_SELECTED)) },
    )

    addLayer(
        CircleLayer(START_LAYER, MARKERS_SOURCE).withProperties(
            PropertyFactory.circleColor(colors.start),
            PropertyFactory.circleRadius(6.5f),
            PropertyFactory.circleStrokeColor(colors.markerStroke),
            PropertyFactory.circleStrokeWidth(2.5f),
        ).apply { setFilter(kindFilter(KIND_START)) },
    )
    addLayer(
        CircleLayer(FINISH_LAYER, MARKERS_SOURCE).withProperties(
            PropertyFactory.circleColor(colors.finish),
            PropertyFactory.circleRadius(6.5f),
            PropertyFactory.circleStrokeColor(colors.markerStroke),
            PropertyFactory.circleStrokeWidth(2.5f),
        ).apply { setFilter(kindFilter(KIND_FINISH)) },
    )
    addLayer(
        CircleLayer(CURRENT_LAYER, MARKERS_SOURCE).withProperties(
            PropertyFactory.circleColor(colors.current),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor(colors.markerStroke),
            PropertyFactory.circleStrokeWidth(3f),
        ).apply { setFilter(kindFilter(KIND_CURRENT)) },
    )
    addLayer(
        CircleLayer(CURRENT_PAUSED_LAYER, MARKERS_SOURCE).withProperties(
            PropertyFactory.circleColor(colors.pausedRoute),
            PropertyFactory.circleOpacity(0.7f),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor(colors.markerStroke),
            PropertyFactory.circleStrokeWidth(3f),
        ).apply { setFilter(kindFilter(KIND_CURRENT_PAUSED)) },
    )
}

/** Перерисовывает линию трека. */
internal fun Style.setRoute(points: List<MapPoint>) {
    val sections = points.toRouteSections()
    getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(sections.toFeatureCollection(paused = false))
    getSourceAs<GeoJsonSource>(PAUSED_ROUTE_SOURCE)?.setGeoJson(sections.toFeatureCollection(paused = true))
}

@Immutable
internal data class RouteSection(
    val points: List<MapPoint>,
    val isPaused: Boolean,
)

/**
 * Стиль относится к ребру, а не к отдельной координате. Обе границы паузы
 * пунктирные, чтобы обычная линия не соединяла напрямую точки до и после неё.
 */
internal fun List<MapPoint>.toRouteSections(): List<RouteSection> {
    if (size < 2) return emptyList()
    val sections = mutableListOf<RouteSection>()
    var sectionPoints = mutableListOf(first(), this[1])
    var sectionPaused = first().isPaused || this[1].isPaused

    for (index in 1 until lastIndex) {
        val edgePaused = this[index].isPaused || this[index + 1].isPaused
        if (edgePaused == sectionPaused) {
            sectionPoints += this[index + 1]
        } else {
            sections += RouteSection(sectionPoints, sectionPaused)
            sectionPoints = mutableListOf(this[index], this[index + 1])
            sectionPaused = edgePaused
        }
    }
    sections += RouteSection(sectionPoints, sectionPaused)
    return sections
}

private fun List<RouteSection>.toFeatureCollection(paused: Boolean): FeatureCollection {
    val features = filter { it.isPaused == paused }.map { section ->
        Feature.fromGeometry(LineString.fromLngLats(section.points.map(MapPoint::toGeoJson)))
    }
    return FeatureCollection.fromFeatures(features)
}

/** Перерисовывает отдельные точки трека (экран ручной коррекции). */
internal fun Style.setEditablePoints(points: List<EditableMapPoint>) {
    val features = points.map { editable ->
        Feature.fromGeometry(editable.point.toGeoJson()).apply {
            addNumberProperty(PROPERTY_POINT_ID, editable.id)
            addStringProperty(
                PROPERTY_STATE,
                when {
                    editable.isSelected -> STATE_SELECTED
                    editable.isOutlier -> STATE_OUTLIER
                    editable.point.isPaused -> STATE_PAUSED
                    else -> STATE_NORMAL
                },
            )
        }
    }
    getSourceAs<GeoJsonSource>(POINTS_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
}

/** Перерисовывает маркеры старта, финиша и текущей позиции. */
internal fun Style.setMarkers(
    start: MapPoint?,
    finish: MapPoint?,
    current: MapPoint?,
) {
    val features = buildList {
        start?.let { add(marker(it, KIND_START)) }
        finish?.let { add(marker(it, KIND_FINISH)) }
        current?.let {
            add(marker(it, if (it.isPaused) KIND_CURRENT_PAUSED else KIND_CURRENT))
        }
    }
    getSourceAs<GeoJsonSource>(MARKERS_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
}

private fun marker(point: MapPoint, kind: String): Feature =
    Feature.fromGeometry(point.toGeoJson()).apply { addStringProperty(PROPERTY_KIND, kind) }

private fun kindFilter(kind: String): Expression =
    Expression.eq(Expression.get(PROPERTY_KIND), Expression.literal(kind))

private fun stateFilter(state: String): Expression =
    Expression.eq(Expression.get(PROPERTY_STATE), Expression.literal(state))

private fun MapPoint.toGeoJson(): Point = Point.fromLngLat(longitude, latitude)
