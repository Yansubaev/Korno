package com.ians.korno.core.designsystem.animation

import android.os.Build
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Флаг в `SavedStateHandle` записи стека, которая уходит, рассыпаясь: экран под ней должен
 * появиться сразу, а не проявляться fade-ом сквозь пыль. Ставит его навигация экрана перед
 * возвратом, читает NavHost.
 */
const val DISINTEGRATING_ENTRY_KEY = "korno.disintegrating"

private const val DISINTEGRATION_MILLIS = 1000
private const val FADE_MILLIS = 700

@Stable
class DisintegrationState internal constructor(
    internal val layer: GraphicsLayer,
    private val scope: CoroutineScope,
) {
    internal var snapshot by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Уход уже начат: экран заморожен и покажет только снимок. */
    val isStarted: Boolean get() = snapshot != null

    /**
     * Снимает экран и с этого момента показывает только снимок; [then] — когда он готов.
     * Замораживать нужно сразу: действие, ради которого экран уходит (удаление), успевает
     * поменять его содержимое до навигации.
     */
    fun start(then: () -> Unit) {
        if (snapshot != null) return
        scope.launch {
            snapshot = layer.toImageBitmap()
            then()
        }
    }
}

@Composable
fun rememberDisintegrationState(): DisintegrationState {
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    return remember(layer, scope) { DisintegrationState(layer, scope) }
}

/**
 * Экран сам анимирует свой уход — destination должен отдать ему exit без анимации: после
 * [DisintegrationState.start] рассыпается в пыль, в остальных случаях просто гаснет, как fade NavHost
 * (кроме [fadeOnExit] = false: тогда гашение на destination).
 */
@Composable
fun Modifier.disintegrateOnExit(
    state: DisintegrationState,
    animatedVisibilityScope: AnimatedVisibilityScope,
    fadeOnExit: Boolean = true,
): Modifier {
    val progress by animatedVisibilityScope.transition.animateFloat(
        transitionSpec = {
            tween(if (state.snapshot != null) DISINTEGRATION_MILLIS else FADE_MILLIS, easing = LinearEasing)
        },
        label = "disintegrateOnExit",
    ) { if (it == EnterExitState.PostExit) 1f else 0f }
    val particleSizePx = with(LocalDensity.current) { 3.dp.toPx() }
    val snapshot = state.snapshot
    val disintegration = remember(snapshot) { snapshot?.let { Disintegration(it, particleSizePx) } }

    return drawWithContent {
        when {
            snapshot == null && !fadeOnExit -> drawContent()
            snapshot == null -> {
                state.layer.record { this@drawWithContent.drawContent() }
                state.layer.alpha = 1f - progress
                drawLayer(state.layer)
            }
            disintegration != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                drawIntoCanvas { disintegration.draw(it.nativeCanvas, progress) }
            else -> drawImage(snapshot, alpha = 1f - progress)
        }
    }
        // Фон внутри снимка: иначе в пыль рассыпался бы только текст, а экран под этим
        // просвечивал бы сквозь него ещё до начала эффекта.
        .background(MaterialTheme.colorScheme.surface)
}
