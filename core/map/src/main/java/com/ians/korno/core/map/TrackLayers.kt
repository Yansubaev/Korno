package com.ians.korno.core.map

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.Locale
import kotlin.math.ln
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
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
private const val GAP_ROUTE_SOURCE = "korno-gap-route-source"
private const val GAP_ROUTE_CASING_LAYER = "korno-gap-route-casing-layer"
private const val GAP_ROUTE_LAYER = "korno-gap-route-layer"
private const val REJECTED_ROUTE_SOURCE = "korno-rejected-route-source"
private const val REJECTED_ROUTE_LAYER = "korno-rejected-route-layer"

private const val SECTION_SOURCE = "korno-section-source"
private const val SECTION_LAYER = "korno-section-layer"

private const val STOPS_SOURCE = "korno-stops-source"
private const val STOPS_AUTO_LAYER = "korno-stops-auto-layer"
private const val STOPS_MANUAL_LAYER = "korno-stops-manual-layer"

private const val DISTANCE_SOURCE = "korno-distance-source"
private const val DISTANCE_CIRCLE_LAYER = "korno-distance-circle-layer"
private const val DISTANCE_LABEL_LAYER = "korno-distance-label-layer"

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
private const val HIGHLIGHT_LAYER = "korno-highlight-layer"

private const val PROPERTY_KIND = "kind"
private const val PROPERTY_STATE = "state"
private const val PROPERTY_COLOR = "color"
private const val PROPERTY_RADIUS = "radius"
private const val PROPERTY_LABEL = "label"
internal const val PROPERTY_POINT_ID = "pointId"

private const val KIND_START = "start"
private const val KIND_FINISH = "finish"
private const val KIND_CURRENT = "current"
private const val KIND_CURRENT_PAUSED = "current_paused"
private const val KIND_HIGHLIGHT = "highlight"
private const val KIND_STOP_AUTO = "stop"
private const val KIND_STOP_MANUAL = "pause"

private const val STATE_NORMAL = "normal"
private const val STATE_OUTLIER = "outlier"
private const val STATE_PAUSED = "paused"
private const val STATE_SELECTED = "selected"

private const val STOP_MIN_RADIUS = 5f
private const val STOP_MAX_RADIUS = 18f
private const val STOP_REFERENCE_MILLIS = 30_000.0

/** ARGB-цвета оформления трека, приходят из Material-темы приложения. */
@Immutable
internal data class TrackMapColors(
    val route: Int,
    val pausedRoute: Int,
    /** Отрезок, которого запись не видела: цвет отдельно от паузы — это разные события. */
    val gapRoute: Int,
    /** Отрезок через отбракованные фиксы: сплошной, но бледный — место есть, доверия нет. */
    val rejectedRoute: Int,
    val routeCasing: Int,
    val start: Int,
    val finish: Int,
    val current: Int,
    val markerStroke: Int,
    val point: Int,
    val outlier: Int,
    val selected: Int,
    val stop: Int,
    val highlight: Int,
    val label: Int,
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
    addSource(GeoJsonSource(GAP_ROUTE_SOURCE))
    addSource(GeoJsonSource(REJECTED_ROUTE_SOURCE))
    addSource(GeoJsonSource(SECTION_SOURCE))
    addSource(GeoJsonSource(STOPS_SOURCE))
    addSource(GeoJsonSource(DISTANCE_SOURCE))
    addSource(GeoJsonSource(POINTS_SOURCE))
    addSource(GeoJsonSource(MARKERS_SOURCE))

    // Ниже всего и без обводки: там, где выброс лёг поверх настоящей линии, она остаётся
    // видна. Сплошная, чтобы не спорить с пунктирами паузы и разрыва.
    addLayer(
        LineLayer(REJECTED_ROUTE_LAYER, REJECTED_ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(colors.rejectedRoute),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineOpacity(0.35f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
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
            // Цвет приходит с каждым отрезком: линза красит маршрут, а не слой целиком,
            // и пересоздавать слой на каждое переключение не приходится.
            PropertyFactory.lineColor(Expression.toColor(Expression.get(PROPERTY_COLOR))),
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
        LineLayer(GAP_ROUTE_CASING_LAYER, GAP_ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(colors.routeCasing),
            PropertyFactory.lineWidth(8f),
            PropertyFactory.lineOpacity(0.75f),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
            PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    // Рисунок пунктира тот же, что у паузы, а цвет свой: пауза — то, что человек или
    // автопауза сделали, разрыв — то, чего запись не видела. Одним цветом они читались
    // как одно событие.
    addLayer(
        LineLayer(GAP_ROUTE_LAYER, GAP_ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(colors.gapRoute),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineOpacity(0.65f),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
            PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )

    // Выбранный сплит — широкая полупрозрачная подсветка поверх линии: под линией
    // её съела бы обводка маршрута.
    addLayer(
        LineLayer(SECTION_LAYER, SECTION_SOURCE).withProperties(
            PropertyFactory.lineColor(colors.highlight),
            PropertyFactory.lineWidth(13f),
            PropertyFactory.lineOpacity(0.4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )

    addLayer(
        CircleLayer(STOPS_AUTO_LAYER, STOPS_SOURCE).withProperties(
            PropertyFactory.circleColor(colors.stop),
            PropertyFactory.circleOpacity(0.45f),
            PropertyFactory.circleRadius(Expression.toNumber(Expression.get(PROPERTY_RADIUS))),
            PropertyFactory.circleStrokeColor(colors.stop),
            PropertyFactory.circleStrokeWidth(1.5f),
        ).apply { setFilter(kindFilter(KIND_STOP_AUTO)) },
    )
    addLayer(
        CircleLayer(STOPS_MANUAL_LAYER, STOPS_SOURCE).withProperties(
            PropertyFactory.circleColor(colors.pausedRoute),
            PropertyFactory.circleOpacity(0.45f),
            PropertyFactory.circleRadius(Expression.toNumber(Expression.get(PROPERTY_RADIUS))),
            PropertyFactory.circleStrokeColor(colors.pausedRoute),
            PropertyFactory.circleStrokeWidth(1.5f),
        ).apply { setFilter(kindFilter(KIND_STOP_MANUAL)) },
    )

    addLayer(
        CircleLayer(DISTANCE_CIRCLE_LAYER, DISTANCE_SOURCE).withProperties(
            PropertyFactory.circleColor(colors.markerStroke),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor(colors.route),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    addLayer(
        SymbolLayer(DISTANCE_LABEL_LAYER, DISTANCE_SOURCE).withProperties(
            PropertyFactory.textField(Expression.get(PROPERTY_LABEL)),
            PropertyFactory.textSize(10f),
            PropertyFactory.textColor(colors.label),
            // Шрифт берётся из стиля карты; если ни одного из перечисленных в нём нет,
            // подпись не отрисуется, но кружок отметки останется на месте.
            PropertyFactory.textFont(
                arrayOf("Noto Sans Regular", "Open Sans Regular", "Arial Unicode MS Regular"),
            ),
            PropertyFactory.textAllowOverlap(false),
            PropertyFactory.textIgnorePlacement(false),
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
    addLayer(
        CircleLayer(HIGHLIGHT_LAYER, MARKERS_SOURCE).withProperties(
            PropertyFactory.circleColor(colors.highlight),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor(colors.markerStroke),
            PropertyFactory.circleStrokeWidth(3f),
        ).apply { setFilter(kindFilter(KIND_HIGHLIGHT)) },
    )
}

/** Перерисовывает линию трека. */
internal fun Style.setRoute(points: List<MapPoint>, defaultColor: Int) {
    val sections = points.toRouteSections()
    getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
        ?.setGeoJson(sections.toFeatureCollection(EdgeKind.NORMAL, defaultColor))
    getSourceAs<GeoJsonSource>(PAUSED_ROUTE_SOURCE)
        ?.setGeoJson(sections.toFeatureCollection(EdgeKind.PAUSED, defaultColor))
    getSourceAs<GeoJsonSource>(GAP_ROUTE_SOURCE)
        ?.setGeoJson(sections.toFeatureCollection(EdgeKind.GAP, defaultColor))
    getSourceAs<GeoJsonSource>(REJECTED_ROUTE_SOURCE)
        ?.setGeoJson(sections.toFeatureCollection(EdgeKind.REJECTED, defaultColor))
}

/**
 * Чем ребро маршрута нарисовано. Пауза и разрыв оба пунктирные — сплошная линия в обоих
 * случаях утверждала бы, что маршрут между точками известен, — но разными цветами:
 * пауза это сделанное, разрыв это неувиденное. Отрезок через отбракованный фикс —
 * бледная сплошная линия: точки были, но верить им нельзя.
 */
internal enum class EdgeKind { NORMAL, PAUSED, GAP, REJECTED }

@Immutable
internal data class RouteSection(
    val points: List<MapPoint>,
    val kind: EdgeKind,
    /** Цвет линзы для этого отрезка; `null` — цвет линии по умолчанию. */
    val shade: Color? = null,
)

/** Оформление ребра маршрута: пока оно не меняется, отрезок можно рисовать одной линией. */
private data class EdgeStyle(val kind: EdgeKind, val shade: Color?)

/**
 * Стиль относится к ребру, а не к отдельной координате. Обе границы паузы
 * пунктирные, чтобы обычная линия не соединяла напрямую точки до и после неё.
 */
internal fun List<MapPoint>.toRouteSections(): List<RouteSection> {
    if (size < 2) return emptyList()
    val sections = mutableListOf<RouteSection>()
    var sectionPoints = mutableListOf(first(), this[1])
    var sectionStyle = edgeStyle(0)

    for (index in 1 until lastIndex) {
        val style = edgeStyle(index)
        if (style == sectionStyle) {
            sectionPoints += this[index + 1]
        } else {
            sections += RouteSection(sectionPoints, sectionStyle.kind, sectionStyle.shade)
            sectionPoints = mutableListOf(this[index], this[index + 1])
            sectionStyle = style
        }
    }
    sections += RouteSection(sectionPoints, sectionStyle.kind, sectionStyle.shade)
    return sections
}

private fun List<MapPoint>.edgeStyle(index: Int): EdgeStyle = EdgeStyle(
    // Пауза старше разрыва: если на паузе ещё и пропал сигнал, ребро всё равно про паузу —
    // она объясняет отсутствие точек, а разрыв её не отменяет.
    kind = when {
        // Отбраковка старше всего: координатам ребра не верят, и утверждать про него
        // паузу или разрыв по этим же координатам нечем.
        this[index].isRejected || this[index + 1].isRejected -> EdgeKind.REJECTED
        this[index].isPaused || this[index + 1].isPaused -> EdgeKind.PAUSED
        this[index + 1].isGap -> EdgeKind.GAP
        else -> EdgeKind.NORMAL
    },
    shade = this[index].shade,
)

private fun List<RouteSection>.toFeatureCollection(
    kind: EdgeKind,
    defaultColor: Int,
): FeatureCollection {
    val features = filter { it.kind == kind }.map { section ->
        Feature.fromGeometry(LineString.fromLngLats(section.points.map(MapPoint::toGeoJson))).apply {
            addStringProperty(
                PROPERTY_COLOR,
                (section.shade?.toArgb() ?: defaultColor).toColorString(),
            )
        }
    }
    return FeatureCollection.fromFeatures(features)
}

/** Перерисовывает всё, что лежит поверх линии: остановки, отметки и выделение. */
internal fun Style.setOverlay(overlay: TrackOverlay) {
    getSourceAs<GeoJsonSource>(STOPS_SOURCE)?.setGeoJson(
        FeatureCollection.fromFeatures(
            overlay.stops.map { stop ->
                Feature.fromGeometry(stop.point.toGeoJson()).apply {
                    addStringProperty(
                        PROPERTY_KIND,
                        if (stop.isManualPause) KIND_STOP_MANUAL else KIND_STOP_AUTO,
                    )
                    addNumberProperty(PROPERTY_RADIUS, stopRadius(stop.durationMillis))
                }
            },
        ),
    )
    getSourceAs<GeoJsonSource>(DISTANCE_SOURCE)?.setGeoJson(
        FeatureCollection.fromFeatures(
            overlay.distanceMarkers.map { marker ->
                Feature.fromGeometry(marker.point.toGeoJson()).apply {
                    addStringProperty(PROPERTY_LABEL, marker.label)
                }
            },
        ),
    )
    getSourceAs<GeoJsonSource>(SECTION_SOURCE)?.setGeoJson(
        FeatureCollection.fromFeatures(
            if (overlay.highlightedSection.size < 2) {
                emptyList()
            } else {
                listOf(
                    Feature.fromGeometry(
                        LineString.fromLngLats(overlay.highlightedSection.map(MapPoint::toGeoJson)),
                    ),
                )
            },
        ),
    )
}

/**
 * Радиус растёт логарифмически: разница между 10 с и минутой должна быть видна, а
 * получасовой привал не может занять пол-экрана.
 */
private fun stopRadius(durationMillis: Long): Float {
    if (durationMillis <= 0L) return STOP_MIN_RADIUS
    val growth = ln(1.0 + durationMillis / STOP_REFERENCE_MILLIS) / ln(2.0)
    return (STOP_MIN_RADIUS + growth * 4f).toFloat().coerceIn(STOP_MIN_RADIUS, STOP_MAX_RADIUS)
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

/** Перерисовывает маркеры старта, финиша, текущей позиции и выбранной точки. */
internal fun Style.setMarkers(
    start: MapPoint?,
    finish: MapPoint?,
    current: MapPoint?,
    highlight: MapPoint? = null,
) {
    val features = buildList {
        start?.let { add(marker(it, KIND_START)) }
        finish?.let { add(marker(it, KIND_FINISH)) }
        current?.let {
            add(marker(it, if (it.isPaused) KIND_CURRENT_PAUSED else KIND_CURRENT))
        }
        highlight?.let { add(marker(it, KIND_HIGHLIGHT)) }
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

/** MapLibre понимает цвет в свойстве фичи только строкой; альфа-канал отбрасывается. */
private fun Int.toColorString(): String =
    String.format(Locale.US, "#%06X", this and 0xFFFFFF)
