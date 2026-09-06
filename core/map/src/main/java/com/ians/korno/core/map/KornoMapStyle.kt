package com.ians.korno.core.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.luminance

/**
 * Источник тайлов. URL задаются на этапе сборки в `core/map/build.gradle.kts`,
 * по умолчанию это OpenFreeMap (OSM-данные, без ключей и регистрации).
 */
object KornoMapStyle {

    /** Светлый стиль карты. */
    const val LIGHT_URL: String = BuildConfig.MAP_STYLE_URL_LIGHT

    /** Тёмный стиль карты. */
    const val DARK_URL: String = BuildConfig.MAP_STYLE_URL_DARK

    fun urlFor(darkTheme: Boolean): String = if (darkTheme) DARK_URL else LIGHT_URL
}

/**
 * Стиль карты подбирается по текущей теме приложения, а не по системной:
 * так карта остаётся консистентной, если тема где-то переопределена вручную.
 */
@Composable
@ReadOnlyComposable
internal fun currentMapStyleUrl(): String =
    KornoMapStyle.urlFor(darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f)
