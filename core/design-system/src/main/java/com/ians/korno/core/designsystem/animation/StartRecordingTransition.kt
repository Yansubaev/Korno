package com.ians.korno.core.designsystem.animation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/** Scope из SharedTransitionLayout вокруг NavHost; в превью и тестах экранов его нет. */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * Переход Home → настройка записи: кнопка «Начать запись» раскрывается в экран настройки
 * и сама перелетает в кнопку «Старт». Ключи общие для двух фич, поэтому живут здесь.
 */
object StartRecordingTransition {
    const val DURATION_MILLIS = 450
    const val CONTAINER_KEY = "start_recording_container"
    const val BUTTON_KEY = "start_recording_button"
}

@Composable
fun Modifier.startRecordingSharedBounds(
    key: String,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    clipShape: Shape,
): Modifier {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    if (sharedTransitionScope == null || animatedVisibilityScope == null) return this
    return with(sharedTransitionScope) {
        sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animatedVisibilityScope,
            boundsTransform = { _, _ ->
                tween(StartRecordingTransition.DURATION_MILLIS, easing = FastOutSlowInEasing)
            },
            clipInOverlayDuringTransition = OverlayClip(clipShape),
        )
    }
}
