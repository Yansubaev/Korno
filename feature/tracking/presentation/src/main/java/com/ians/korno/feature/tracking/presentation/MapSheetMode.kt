package com.ians.korno.feature.tracking.presentation

/**
 * Положение шторки со статистикой поверх карты на экране записи.
 *
 * [MAP] — карта на весь экран, видна только компактная шторка,
 * [SPLIT] — карта и статистика пополам,
 * [TEXT] — шторка развёрнута на всю область, карты не видно.
 */
internal enum class MapSheetMode {
    MAP,
    SPLIT,
    TEXT,
    ;

    fun next(): MapSheetMode = entries[(ordinal + 1) % entries.size]
}
