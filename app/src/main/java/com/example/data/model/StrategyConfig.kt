package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class StakeProgressionMode(val label: String) {
    DISABLED("Disabled (Flat Stake)"),
    ADD_AMOUNT("Add Amount"),
    DOUBLE_AMOUNT("Double Amount (2x)")
}

enum class AfterLossMode {
    DOUBLE,
    ADD,
    KEEP
}

enum class BettingMode {
    BET_EVERY_ROUND,
    USE_STRATEGY_RULES
}

enum class StrategyCategory(val title: String) {
    STANDARD_RECOVERY("Standard Recovery Strategies"),
    MULTIPLIER_WAIT("Multiplier-Range Waiting Strategies"),
    QUICK_RECOVERY("Quick Recovery Strategies")
}

enum class StrategyRuleId(
    val displayName: String,
    val description: String,
    val category: StrategyCategory,
    val requiredLosses: Int = 0,
    val waitDurationMs: Long = 0L,
    val minRangeExclusive: Double = 0.0,
    val maxRangeExclusive: Double = 0.0,
    val defaultEnabled: Boolean = false
) {
    AFTER_9_LOSSES(
        displayName = "After 9 Lows",
        description = "Observe consecutive crashes below threshold",
        category = StrategyCategory.STANDARD_RECOVERY,
        requiredLosses = 9,
        defaultEnabled = false
    ),
    AFTER_14_LOSSES(
        displayName = "After 14 Lows",
        description = "Observe 14 consecutive crashes below threshold",
        category = StrategyCategory.STANDARD_RECOVERY,
        requiredLosses = 14,
        defaultEnabled = false
    ),
    RANGE_10_TO_100_WAIT_10M(
        displayName = "10x – 100x · 10m Window",
        description = "Track interval window after high multiplier",
        category = StrategyCategory.MULTIPLIER_WAIT,
        waitDurationMs = 10 * 60_000L,
        minRangeExclusive = 10.0,
        maxRangeExclusive = 100.0,
        defaultEnabled = false
    ),
    RANGE_100_TO_1000_WAIT_5M(
        displayName = "100x – 1000x · 5m Window",
        description = "Track interval window after ultra-high multiplier",
        category = StrategyCategory.MULTIPLIER_WAIT,
        waitDurationMs = 5 * 60_000L,
        minRangeExclusive = 100.0,
        maxRangeExclusive = 1000.0,
        defaultEnabled = false
    ),
    AFTER_2_LOSSES(
        displayName = "After 2 Lows",
        description = "Observe 2 consecutive low crashes",
        category = StrategyCategory.QUICK_RECOVERY,
        requiredLosses = 2,
        defaultEnabled = false
    ),
    AFTER_3_LOSSES(
        displayName = "After 3 Lows",
        description = "Observe 3 consecutive low crashes",
        category = StrategyCategory.QUICK_RECOVERY,
        requiredLosses = 3,
        defaultEnabled = false
    ),
    AFTER_5_LOSSES(
        displayName = "After 5 Lows",
        description = "Observe 5 consecutive low crashes",
        category = StrategyCategory.QUICK_RECOVERY,
        requiredLosses = 5,
        defaultEnabled = false
    ),
    AFTER_7_LOSSES(
        displayName = "After 7 Lows",
        description = "Observe 7 consecutive low crashes",
        category = StrategyCategory.QUICK_RECOVERY,
        requiredLosses = 7,
        defaultEnabled = false
    )
}

data class StrategyRuleConfig(
    val ruleId: StrategyRuleId,
    val isEnabled: Boolean = ruleId.defaultEnabled,
    val progressionMode: StakeProgressionMode = StakeProgressionMode.DISABLED,
    val customAddAmount: Double = 10.0,
    val targetWinsToFetch: Int = 2,
    val requiredLosses: Int = ruleId.requiredLosses,
    val waitMinutes: Int = (ruleId.waitDurationMs / 60_000L).toInt(),
    val minMultiplier: Double = ruleId.minRangeExclusive,
    val maxMultiplier: Double = ruleId.maxRangeExclusive
) {
    val waitDurationMs: Long get() = waitMinutes.coerceAtLeast(0) * 60_000L

    fun dynamicTitle(): String {
        return when {
            ruleId.category == StrategyCategory.MULTIPLIER_WAIT ->
                "${formatShortMult(minMultiplier)}–${formatShortMult(maxMultiplier)} · ${waitMinutes}m"
            else -> "After $requiredLosses Lows"
        }
    }

    private fun formatShortMult(v: Double): String {
        return if (v % 1.0 == 0.0) "${v.toLong()}x" else "${v}x"
    }
}

@Entity(tableName = "strategy_configs")
data class StrategyConfig(
    @PrimaryKey
    val id: Int = 1,
    val baseStake: Double = 20.0,
    val afterLoss: AfterLossMode = AfterLossMode.DOUBLE,
    val addAmount: Double = 10.0,
    val stopAfterLosses: Int = 3,
    val cashOutTarget: Double = 2.0,
    val resetAfterWin: Boolean = true,
    val bettingMode: BettingMode = BettingMode.USE_STRATEGY_RULES,
    val dailyLossLimit: Double = 0.0,
    val dailyProfitTarget: Double = 0.0,
    val balanceFloor: Double = 0.0,
    val scanHeartbeatMs: Long = 3000L,
    val stakeTolerance: Double = 0.05,
    val showFloatingHand: Boolean = true,
    val isStrategy1Enabled: Boolean = false,
    val isStrategy2Enabled: Boolean = false,
    val isHighMultiplierLossRuleEnabled: Boolean = false,
    val strategy1LowThreshold: Double = 2.00,
    val strategy1RequiredLowRounds: Int = 1,
    val strategy2HighThreshold: Double = 10.00,
    val strategy2WaitRounds: Int = 15,
    val multiplierProgression: Double = 2.0,
    val stopAfterConsecutiveWins: Int = 2,
    val targetMultiplier: Double = 2.00,
    val isAutoBetEnabled: Boolean = false,
    val isAutoCashOutEnabled: Boolean = false,
    val sessionTimeoutMinutes: Int = 3,
    val currencyPreference: String = "AUTO",
    val keepScreenAwake: Boolean = true,
    val diagnosticToastsEnabled: Boolean = true,
    val diagnosticNotificationsEnabled: Boolean = true,
    val scheduleEnabled: Boolean = false,
    val scheduleStartHour: Int = 8,
    val scheduleStartMinute: Int = 0,
    val scheduleEndHour: Int = 23,
    val scheduleEndMinute: Int = 0,
    val lowThreshold: Double = 2.00,
    val boundaryValue: Double = 2.00,
    val bigThreshold: Double = 10.00,
    val gridRows: Int = 5,
    val gridColumns: Int = 8,
    val monitoringEnabled: Boolean = true,
    val pixelColorCaptureEnabled: Boolean = false,
    val ruleConfigsJson: String = serializeRuleConfigs(defaultRuleConfigs()),
    val maxStrategyBetAttempts: Int = 2,
    val isMartingaleEnabled: Boolean = true,
    val lossProgressionEnabled: Boolean = true,
    val lossProgressionMode: StakeProgressionMode = StakeProgressionMode.DOUBLE_AMOUNT,
    val lossProgressionAddAmount: Double = 10.0,
    val minDetectionConfidence: Float = 0.5f,
    val historyCollectionEnabled: Boolean = true,
    val autoGameRecognitionEnabled: Boolean = true,
    val preferredGameId: String = "ALL",
    val automationMasterEnabled: Boolean = true,
    val minFlightTimeBeforeCashOutMs: Long = 1200L,
    val maxBetRetries: Int = 2,
    val retryDelayMs: Long = 350L,
    val bettingWindowExpiryMs: Long = 6000L,
    val dismissKeyboardBeforeBet: Boolean = true,
    val verifyStakeReadBack: Boolean = true,
    val verifyButtonStateBeforeAction: Boolean = true,
    val gameAutoCashOutEnabled: Boolean = false,
    val gameAutoCashOutTarget: Double = 2.00,
    val gameAutoCashOutMode: String = "ENSURE",
    val stakeEditTimeoutMs: Long = 500L,
    val autoCashOutTimeoutMs: Long = 500L,
    val betWindowTotalTimeoutMs: Long = 3000L,
    val minRemainingBetWindowMs: Long = 1200L,
    val minStake: Double = 0.0,
    val maxStake: Double = 0.0,
    val cashOutUntrackedBets: Boolean = true,
    val queueBetDuringFlight: Boolean = false
) {
    /**
     * SINGLE SOURCE OF TRUTH: [afterLoss] + [addAmount] decide the stake progression.
     * The lossProgression* / isMartingaleEnabled fields are derived mirrors only.
     */
    fun normalized(): StrategyConfig = syncProgressionFromLegacy()

    @Deprecated("Mirrors can no longer drive the mode; use normalized()", ReplaceWith("normalized()"))
    fun syncLegacyFromProgression(): StrategyConfig = syncProgressionFromLegacy()

    fun syncProgressionFromLegacy(): StrategyConfig {
        val syncedMode = when (afterLoss) {
            AfterLossMode.DOUBLE -> StakeProgressionMode.DOUBLE_AMOUNT
            AfterLossMode.ADD -> StakeProgressionMode.ADD_AMOUNT
            AfterLossMode.KEEP -> StakeProgressionMode.DISABLED
        }
        return copy(
            lossProgressionEnabled = afterLoss != AfterLossMode.KEEP,
            lossProgressionMode = syncedMode,
            lossProgressionAddAmount = addAmount.coerceAtLeast(0.0),
            isMartingaleEnabled = afterLoss != AfterLossMode.KEEP,
            targetMultiplier = cashOutTarget.coerceAtLeast(1.01)
        )
    }

    fun resolvedMasterProgressionMode(): StakeProgressionMode = when (afterLoss) {
        AfterLossMode.DOUBLE -> StakeProgressionMode.DOUBLE_AMOUNT
        AfterLossMode.ADD -> StakeProgressionMode.ADD_AMOUNT
        AfterLossMode.KEEP -> StakeProgressionMode.DISABLED
    }

    fun getRuleConfigs(): List<StrategyRuleConfig> = deserializeRuleConfigs(ruleConfigsJson)

    fun getRuleConfig(ruleId: StrategyRuleId): StrategyRuleConfig =
        getRuleConfigs().firstOrNull { it.ruleId == ruleId }
            ?: StrategyRuleConfig(ruleId = ruleId)

    fun withUpdatedRuleConfigs(configs: List<StrategyRuleConfig>): StrategyConfig {
        return copy(ruleConfigsJson = serializeRuleConfigs(configs))
    }

    fun withUpdatedRule(updated: StrategyRuleConfig): StrategyConfig {
        val current = getRuleConfigs().toMutableList()
        val index = current.indexOfFirst { it.ruleId == updated.ruleId }
        if (index >= 0) {
            current[index] = updated
        } else {
            current.add(updated)
        }
        return withUpdatedRuleConfigs(current)
    }

    companion object {
        fun defaultRuleConfigs(): List<StrategyRuleConfig> =
            StrategyRuleId.entries.map { ruleId ->
                StrategyRuleConfig(
                    ruleId = ruleId,
                    isEnabled = ruleId.defaultEnabled,
                    progressionMode = StakeProgressionMode.DISABLED,
                    customAddAmount = 10.0,
                    targetWinsToFetch = 2,
                    requiredLosses = ruleId.requiredLosses,
                    waitMinutes = (ruleId.waitDurationMs / 60_000L).toInt(),
                    minMultiplier = ruleId.minRangeExclusive,
                    maxMultiplier = ruleId.maxRangeExclusive
                )
            }

        fun serializeRuleConfigs(configs: List<StrategyRuleConfig>): String {
            return configs.joinToString(";") { rule ->
                listOf(
                    rule.ruleId.name,
                    rule.isEnabled,
                    rule.progressionMode.name,
                    rule.customAddAmount,
                    rule.targetWinsToFetch,
                    rule.requiredLosses,
                    rule.waitMinutes,
                    rule.minMultiplier,
                    rule.maxMultiplier
                ).joinToString("|")
            }
        }

        fun deserializeRuleConfigs(encoded: String?): List<StrategyRuleConfig> {
            val defaultsById = defaultRuleConfigs().associateBy { it.ruleId }.toMutableMap()
            if (encoded.isNullOrBlank()) {
                return StrategyRuleId.entries.mapNotNull { defaultsById[it] }
            }
            encoded.split(";").forEach { entry ->
                val parts = entry.split("|")
                if (parts.size >= 5) {
                    val ruleId = runCatching { StrategyRuleId.valueOf(parts[0]) }.getOrNull()
                    if (ruleId != null) {
                        val isEnabled = parts[1].toBooleanStrictOrNull() ?: ruleId.defaultEnabled
                        val mode = runCatching { StakeProgressionMode.valueOf(parts[2]) }
                            .getOrDefault(StakeProgressionMode.DISABLED)
                        val addAmount = parts[3].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 10.0
                        val targetWins = parts[4].toIntOrNull()?.coerceAtLeast(1) ?: 2
                        val reqLosses = if (ruleId.category == StrategyCategory.MULTIPLIER_WAIT) {
                            0
                        } else {
                            parts.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(1) ?: ruleId.requiredLosses
                        }
                        val waitMins = parts.getOrNull(6)?.toIntOrNull()?.coerceAtLeast(0)
                            ?: (ruleId.waitDurationMs / 60_000L).toInt()
                        val minMult = parts.getOrNull(7)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: ruleId.minRangeExclusive
                        val maxMult = parts.getOrNull(8)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: ruleId.maxRangeExclusive

                        defaultsById[ruleId] = StrategyRuleConfig(
                            ruleId = ruleId,
                            isEnabled = isEnabled,
                            progressionMode = mode,
                            customAddAmount = addAmount,
                            targetWinsToFetch = targetWins,
                            requiredLosses = reqLosses,
                            waitMinutes = waitMins,
                            minMultiplier = minMult,
                            maxMultiplier = maxMult
                        )
                    }
                }
            }
            return StrategyRuleId.entries.mapNotNull { defaultsById[it] }
        }
    }
}
