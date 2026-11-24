package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.engine.EngineUiState
import com.example.data.engine.PatternSignalStage
import com.example.data.engine.StakeCalculator
import com.example.data.model.RoundRecord
import com.example.data.model.StrategyConfig
import com.example.ui.components.BmButton
import com.example.ui.components.BmButtonKind
import com.example.ui.theme.CoralDanger
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.GoldAccent
import com.example.ui.theme.MonoFontFamily

@Composable
fun SimpleHomeScreen(
    engineState: EngineUiState,
    config: StrategyConfig,
    lastFiveResults: List<RoundRecord>,
    todayProfit: Double,
    keepScreenAwake: Boolean = false,
    onToggleStartStop: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onToggleFloatingHand: (Boolean) -> Unit,
    onToggleHandAutoBet: (Boolean) -> Unit = {},
    onToggleKeepScreenAwake: (Boolean) -> Unit = {},
    onExitApp: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val statusColor by animateColorAsState(
        targetValue = when {
            !engineState.serviceConnected -> CoralDanger
            engineState.isRunning -> EmeraldPrimary
            else -> GoldAccent
        },
        label = "statusColor"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "BetManager",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "${engineState.gameName} · ${engineState.siteUrl}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = statusColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = when {
                                    !engineState.serviceConnected -> "Service Off"
                                    engineState.isRunning -> "Active"
                                    else -> "Ready"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = statusColor
                            )
                        }
                    }
                }
            }

            if (!engineState.serviceConnected) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                        ),
                        border = BorderStroke(1.dp, CoralDanger.copy(alpha = 0.45f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AccessibilityNew,
                                    contentDescription = "Accessibility required",
                                    tint = CoralDanger
                                )
                                Text(
                                    text = "Enable BetManager Automation",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = "Turn on BetManager Automation in Android Accessibility Settings so BetManager can track current crashes and tap verified bets.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            BmButton(
                                text = "Open Accessibility Settings",
                                onClick = onOpenAccessibilitySettings,
                                kind = BmButtonKind.SOFT,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("enable_accessibility_button")
                            )
                        }
                    }
                }
            }

            // Primary Start/Stop, Exit & Floating BET Hand Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "LIVE STATUS",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = engineState.statusLine,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("home_status_line")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            BmButton(
                                text = if (engineState.isRunning) "Stop" else "Start",
                                onClick = onToggleStartStop,
                                kind = if (engineState.isRunning) BmButtonKind.STOP else BmButtonKind.GO,
                                icon = if (engineState.isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("start_stop_button")
                            )
                            BmButton(
                                text = "Exit",
                                onClick = onExitApp,
                                kind = BmButtonKind.STOP,
                                icon = Icons.AutoMirrored.Filled.ExitToApp,
                                modifier = Modifier
                                    .weight(0.68f)
                                    .testTag("exit_app_button")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.TouchApp,
                                        contentDescription = "Floating BET hand",
                                        tint = EmeraldPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Floating BET Hand & Menu",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = engineState.betTargetSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (engineState.betTargetDetected) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = config.showFloatingHand,
                                onCheckedChange = onToggleFloatingHand,
                                modifier = Modifier.testTag("floating_hand_toggle")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto Bet (BET Hand)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (engineState.handAutoBetEnabled) {
                                        "Automatically taps calibrated BET hand each trigger"
                                    } else {
                                        "Manual tap only — enable for automatic hand betting"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (engineState.handAutoBetEnabled) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = engineState.handAutoBetEnabled,
                                onCheckedChange = onToggleHandAutoBet,
                                modifier = Modifier.testTag("auto_bet_hand_toggle")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.BrightnessHigh,
                                        contentDescription = "Never sleep keep screen active",
                                        tint = if (keepScreenAwake) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Never Sleep (Keep Screen On)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = if (keepScreenAwake) {
                                        "Screen stays active while watching and betting"
                                    } else {
                                        "Allow normal Android screen timeout"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (keepScreenAwake) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = keepScreenAwake,
                                onCheckedChange = onToggleKeepScreenAwake,
                                modifier = Modifier.testTag("never_sleep_toggle")
                            )
                        }
                    }
                }
            }

            // Recorded Multipliers & Strategy Pattern Tracker Box
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.testTag("pattern_tracker_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "RECORDED CRASHES & PATTERNS",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            val (badgeText, badgeBg, badgeTextCol) = when (engineState.patternSignalStage) {
                                PatternSignalStage.HIGH_SIGNAL -> Triple("🔔 TIME TO BET!", EmeraldPrimary.copy(alpha = 0.18f), EmeraldPrimary)
                                PatternSignalStage.PRE_SIGNAL -> Triple("⚠️ PRE-SIGNAL (1 AWAY)", GoldAccent.copy(alpha = 0.18f), GoldAccent)
                                PatternSignalStage.NONE -> if (config.bettingMode == com.example.data.model.BettingMode.BET_EVERY_ROUND) {
                                    Triple("BET EVERY ROUND", MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), MaterialTheme.colorScheme.primary)
                                } else {
                                    Triple("WATCHING", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Surface(
                                color = badgeBg,
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, badgeTextCol.copy(alpha = 0.45f))
                            ) {
                                Text(
                                    text = badgeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeTextCol,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // Recorded Multipliers horizontal row
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Live Multipliers (${engineState.recordedMultipliers.size} recorded)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Newest ← Oldest",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (engineState.recordedMultipliers.isEmpty()) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp)),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = if (engineState.isRunning) "Watching game screen for crashed multipliers..." else "Press Start to monitor crashed multipliers",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            } else {
                                LazyRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("recorded_multipliers_row"),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(engineState.recordedMultipliers) { mult ->
                                        val (pillBg, pillStroke, pillText) = when {
                                            mult < 2.0 -> Triple(Color(0xFFD8E4F0), Color(0xFF5B88C4), Color(0xFF1C4278))
                                            mult < 10.0 -> Triple(Color(0xFFE8DCF0), Color(0xFF9668B8), Color(0xFF58287A))
                                            else -> Triple(Color(0xFFF2D8E6), Color(0xFFC25B96), Color(0xFF821A58))
                                        }
                                        Surface(
                                            color = pillBg,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, pillStroke)
                                        ) {
                                            Text(
                                                text = "${StakeCalculator.formatStake(mult)}x",
                                                fontFamily = MonoFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = pillText,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Pattern Progress and Trigger Status
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = engineState.patternProgress.ruleName.ifBlank { "Strategy Pattern" },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${engineState.patternProgress.matchedCount}/${engineState.patternProgress.requiredCount}",
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (engineState.isPatternMatched) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            LinearProgressIndicator(
                                progress = { engineState.patternProgress.progressPercent },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp)),
                                color = if (engineState.isPatternMatched) EmeraldPrimary else if (engineState.patternSignalStage == PatternSignalStage.PRE_SIGNAL) GoldAccent else EmeraldPrimary.copy(alpha = 0.7f),
                                trackColor = MaterialTheme.colorScheme.surface
                            )

                            Text(
                                text = engineState.patternProgress.statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("pattern_progress_text")
                            )
                        }
                    }
                }
            }

            // Telemetry Metrics Grid
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricTile(
                        label = "CURRENT STAKE",
                        value = "${StakeCalculator.formatStake(engineState.currentStake)} ${engineState.currency}",
                        subtitle = if (engineState.consecutiveLosses > 0) {
                            "${engineState.consecutiveLosses} loss streak"
                        } else {
                            "Base stake active"
                        },
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "LAST CRASH",
                        value = engineState.latestCrashMultiplier?.let {
                            "${StakeCalculator.formatStake(it)}x"
                        } ?: "--",
                        subtitle = "${engineState.monitoredCrashCount} new tracked",
                        accentColor = EmeraldPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricTile(
                        label = "BALANCE",
                        value = engineState.balance?.let {
                            "${StakeCalculator.formatStake(it)} ${engineState.currency}"
                        } ?: "Not visible",
                        subtitle = if (config.balanceFloor > 0.0) {
                            "Floor ${StakeCalculator.formatStake(config.balanceFloor)}"
                        } else {
                            "Non-blocking read"
                        },
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "TODAY'S PROFIT",
                        value = formatSignedProfit(todayProfit, engineState.currency),
                        subtitle = engineState.monitorStatusLabel,
                        accentColor = if (todayProfit >= 0.0) EmeraldPrimary else CoralDanger,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Last 5 Results
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "LAST 5 RESULTS",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (lastFiveResults.isEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = "No results yet",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "No bets settled yet. Placed bets and crash results will appear here.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            lastFiveResults.forEach { record ->
                                val isWin = record.won == true
                                val badgeColor = if (isWin) EmeraldPrimary else CoralDanger
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = if (isWin) "Win" else "Loss",
                                            tint = badgeColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Column {
                                            Text(
                                                text = if (isWin) "WIN" else "LOSS",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = badgeColor
                                            )
                                            Text(
                                                text = "Crash ${StakeCalculator.formatStake(record.crashMultiplier)}x · Stake ${StakeCalculator.formatStake(record.stakePlaced ?: 0.0)}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Text(
                                        text = formatSignedProfit(record.profitDelta, engineState.currency),
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = badgeColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    accentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatSignedProfit(amount: Double, currency: String): String {
    val prefix = if (amount >= 0.0) "+" else "-"
    return "$prefix${StakeCalculator.formatStake(kotlin.math.abs(amount))} $currency"
}
