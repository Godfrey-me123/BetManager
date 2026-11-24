package com.example.ui.screens

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.BetManagerViewModel

enum class BetManagerTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    HOME("home", "Home", Icons.Filled.Home, Icons.Outlined.Home, "nav_home"),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings"),
    STRATEGY("strategy", "Strategy", Icons.Filled.Tune, Icons.Outlined.Tune, "nav_strategy"),
    LOGS("logs", "Logs", Icons.Filled.ReceiptLong, Icons.Outlined.ReceiptLong, "nav_logs")
}

@Composable
fun BetManagerRoot(
    viewModel: BetManagerViewModel,
    onExitActivity: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: BetManagerTab.HOME.route

    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val lastFiveResults by viewModel.lastFiveResults.collectAsStateWithLifecycle()
    val todayProfit by viewModel.todayProfit.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val keepScreenAwake by viewModel.keepScreenAwake.collectAsStateWithLifecycle()
    val alarmHighSignalEnabled by viewModel.alarmHighSignalEnabled.collectAsStateWithLifecycle()
    val preSignalNotificationEnabled by viewModel.preSignalNotificationEnabled.collectAsStateWithLifecycle()
    val alertSoundEnabled by viewModel.alertSoundEnabled.collectAsStateWithLifecycle()
    val alertVibrationEnabled by viewModel.alertVibrationEnabled.collectAsStateWithLifecycle()

    fun selectTab(tab: BetManagerTab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 640.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!useRail) {
                    NavigationBar(windowInsets = WindowInsets.navigationBars) {
                        BetManagerTab.entries.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { selectTab(tab) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail {
                        BetManagerTab.entries.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { selectTab(tab) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = BetManagerTab.HOME.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(BetManagerTab.HOME.route) {
                        SimpleHomeScreen(
                            engineState = engineState,
                            config = config,
                            lastFiveResults = lastFiveResults,
                            todayProfit = todayProfit,
                            keepScreenAwake = keepScreenAwake,
                            onToggleStartStop = viewModel::toggleStartStop,
                            onOpenAccessibilitySettings = viewModel::openAccessibilitySettings,
                            onToggleFloatingHand = viewModel::toggleFloatingHandOverlay,
                            onToggleHandAutoBet = viewModel::toggleHandAutoBet,
                            onToggleKeepScreenAwake = viewModel::toggleKeepScreenAwake,
                            onExitApp = {
                                viewModel.exitApp()
                                onExitActivity()
                            }
                        )
                    }
                    composable(BetManagerTab.SETTINGS.route) {
                        SimpleSettingsScreen(
                            config = config,
                            alarmHighSignalEnabled = alarmHighSignalEnabled,
                            preSignalNotificationEnabled = preSignalNotificationEnabled,
                            alertSoundEnabled = alertSoundEnabled,
                            alertVibrationEnabled = alertVibrationEnabled,
                            onBaseStakeChange = viewModel::updateBaseStake,
                            onAfterLossModeChange = viewModel::updateAfterLossMode,
                            onAddAmountChange = viewModel::updateAddAmount,
                            onStopAfterLossesChange = viewModel::updateStopAfterLosses,
                            onBettingModeChange = viewModel::updateBettingMode,
                            onDailyLossLimitChange = viewModel::updateDailyLossLimit,
                            onDailyProfitTargetChange = viewModel::updateDailyProfitTarget,
                            onBalanceFloorChange = viewModel::updateBalanceFloor,
                            onHeartbeatMsChange = viewModel::updateHeartbeatMs,
                            onStakeToleranceChange = viewModel::updateStakeTolerance,
                            onResetOverlayPosition = viewModel::resetFloatingHandPosition,
                            onResetStakeToBase = viewModel::resetStakeToBase,
                            onToggleAlarmHighSignal = viewModel::toggleAlarmHighSignal,
                            onTogglePreSignalNotification = viewModel::togglePreSignalNotification,
                            onToggleAlertSound = viewModel::toggleAlertSound,
                            onToggleAlertVibration = viewModel::toggleAlertVibration
                        )
                    }
                    composable(BetManagerTab.STRATEGY.route) {
                        StrategyScreen(
                            rules = rules,
                            bettingMode = config.bettingMode,
                            onToggleRule = viewModel::toggleRule,
                            onAddRule = viewModel::addRule,
                            onDeleteRule = viewModel::deleteRule
                        )
                    }
                    composable(BetManagerTab.LOGS.route) {
                        LogsScreen(
                            logs = logs,
                            onClearLogs = viewModel::clearLogs
                        )
                    }
                }
            }
        }
    }
}
