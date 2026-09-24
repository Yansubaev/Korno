package com.ians.korno.feature.tracking.data.service

import java.util.concurrent.atomic.AtomicInteger

/**
 * Счётчики входящих потоков записи. Раз в минуту они уходят в лог строкой
 * `Recording heartbeat` — и в лог активности, и в общий поток.
 *
 * Молчащий источник раньше выражался только отсутствием строк: в записях 18 и 19
 * распознавание не прислало ничего за полчаса, и это пришлось выводить из того, чего
 * в логе нет. Счётчик с нулём — факт, а не вывод.
 */
internal class RecordingPulse {
    val locationSamples = AtomicInteger()
    val recognitionBroadcasts = AtomicInteger()
    val recognitionResults = AtomicInteger()
    val stepReadings = AtomicInteger()

    private var previous = IntArray(COUNTER_COUNT)

    /** Прирост с прошлого вызова и накопленные итоги; вызывается из одного места. */
    @Synchronized
    fun snapshot(): Map<String, String> {
        val current = intArrayOf(
            locationSamples.get(),
            recognitionBroadcasts.get(),
            recognitionResults.get(),
            stepReadings.get(),
        )
        val fields = buildMap {
            NAMES.forEachIndexed { index, name ->
                put(name, (current[index] - previous[index]).toString())
                put("${name}Total", current[index].toString())
            }
        }
        previous = current
        return fields
    }

    private companion object {
        val NAMES = listOf("locationSamples", "recognitionBroadcasts", "recognitionResults", "stepReadings")
        val COUNTER_COUNT = NAMES.size
    }
}
