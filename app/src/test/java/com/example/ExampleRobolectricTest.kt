package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.example.data.engine.EngineUiState
import com.example.data.model.StrategyConfig
import com.example.ui.screens.SimpleHomeScreen
import com.example.ui.theme.BetManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `app name is BetManager`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("BetManager", appName)
    }

    @Test
    fun `home screen displays start stop button and status`() {
        composeRule.setContent {
            BetManagerTheme {
                SimpleHomeScreen(
                    engineState = EngineUiState(statusLine = "Waiting for a trigger"),
                    config = StrategyConfig(),
                    lastFiveResults = emptyList(),
                    todayProfit = 0.0,
                    onToggleStartStop = {},
                    onOpenAccessibilitySettings = {},
                    onToggleFloatingHand = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("start_stop_button").assertIsDisplayed()
        composeRule.onNodeWithTag("home_status_line").assertIsDisplayed()
    }
}
