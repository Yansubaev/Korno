package com.ians.korno.core.domain.model

import com.ians.korno.core.domain.error.TrackFileError

/**
 * Состояние фоновой операции обмена файлами. Одна операция может обрабатывать
 * несколько файлов, поэтому успешное завершение — это не «да/нет», а два счётчика:
 * частичный успех при импорте пачки файлов — нормальный исход, а не ошибка.
 */
sealed interface TrackFileOperation {
    data object Idle : TrackFileOperation

    data object Running : TrackFileOperation

    data class Completed(
        val successCount: Int,
        val failureCount: Int,
    ) : TrackFileOperation

    data class Failed(val error: TrackFileError) : TrackFileOperation
}

/** Файл, подготовленный во внутреннем кэше для передачи в другое приложение. */
data class SharedTrackFile(
    val uri: String,
    val fileName: String,
    val mimeType: String,
)
