package com.ians.korno.data.gpxexport.format

/** Пространство имён расширений Korno — одно на оба формата. */
internal const val KORNO_NAMESPACE = "https://korno.app/xmlschemas/TrackExtension/v1"

/** Значение атрибута `creator` в GPX. */
internal const val KORNO_CREATOR = "Korno"

/**
 * Узнаёт собственный файл по корневому элементу.
 *
 * Отдельной метки в файлы не добавлялось намеренно: признак должен работать и на тех
 * файлах, что уже выгружены прошлыми сборками. Обе подписи там есть с самого начала —
 * `creator="Korno"` у GPX и объявление `xmlns:korno` у обоих форматов, — и вместе они
 * покрывают и наш GPX, и наш TCX, у которого своего `creator` в схеме нет.
 *
 * [attributes] — атрибуты корневого элемента с уже отброшенными префиксами, поэтому
 * `xmlns:korno` приходит сюда под именем `korno`.
 */
internal fun isKornoDocument(attributes: Map<String, String>): Boolean =
    attributes["creator"] == KORNO_CREATOR || attributes["korno"] == KORNO_NAMESPACE
