package com.ians.korno.core.domain.repository

import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.SharedTrackFile
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.core.domain.model.TrackFileOperation
import kotlinx.coroutines.flow.Flow

/**
 * Экспорт и импорт треков.
 *
 * Документы адресуются строкой: домен не знает про `android.net.Uri`, но и не пытается
 * подменить его своим типом — это непрозрачный дескриптор, выданный системным
 * выбором файла, и домену достаточно передавать его дальше без интерпретации.
 *
 * Запись и чтение файлов ставятся в очередь и выполняются вне жизненного цикла экрана:
 * пользователь может уйти с экрана или свернуть приложение, не потеряв операцию.
 */
interface TrackFileRepository {
    /** Ставит в очередь экспорт одной активности в уже созданный документ. */
    fun exportActivity(activityId: Long, destinationUri: String, format: TrackFileFormat)

    /** Ставит в очередь экспорт всех активностей в выбранную папку — по файлу на активность. */
    fun exportAllActivities(destinationTreeUri: String, format: TrackFileFormat)

    /** Ставит в очередь импорт выбранных документов. */
    fun importTracks(sourceUris: List<String>)

    /**
     * Состояние экспорта отдельной активности. Завершение отдаётся ровно один раз:
     * WorkManager помнит результат и после показа снекбара, а повторять его не нужно.
     *
     * Экспорт одной активности и выгрузка всех разведены по разным потокам намеренно:
     * их слушают разные экраны, и общий поток означал бы, что итог операции достанется
     * тому, кто подписался первым, а не тому, кто её запустил.
     */
    fun observeActivityExport(): Flow<TrackFileOperation>

    /** Состояние выгрузки всех активностей; правила те же, что у [observeActivityExport]. */
    fun observeBulkExport(): Flow<TrackFileOperation>

    /** Состояние импорта; правила те же, что у [observeActivityExport]. */
    fun observeImport(): Flow<TrackFileOperation>

    /**
     * Готовит экспорт: проверяет, что записать есть что, и возвращает имя файла для
     * системного диалога сохранения.
     *
     * Проверка идёт до диалога, а не после: документ создаёт система в момент выбора,
     * и неудача на следующем шаге оставила бы у пользователя пустой файл.
     */
    suspend fun prepareExport(
        activityId: Long,
        format: TrackFileFormat,
    ): Result<String, TrackFileError>

    /** Готовит файл во внутреннем кэше и отдаёт ссылку, пригодную для передачи наружу. */
    suspend fun prepareShare(
        activityId: Long,
        format: TrackFileFormat,
    ): Result<SharedTrackFile, TrackFileError>
}
