package com.ians.korno.feature.tracking.domain.navigation

/**
 * Адреса экранов записи, по которым в приложение можно попасть извне.
 *
 * Лежат в `:domain`, потому что это контракт между модулями, которые друг о друге не знают:
 * ссылку подставляет в PendingIntent сервис из `:data`, а разбирает граф навигации из
 * `:presentation`. Схема и host продублированы в intent-filter'е `MainActivity` в `:app` —
 * при изменении константы манифест надо править вместе с ней.
 */
object TrackingDeepLinks {
    /** Экран текущей записи; открывается тапом по уведомлению foreground-сервиса. */
    const val RECORDING = "korno://tracking/recording"

    /**
     * Флаг «сразу спросить о завершении»: `RECORDING?finish=true`. Его ставит кнопка «Стоп»
     * своего уведомления — остановить запись без подтверждения с экрана блокировки слишком легко.
     * Имя совпадает с полем маршрута экрана записи.
     */
    const val FINISH_PARAM = "finish"

    const val RECORDING_FINISH = "$RECORDING?$FINISH_PARAM=true"
}
