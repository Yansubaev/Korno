package com.ians.korno.core.designsystem.animation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale

/** Scope из SharedTransitionLayout вокруг NavHost; в превью и тестах экранов его нет. */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * Кнопка записи ↔ экран настройки/записи: экран раскрывается из FAB и сворачивается в него.
 * Ключи разные, чтобы «Начать» связывалась только с настройкой, а секундомер — только с записью:
 * иначе экран настройки при старте «перетекал» бы в экран записи со скруглёнными углами.
 * Общие для приложения и фичи, поэтому живут здесь.
 */
object StartRecordingTransition {
    /** Совпадает с fade Home/список ↔ экран записи в KornoApp, иначе фон возвращается не в такт. */
    const val DURATION_MILLIS = 300
    const val SETUP_KEY = "start_recording_setup"
    const val RECORDING_KEY = "start_recording_ongoing"
}

/**
 * Откуда открыт экран обзора активности. Входит в ключ общих границ: без него главная и список,
 * лежащие рядом при переключении вкладок, связывали бы свои одинаковые строки друг с другом.
 */
object ActivityBoundsSource {
    const val HOME = "home"
    const val LIST = "list"
}

fun activityBoundsKey(activityId: Long, source: String) = "activity_${source}_$activityId"

/**
 * Сама кнопка записи. Поверх экрана, как строка списка поверх обзора, иначе непрозрачный экран
 * с первого кадра закрывает её и видно только окно, растущее из угла. Кнопка тянется по границам,
 * а не висит пилюлей посреди них: так экран вырастает из неё самой. Гаснет она при открытии
 * не сразу: маленькую кнопку быстрый fade убирает, пока окно ещё с неё размером, — и экран
 * снова будто вылезает из угла.
 */
@Composable
fun Modifier.recordingFabSharedBounds(key: String, animatedVisibilityScope: AnimatedVisibilityScope?): Modifier =
    containerTransformBounds(
        key,
        animatedVisibilityScope,
        FloatingActionButtonDefaults.extendedFabShape,
        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        exit = fadeOut(
            // Треть пути держится целиком, гаснет до самого конца: иначе глаз её не успевает связать с экраном.
            tween(StartRecordingTransition.DURATION_MILLIS * 2 / 3, delayMillis = StartRecordingTransition.DURATION_MILLIS / 3),
        ),
        zIndexInOverlay = 1f,
    )

/**
 * Экран, раскрывающийся из кнопки записи. Как container transform в Material: экран целиком,
 * уменьшенный по ширине кнопки, а не его левый верхний угол в окошке — иначе он лезет из угла.
 * Сворачиваясь, не гаснет: сверху проявляется кнопка.
 */
@Composable
fun Modifier.recordingScreenSharedBounds(key: String, animatedVisibilityScope: AnimatedVisibilityScope?): Modifier =
    containerTransformBounds(
        key,
        animatedVisibilityScope,
        FloatingActionButtonDefaults.extendedFabShape,
        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopCenter),
        exit = ExitTransition.None,
    )

/**
 * Контейнер, который раскрывается в экран и сворачивается обратно. Содержимое не масштабируется:
 * границы лишь открывают или прячут его, а [contentAlignment] — какой его частью они прилипают
 * к краю. Без scope (превью, тесты) или без [animatedVisibilityScope] — обычный модификатор.
 */
@Composable
fun Modifier.containerTransformBounds(
    key: String,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    clipShape: Shape,
    contentAlignment: Alignment = Alignment.Center,
    resizeMode: SharedTransitionScope.ResizeMode =
        SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.None, contentAlignment),
    enter: EnterTransition = fadeIn(),
    exit: ExitTransition = fadeOut(),
    zIndexInOverlay: Float = 0f,
): Modifier {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    if (sharedTransitionScope == null || animatedVisibilityScope == null) return this
    return with(sharedTransitionScope) {
        sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animatedVisibilityScope,
            enter = enter,
            exit = exit,
            boundsTransform = { _, _ ->
                tween(StartRecordingTransition.DURATION_MILLIS, easing = FastOutSlowInEasing)
            },
            resizeMode = resizeMode,
            clipInOverlayDuringTransition = OverlayClip(clipShape),
            zIndexInOverlay = zIndexInOverlay,
        )
    }
}

/**
 * Scope, в котором экран «виден», пока он верхний в стеке навигации, и сразу перестаёт быть
 * видимым, когда стек сменился. Родной scope NavHost переключается на несколько кадров позже,
 * чем кнопка над NavHost начинает появляться, — и эти кадры экран и кнопка «видны» оба,
 * общие границы не двигаются, а к моменту, когда экран наконец уходит, анимация уже привязана
 * к неверной цели.
 */
@Composable
fun rememberTopEntryVisibilityScope(isTop: Boolean): AnimatedVisibilityScope {
    val state = remember { MutableTransitionState(EnterExitState.PreEnter) }
    state.targetState = if (isTop) EnterExitState.Visible else EnterExitState.PostExit
    val transition = rememberTransition(state, "topEntryVisibility")
    return remember(transition) {
        object : AnimatedVisibilityScope {
            override val transition = transition
        }
    }
}
