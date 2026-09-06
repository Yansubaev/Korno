package com.ians.korno.feature.settings.presentation

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.PrivacyZone
import com.ians.korno.core.domain.model.SharedTrackFile
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.core.domain.model.TrackFileOperation
import com.ians.korno.core.domain.model.UserPreferences
import com.ians.korno.core.domain.repository.MapCacheRepository
import com.ians.korno.core.domain.repository.TrackFileRepository
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.logging.ActivityLogInfo
import com.ians.korno.core.logging.LogArchiveResult
import com.ians.korno.core.logging.LogLevel
import com.ians.korno.core.logging.LogManager
import com.ians.korno.core.logging.LogSettings
import com.ians.korno.core.logging.LogStorageInfo
import com.ians.korno.core.logging.SharedLogArchive
import com.ians.korno.feature.settings.domain.usecase.ClearMapCacheUseCase
import com.ians.korno.feature.settings.domain.usecase.ExportAllActivitiesUseCase
import com.ians.korno.feature.settings.domain.usecase.GetMapCacheSizeUseCase
import com.ians.korno.feature.settings.domain.usecase.ObserveTrackExportUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `dynamic color switch reflects the stored preference`() = runTest(testDispatcher) {
        val preferences = FakeUserPreferencesRepository(dynamicColorEnabled = false)
        val viewModel = settingsViewModel(preferences)

        runCurrent()

        assertThat(viewModel.state.value.dynamicColor).isFalse()
    }

    @Test
    fun `turning dynamic color off persists it and updates the state`() = runTest(testDispatcher) {
        val preferences = FakeUserPreferencesRepository(dynamicColorEnabled = true)
        val viewModel = settingsViewModel(preferences)
        runCurrent()

        viewModel.onAction(SettingsAction.SetDynamicColor(enabled = false))
        runCurrent()

        assertThat(preferences.preferences.value.dynamicColorEnabled).isFalse()
        assertThat(viewModel.state.value.dynamicColor).isFalse()
    }

    @Test
    fun `turning dynamic color back on persists it`() = runTest(testDispatcher) {
        val preferences = FakeUserPreferencesRepository(dynamicColorEnabled = false)
        val viewModel = settingsViewModel(preferences)
        runCurrent()

        viewModel.onAction(SettingsAction.SetDynamicColor(enabled = true))
        runCurrent()

        assertThat(preferences.preferences.value.dynamicColorEnabled).isTrue()
        assertThat(viewModel.state.value.dynamicColor).isTrue()
    }

    @Test
    fun `choosing a folder starts the export and reports how many files were written`() =
        runTest(testDispatcher) {
            val trackFiles = FakeTrackFileRepository()
            val viewModel = settingsViewModel(
                FakeUserPreferencesRepository(dynamicColorEnabled = true),
                trackFiles,
            )
            runCurrent()

            viewModel.onAction(SettingsAction.ExportFolderChosen("content://tree/backup"))
            runCurrent()

            assertThat(trackFiles.exportedTreeUri).isEqualTo("content://tree/backup")
            assertThat(viewModel.state.value.isExporting).isTrue()

            trackFiles.bulkExport.value = TrackFileOperation.Completed(successCount = 3, failureCount = 0)
            runCurrent()

            assertThat(viewModel.state.value.isExporting).isFalse()
            assertThat(viewModel.state.value.snackbarMessage)
                .isEqualTo(SettingsMessage.Exported(activityCount = 3))
        }

    @Test
    fun `closing the folder dialog without a choice starts nothing`() = runTest(testDispatcher) {
        val trackFiles = FakeTrackFileRepository()
        val viewModel = settingsViewModel(
            FakeUserPreferencesRepository(dynamicColorEnabled = true),
            trackFiles,
        )
        runCurrent()

        viewModel.onAction(SettingsAction.ExportFolderChosen(treeUri = null))
        runCurrent()

        assertThat(trackFiles.exportedTreeUri).isNull()
        assertThat(viewModel.state.value.isExporting).isFalse()
    }

    @Test
    fun `a failed export is reported instead of a success message`() = runTest(testDispatcher) {
        val trackFiles = FakeTrackFileRepository()
        val viewModel = settingsViewModel(
            FakeUserPreferencesRepository(dynamicColorEnabled = true),
            trackFiles,
        )
        runCurrent()

        viewModel.onAction(SettingsAction.ExportFolderChosen("content://tree/backup"))
        trackFiles.bulkExport.value =
            TrackFileOperation.Failed(TrackFileError.UNWRITABLE_DESTINATION)
        runCurrent()

        assertThat(viewModel.state.value.isExporting).isFalse()
        assertThat(viewModel.state.value.snackbarMessage).isEqualTo(SettingsMessage.ExportFailed)
    }

    @Test
    fun `the map cache size is shown as soon as the screen opens`() = runTest(testDispatcher) {
        val mapCache = FakeMapCacheRepository()
        val viewModel = settingsViewModel(
            FakeUserPreferencesRepository(dynamicColorEnabled = true),
            mapCache = mapCache,
        )

        runCurrent()

        assertThat(viewModel.state.value.mapCacheSizeBytes).isEqualTo(12_000_000L)
    }

    @Test
    fun `clearing the cache empties it and refreshes the shown size`() = runTest(testDispatcher) {
        val mapCache = FakeMapCacheRepository()
        val viewModel = settingsViewModel(
            FakeUserPreferencesRepository(dynamicColorEnabled = true),
            mapCache = mapCache,
        )
        runCurrent()

        viewModel.onAction(SettingsAction.ClearCacheClicked)
        runCurrent()

        assertThat(mapCache.cleared).isTrue()
        assertThat(viewModel.state.value.isClearingMapCache).isFalse()
        assertThat(viewModel.state.value.mapCacheSizeBytes).isEqualTo(0L)
        assertThat(viewModel.state.value.snackbarMessage).isEqualTo(SettingsMessage.MapCacheCleared)
    }

    @Test
    fun `a failed cleanup is reported and leaves the size alone`() = runTest(testDispatcher) {
        val mapCache = FakeMapCacheRepository(clearResult = Result.Error(DataError.Local.UNKNOWN))
        val viewModel = settingsViewModel(
            FakeUserPreferencesRepository(dynamicColorEnabled = true),
            mapCache = mapCache,
        )
        runCurrent()

        viewModel.onAction(SettingsAction.ClearCacheClicked)
        runCurrent()

        assertThat(viewModel.state.value.mapCacheSizeBytes).isEqualTo(12_000_000L)
        assertThat(viewModel.state.value.snackbarMessage)
            .isEqualTo(SettingsMessage.MapCacheClearFailed)
    }

    @Test
    fun `the privacy zone is stored when picked`() = runTest(testDispatcher) {
        val preferences = FakeUserPreferencesRepository(dynamicColorEnabled = true)
        val viewModel = settingsViewModel(preferences)
        runCurrent()

        viewModel.onAction(SettingsAction.SetPrivacyZone(PrivacyZone.MEDIUM))
        runCurrent()

        assertThat(preferences.preferences.value.privacyZone).isEqualTo(PrivacyZone.MEDIUM)
        assertThat(viewModel.state.value.privacyZone).isEqualTo(PrivacyZone.MEDIUM)
    }

    @Test
    fun `changing diagnostic level persists it`() = runTest(testDispatcher) {
        val logs = FakeLogManager()
        val viewModel = settingsViewModel(
            preferences = FakeUserPreferencesRepository(dynamicColorEnabled = true),
            logs = logs,
        )
        runCurrent()

        viewModel.onAction(SettingsAction.SetMinimumLogLevel(LogLevel.WARN))
        runCurrent()

        assertThat(logs.settings.value.minimumLevel).isEqualTo(LogLevel.WARN)
        assertThat(viewModel.state.value.logSettings.minimumLevel).isEqualTo(LogLevel.WARN)
    }

    @Test
    fun `prepared diagnostic archive is emitted as a share event`() = runTest(testDispatcher) {
        val logs = FakeLogManager(
            archiveResult = LogArchiveResult.Success(
                SharedLogArchive(
                    uri = "content://diagnostics/logs.zip",
                    fileName = "logs.zip",
                ),
            ),
        )
        val viewModel = settingsViewModel(
            preferences = FakeUserPreferencesRepository(dynamicColorEnabled = true),
            logs = logs,
        )
        runCurrent()

        viewModel.events.test {
            viewModel.onAction(SettingsAction.ShareLogsClicked)
            runCurrent()

            assertThat(awaitItem()).isEqualTo(
                SettingsEvent.ShareLogArchive(
                    uri = "content://diagnostics/logs.zip",
                    mimeType = "application/zip",
                ),
            )
        }
    }

    private fun settingsViewModel(
        preferences: UserPreferencesRepository,
        trackFiles: TrackFileRepository = FakeTrackFileRepository(),
        mapCache: MapCacheRepository = FakeMapCacheRepository(),
        logs: LogManager = FakeLogManager(),
    ) = SettingsViewModel(
        preferencesRepository = preferences,
        exportAllActivities = ExportAllActivitiesUseCase(trackFiles),
        getMapCacheSize = GetMapCacheSizeUseCase(mapCache),
        clearMapCache = ClearMapCacheUseCase(mapCache),
        logManager = logs,
        observeTrackExport = ObserveTrackExportUseCase(trackFiles),
    )

    private class FakeLogManager(
        val settings: MutableStateFlow<LogSettings> = MutableStateFlow(LogSettings()),
        private val archiveResult: LogArchiveResult = LogArchiveResult.NoLogs,
    ) : LogManager {
        override fun observeSettings(): Flow<LogSettings> = settings

        override suspend fun setEnabled(enabled: Boolean) {
            settings.value = settings.value.copy(enabled = enabled)
        }

        override suspend fun setMinimumLevel(level: LogLevel) {
            settings.value = settings.value.copy(minimumLevel = level)
        }

        override suspend fun setIncludeLocationSamples(enabled: Boolean) {
            settings.value = settings.value.copy(includeLocationSamples = enabled)
        }

        override suspend fun getStorageInfo() = LogStorageInfo(
            sizeBytes = 128L,
            fileCount = 1,
            sessionId = "session1",
        )

        override suspend fun clearLogs() = true

        override suspend fun createArchive(): LogArchiveResult = archiveResult

        override suspend fun getActivityLogInfo(activityId: Long) = ActivityLogInfo(activityId)

        override suspend fun createActivityArchive(activityId: Long): LogArchiveResult =
            archiveResult

        override suspend fun deleteActivityLogs(activityId: Long) = true
    }

    private class FakeMapCacheRepository(
        private val clearResult: EmptyResult<DataError.Local> = Result.Success(Unit),
    ) : MapCacheRepository {
        var sizeBytes: Long? = 12_000_000L
        var cleared = false

        override suspend fun configure(): EmptyResult<DataError.Local> = Result.Success(Unit)

        override suspend fun cacheSizeBytes(): Long? = sizeBytes

        override suspend fun clear(): EmptyResult<DataError.Local> {
            cleared = true
            if (clearResult is Result.Success) sizeBytes = 0L
            return clearResult
        }
    }

    private class FakeTrackFileRepository : TrackFileRepository {
        val bulkExport = MutableStateFlow<TrackFileOperation>(TrackFileOperation.Idle)
        var exportedTreeUri: String? = null

        override fun exportAllActivities(destinationTreeUri: String, format: TrackFileFormat) {
            exportedTreeUri = destinationTreeUri
        }

        override fun observeBulkExport(): Flow<TrackFileOperation> = bulkExport

        override fun exportActivity(activityId: Long, destinationUri: String, format: TrackFileFormat) = Unit
        override fun importTracks(sourceUris: List<String>) = Unit
        override fun observeActivityExport(): Flow<TrackFileOperation> = MutableStateFlow(TrackFileOperation.Idle)
        override fun observeImport(): Flow<TrackFileOperation> = MutableStateFlow(TrackFileOperation.Idle)
        override suspend fun prepareExport(
            activityId: Long,
            format: TrackFileFormat,
        ): Result<String, TrackFileError> = Result.Success("track.gpx")

        override suspend fun prepareShare(
            activityId: Long,
            format: TrackFileFormat,
        ): Result<SharedTrackFile, TrackFileError> =
            Result.Error(TrackFileError.UNKNOWN)
    }

    private class FakeUserPreferencesRepository(
        dynamicColorEnabled: Boolean,
    ) : UserPreferencesRepository {
        val preferences = MutableStateFlow(
            UserPreferences(
                measurementSystem = MeasurementSystem.METRIC,
                autoPauseEnabled = true,
                gpsAccuracy = GpsAccuracy.BALANCED,
                autoPauseSensitivity = AutoPauseSensitivity.MEDIUM,
                dynamicColorEnabled = dynamicColorEnabled,
                privacyZone = PrivacyZone.OFF,
                onboardingCompleted = true,
                lastActivityType = ActivityType.RUNNING,
            ),
        )

        override fun observePreferences(): Flow<UserPreferences> = preferences
        override suspend fun initialize(): EmptyResult<DataError.Local> = success()
        override suspend fun setMeasurementSystem(value: MeasurementSystem): EmptyResult<DataError.Local> = success()
        override suspend fun setAutoPauseEnabled(value: Boolean): EmptyResult<DataError.Local> = success()
        override suspend fun setGpsAccuracy(value: GpsAccuracy): EmptyResult<DataError.Local> = success()
        override suspend fun setAutoPauseSensitivity(value: AutoPauseSensitivity): EmptyResult<DataError.Local> = success()
        override suspend fun setOnboardingCompleted(value: Boolean): EmptyResult<DataError.Local> = success()
        override suspend fun setLastActivityType(value: ActivityType): EmptyResult<DataError.Local> = success()

        override suspend fun setPrivacyZone(value: PrivacyZone): EmptyResult<DataError.Local> {
            preferences.value = preferences.value.copy(privacyZone = value)
            return success()
        }
        override suspend fun setDynamicColorEnabled(value: Boolean): EmptyResult<DataError.Local> {
            preferences.value = preferences.value.copy(dynamicColorEnabled = value)
            return success()
        }

        private fun success(): EmptyResult<DataError.Local> = Result.Success(Unit)
    }
}
