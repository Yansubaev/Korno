package com.ians.korno.feature.tracking.presentation

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.PrivacyZone
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.UserPreferences
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.feature.tracking.domain.model.RecordingSession
import com.ians.korno.feature.tracking.domain.repository.TrackingController
import com.ians.korno.feature.tracking.domain.usecase.CancelTrackingPreparationUseCase
import com.ians.korno.feature.tracking.domain.usecase.IsAutoModeAvailableUseCase
import com.ians.korno.feature.tracking.domain.usecase.StartTrackingUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingSetupViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val controller = FakeTrackingController()
    private val preferences = FakeUserPreferencesRepository()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `remembered auto mode starts the recording in auto mode`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onAction(TrackingSetupAction.StartClicked)
        runCurrent()

        assertThat(controller.startedAutoMode).isEqualTo(true)
    }

    @Test
    fun `without recognition remembered auto quietly falls back to the type`() = runTest(testDispatcher) {
        controller.available = false
        val viewModel = viewModel()
        runCurrent()

        assertThat(viewModel.state.value.autoMode).isEqualTo(false)
    }

    @Test
    fun `a refused permission leaves the type selected and explains why`() = runTest(testDispatcher) {
        controller.available = false
        val viewModel = viewModel()
        runCurrent()

        viewModel.onAction(TrackingSetupAction.AutoModePermissionResult)

        assertThat(viewModel.state.value.autoMode).isEqualTo(false)
        assertThat(viewModel.state.value.autoModeUnavailableHint).isEqualTo(true)
    }

    @Test
    fun `choosing a kind turns auto off and remembers that`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onAction(TrackingSetupAction.SelectSegmentKind(SegmentKind.CYCLING))
        runCurrent()

        assertThat(viewModel.state.value.autoMode).isEqualTo(false)
        assertThat(preferences.state.value.lastRecordingAutoMode).isEqualTo(false)
    }

    private fun viewModel() = TrackingSetupViewModel(
        initialSegmentKind = SegmentKind.RUNNING,
        startTracking = StartTrackingUseCase(controller),
        preferencesRepository = preferences,
        cancelTrackingPreparation = CancelTrackingPreparationUseCase(controller),
        isAutoModeAvailable = IsAutoModeAvailableUseCase(controller),
    )

    private class FakeTrackingController : TrackingController {
        var available = true
        var startedAutoMode: Boolean? = null

        override fun observeSession(): Flow<RecordingSession> = emptyFlow()
        override fun prepare() = Unit
        override fun cancelPreparation() = Unit
        override suspend fun start(kind: SegmentKind, autoMode: Boolean) {
            startedAutoMode = autoMode
        }
        override fun isAutoModeAvailable(): Boolean = available
        override suspend fun changeSegmentKind(kind: SegmentKind) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override suspend fun stop(): Result<Long, DataError.Local> = Result.Success(0L)
        override suspend fun recoverActiveSession() = Unit
    }

    private class FakeUserPreferencesRepository : UserPreferencesRepository {
        val state = MutableStateFlow(
            UserPreferences(
                measurementSystem = MeasurementSystem.METRIC,
                autoPauseEnabled = true,
                gpsAccuracy = GpsAccuracy.BALANCED,
                autoPauseSensitivity = AutoPauseSensitivity.MEDIUM,
                dynamicColorEnabled = true,
                privacyZone = PrivacyZone.OFF,
                onboardingCompleted = true,
                lastSegmentKind = SegmentKind.RUNNING,
                lastRecordingAutoMode = true,
            ),
        )

        override fun observePreferences(): Flow<UserPreferences> = state
        override suspend fun initialize() = success()
        override suspend fun setMeasurementSystem(value: MeasurementSystem) = success()
        override suspend fun setAutoPauseEnabled(value: Boolean) = success()
        override suspend fun setGpsAccuracy(value: GpsAccuracy) = success()
        override suspend fun setAutoPauseSensitivity(value: AutoPauseSensitivity) = success()
        override suspend fun setDynamicColorEnabled(value: Boolean) = success()
        override suspend fun setPrivacyZone(value: PrivacyZone) = success()
        override suspend fun setOnboardingCompleted(value: Boolean) = success()
        override suspend fun setLastSegmentKind(value: SegmentKind): EmptyResult<DataError.Local> {
            state.value = state.value.copy(lastSegmentKind = value)
            return success()
        }
        override suspend fun setLastRecordingAutoMode(value: Boolean): EmptyResult<DataError.Local> {
            state.value = state.value.copy(lastRecordingAutoMode = value)
            return success()
        }

        private fun success(): EmptyResult<DataError.Local> = Result.Success(Unit)
    }
}
