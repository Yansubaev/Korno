package com.ians.korno.feature.tracking.domain.usecase

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.feature.tracking.domain.model.RecordingSession
import com.ians.korno.feature.tracking.domain.repository.TrackingController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TrackingUseCasesTest {
    private val controller = FakeTrackingController()

    @Test
    fun `start delegates the selected activity type to the controller`() = runTest {
        StartTrackingUseCase(controller)(ActivityType.CYCLING, autoMode = false)

        assertThat(controller.startedWith).isEqualTo(ActivityType.CYCLING)
    }

    @Test
    fun `prepare and cancel preparation delegate to controller`() {
        PrepareTrackingUseCase(controller)()
        CancelTrackingPreparationUseCase(controller)()

        assertThat(controller.prepareCalled).isTrue()
        assertThat(controller.cancelPreparationCalled).isTrue()
    }

    @Test
    fun `pause delegates to the controller`() {
        PauseTrackingUseCase(controller)()

        assertThat(controller.pauseCalled).isTrue()
    }

    @Test
    fun `resume delegates to the controller`() {
        ResumeTrackingUseCase(controller)()

        assertThat(controller.resumeCalled).isTrue()
    }

    @Test
    fun `stop returns the controller result`() = runTest {
        val result = StopTrackingUseCase(controller)()

        assertThat(controller.stopCalled).isTrue()
        assertThat(result).isEqualTo(Result.Success(42L))
    }

    @Test
    fun `recover delegates to the controller`() = runTest {
        RecoverActiveSessionUseCase(controller)()

        assertThat(controller.recoverCalled).isTrue()
    }

    @Test
    fun `observe forwards the controller session flow`() = runTest {
        ObserveTrackingSessionUseCase(controller)().test {
            assertThat(awaitItem()).isEqualTo(RecordingSession())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private class FakeTrackingController : TrackingController {
        var startedWith: ActivityType? = null
        var pauseCalled = false
        var resumeCalled = false
        var stopCalled = false
        var recoverCalled = false
        var prepareCalled = false
        var cancelPreparationCalled = false
        private val sessionFlow = MutableStateFlow(RecordingSession())

        override fun observeSession(): Flow<RecordingSession> = sessionFlow

        override fun prepare() {
            prepareCalled = true
        }

        override fun cancelPreparation() {
            cancelPreparationCalled = true
        }

        override suspend fun start(activityType: ActivityType, autoMode: Boolean) {
            startedWith = activityType
        }

        override fun isAutoModeAvailable(): Boolean = true

        override suspend fun changeSegmentKind(kind: SegmentKind) = Unit

        override fun pause() {
            pauseCalled = true
        }

        override fun resume() {
            resumeCalled = true
        }

        override suspend fun stop(): Result<Long, DataError.Local> {
            stopCalled = true
            return Result.Success(42L)
        }

        override suspend fun recoverActiveSession() {
            recoverCalled = true
        }
    }
}
