package com.ians.korno.core.designsystem.layout

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Сколько снизу занимает нижняя панель навигации сверх системных отступов. Панель лежит поверх
 * NavHost, а не сжимает его, — иначе её появление мгновенно меняло бы размер уходящего экрана
 * посреди перехода. Поэтому отступ под неё берут только экраны вкладок.
 */
val LocalBottomBarPadding = compositionLocalOf { 0.dp }
