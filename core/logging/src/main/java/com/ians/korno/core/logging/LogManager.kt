package com.ians.korno.core.logging

import kotlinx.coroutines.flow.Flow

data class LogSettings(
    val isAvailable: Boolean = true,
    val enabled: Boolean = true,
    val minimumLevel: LogLevel = LogLevel.DEBUG,
    val includeLocationSamples: Boolean = false,
)

data class LogStorageInfo(
    val sizeBytes: Long = 0L,
    val fileCount: Int = 0,
    val sessionId: String = "",
)

/**
 * Объём лога отдельной активности. [recordCount] равен нулю и когда логов нет вовсе,
 * и когда логирование было выключено во время записи — экрану достаточно этого различия,
 * чтобы не предлагать пустой экспорт.
 */
data class ActivityLogInfo(
    val activityId: Long = 0L,
    val sizeBytes: Long = 0L,
    val recordCount: Int = 0,
    /** Лог активности обрезан по лимиту размера: конец записи в него не попал. */
    val isTruncated: Boolean = false,
) {
    val hasRecords: Boolean get() = recordCount > 0
}

data class SharedLogArchive(
    val uri: String,
    val fileName: String,
    val mimeType: String = "application/zip",
)

sealed interface LogArchiveResult {
    data class Success(val archive: SharedLogArchive) : LogArchiveResult

    data object NoLogs : LogArchiveResult

    data object Error : LogArchiveResult
}

interface LogManager {
    fun observeSettings(): Flow<LogSettings>

    suspend fun setEnabled(enabled: Boolean)

    suspend fun setMinimumLevel(level: LogLevel)

    suspend fun setIncludeLocationSamples(enabled: Boolean)

    suspend fun getStorageInfo(): LogStorageInfo

    suspend fun clearLogs(): Boolean

    suspend fun createArchive(): LogArchiveResult

    /** Сколько записей накопил лог одной активности. */
    suspend fun getActivityLogInfo(activityId: Long): ActivityLogInfo

    /** Архив с логом одной активности — без записей всего остального приложения. */
    suspend fun createActivityArchive(activityId: Long): LogArchiveResult

    /** Вызывается при удалении активности: лог не должен пережить свой трек. */
    suspend fun deleteActivityLogs(activityId: Long): Boolean
}
