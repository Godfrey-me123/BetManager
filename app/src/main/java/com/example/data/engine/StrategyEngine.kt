package com.example.data.engine

import android.util.Log
import com.example.data.model.StakeProgressionMode
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyRuleConfig
import com.example.data.model.StrategyRuleId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.Locale

sealed class StrategyTrigger {
    object None : StrategyTrigger()
    data class Strategy1Fired(val consecutiveLowRounds: Int) : StrategyTrigger()
    data class Strategy2Fired(val waitRoundsCompleted: Int, val initialHighMultiplier: Double) : StrategyTrigger()
    data class HighMultiplierLossTrigger(val multiplier: Double, val doubledStake: Double) : StrategyTrigger()
    data class LossRecoveryFired(
        val ruleConfig: StrategyRuleConfig,
        val observedLosses: Int
    ) : StrategyTrigger()
    data class RangeWaitStarted(
        val ruleConfig: StrategyRuleConfig,
        val observedMultiplier: Double,
        val waitUntilTimestamp: Long
    ) : StrategyTrigger()
    data class WaitTimerCompleted(
        val ruleConfig: StrategyRuleConfig
    ) : StrategyTrigger()
    data class SessionTimedOut(
        val previousRuleName: String,
        val elapsedMs: Long
    ) : StrategyTrigger()
}

data class StrategyState(
    val consecutiveLowCount: Int = 0,
    val consecutiveObservedLosses: Int = 0,
    val postHighWaitCount: Int = 0,
    val lastHighMultiplier: Double = 0.0,
    val isWaitingForPostHigh: Boolean = false,
    val activeTrigger: StrategyTrigger = StrategyTrigger.None,
    val activeRuleId: StrategyRuleId? = null,
    val activeRuleLabel: String? = null,
    val activeProgressionMode: StakeProgressionMode = StakeProgressionMode.DISABLED,
    val activeCustomAddAmount: Double = 10.0,
    val targetWinsToFetch: Int = 2,
    val pendingWaitRuleId: StrategyRuleId? = null,
    val pendingWaitRuleLabel: String? = null,
    val waitUntilTimestamp: Long = 0L,
    val activeWaitRemainingMs: Long = 0L,
    val isBettingSessionActive: Boolean = false,
    val sessionStartedTimestamp: Long = 0L,
    val sessionTimeoutRemainingMs: Long = 0L,
    val currentStakeLevel: Int = 1,
    val consecutiveWinsInSession: Int = 0,
    val consecutiveLossesInSession: Int = 0,
    val currentCalculatedStake: Double = 10.0,
    val highMultiplierLossesInHour: Int = 0,
    val isNearHourEndRuleActive: Boolean = false,
    val enabledRuleIds: Set<StrategyRuleId> = StrategyRuleId.entries.filter { it.defaultEnabled }.toSet()
)

enum class ActionType {
    BET,
    CASH_OUT
}

data class AutomationActionEvent(
    val actionType: ActionType,
    val amountOrMultiplier: Double = 0.0,
    val reason: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val eventId: Long = System.nanoTime()
)

data class StakeInstruction(
    val stakeAmount: Double = 10.0,
    val formattedStake: String = "10",
    val progressionMode: StakeProgressionMode = StakeProgressionMode.DISABLED,
    val incrementAmount: Double = 10.0,
    val targetMultiplier: Double = 2.00,
    val stakeLevel: Int = 1,
    val isSessionActive: Boolean = false,
    val reason: String = "Base Stake"
)

class StrategyEngine {

    companion object {
        private const val TAG = "StrategyEngine"
        const val DEFAULT_SESSION_TIMEOUT_MS = 180_000L

        private val _actionEvent = MutableStateFlow<AutomationActionEvent?>(null)
        val actionEvent: StateFlow<AutomationActionEvent?> = _actionEvent.asStateFlow()

        private val _latestStakeInstruction = MutableStateFlow(StakeInstruction())
        val latestStakeInstruction: StateFlow<StakeInstruction> = _latestStakeInstruction.asStateFlow()

        fun triggerAction(event: AutomationActionEvent) {
            _actionEvent.value = event
            safeLogI("Automation Action Triggered: ${event.actionType} (${event.amountOrMultiplier}) - ${event.reason}")
        }

        fun roundStake(value: Double): Double {
            return kotlin.math.round(value.coerceAtLeast(0.0) * 100.0) / 100.0
        }

        fun formatStakeAmount(stake: Double): String {
            val rounded = roundStake(stake)
            return if (rounded % 1.0 == 0.0) {
                rounded.toLong().toString()
            } else {
                String.format(Locale.US, "%.2f", rounded)
            }
        }

        private fun safeLogI(message: String) {
            try {
                Log.i(TAG, message)
            } catch (_: RuntimeException) {}
        }

        private fun safeLogW(message: String) {
            try {
                Log.w(TAG, message)
            } catch (_: RuntimeException) {}
        }
    }

    private var state = StrategyState()
    private var currentStakeInstruction = StakeInstruction()
    private val _boundConfig = MutableStateFlow(StrategyConfig())
    val boundConfig: StateFlow<StrategyConfig> = _boundConfig.asStateFlow()

    private val highMultiplierLossTimestamps = mutableListOf<Long>()
    private var totalRounds: Int = 0
    private var totalWins: Int = 0
    private var totalLosses: Int = 0

    fun getState(): StrategyState = state

    fun getPendingStakeInstruction(): StakeInstruction = currentStakeInstruction

    private fun resolveEffectiveProgression(config: StrategyConfig): Pair<StakeProgressionMode, Double> {
        val masterMode = config.resolvedMasterProgressionMode()
        return if (masterMode == StakeProgressionMode.DISABLED) {
            StakeProgressionMode.DISABLED to 0.0
        } else {
            masterMode to roundStake(config.lossProgressionAddAmount.coerceAtLeast(0.0))
        }
    }

    fun bindStrategyConfig(config: StrategyConfig) {
        _boundConfig.value = config
        val enabledIds = config.getRuleConfigs()
            .filter { it.isEnabled }
            .map { it.ruleId }
            .toSet()

        val shouldClearPendingWait = state.pendingWaitRuleId != null && state.pendingWaitRuleId !in enabledIds
        val updatedStake = if (!state.isBettingSessionActive) roundStake(config.baseStake) else state.currentCalculatedStake
        val (masterMode, masterAddAmount) = resolveEffectiveProgression(config)

        state = state.copy(
            enabledRuleIds = enabledIds,
            pendingWaitRuleId = if (shouldClearPendingWait) null else state.pendingWaitRuleId,
            pendingWaitRuleLabel = if (shouldClearPendingWait) null else state.pendingWaitRuleLabel,
            waitUntilTimestamp = if (shouldClearPendingWait) 0L else state.waitUntilTimestamp,
            activeWaitRemainingMs = if (shouldClearPendingWait) 0L else state.activeWaitRemainingMs,
            activeProgressionMode = masterMode,
            activeCustomAddAmount = masterAddAmount,
            currentCalculatedStake = updatedStake
        )

        if (!state.isBettingSessionActive) {
            publishStakeInstruction(
                stake = updatedStake,
                mode = masterMode,
                addAmount = masterAddAmount,
                targetMultiplier = config.targetMultiplier,
                stakeLevel = 1,
                isSessionActive = false,
                reason = if (enabledIds.isEmpty()) "No Recovery Rule Selected" else "${enabledIds.size} Rules Bound"
            )
        }
        safeLogI("Bound ${enabledIds.size} strategy rules to StrategyEngine: $enabledIds (progression=$masterMode)")
    }

    fun updateStrategySelection(
        selectedRuleIds: Set<StrategyRuleId>,
        isStrategy1Enabled: Boolean? = null,
        isStrategy2Enabled: Boolean? = null,
        isHighMultiplierLossRuleEnabled: Boolean? = null
    ): StrategyConfig {
        val current = _boundConfig.value
        val updatedRules = current.getRuleConfigs().map { rule ->
            rule.copy(isEnabled = rule.ruleId in selectedRuleIds)
        }
        val updatedConfig = current.copy(
            isStrategy1Enabled = isStrategy1Enabled ?: current.isStrategy1Enabled,
            isStrategy2Enabled = isStrategy2Enabled ?: current.isStrategy2Enabled,
            isHighMultiplierLossRuleEnabled = isHighMultiplierLossRuleEnabled ?: current.isHighMultiplierLossRuleEnabled
        ).withUpdatedRuleConfigs(updatedRules)
        bindStrategyConfig(updatedConfig)
        return updatedConfig
    }

    fun calculateNextStakeAfterLoss(
        currentStake: Double,
        progressionMode: StakeProgressionMode,
        customAddAmount: Double,
        lossMultiplier: Double = 0.0,
        isHighMultiplierLossRuleEnabled: Boolean = false
    ): Double {
        val safeCurrent = currentStake.coerceAtLeast(0.0)
        val rawNext = when (progressionMode) {
            StakeProgressionMode.DISABLED -> safeCurrent
            StakeProgressionMode.ADD_AMOUNT -> safeCurrent + customAddAmount.coerceAtLeast(0.0)
            StakeProgressionMode.DOUBLE_AMOUNT -> safeCurrent * 2.0
        }
        return roundStake(rawNext)
    }

    private fun publishStakeInstruction(
        stake: Double,
        mode: StakeProgressionMode,
        addAmount: Double,
        targetMultiplier: Double,
        stakeLevel: Int,
        isSessionActive: Boolean,
        reason: String
    ) {
        val rounded = roundStake(stake)
        val instruction = StakeInstruction(
            stakeAmount = rounded,
            formattedStake = formatStakeAmount(rounded),
            progressionMode = mode,
            incrementAmount = roundStake(addAmount),
            targetMultiplier = targetMultiplier,
            stakeLevel = stakeLevel,
            isSessionActive = isSessionActive,
            reason = reason
        )
        currentStakeInstruction = instruction
        _latestStakeInstruction.value = instruction
    }

    fun cancelPatternSession(reason: String = "Pattern Expired") {
        if (!state.isBettingSessionActive) return
        val cleanBase = roundStake(_boundConfig.value.baseStake)
        val (masterMode, masterAddAmount) = resolveEffectiveProgression(_boundConfig.value)
        state = state.copy(
            isBettingSessionActive = false,
            activeRuleId = null,
            activeRuleLabel = null,
            currentCalculatedStake = cleanBase,
            currentStakeLevel = 1,
            consecutiveWinsInSession = 0,
            consecutiveLossesInSession = 0
        )
        publishStakeInstruction(
            stake = cleanBase,
            mode = masterMode,
            addAmount = masterAddAmount,
            targetMultiplier = _boundConfig.value.targetMultiplier,
            stakeLevel = 1,
            isSessionActive = false,
            reason = reason
        )
        safeLogI("Pattern session cancelled: $reason")
    }

    fun reset(baseStake: Double = _boundConfig.value.baseStake) {
        val cleanBase = roundStake(baseStake)
        val boundEnabledIds = _boundConfig.value.getRuleConfigs()
            .filter { it.isEnabled }
            .map { it.ruleId }
            .toSet()
        val (masterMode, masterAddAmount) = resolveEffectiveProgression(_boundConfig.value)
        state = StrategyState(
            activeProgressionMode = masterMode,
            activeCustomAddAmount = masterAddAmount,
            currentCalculatedStake = cleanBase,
            enabledRuleIds = boundEnabledIds
        )
        highMultiplierLossTimestamps.clear()
        totalRounds = 0
        totalWins = 0
        totalLosses = 0
        publishStakeInstruction(
            stake = cleanBase,
            mode = masterMode,
            addAmount = masterAddAmount,
            targetMultiplier = _boundConfig.value.targetMultiplier,
            stakeLevel = 1,
            isSessionActive = false,
            reason = "Reset to Base Stake"
        )
        safeLogI("Strategy engine reset.")
    }

    fun checkPendingTimers(
        config: StrategyConfig = _boundConfig.value,
        currentTimeMs: Long = System.currentTimeMillis()
    ): StrategyTrigger {
        val timeoutLimitMs = config.sessionTimeoutMinutes.coerceAtLeast(1) * 60_000L

        if (state.isBettingSessionActive && state.sessionStartedTimestamp > 0L) {
            val elapsed = (currentTimeMs - state.sessionStartedTimestamp).coerceAtLeast(0L)
            if (elapsed >= timeoutLimitMs) {
                val timedOutLabel = state.activeRuleLabel ?: state.activeRuleId?.displayName ?: "Active Session"
                safeLogW("3-minute timeout guard triggered for '$timedOutLabel' after ${elapsed}ms. Terminating cycle.")
                val timeoutTrigger = StrategyTrigger.SessionTimedOut(timedOutLabel, elapsed)
                state = state.copy(
                    isBettingSessionActive = false,
                    sessionStartedTimestamp = 0L,
                    sessionTimeoutRemainingMs = 0L,
                    activeRuleId = null,
                    activeRuleLabel = null,
                    activeTrigger = timeoutTrigger,
                    currentStakeLevel = 1,
                    consecutiveWinsInSession = 0,
                    consecutiveLossesInSession = 0,
                    consecutiveObservedLosses = 0,
                    currentCalculatedStake = config.baseStake,
                    consecutiveLowCount = 0,
                    postHighWaitCount = 0
                )
                publishStakeInstruction(
                    stake = config.baseStake,
                    mode = state.activeProgressionMode,
                    addAmount = state.activeCustomAddAmount,
                    targetMultiplier = config.targetMultiplier,
                    stakeLevel = 1,
                    isSessionActive = false,
                    reason = "3m Guard Timed Out -> Reset to Base Stake"
                )
                return timeoutTrigger
            } else {
                state = state.copy(sessionTimeoutRemainingMs = timeoutLimitMs - elapsed)
            }
        }

        if (state.isBettingSessionActive) {
            return StrategyTrigger.None
        }

        val pendingRuleId = state.pendingWaitRuleId ?: return StrategyTrigger.None
        val waitUntil = state.waitUntilTimestamp
        if (waitUntil <= 0L) return StrategyTrigger.None

        val remaining = (waitUntil - currentTimeMs).coerceAtLeast(0L)
        if (remaining > 0L) {
            state = state.copy(activeWaitRemainingMs = remaining)
            return StrategyTrigger.None
        }

        val ruleConfig = config.getRuleConfig(pendingRuleId)
        val trigger = StrategyTrigger.WaitTimerCompleted(ruleConfig)
        val initialStake = roundStake(config.baseStake)
        val (effectiveMode, effectiveAddAmount) = resolveEffectiveProgression(config)

        state = state.copy(
            pendingWaitRuleId = null,
            pendingWaitRuleLabel = null,
            waitUntilTimestamp = 0L,
            activeWaitRemainingMs = 0L,
            activeRuleId = pendingRuleId,
            activeRuleLabel = ruleConfig.dynamicTitle(),
            activeProgressionMode = effectiveMode,
            activeCustomAddAmount = effectiveAddAmount,
            targetWinsToFetch = ruleConfig.targetWinsToFetch,
            activeTrigger = trigger,
            isBettingSessionActive = true,
            sessionStartedTimestamp = currentTimeMs,
            sessionTimeoutRemainingMs = timeoutLimitMs,
            currentCalculatedStake = initialStake,
            consecutiveWinsInSession = 0,
            consecutiveLossesInSession = 0
        )

        publishStakeInstruction(
            stake = initialStake,
            mode = effectiveMode,
            addAmount = effectiveAddAmount,
            targetMultiplier = config.targetMultiplier,
            stakeLevel = 1,
            isSessionActive = true,
            reason = "${ruleConfig.dynamicTitle()} Ready"
        )

        triggerAction(
            AutomationActionEvent(
                actionType = ActionType.BET,
                amountOrMultiplier = initialStake,
                reason = "${ruleConfig.dynamicTitle()} wait elapsed -> Fetch ${ruleConfig.targetWinsToFetch} wins",
                timestamp = currentTimeMs
            )
        )
        return trigger
    }

    fun activateExternalPatternTrigger(
        strategyName: String,
        config: StrategyConfig = _boundConfig.value,
        currentTimeMs: Long = System.currentTimeMillis(),
        progressionModeOverride: StakeProgressionMode? = null,
        customAddAmountOverride: Double? = null,
        targetWinsOverride: Int? = null,
        targetMultiplierOverride: Double? = null
    ): Boolean {
        if (state.isBettingSessionActive) return false
        val firstEnabledRule = config.getRuleConfigs().firstOrNull { it.isEnabled }
        val (mode, addAmt) = resolveEffectiveProgression(config)
        val targetWins = (targetWinsOverride ?: firstEnabledRule?.targetWinsToFetch ?: config.stopAfterConsecutiveWins).coerceAtLeast(1)
        val effectiveTargetMult = (targetMultiplierOverride ?: config.targetMultiplier).coerceAtLeast(1.01)
        val initialStake = roundStake(config.baseStake)
        val timeoutLimitMs = config.sessionTimeoutMinutes.coerceAtLeast(1) * 60_000L

        state = state.copy(
            isBettingSessionActive = true,
            activeRuleId = null,
            activeRuleLabel = strategyName,
            activeProgressionMode = mode,
            activeCustomAddAmount = addAmt,
            targetWinsToFetch = targetWins,
            sessionStartedTimestamp = currentTimeMs,
            sessionTimeoutRemainingMs = timeoutLimitMs,
            currentCalculatedStake = initialStake,
            currentStakeLevel = 1,
            consecutiveWinsInSession = 0,
            consecutiveLossesInSession = 0
        )

        publishStakeInstruction(
            stake = initialStake,
            mode = mode,
            addAmount = addAmt,
            targetMultiplier = effectiveTargetMult,
            stakeLevel = 1,
            isSessionActive = true,
            reason = "$strategyName Triggered"
        )
        triggerAction(
            AutomationActionEvent(
                actionType = ActionType.BET,
                amountOrMultiplier = initialStake,
                reason = "$strategyName matched -> Fetch $targetWins wins",
                timestamp = currentTimeMs
            )
        )
        safeLogI("External pattern strategy '$strategyName' activated betting session (stake=$initialStake)")
        return true
    }

    fun processCompletedRound(
        multiplier: Double,
        config: StrategyConfig = _boundConfig.value,
        currentTimeMs: Long = System.currentTimeMillis()
    ): StrategyTrigger {
        totalRounds++
        highMultiplierLossTimestamps.removeAll { currentTimeMs - it > 3_600_000L }

        val timerTrigger = checkPendingTimers(config, currentTimeMs)
        val timeoutLimitMs = config.sessionTimeoutMinutes.coerceAtLeast(1) * 60_000L

        if (state.isBettingSessionActive) {
            state = state.copy(
                highMultiplierLossesInHour = highMultiplierLossTimestamps.size,
                isNearHourEndRuleActive = shouldContinueBettingBasedOnHourRule(currentTimeMs)
            )
            return StrategyTrigger.None
        }

        var consecutiveLow = state.consecutiveLowCount
        var consecutiveObservedLosses = state.consecutiveObservedLosses
        var postHighWait = state.postHighWaitCount
        var isWaiting = state.isWaitingForPostHigh
        var lastHigh = state.lastHighMultiplier

        if (multiplier < config.targetMultiplier) {
            consecutiveObservedLosses += 1
        } else {
            consecutiveObservedLosses = 0
        }

        if (config.isStrategy1Enabled && multiplier < config.strategy1LowThreshold) {
            consecutiveLow += 1
        } else {
            consecutiveLow = 0
        }

        if (config.isStrategy2Enabled) {
            if (multiplier > config.strategy2HighThreshold && !isWaiting) {
                isWaiting = true
                postHighWait = 0
                lastHigh = multiplier
            } else if (isWaiting) {
                postHighWait += 1
            }
        }

        val allRules = config.getRuleConfigs()
        var pendingWaitRuleId = state.pendingWaitRuleId
        var pendingWaitRuleLabel = state.pendingWaitRuleLabel
        var waitUntilTimestamp = state.waitUntilTimestamp

        var rangeTrigger: StrategyTrigger? = null
        if (pendingWaitRuleId == null) {
            val matchedRangeRule = allRules.filter {
                it.isEnabled &&
                    it.ruleId.category == com.example.data.model.StrategyCategory.MULTIPLIER_WAIT &&
                    it.waitDurationMs > 0L &&
                    multiplier > it.minMultiplier &&
                    multiplier < it.maxMultiplier
            }.maxByOrNull { it.minMultiplier }

            if (matchedRangeRule != null) {
                pendingWaitRuleId = matchedRangeRule.ruleId
                pendingWaitRuleLabel = matchedRangeRule.dynamicTitle()
                waitUntilTimestamp = currentTimeMs + matchedRangeRule.waitDurationMs
                rangeTrigger = StrategyTrigger.RangeWaitStarted(
                    ruleConfig = matchedRangeRule,
                    observedMultiplier = multiplier,
                    waitUntilTimestamp = waitUntilTimestamp
                )
                safeLogI("Range rule triggered: ${matchedRangeRule.dynamicTitle()} at ${multiplier}x")
            }
        }

        var triggerFired: StrategyTrigger = if (timerTrigger is StrategyTrigger.WaitTimerCompleted) {
            timerTrigger
        } else {
            StrategyTrigger.None
        }

        var activatedRule: StrategyRuleConfig? = null
        if (triggerFired is StrategyTrigger.None && pendingWaitRuleId == null) {
            val matchingLossRule = allRules
                .filter {
                    it.isEnabled &&
                        it.ruleId.category != com.example.data.model.StrategyCategory.MULTIPLIER_WAIT &&
                        it.requiredLosses > 0 &&
                        consecutiveObservedLosses >= it.requiredLosses
                }
                .minByOrNull { it.requiredLosses }

            if (matchingLossRule != null) {
                activatedRule = matchingLossRule
                triggerFired = StrategyTrigger.LossRecoveryFired(
                    ruleConfig = matchingLossRule,
                    observedLosses = consecutiveObservedLosses
                )
            } else if (config.isStrategy1Enabled && consecutiveLow >= config.strategy1RequiredLowRounds) {
                triggerFired = StrategyTrigger.Strategy1Fired(consecutiveLow)
            } else if (config.isStrategy2Enabled && isWaiting && postHighWait >= config.strategy2WaitRounds) {
                triggerFired = StrategyTrigger.Strategy2Fired(postHighWait, lastHigh)
            }
        }

        val hourRuleActive = shouldContinueBettingBasedOnHourRule(currentTimeMs)
        val sessionActive = state.isBettingSessionActive ||
            (triggerFired is StrategyTrigger.LossRecoveryFired ||
                triggerFired is StrategyTrigger.WaitTimerCompleted ||
                triggerFired is StrategyTrigger.Strategy1Fired ||
                triggerFired is StrategyTrigger.Strategy2Fired ||
                hourRuleActive)

        val initialStake = if (!state.isBettingSessionActive && sessionActive) {
            config.baseStake
        } else {
            state.currentCalculatedStake
        }

        val resolvedActiveRuleId = when {
            activatedRule != null -> activatedRule.ruleId
            triggerFired is StrategyTrigger.WaitTimerCompleted -> triggerFired.ruleConfig.ruleId
            state.isBettingSessionActive -> state.activeRuleId
            else -> null
        }

        val resolvedRuleConfig = resolvedActiveRuleId?.let { config.getRuleConfig(it) }
        val resolvedRuleLabel = when {
            resolvedRuleConfig != null -> resolvedRuleConfig.dynamicTitle()
            triggerFired is StrategyTrigger.Strategy1Fired -> "Strategy 1 (<${config.strategy1LowThreshold}x)"
            triggerFired is StrategyTrigger.Strategy2Fired -> "Strategy 2 (>${config.strategy2HighThreshold}x)"
            else -> state.activeRuleLabel
        }

        val sessionStarted = when {
            !state.isBettingSessionActive && sessionActive -> currentTimeMs
            sessionActive -> state.sessionStartedTimestamp
            else -> 0L
        }

        val (effectiveMode, effectiveAddAmount) = resolveEffectiveProgression(config)
        state = state.copy(
            consecutiveLowCount = consecutiveLow,
            consecutiveObservedLosses = consecutiveObservedLosses,
            postHighWaitCount = postHighWait,
            lastHighMultiplier = lastHigh,
            isWaitingForPostHigh = if (isWaiting && postHighWait >= config.strategy2WaitRounds) false else isWaiting,
            activeRuleId = resolvedActiveRuleId,
            activeRuleLabel = resolvedRuleLabel,
            activeProgressionMode = effectiveMode,
            activeCustomAddAmount = effectiveAddAmount,
            targetWinsToFetch = resolvedRuleConfig?.targetWinsToFetch ?: config.stopAfterConsecutiveWins,
            pendingWaitRuleId = pendingWaitRuleId,
            pendingWaitRuleLabel = pendingWaitRuleLabel,
            waitUntilTimestamp = waitUntilTimestamp,
            activeWaitRemainingMs = if (pendingWaitRuleId != null) (waitUntilTimestamp - currentTimeMs).coerceAtLeast(0L) else 0L,
            activeTrigger = when {
                triggerFired !is StrategyTrigger.None -> triggerFired
                rangeTrigger != null -> rangeTrigger
                timerTrigger is StrategyTrigger.SessionTimedOut -> timerTrigger
                else -> state.activeTrigger
            },
            isBettingSessionActive = sessionActive,
            sessionStartedTimestamp = sessionStarted,
            sessionTimeoutRemainingMs = if (sessionActive) timeoutLimitMs else 0L,
            currentCalculatedStake = initialStake,
            highMultiplierLossesInHour = highMultiplierLossTimestamps.size,
            isNearHourEndRuleActive = hourRuleActive
        )

        when (val fired = triggerFired) {
            is StrategyTrigger.LossRecoveryFired -> {
                publishStakeInstruction(
                    stake = initialStake,
                    mode = effectiveMode,
                    addAmount = effectiveAddAmount,
                    targetMultiplier = config.targetMultiplier,
                    stakeLevel = state.currentStakeLevel,
                    isSessionActive = true,
                    reason = "${fired.ruleConfig.dynamicTitle()} Triggered"
                )
                triggerAction(
                    AutomationActionEvent(
                        actionType = ActionType.BET,
                        amountOrMultiplier = initialStake,
                        reason = "${fired.ruleConfig.dynamicTitle()} (${fired.observedLosses} losses) -> Fetch ${fired.ruleConfig.targetWinsToFetch} wins",
                        timestamp = currentTimeMs
                    )
                )
            }
            is StrategyTrigger.Strategy1Fired -> {
                publishStakeInstruction(
                    stake = initialStake,
                    mode = state.activeProgressionMode,
                    addAmount = state.activeCustomAddAmount,
                    targetMultiplier = config.targetMultiplier,
                    stakeLevel = state.currentStakeLevel,
                    isSessionActive = true,
                    reason = "Strategy 1 Triggered"
                )
                triggerAction(
                    AutomationActionEvent(
                        actionType = ActionType.BET,
                        amountOrMultiplier = initialStake,
                        reason = "Strategy 1: <${config.strategy1LowThreshold}x streak ($consecutiveLow rounds)",
                        timestamp = currentTimeMs
                    )
                )
            }
            is StrategyTrigger.Strategy2Fired -> {
                publishStakeInstruction(
                    stake = initialStake,
                    mode = state.activeProgressionMode,
                    addAmount = state.activeCustomAddAmount,
                    targetMultiplier = config.targetMultiplier,
                    stakeLevel = state.currentStakeLevel,
                    isSessionActive = true,
                    reason = "Strategy 2 Triggered"
                )
                triggerAction(
                    AutomationActionEvent(
                        actionType = ActionType.BET,
                        amountOrMultiplier = initialStake,
                        reason = "Strategy 2: >${config.strategy2HighThreshold}x (${lastHigh}x) + ${config.strategy2WaitRounds} rounds wait",
                        timestamp = currentTimeMs
                    )
                )
            }
            else -> Unit
        }

        return when {
            triggerFired !is StrategyTrigger.None -> triggerFired
            rangeTrigger != null -> rangeTrigger
            timerTrigger is StrategyTrigger.SessionTimedOut -> timerTrigger
            else -> StrategyTrigger.None
        }
    }

    fun processBetOutcome(
        isWin: Boolean,
        multiplier: Double,
        config: StrategyConfig = _boundConfig.value,
        currentTimeMs: Long = System.currentTimeMillis()
    ): StrategyState {
        val activeRuleConfig = state.activeRuleId?.let { config.getRuleConfig(it) }
        val targetWins = activeRuleConfig?.targetWinsToFetch ?: config.stopAfterConsecutiveWins
        val (activeMode, activeAddAmount) = resolveEffectiveProgression(config)

        if (isWin) {
            totalWins++
            val wins = state.consecutiveWinsInSession + 1
            safeLogI("Bet Won! Verified wins in recovery session: $wins / $targetWins")
            triggerAction(
                AutomationActionEvent(
                    actionType = ActionType.CASH_OUT,
                    amountOrMultiplier = multiplier,
                    reason = "Auto Cashout Won ($wins/$targetWins) @ ${String.format(Locale.US, "%.2f", multiplier)}x",
                    timestamp = currentTimeMs
                )
            )

            val resetBase = roundStake(config.baseStake)
            val shouldResetOnWin = (config.isMartingaleEnabled && activeMode != StakeProgressionMode.DISABLED) ||
                (wins >= targetWins && !shouldContinueBettingBasedOnHourRule(currentTimeMs))
            if (shouldResetOnWin) {
                state = state.copy(
                    isBettingSessionActive = false,
                    sessionStartedTimestamp = 0L,
                    sessionTimeoutRemainingMs = 0L,
                    activeTrigger = StrategyTrigger.None,
                    activeRuleId = null,
                    activeRuleLabel = null,
                    activeProgressionMode = activeMode,
                    activeCustomAddAmount = activeAddAmount,
                    currentStakeLevel = 1,
                    consecutiveWinsInSession = 0,
                    consecutiveLossesInSession = 0,
                    consecutiveObservedLosses = 0,
                    currentCalculatedStake = resetBase,
                    consecutiveLowCount = 0,
                    postHighWaitCount = 0
                )
                publishStakeInstruction(
                    stake = resetBase,
                    mode = activeMode,
                    addAmount = activeAddAmount,
                    targetMultiplier = config.targetMultiplier,
                    stakeLevel = 1,
                    isSessionActive = false,
                    reason = if (config.isMartingaleEnabled && activeMode != StakeProgressionMode.DISABLED) {
                        "Martingale Win -> Reset to Base Stake"
                    } else {
                        "Target $targetWins Wins Fetched -> Reset to Base Stake"
                    }
                )
            } else {
                state = state.copy(
                    consecutiveWinsInSession = wins,
                    consecutiveLossesInSession = 0
                )
                publishStakeInstruction(
                    stake = state.currentCalculatedStake,
                    mode = activeMode,
                    addAmount = activeAddAmount,
                    targetMultiplier = config.targetMultiplier,
                    stakeLevel = state.currentStakeLevel,
                    isSessionActive = true,
                    reason = "Win $wins/$targetWins Verified -> Continue Session"
                )
            }
        } else {
            totalLosses++
            val losses = state.consecutiveLossesInSession + 1

            val nextStake = calculateNextStakeAfterLoss(
                currentStake = state.currentCalculatedStake,
                progressionMode = activeMode,
                customAddAmount = activeAddAmount,
                lossMultiplier = multiplier,
                isHighMultiplierLossRuleEnabled = false
            )

            val hourRuleActive = shouldContinueBettingBasedOnHourRule(currentTimeMs)
            val newLevel = state.currentStakeLevel + 1

            state = state.copy(
                isBettingSessionActive = true,
                activeProgressionMode = activeMode,
                activeCustomAddAmount = activeAddAmount,
                currentStakeLevel = newLevel,
                consecutiveWinsInSession = 0,
                consecutiveLossesInSession = losses,
                currentCalculatedStake = nextStake,
                highMultiplierLossesInHour = highMultiplierLossTimestamps.size,
                isNearHourEndRuleActive = hourRuleActive
            )

            publishStakeInstruction(
                stake = nextStake,
                mode = activeMode,
                addAmount = activeAddAmount,
                targetMultiplier = config.targetMultiplier,
                stakeLevel = newLevel,
                isSessionActive = true,
                reason = "Loss #$losses (${activeMode.label}) -> Next Stake ${formatStakeAmount(nextStake)}"
            )
        }

        return state
    }

    private fun shouldContinueBettingBasedOnHourRule(currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        if (highMultiplierLossTimestamps.size < 3) return false
        val calendar = Calendar.getInstance().apply { timeInMillis = currentTimeMs }
        val minute = calendar.get(Calendar.MINUTE)
        return minute >= 50
    }
}
