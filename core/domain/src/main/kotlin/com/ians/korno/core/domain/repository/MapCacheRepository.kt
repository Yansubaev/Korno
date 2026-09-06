package com.ians.korno.core.domain.repository

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult

/**
 * Кэш тайлов карты.
 *
 * Тайлы, однажды загруженные на экране, остаются на диске и потом рисуются без сети —
 * это единственный офлайн, который получается бесплатно. Взамен кэш растёт, поэтому
 * его размер видно в настройках, а очистка — обычное действие пользователя.
 */
interface MapCacheRepository {
    /** Задаёт верхнюю границу кэша. Вызывается один раз при старте приложения. */
    suspend fun configure(): EmptyResult<DataError.Local>

    /** Занятый объём в байтах; `null`, если хранилище карты ещё не создано. */
    suspend fun cacheSizeBytes(): Long?

    suspend fun clear(): EmptyResult<DataError.Local>
}
