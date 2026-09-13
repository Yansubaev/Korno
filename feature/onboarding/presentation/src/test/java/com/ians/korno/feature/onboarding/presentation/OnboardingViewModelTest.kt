package com.ians.korno.feature.onboarding.presentation

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.ians.korno.feature.onboarding.domain.model.PermissionType
import com.ians.korno.feature.onboarding.domain.repository.PermissionRepository
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.PrivacyZone
import com.ians.korno.core.domain.model.UserPreferences
import com.ians.korno.core.domain.repository.UserPreferencesRepository
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
class OnboardingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `all permissions already granted still waits for explicit continue`() {
        val viewModel = createViewModel(FakePermissionRepository(grantedAll = true))

        assertThat(viewModel.state.value.grantedSteps).hasSize(4)
        assertThat(viewModel.state.value.isFinished).isFalse()
    }

    @Test
    fun `nothing granted lists all four steps ungranted`() {
        val viewModel = createViewModel(FakePermissionRepository())

        assertThat(viewModel.state.value.steps).hasSize(4)
        assertThat(viewModel.state.value.grantedSteps).hasSize(0)
    }

    @Test
    fun `background location is offered through settings when the dialog cannot grant it`() {
        val viewModel = createViewModel(
            FakePermissionRepository(settingsOnly = setOf(PermissionType.BACKGROUND_LOCATION)),
        )

        assertThat(viewModel.step(PermissionType.BACKGROUND_LOCATION).action)
            .isEqualTo(PermissionAction.OPEN_SETTINGS)
        assertThat(viewModel.step(PermissionType.ACTIVITY_RECOGNITION).action)
            .isEqualTo(PermissionAction.REQUEST)
    }

    @Test
    fun `background location waits for the foreground one`() {
        val repository = FakePermissionRepository()
        val viewModel = createViewModel(repository)

        assertThat(viewModel.step(PermissionType.BACKGROUND_LOCATION).isBlocked).isTrue()

        repository.granted += PermissionType.FOREGROUND_LOCATION
        viewModel.onScreenResumed()

        assertThat(viewModel.step(PermissionType.BACKGROUND_LOCATION).isBlocked).isFalse()
    }

    @Test
    fun `a denial the system will not repeat sends the step to settings`() {
        val viewModel = createViewModel(FakePermissionRepository())

        viewModel.onPermissionResult(
            PermissionType.POST_NOTIFICATIONS,
            granted = false,
            canAskAgain = false,
        )

        assertThat(viewModel.step(PermissionType.POST_NOTIFICATIONS).action)
            .isEqualTo(PermissionAction.OPEN_SETTINGS)
    }

    @Test
    fun `a denial the system will repeat keeps the ordinary dialog`() {
        val viewModel = createViewModel(FakePermissionRepository())

        viewModel.onPermissionResult(
            PermissionType.POST_NOTIFICATIONS,
            granted = false,
            canAskAgain = true,
        )

        assertThat(viewModel.step(PermissionType.POST_NOTIFICATIONS).action)
            .isEqualTo(PermissionAction.REQUEST)
    }

    @Test
    fun `permission granted outside the app is picked up on resume`() {
        val repository = FakePermissionRepository()
        val viewModel = createViewModel(repository)

        repository.granted += PermissionType.BACKGROUND_LOCATION
        viewModel.onScreenResumed()

        assertThat(viewModel.state.value.grantedSteps).contains(PermissionType.BACKGROUND_LOCATION)
    }

    @Test
    fun `denied foreground location does not mark it granted`() {
        val viewModel = createViewModel(FakePermissionRepository())

        viewModel.onPermissionResult(PermissionType.FOREGROUND_LOCATION, granted = false)

        assertThat(viewModel.state.value.grantedSteps).hasSize(0)
        assertThat(viewModel.state.value.isForegroundLocationDenied).isTrue()
    }

    @Test
    fun `granted foreground location marks only that step granted`() {
        val repository = FakePermissionRepository()
        val viewModel = createViewModel(repository)

        repository.granted += PermissionType.FOREGROUND_LOCATION
        viewModel.onPermissionResult(PermissionType.FOREGROUND_LOCATION, granted = true)

        assertThat(viewModel.state.value.grantedSteps).contains(PermissionType.FOREGROUND_LOCATION)
        assertThat(viewModel.state.value.isForegroundLocationDenied).isFalse()
    }

    @Test
    fun `continue finishes onboarding regardless of granted steps`() = runTest(testDispatcher) {
        val preferences = FakeUserPreferencesRepository()
        val viewModel = OnboardingViewModel(FakePermissionRepository(), preferences)

        viewModel.onContinueClicked()
        runCurrent()

        assertThat(viewModel.state.value.isFinished).isTrue()
        assertThat(preferences.preferences.value.onboardingCompleted).isTrue()
    }

    private fun createViewModel(permissionRepository: PermissionRepository) =
        OnboardingViewModel(permissionRepository, FakeUserPreferencesRepository())

    private fun OnboardingViewModel.step(type: PermissionType): OnboardingStep =
        state.value.steps.first { it.type == type }

    private class FakePermissionRepository(
        grantedAll: Boolean = false,
        private val settingsOnly: Set<PermissionType> = emptySet(),
    ) : PermissionRepository {
        val granted: MutableSet<PermissionType> =
            if (grantedAll) PermissionType.entries.toMutableSet() else mutableSetOf()

        override fun isGranted(type: PermissionType): Boolean = type in granted
        override fun isSettingsOnly(type: PermissionType): Boolean = type in settingsOnly
    }

    private class FakeUserPreferencesRepository : UserPreferencesRepository {
        val preferences = MutableStateFlow(defaultPreferences())

        override fun observePreferences(): Flow<UserPreferences> = preferences
        override suspend fun initialize(): EmptyResult<DataError.Local> = Result.Success(Unit)
        override suspend fun setMeasurementSystem(value: MeasurementSystem): EmptyResult<DataError.Local> = success()
        override suspend fun setAutoPauseEnabled(value: Boolean): EmptyResult<DataError.Local> = success()
        override suspend fun setGpsAccuracy(value: GpsAccuracy): EmptyResult<DataError.Local> = success()
        override suspend fun setAutoPauseSensitivity(value: AutoPauseSensitivity): EmptyResult<DataError.Local> = success()
        override suspend fun setPrivacyZone(value: PrivacyZone): EmptyResult<DataError.Local> = success()
        override suspend fun setDynamicColorEnabled(value: Boolean): EmptyResult<DataError.Local> = success()
        override suspend fun setOnboardingCompleted(value: Boolean): EmptyResult<DataError.Local> {
            preferences.value = preferences.value.copy(onboardingCompleted = value)
            return success()
        }
        override suspend fun setLastActivityType(value: ActivityType): EmptyResult<DataError.Local> = success()

        override suspend fun setLastRecordingAutoMode(value: Boolean): EmptyResult<DataError.Local> = success()

        private fun success(): EmptyResult<DataError.Local> = Result.Success(Unit)

        private companion object {
            fun defaultPreferences() = UserPreferences(
                measurementSystem = MeasurementSystem.METRIC,
                autoPauseEnabled = true,
                gpsAccuracy = GpsAccuracy.BALANCED,
                autoPauseSensitivity = AutoPauseSensitivity.MEDIUM,
                dynamicColorEnabled = true,
                privacyZone = PrivacyZone.OFF,
                onboardingCompleted = false,
                lastActivityType = ActivityType.RUNNING,
            )
        }
    }
}
