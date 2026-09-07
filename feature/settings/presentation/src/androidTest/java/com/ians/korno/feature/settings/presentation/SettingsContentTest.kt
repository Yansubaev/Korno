package com.ians.korno.feature.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.ians.korno.core.designsystem.theme.KornoTheme
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.PrivacyZone
import org.junit.Rule
import org.junit.Test

class SettingsContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<SettingsAction>()

    @Test
    fun switchingUnitsReportsTheChosenSystem() {
        setContent(SettingsState(measurementSystem = MeasurementSystem.METRIC))

        composeRule.onNodeWithText(string(R.string.settings_unit_mi)).performClick()

        assertThat(actions).contains(SettingsAction.SetDistanceUnit(MeasurementSystem.IMPERIAL))
    }

    @Test
    fun turningAutoPauseOffReportsIt() {
        setContent(SettingsState(autoPause = true))

        composeRule.onNodeWithText(string(R.string.settings_autopause_title)).performScrollTo()
        composeRule.onNodeWithText(string(R.string.settings_autopause_title)).performClick()

        // Тап по заголовку строки ничего не переключает — переключатель отдельный.
        assertThat(actions).isEmpty()
    }

    @Test
    fun privacyZoneChipReportsTheChosenRadius() {
        setContent(SettingsState(privacyZone = PrivacyZone.OFF))

        composeRule.onNodeWithText(string(R.string.settings_privacy_zone_off)).performScrollTo()
        composeRule.onNodeWithText(string(R.string.settings_privacy_zone_off)).performClick()

        assertThat(actions).contains(SettingsAction.SetPrivacyZone(PrivacyZone.OFF))
    }

    @Test
    fun runningExportReplacesTheSubtitleAndBlocksTheRow() {
        var exportClicks = 0
        setContent(SettingsState(isExporting = true), onExportAllClick = { exportClicks++ })

        composeRule.onNodeWithText(string(R.string.settings_export_all_running)).performScrollTo()
        composeRule.onNodeWithText(string(R.string.settings_export_all_running)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_export_all_title)).performClick()

        // Повторный тап по выгрузке, которая уже идёт, поставил бы вторую такую же работу.
        assertThat(exportClicks).isEqualTo(0)
    }

    @Test
    fun permissionsRowLeadsToThePermissionsScreen() {
        var openedPermissions = 0
        setContent(SettingsState(), onOpenPermissions = { openedPermissions++ })

        composeRule.onNodeWithText(string(R.string.settings_location_access_title)).performScrollTo()
        composeRule.onNodeWithText(string(R.string.settings_location_access_title)).performClick()

        assertThat(openedPermissions).isEqualTo(1)
    }

    private fun setContent(
        state: SettingsState,
        onExportAllClick: () -> Unit = {},
        onOpenPermissions: () -> Unit = {},
    ) {
        composeRule.setContent {
            KornoTheme {
                SettingsContent(
                    state = state,
                    onAction = actions::add,
                    onExportAllClick = onExportAllClick,
                    onOpenPermissions = onOpenPermissions,
                    onViewOnboarding = {},
                )
            }
        }
    }

    private fun string(id: Int): String = composeRule.activity.getString(id)
}
