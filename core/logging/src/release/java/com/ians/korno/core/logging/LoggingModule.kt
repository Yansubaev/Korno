package com.ians.korno.core.logging

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koin.dsl.module

private object NoOpLogManager : LogManager {
    override fun observeSettings(): Flow<LogSettings> = flowOf(
        LogSettings(isAvailable = false, enabled = false),
    )

    override suspend fun setEnabled(enabled: Boolean) = Unit
    override suspend fun setMinimumLevel(level: LogLevel) = Unit
    override suspend fun setIncludeLocationSamples(enabled: Boolean) = Unit
    override suspend fun setIgnoreActivityRecognition(enabled: Boolean) = Unit
    override suspend fun getStorageInfo() = LogStorageInfo()
    override suspend fun clearLogs() = true
    override suspend fun createArchive(): LogArchiveResult = LogArchiveResult.NoLogs
    override suspend fun getActivityLogInfo(activityId: Long) = ActivityLogInfo(activityId)
    override suspend fun createActivityArchive(activityId: Long): LogArchiveResult =
        LogArchiveResult.NoLogs

    override suspend fun deleteActivityLogs(activityId: Long) = true
}

val loggingModule = module {
    single<AppLogger> { NoOpAppLogger }
    single<LogManager> { NoOpLogManager }
    single<ActivityLogScope> { NoOpActivityLogScope }
}
