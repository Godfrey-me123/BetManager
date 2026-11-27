package com.example.ui.screens

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Analytics
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
import androidx.compose.runtime.LaunchedEffect
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
import com.example.MainActivity
import com.example.ui.BetManagerViewModel

enum class BetManagerTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    HOME("home", "Home", Icons.Filled.Home, Icons.Outlined.Home, "nav_home"),
    STRATEGY("strategy", "Strategy", Icons.Filled.Tune, Icons.Outlined.Tune, "nav_strategy"),
    PATTERNS("patterns", "Patterns", Icons.Filled.Analytics, Icons.Outlined.Analytics, "nav_patterns"),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings"),
    LOGS("logs", "Logs", Icons.Filled.ReceiptLong, Icons.Outlined.ReceiptLong, "nav_logs")
}

@Composable
fun BetManagerRoot(
    viewModel: BetManagerViewModel,
    requestedTab: Int = -1,
    onRequestedTabConsumed: () -> Unit = {},
    onSwitchToOverlay: () -> Unit = {},
    onExitActivity: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: BetManagerTab.HOME.route

    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val customStrategies by viewModel.customStrategies.collectAsStateWithLifecycle()
    val patternOccurrences by viewModel.patternOccurrences.collectAsStateWithLifecycle()
    val statisticalReport by viewModel.statisticalReport.collectAsStateWithLifecycle()
    val agentExplanation by viewModel.agentExplanation.collectAsStateWithLifecycle()
    val selectedPatternGameId by viewModel.selectedPatternGameId.collectAsStateWithLifecycle()
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

    LaunchedEffect(requestedTab) {
        if (requestedTab >= 0) {
            val target = when (requestedTab) {
                MainActivity.TAB_STRATEGY -> BetManagerTab.STRATEGY
                MainActivity.TAB_HOURLY_ANALYSIS -> BetManagerTab.PATTERNS
                MainActivity.TAB_LIVE_MONITOR -> BetManagerTab.HOME
                else -> BetManagerTab.HOME
            }
            selectTab(target)
            onRequestedTabConsumed()
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
                                label = { Text(tab.label, maxLines = 1) },
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
                            onCancelAlarm = viewModel::cancelActiveAlarm,
                            onSwitchToOverlay = {
                                viewModel.switchToOverlayMode(onMinimized = onSwitchToOverlay)
                            },
                            onExitApp = {
                                viewModel.exitApp()
                                onExitActivity()
                            }
                        )
                    }
                    composable(BetManagerTab.STRATEGY.route) {
                        StrategyScreen(
                            currentConfig = config,
                            customStrategies = customStrategies,
                            patternOccurrences = patternOccurrences,
                            onSaveCustomStrategy = viewModel::saveCustomStrategy,
                            onToggleCustomStrategyEnabled = viewModel::toggleCustomStrategyEnabled,
                            onToggleCustomStrategyNotifications = viewModel::toggleCustomStrategyNotifications,
                            onDeleteCustomStrategy = viewModel::deleteCustomStrategy,
                            onReorderStrategies = viewModel::reorderCustomStrategies,
                            onSaveConfig = viewModel::saveStrategyConfig
                        )
                    }
                    composable(BetManagerTab.PATTERNS.route) {
                        PatternRecognitionScreen(
                            report = statisticalReport,
                            agentExplanation = agentExplanation,
                            selectedGameId = selectedPatternGameId,
                            onSelectGameId = viewModel::selectPatternGameId,
                            engineState = engineState
                        )
                    }
                    composable(BetManagerTab.SETTINGS.route) {
                        SimpleSettingsScreen(
                            config = config,
                            isAlarmActive = engineState.isAlarmActive,
                            alarmHighSignalEnabled = alarmHighSignalEnabled,
                            preSignalNotificationEnabled = preSignalNotificationEnabled,
                            alertSoundEnabled = alertSoundEnabled,
                            alertVibrationEnabled = alertVibrationEnabled,
                            onBaseStakeChange = viewModel::updateBaseStake,
                            onAfterLossModeChange = viewModel::updateAfterLossMode,
                            onAddAmountChange = viewModel::updateAddAmount,
                            onStopAfterLossesChange = viewModel::updateStopAfterLosses,
                            onCashOutTargetChange = viewModel::updateCashOutTarget,
                            onBettingModeChange = viewModel::updateBettingMode,
                            onDailyLossLimitChange = viewModel::updateDailyLossLimit,
                            onDailyProfitTargetChange = viewModel::updateDailyProfitTarget,
                            onBalanceFloorChange = viewModel::updateBalanceFloor,
                            onHeartbeatMsChange = viewModel::updateHeartbeatMs,
                            onStakeToleranceChange = viewModel::updateStakeTolerance,
                            onResetOverlayPosition = viewModel::resetFloatingHandPosition,
                            onResetStakeToBase = viewModel::resetStakeToBase,
                            onCancelAlarm = viewModel::cancelActiveAlarm,
                            onToggleAlarmHighSignal = viewModel::toggleAlarmHighSignal,
                            onTogglePreSignalNotification = viewModel::togglePreSignalNotification,
                            onToggleAlertSound = viewModel::toggleAlertSound,
                            onToggleAlertVibration = viewModel::toggleAlertVibration,
                            onSaveConfig = viewModel::saveStrategyConfig
                        )
                    }
                    composable(BetManagerTab.LOGS.route) {
                        LogsScreen(
                            logs = logs,
                            onClearLogs = viewModel::clearLogs,
                            onCopyLogs = viewModel::copyLogsToClipboard,
                            onShareLogs = viewModel::shareLogs
                        )
                    }
                }
            }
        }
    }
}
