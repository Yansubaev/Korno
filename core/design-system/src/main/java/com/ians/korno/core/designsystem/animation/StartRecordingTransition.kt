package com.ians.korno.core.designsystem.animation

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.staticCompositionLocalOf

/** Scope из SharedTransitionLayout вокруг NavHost; в превью и тестах экранов его нет. */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** Длительность перехода Home → настройка записи, использована для парного slide/fade в KornoApp. */
object StartRecordingTransition {
    const val DURATION_MILLIS = 450
}
