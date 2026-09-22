package com.ians.korno.feature.tracking.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ians.korno.core.designsystem.theme.KornoTheme
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.presentation.R as CoreR
import org.junit.Rule
import org.junit.Test

/**
 * Ряд чипов вида на экране сохранения есть только у фиксированной записи: участок там
 * один, и правка однозначна. У авто-записи виды размечены детектором и правятся на
 * экране активности, где видно, какой участок правишь (`docs/adr/0004`).
 */
class TrackingSummaryKindRowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<TrackingSummaryAction>()

    @Test
    fun fixedRecordingOffersTheKindRow() {
        setContent(TrackingSummaryState(isLoading = false, editableKind = SegmentKind.ON_FOOT))

        composeRule.onNodeWithText(string(CoreR.string.segment_kind_on_foot)).assertIsDisplayed()
    }

    @Test
    fun autoRecordingHasNoKindRow() {
        setContent(TrackingSummaryState(isLoading = false, editableKind = null))

        composeRule.onNodeWithText(string(CoreR.string.segment_kind_on_foot)).assertDoesNotExist()
    }

    @Test
    fun hikingIsOfferedAndPickingItReportsTheChoice() {
        // Хайкинг датчики не выдают вовсе: поставить его можно только руками (`docs/adr/0005`).
        setContent(TrackingSummaryState(isLoading = false, editableKind = SegmentKind.ON_FOOT))

        composeRule.onNodeWithText(string(CoreR.string.segment_kind_hiking)).performClick()

        assertThat(actions).isEqualTo(listOf(TrackingSummaryAction.SelectSegmentKind(SegmentKind.HIKING)))
    }

    private fun setContent(state: TrackingSummaryState) {
        composeRule.setContent {
            KornoTheme {
                TrackingSummaryContent(state = state, onAction = actions::add)
            }
        }
    }

    private fun string(id: Int): String = composeRule.activity.getString(id)
}
