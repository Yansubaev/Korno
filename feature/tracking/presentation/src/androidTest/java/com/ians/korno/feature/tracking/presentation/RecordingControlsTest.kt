package com.ians.korno.feature.tracking.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ians.korno.core.designsystem.theme.KornoTheme
import com.ians.korno.core.domain.model.RecordingStatus
import org.junit.Rule
import org.junit.Test

/**
 * Кнопки записи — то немногое, что нельзя переделать после ошибки: перепутанные
 * пауза и стоп заканчивают тренировку вместо того, чтобы её приостановить.
 */
class RecordingControlsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<TrackingRecordingAction>()

    @Test
    fun recordingOffersPause() {
        setContent(RecordingStatus.RECORDING)

        composeRule.onNodeWithContentDescription(string(R.string.tracking_pause)).performClick()

        assertThat(actions).isEqualTo(listOf(TrackingRecordingAction.PauseClicked))
    }

    @Test
    fun manualPauseOffersResume() {
        setContent(RecordingStatus.MANUAL_PAUSE)

        composeRule.onNodeWithContentDescription(string(R.string.tracking_resume)).performClick()

        assertThat(actions).isEqualTo(listOf(TrackingRecordingAction.ResumeClicked))
    }

    @Test
    fun autoPauseStillOffersManualPause() {
        // Автопауза — состояние трека, а не пользователя: остановить запись рукой
        // он всё ещё вправе.
        setContent(RecordingStatus.AUTO_PAUSE)

        composeRule.onNodeWithContentDescription(string(R.string.tracking_pause)).assertIsEnabled()
    }

    @Test
    fun waitingForGpsHasNothingToPause() {
        setContent(RecordingStatus.WAITING_FOR_GPS)

        composeRule.onNodeWithContentDescription(string(R.string.tracking_pause)).assertIsNotEnabled()
    }

    @Test
    fun finishAsksBeforeItStops() {
        setContent(RecordingStatus.RECORDING)

        composeRule.onNodeWithContentDescription(string(R.string.tracking_stop)).performClick()

        // Не ConfirmFinish: между кнопкой и концом записи стоит диалог.
        assertThat(actions).isEqualTo(listOf(TrackingRecordingAction.RequestFinish))
    }

    @Test
    fun mapToggleCyclesTheSheet() {
        setContent(RecordingStatus.RECORDING)

        composeRule.onNodeWithContentDescription(string(R.string.tracking_cd_toggle_map)).performClick()

        assertThat(actions).isEqualTo(listOf(TrackingRecordingAction.CycleMapSheetMode))
    }

    private fun setContent(status: RecordingStatus) {
        composeRule.setContent {
            KornoTheme {
                RecordingControls(
                    state = TrackingRecordingState(recordingStatus = status),
                    onAction = actions::add,
                )
            }
        }
    }

    private fun string(id: Int): String = composeRule.activity.getString(id)
}
