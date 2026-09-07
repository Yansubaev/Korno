package com.ians.korno.feature.onboarding.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ians.korno.core.designsystem.theme.KornoTheme
import com.ians.korno.feature.onboarding.domain.model.PermissionType
import org.junit.Rule
import org.junit.Test

/**
 * Экран разрешений — единственное место, где пользователь может застрять навсегда:
 * кнопка, которая ведёт не туда, оставляет запись в фоне выключенной без объяснений.
 */
class OnboardingPermissionsContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun grantedStepShowsTheGrantedMarkInsteadOfAButton() {
        setContent(
            steps = listOf(
                step(PermissionType.FOREGROUND_LOCATION, isGranted = true),
                step(PermissionType.POST_NOTIFICATIONS),
            ),
        )

        composeRule.onNodeWithText(string(R.string.onboarding_granted)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onboarding_allow)).assertIsDisplayed()
    }

    @Test
    fun settingsOnlyStepOffersSettingsAndReportsTheClick() {
        var clicked: PermissionType? = null
        setContent(
            steps = listOf(step(PermissionType.BACKGROUND_LOCATION, action = PermissionAction.OPEN_SETTINGS)),
            onStepClick = { clicked = it.type },
        )

        composeRule.onNodeWithText(string(R.string.onboarding_open_settings)).performClick()

        assertThat(clicked).isEqualTo(PermissionType.BACKGROUND_LOCATION)
    }

    @Test
    fun backgroundLocationIsNotOfferedBeforeTheForegroundOne() {
        setContent(
            steps = listOf(
                step(PermissionType.BACKGROUND_LOCATION, action = PermissionAction.OPEN_SETTINGS, isBlocked = true),
            ),
        )

        composeRule.onNodeWithText(string(R.string.onboarding_open_settings)).assertIsNotEnabled()
        composeRule
            .onNodeWithText(string(R.string.onboarding_background_location_blocked))
            .assertIsDisplayed()
    }

    @Test
    fun unblockedStepStaysClickable() {
        setContent(steps = listOf(step(PermissionType.BACKGROUND_LOCATION, action = PermissionAction.OPEN_SETTINGS)))

        composeRule.onNodeWithText(string(R.string.onboarding_open_settings)).assertIsEnabled()
    }

    @Test
    fun continueReportsThatOnboardingIsOver() {
        var continued = false
        setContent(steps = listOf(step(PermissionType.POST_NOTIFICATIONS)), onContinueClick = { continued = true })

        composeRule.onNodeWithText(string(R.string.onboarding_continue)).performClick()

        assertThat(continued).isEqualTo(true)
    }

    private fun setContent(
        steps: List<OnboardingStep>,
        onStepClick: (OnboardingStep) -> Unit = {},
        onContinueClick: () -> Unit = {},
    ) {
        composeRule.setContent {
            KornoTheme {
                OnboardingPermissionsContent(
                    state = OnboardingUiState(steps = steps),
                    onStepClick = onStepClick,
                    onContinueClick = onContinueClick,
                )
            }
        }
    }

    private fun string(id: Int): String = composeRule.activity.getString(id)

    private fun step(
        type: PermissionType,
        isGranted: Boolean = false,
        action: PermissionAction = PermissionAction.REQUEST,
        isBlocked: Boolean = false,
    ) = OnboardingStep(type = type, isGranted = isGranted, action = action, isBlocked = isBlocked)
}
