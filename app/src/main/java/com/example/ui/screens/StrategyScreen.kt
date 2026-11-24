package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.BettingMode
import com.example.data.model.StrategyRule
import com.example.data.model.StrategyRuleType
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.GoldAccent

@Composable
fun StrategyScreen(
    rules: List<StrategyRule>,
    bettingMode: BettingMode,
    onToggleRule: (StrategyRule, Boolean) -> Unit,
    onAddRule: (StrategyRule) -> Unit,
    onDeleteRule: (StrategyRule) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = EmeraldPrimary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_strategy_rule_fab")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add strategy rule")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Strategy Rules",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = if (bettingMode == BettingMode.BET_EVERY_ROUND) {
                                "Currently in 'Bet every round' mode. Switch to 'Use strategy rules' in Settings to gate bets by these cards."
                            } else {
                                "Toggle rules or add custom multiplier, color tier, cooldown, and pattern triggers."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (bettingMode == BettingMode.BET_EVERY_ROUND) {
                                GoldAccent
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                if (rules.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Tune,
                                    contentDescription = "No strategy rules",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "No strategy rules configured. Tap + to create a rule or custom pattern.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(rules, key = { it.id }) { rule ->
                        StrategyRuleCard(
                            rule = rule,
                            onToggle = { enabled -> onToggleRule(rule, enabled) },
                            onDelete = { onDeleteRule(rule) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddStrategyRuleDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { newRule ->
                onAddRule(newRule)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun StrategyRuleCard(
    rule: StrategyRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (rule.enabled) EmeraldPrimary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = rule.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val meta = buildString {
                    append(rule.type.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
                    if (rule.waitRoundsAfterTrigger > 0) append(" · Wait ${rule.waitRoundsAfterTrigger}r")
                    if (rule.cooldownSeconds > 0) append(" · Cooldown ${rule.cooldownSeconds}s")
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Delete rule",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("rule_switch_${rule.id}")
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddStrategyRuleDialog(
    onDismiss: () -> Unit,
    onConfirm: (StrategyRule) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(StrategyRuleType.CONSECUTIVE_LOW_MULTIPLIERS) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var thresholdText by remember { mutableStateOf("2.0") }
    var countText by remember { mutableStateOf("3") }
    var colorTier by remember { mutableStateOf("BLUE") }
    var customPattern by remember { mutableStateOf("<2.0,<2.0,<2.0") }
    var waitRoundsText by remember { mutableStateOf("0") }
    var cooldownText by remember { mutableStateOf("0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Strategy Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = !dropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedType.name.replace('_', ' '),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Rule type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        StrategyRuleType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name.replace('_', ' ')) },
                                onClick = {
                                    selectedType = type
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                if (selectedType == StrategyRuleType.CUSTOM_PATTERN) {
                    OutlinedTextField(
                        value = customPattern,
                        onValueChange = { customPattern = it },
                        label = { Text("Pattern (e.g. B,B,B or <1.5,<2.0,>=2.0)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = thresholdText,
                        onValueChange = { thresholdText = it },
                        label = { Text("Multiplier threshold (x)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = countText,
                        onValueChange = { countText = it },
                        label = { Text("Consecutive count") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (selectedType == StrategyRuleType.COLOR_TIER_STREAK) {
                    OutlinedTextField(
                        value = colorTier,
                        onValueChange = { colorTier = it.uppercase() },
                        label = { Text("Color tier (BLUE, PURPLE, PINK)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = waitRoundsText,
                        onValueChange = { waitRoundsText = it },
                        label = { Text("Wait rounds") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = cooldownText,
                        onValueChange = { cooldownText = it },
                        label = { Text("Cooldown (s)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val threshold = thresholdText.toDoubleOrNull() ?: 2.0
                    val count = countText.toIntOrNull()?.coerceAtLeast(1) ?: 3
                    val waitRounds = waitRoundsText.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val cooldown = cooldownText.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val finalName = name.ifBlank { selectedType.name.replace('_', ' ') }
                    val desc = when (selectedType) {
                        StrategyRuleType.CUSTOM_PATTERN -> "Pattern: $customPattern"
                        StrategyRuleType.COLOR_TIER_STREAK -> "$count consecutive $colorTier rounds"
                        StrategyRuleType.TIME_COOLDOWN -> "Wait $waitRounds rounds & ${cooldown}s after >=${threshold}x"
                        else -> "$count rounds at threshold ${threshold}x"
                    }
                    onConfirm(
                        StrategyRule(
                            name = finalName,
                            description = desc,
                            type = selectedType,
                            enabled = true,
                            thresholdMultiplier = threshold,
                            consecutiveCount = count,
                            colorTier = colorTier.ifBlank { "BLUE" },
                            customPattern = customPattern.trim(),
                            waitRoundsAfterTrigger = waitRounds,
                            cooldownSeconds = cooldown
                        )
                    )
                },
                modifier = Modifier.height(48.dp)
            ) {
                Text("Save Rule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
