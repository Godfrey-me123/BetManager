package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.AfterLossMode
import com.example.data.model.BettingMode
import com.example.data.model.StrategyConfig
import com.example.ui.theme.EmeraldPrimary

@Composable
fun SimpleSettingsScreen(
    config: StrategyConfig,
    isAlarmActive: Boolean = false,
    alarmHighSignalEnabled: Boolean = true,
    preSignalNotificationEnabled: Boolean = true,
    alertSoundEnabled: Boolean = true,
    alertVibrationEnabled: Boolean = true,
    onBaseStakeChange: (Double) -> Unit = {},
    onAfterLossModeChange: (AfterLossMode) -> Unit = {},
    onAddAmountChange: (Double) -> Unit = {},
    onStopAfterLossesChange: (Int) -> Unit = {},
    onCashOutTargetChange: (Double) -> Unit = {},
    onBettingModeChange: (BettingMode) -> Unit = {},
    onDailyLossLimitChange: (Double) -> Unit = {},
    onDailyProfitTargetChange: (Double) -> Unit = {},
    onBalanceFloorChange: (Double) -> Unit = {},
    onHeartbeatMsChange: (Long) -> Unit = {},
    onStakeToleranceChange: (Double) -> Unit = {},
    onResetOverlayPosition: () -> Unit = {},
    onResetStakeToBase: () -> Unit = {},
    onCancelAlarm: () -> Unit = {},
    onToggleAlarmHighSignal: (Boolean) -> Unit = {},
    onTogglePreSignalNotification: (Boolean) -> Unit = {},
    onToggleAlertSound: (Boolean) -> Unit = {},
    onToggleAlertVibration: (Boolean) -> Unit = {},
    onSaveConfig: ((StrategyConfig) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var baseStakeText by remember { mutableStateOf(config.baseStake.toString()) }
    var addAmountText by remember { mutableStateOf(config.addAmount.toString()) }
    var stopLossesText by remember { mutableStateOf(config.stopAfterLosses.toString()) }
    var cashOutTargetText by remember { mutableStateOf(config.cashOutTarget.toString()) }
    var dailyLossText by remember { mutableStateOf(config.dailyLossLimit.toString()) }
    var dailyProfitText by remember { mutableStateOf(config.dailyProfitTarget.toString()) }
    var balanceFloorText by remember { mutableStateOf(config.balanceFloor.toString()) }
    var heartbeatText by remember { mutableStateOf(config.scanHeartbeatMs.toString()) }
    var toleranceText by remember { mutableStateOf(config.stakeTolerance.toString()) }
    var selectedAfterLossMode by remember(config.afterLoss) { mutableStateOf(config.afterLoss) }
    var selectedBettingMode by remember(config.bettingMode) { mutableStateOf(config.bettingMode) }
    var isSavedMessageVisible by remember { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(config.baseStake) {
        if (baseStakeText.toDoubleOrNull() != config.baseStake) baseStakeText = config.baseStake.toString()
    }
    LaunchedEffect(config.addAmount) {
        if (addAmountText.toDoubleOrNull() != config.addAmount) addAmountText = config.addAmount.toString()
    }
    LaunchedEffect(config.stopAfterLosses) {
        if (stopLossesText.toIntOrNull() != config.stopAfterLosses) stopLossesText = config.stopAfterLosses.toString()
    }
    LaunchedEffect(config.cashOutTarget) {
        if (cashOutTargetText.toDoubleOrNull() != config.cashOutTarget) cashOutTargetText = config.cashOutTarget.toString()
    }

    fun handleSave() {
        val bStake = baseStakeText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: config.baseStake
        val aAmount = addAmountText.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: config.addAmount
        val sLosses = stopLossesText.toIntOrNull()?.takeIf { it >= 0 } ?: config.stopAfterLosses
        val cTarget = cashOutTargetText.toDoubleOrNull()?.takeIf { it >= 1.01 } ?: config.cashOutTarget
        val dLoss = dailyLossText.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: config.dailyLossLimit
        val dProfit = dailyProfitText.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: config.dailyProfitTarget
        val bFloor = balanceFloorText.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: config.balanceFloor
        val hBeat = heartbeatText.toLongOrNull()?.coerceIn(2000L, 15000L) ?: config.scanHeartbeatMs
        val tol = toleranceText.toDoubleOrNull()?.coerceIn(0.01, 1.0) ?: config.stakeTolerance

        val updated = config.copy(
            baseStake = bStake,
            afterLoss = selectedAfterLossMode,
            addAmount = aAmount,
            stopAfterLosses = sLosses,
            cashOutTarget = cTarget,
            targetMultiplier = cTarget,
            bettingMode = selectedBettingMode,
            dailyLossLimit = dLoss,
            dailyProfitTarget = dProfit,
            balanceFloor = bFloor,
            scanHeartbeatMs = hBeat,
            stakeTolerance = tol
        ).syncProgressionFromLegacy()
        if (onSaveConfig != null) {
            onSaveConfig(updated)
        } else {
            onBaseStakeChange(bStake)
            onAfterLossModeChange(selectedAfterLossMode)
            onAddAmountChange(aAmount)
            onStopAfterLossesChange(sLosses)
            onCashOutTargetChange(cTarget)
            onBettingModeChange(selectedBettingMode)
            onDailyLossLimitChange(dLoss)
            onDailyProfitTargetChange(dProfit)
            onBalanceFloorChange(bFloor)
            onHeartbeatMsChange(hBeat)
            onStakeToleranceChange(tol)
        }
        isSavedMessageVisible = true
    }

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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Button(
                        onClick = { handleSave() },
                        modifier = Modifier.testTag("save_settings_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Save")
                    }
                }
            }

            if (isSavedMessageVisible) {
                item {
                    Surface(
                        color = EmeraldPrimary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = EmeraldPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Settings saved successfully and synced with engine",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EmeraldPrimary
                            )
                        }
                    }
                }
            }

            // 4.1 Stake
            item {
                SettingsSectionCard(title = "1. Stake & Progression") {
                    OutlinedTextField(
                        value = baseStakeText,
                        onValueChange = { raw ->
                            baseStakeText = raw
                        },
                        label = { Text("Base stake") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("base_stake_input")
                    )

                    Text(
                        text = "After a loss",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val options = listOf(
                            AfterLossMode.DOUBLE to "Double",
                            AfterLossMode.ADD to "Add amount",
                            AfterLossMode.KEEP to "Keep same"
                        )
                        options.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = selectedAfterLossMode == mode,
                                onClick = { selectedAfterLossMode = mode },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                                modifier = Modifier.testTag("after_loss_${mode.name.lowercase()}")
                            ) {
                                Text(label, maxLines = 1)
                            }
                        }
                    }

                    AnimatedVisibility(visible = selectedAfterLossMode == AfterLossMode.ADD) {
                        OutlinedTextField(
                            value = addAmountText,
                            onValueChange = { raw ->
                                addAmountText = raw
                            },
                            label = { Text("Add amount after loss") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("add_amount_input")
                        )
                    }

                    Text(
                        text = "After a win: automatically returns to base stake.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmeraldPrimary
                    )

                    OutlinedTextField(
                        value = stopLossesText,
                        onValueChange = { raw ->
                            stopLossesText = raw
                        },
                        label = { Text("Stop after N losses in a row (0 = never)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("stop_after_losses_input")
                    )

                    OutlinedTextField(
                        value = cashOutTargetText,
                        onValueChange = { raw ->
                            cashOutTargetText = raw
                        },
                        label = { Text("Target win multiplier (e.g. 2.0x)") },
                        supportingText = {
                            Text("Crash >= target counts as WIN (resets to base); crash < target triggers Double / Add / Keep.")
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("target_multiplier_input")
                    )
                }
            }

            // 4.3 When to bet
            item {
                SettingsSectionCard(title = "2. When to Bet") {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val modes = listOf(
                            BettingMode.BET_EVERY_ROUND to "Bet every round",
                            BettingMode.USE_STRATEGY_RULES to "Use strategy rules"
                        )
                        modes.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = selectedBettingMode == mode,
                                onClick = { selectedBettingMode = mode },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                                modifier = Modifier.testTag("betting_mode_${mode.name.lowercase()}")
                            ) {
                                Text(label, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // Pattern Alerts & Alarms
            item {
                SettingsSectionCard(title = "3. Pattern Alerts & Alarms") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Alarm: High Signal (Time to Bet)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Fires when full strategy pattern is met — exact time to bet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = alarmHighSignalEnabled,
                            onCheckedChange = onToggleAlarmHighSignal,
                            modifier = Modifier.testTag("alarm_high_signal_toggle")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Notification: Pre-Signal (1 Away)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Early warning 1 crash before higher signal to prepare next bet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = preSignalNotificationEnabled,
                            onCheckedChange = onTogglePreSignalNotification,
                            modifier = Modifier.testTag("presignal_notification_toggle")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sound Alerts",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Play alarm ringtone on high signal and tone on pre-signal",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = alertSoundEnabled,
                            onCheckedChange = onToggleAlertSound,
                            modifier = Modifier.testTag("alert_sound_toggle")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Vibration Alerts",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Distinct haptic vibrations on pattern alerts",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = alertVibrationEnabled,
                            onCheckedChange = onToggleAlertVibration,
                            modifier = Modifier.testTag("alert_vibration_toggle")
                        )
                    }
                    if (isAlarmActive) {
                        OutlinedButton(
                            onClick = onCancelAlarm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("settings_stop_alarm_button")
                        ) {
                            Text("Stop Active Alarm", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // 4.4 Safety
            item {
                SettingsSectionCard(title = "4. Safety Limits") {
                    OutlinedTextField(
                        value = dailyLossText,
                        onValueChange = { raw -> dailyLossText = raw },
                        label = { Text("Stop when today's loss reaches (0 = off)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = dailyProfitText,
                        onValueChange = { raw -> dailyProfitText = raw },
                        label = { Text("Stop when today's profit reaches (0 = off)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = balanceFloorText,
                        onValueChange = { raw -> balanceFloorText = raw },
                        label = { Text("Balance floor: stop if balance < (0 = off)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 4.5 More
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clickable { moreExpanded = !moreExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "More",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                imageVector = if (moreExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = "Toggle more settings"
                            )
                        }

                        AnimatedVisibility(visible = moreExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = heartbeatText,
                                    onValueChange = { raw -> heartbeatText = raw },
                                    label = { Text("Heartbeat scan interval (ms)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = toleranceText,
                                    onValueChange = { raw -> toleranceText = raw },
                                    label = { Text("Stake read-back tolerance") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = onResetStakeToBase,
                                        modifier = Modifier
                                            .weight(1f)
                                             .height(48.dp)
                                    ) {
                                        Icon(Icons.Filled.RestartAlt, contentDescription = null)
                                        Text(" Reset Stake")
                                    }
                                    OutlinedButton(
                                        onClick = onResetOverlayPosition,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Text("Reset Hand Pos")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { handleSave() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("save_settings_bottom_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save Configuration")
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
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
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}
