package com.ians.korno.feature.tracking.domain.repository

/**
 * Держит уведомление «есть несохранённые активности» в согласии с БД:
 * показывает его, пока существуют записи с `isPendingSummary`, и убирает,
 * когда пользователь сохранил или удалил последнюю из них.
 *
 * Наблюдение живёт столько же, сколько процесс, поэтому запускается из `Application`.
 */
interface PendingSummaryNotifier {
    fun startWatching()
}
