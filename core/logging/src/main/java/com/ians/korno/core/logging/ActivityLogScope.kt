package com.ians.korno.core.logging

/**
 * Помечает записи логгера принадлежностью к конкретной активности.
 *
 * Область открывается один раз — тем, кто владеет записью трека, — и до её закрытия
 * каждая запись любого компонента получает `activityId`. Иначе идентификатор пришлось бы
 * протаскивать в каждый вызов [AppLogger.log], включая те слои, которые об активности
 * ничего не знают (клиент геолокации, системные приёмники, WorkManager).
 */
interface ActivityLogScope {
    /** Активность, к которой сейчас относятся записи; `null` — запись не идёт. */
    val currentActivityId: Long?

    fun begin(activityId: Long)

    fun end()
}

object NoOpActivityLogScope : ActivityLogScope {
    override val currentActivityId: Long? = null

    override fun begin(activityId: Long) = Unit

    override fun end() = Unit
}
