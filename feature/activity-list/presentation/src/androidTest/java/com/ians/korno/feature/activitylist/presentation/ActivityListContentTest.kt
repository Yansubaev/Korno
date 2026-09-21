package com.ians.korno.feature.activitylist.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ians.korno.core.designsystem.theme.KornoTheme
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.presentation.R as CoreR
import org.junit.Rule
import org.junit.Test

class ActivityListContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<ActivityListAction>()

    @Test
    fun finishedLoadingWithoutActivitiesShowsTheEmptyState() {
        setContent(ActivityListState(isLoading = false))

        composeRule.onNodeWithText(string(R.string.activity_list_empty)).assertIsDisplayed()
    }

    @Test
    fun loadingDoesNotShowTheEmptyStateYet() {
        setContent(ActivityListState(isLoading = true))

        composeRule.onNodeWithText(string(R.string.activity_list_empty)).assertDoesNotExist()
    }

    @Test
    fun tappingARowOpensThatActivity() {
        var opened: Long? = null
        setContent(ActivityListState(isLoading = false, items = listOf(item(id = 42L))), onOpenActivity = { opened = it })

        composeRule.onNodeWithText("Morning run").performClick()

        assertThat(opened).isEqualTo(42L)
    }

    @Test
    fun visibleRowAsksForItsRoutePreview() {
        setContent(ActivityListState(isLoading = false, items = listOf(item(id = 42L))))

        composeRule.waitForIdle()

        assertThat(actions).contains(ActivityListAction.RowShown(42L))
    }

    @Test
    fun searchQueryIsReported() {
        setContent(ActivityListState(isLoading = false))

        composeRule.onNode(hasSetTextAction()).performTextInput("hike")

        assertThat(actions).contains(ActivityListAction.SetSearchQuery("hike"))
    }

    @Test
    fun runningImportHidesTheImportButton() {
        var importClicks = 0
        setContent(ActivityListState(isLoading = false, isImporting = true), onImportClick = { importClicks++ })

        composeRule.onNodeWithContentDescription(string(R.string.activity_list_import)).assertDoesNotExist()
        assertThat(importClicks).isEqualTo(0)
    }

    @Test
    fun idleImportButtonOpensTheFilePicker() {
        var importClicks = 0
        setContent(ActivityListState(isLoading = false), onImportClick = { importClicks++ })

        composeRule.onNodeWithContentDescription(string(R.string.activity_list_import)).performClick()

        assertThat(importClicks).isEqualTo(1)
    }

    @Test
    fun kindFilterIsReported() {
        setContent(
            ActivityListState(isLoading = false, availableKinds = listOf(SegmentKind.CYCLING)),
        )

        composeRule.onNodeWithText(string(CoreR.string.segment_kind_cycling)).performClick()

        assertThat(actions).contains(ActivityListAction.SetFilterKind(SegmentKind.CYCLING))
    }

    private fun setContent(
        state: ActivityListState,
        onOpenActivity: (Long) -> Unit = {},
        onImportClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            KornoTheme {
                ActivityListContent(
                    state = state,
                    onAction = actions::add,
                    onOpenActivity = onOpenActivity,
                    onImportClick = onImportClick,
                )
            }
        }
    }

    private fun string(id: Int): String = composeRule.activity.getString(id)

    private fun item(id: Long) = ActivityListItemUi(
        id = id,
        title = "Morning run",
        supportingText = "5 September, 07:00",
        distanceText = "5.18 km",
        type = ActivityType.RUNNING,
    )
}
