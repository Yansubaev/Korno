package com.ians.korno.core.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Карта с записанным или записываемым треком.
 *
 * @param points точки маршрута в порядке записи.
 * @param currentPoint текущая позиция; если задана, рисуется вместо маркера финиша.
 * @param camera [TrackMapCamera.FitTrack] — показать трек целиком,
 *   [TrackMapCamera.FollowLatest] — следовать за последней точкой.
 * @param interactive разрешить жесты. Внутри вертикально прокручиваемых
 *   контейнеров жесты стоит выключать, иначе карта перехватывает скролл экрана.
 * @param showEndpoints рисовать маркеры старта и финиша.
 * @param bottomInset высота перекрывающей карту панели: камера центрует трек по
 *   видимой части, а не по всей карте. Изменение отступа считается запросом
 *   нового кадра и отменяет ручное смещение камеры.
 */
@Composable
fun TrackMap(
    points: List<MapPoint>,
    modifier: Modifier = Modifier,
    currentPoint: MapPoint? = null,
    camera: TrackMapCamera = TrackMapCamera.FitTrack,
    interactive: Boolean = true,
    showEndpoints: Boolean = true,
    bottomInset: Dp = 0.dp,
) {
    KornoMapCanvas(
        route = points,
        modifier = modifier,
        currentPoint = currentPoint,
        showEndpoints = showEndpoints,
        camera = camera,
        interactive = interactive,
        bottomInset = bottomInset,
    )
}

/**
 * Карта режима ручной коррекции: помимо линии трека рисует каждую точку
 * отдельно и сообщает об их нажатии.
 *
 * @param points активные (не удалённые) точки трека.
 * @param onPointClick вызывается с id точки, по которой попал тап.
 */
@Composable
fun EditableTrackMap(
    points: List<EditableMapPoint>,
    onPointClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    KornoMapCanvas(
        route = points.map(EditableMapPoint::point),
        modifier = modifier,
        editablePoints = points,
        showEndpoints = false,
        camera = TrackMapCamera.FitTrack,
        interactive = interactive,
        onPointClick = onPointClick,
    )
}
