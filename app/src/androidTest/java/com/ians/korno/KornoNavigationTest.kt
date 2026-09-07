package com.ians.korno

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.feature.activitylist.presentation.R as ActivityListR
import com.ians.korno.feature.home.presentation.R as HomeR
import com.ians.korno.feature.onboarding.presentation.R as OnboardingR
import com.ians.korno.feature.settings.presentation.R as SettingsR
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Сквозная проверка того, что разделы приложения вообще открываются друг из друга.
 *
 * Каждый экран покрыт своими тестами по отдельности, а здесь собирается то, чего в них
 * нет: настоящий граф навигации, настоящий Koin и настоящая база. Экраны узнаются не по
 * заголовку — «Activities» и «Settings» дублируются в нижней панели, — а по содержимому.
 */
@RunWith(AndroidJUnit4::class)
class KornoNavigationTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launchPastOnboarding() {
        // Стартовый экран выбирается один раз при первой сборке графа, поэтому флаг
        // ставится до запуска Activity, а не по ходу теста. Порядок важен:
        // initialize() на чистой установке проставляет значения по умолчанию, включая
        // невыполненный онбординг, и затёр бы флаг, поставленный до него.
        runBlocking {
            val preferences = preferences()
            preferences.initialize()
            preferences.setOnboardingCompleted(true)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario.close()
    }

    @Test
    fun bottomBarSwitchesBetweenTheThreeSections() {
        composeRule.onNodeWithText(string(HomeR.string.home_this_week)).assertExists()

        composeRule.onNodeWithText(string(R.string.nav_activities)).performClick()
        composeRule
            .onNodeWithText(string(ActivityListR.string.activity_list_search_placeholder))
            .assertExists()

        composeRule.onNodeWithText(string(R.string.nav_settings)).performClick()
        composeRule.onNodeWithText(string(SettingsR.string.settings_distance_units)).assertExists()

        composeRule.onNodeWithText(string(R.string.nav_home)).performClick()
        composeRule.onNodeWithText(string(HomeR.string.home_this_week)).assertExists()
    }

    @Test
    fun settingsLeadToThePermissionsScreen() {
        composeRule.onNodeWithText(string(R.string.nav_settings)).performClick()
        composeRule
            .onNodeWithText(string(SettingsR.string.settings_location_access_title))
            .performScrollTo()
            .performClick()

        // Тот же экран, что и в онбординге, поэтому начинается он со вступления.
        composeRule
            .onNodeWithText(string(OnboardingR.string.onboarding_intro_slide1_title))
            .assertExists()
        composeRule.onNodeWithText(string(OnboardingR.string.onboarding_skip)).performClick()
        composeRule
            .onNodeWithText(string(OnboardingR.string.onboarding_permissions_title))
            .assertExists()
    }

    private fun preferences(): UserPreferencesRepository =
        GlobalContext.get().get(UserPreferencesRepository::class)

    private fun string(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
