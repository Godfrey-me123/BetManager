package com.example.data.engine

import com.example.data.model.BettingMode
import com.example.data.model.RoundRecord
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyRule
import com.example.data.model.StrategyRuleType

data class StrategyDecision(
    val shouldBet: Boolean,
    val triggeredRuleName: String?,
    val reason: String
)

enum class PatternSignalStage {
    NONE,
    PRE_SIGNAL,   // 1 multiplier before final trigger (approaching)
    HIGH_SIGNAL   // Pattern met! Exactly time to bet!
}

data class PatternProgress(
    val ruleName: String = "",
    val ruleType: StrategyRuleType = StrategyRuleType.CONSECUTIVE_LOW_MULTIPLIERS,
    val matchedCount: Int = 0,
    val requiredCount: Int = 0,
    val isMatched: Boolean = false,
    val isConsumed: Boolean = false,
    val signalStage: PatternSignalStage = PatternSignalStage.NONE,
    val progressPercent: Float = 0f,
    val conditionDescription: String = "",
    val statusText: String = "No data",
    val triggerSignature: String = "",
    val matchingMultipliers: List<Double> = emptyList()
)

data class StrategyEvaluationResult(
    val decision: StrategyDecision,
    val primaryProgress: PatternProgress,
    val allRulesProgress: List<PatternProgress> = emptyList()
)

object StrategyEvaluator {

    fun evaluate(
        config: StrategyConfig,
        rules: List<StrategyRule>,
        recentRoundsNewestFirst: List<RoundRecord>,
        nowMs: Long = System.currentTimeMillis()
    ): StrategyDecision {
        return evaluateWithProgress(
            config = config,
            rules = rules,
            recentRoundsNewestFirst = recentRoundsNewestFirst,
            consumedSignatures = emptySet(),
            nowMs = nowMs
        ).decision
    }

    fun evaluateFromMultipliers(
        config: StrategyConfig,
        rules: List<StrategyRule>,
        multipliersNewestFirst: List<Double>,
        consumedSignatures: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): StrategyEvaluationResult {
        val total = multipliersNewestFirst.size
        val syntheticRecords = multipliersNewestFirst.mapIndexed { idx, m ->
            val orderFromStart = total - 1 - idx
            RoundRecord(
                roundId = "M-$orderFromStart",
                timestampMs = nowMs - (idx * 5000L),
                crashMultiplier = m
            )
        }
        return evaluateWithProgress(
            config = config,
            rules = rules,
            recentRoundsNewestFirst = syntheticRecords,
            consumedSignatures = consumedSignatures,
            nowMs = nowMs
        )
    }

    fun evaluateWithProgress(
        config: StrategyConfig,
        rules: List<StrategyRule>,
        recentRoundsNewestFirst: List<RoundRecord>,
        consumedSignatures: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): StrategyEvaluationResult {
        if (config.bettingMode == BettingMode.BET_EVERY_ROUND) {
            val decision = StrategyDecision(
                shouldBet = true,
                triggeredRuleName = "Bet Every Round",
                reason = "Bet every round mode active"
            )
            val progress = PatternProgress(
                ruleName = "Bet Every Round",
                ruleType = StrategyRuleType.CONSECUTIVE_LOW_MULTIPLIERS,
                matchedCount = 1,
                requiredCount = 1,
                isMatched = true,
                signalStage = PatternSignalStage.HIGH_SIGNAL,
                progressPercent = 1.0f,
                conditionDescription = "Always bet every round",
                statusText = "Bet Every Round active (pattern rules bypassed)"
            )
            return StrategyEvaluationResult(decision, progress, listOf(progress))
        }

        val enabledRules = rules.filter { it.enabled }.sortedBy { it.priority }
        if (enabledRules.isEmpty()) {
            val decision = StrategyDecision(
                shouldBet = false,
                triggeredRuleName = null,
                reason = "No strategy rules enabled"
            )
            val progress = PatternProgress(
                ruleName = "No active rule",
                ruleType = StrategyRuleType.CONSECUTIVE_LOW_MULTIPLIERS,
                matchedCount = 0,
                requiredCount = 0,
                isMatched = false,
                signalStage = PatternSignalStage.NONE,
                progressPercent = 0f,
                conditionDescription = "Enable a rule in Strategy tab",
                statusText = "No strategy rules enabled"
            )
            return StrategyEvaluationResult(decision, progress, emptyList())
        }

        val progressList = enabledRules.map { rule ->
            computeRuleProgress(rule, recentRoundsNewestFirst, consumedSignatures, nowMs)
        }

        // Check if any rule triggered and is ready to bet (isMatched && !isConsumed && !inCooldown)
        for (progress in progressList) {
            if (progress.isMatched && !progress.isConsumed) {
                val decision = StrategyDecision(
                    shouldBet = true,
                    triggeredRuleName = progress.ruleName,
                    reason = progress.statusText
                )
                return StrategyEvaluationResult(decision, progress, progressList)
            }
        }

        // Check if any rule is approaching (PRE_SIGNAL)
        val preSignalRule = progressList.firstOrNull { it.signalStage == PatternSignalStage.PRE_SIGNAL }
        if (preSignalRule != null) {
            val decision = StrategyDecision(
                shouldBet = false,
                triggeredRuleName = preSignalRule.ruleName,
                reason = preSignalRule.statusText
            )
            return StrategyEvaluationResult(decision, preSignalRule, progressList)
        }

        // No rule triggered or approaching: pick the primary rule with the highest progress
        val primary = progressList.maxWithOrNull(
            compareBy<PatternProgress>(
                { if (it.isMatched && it.isConsumed) 0 else 1 },
                { it.progressPercent },
                { it.matchedCount }
            )
        ) ?: progressList.first()

        val decision = StrategyDecision(
            shouldBet = false,
            triggeredRuleName = null,
            reason = primary.statusText
        )
        return StrategyEvaluationResult(decision, primary, progressList)
    }

    fun computeRuleProgress(
        rule: StrategyRule,
        recentRoundsNewestFirst: List<RoundRecord>,
        consumedSignatures: Set<String>,
        nowMs: Long
    ): PatternProgress {
        val inCooldown = rule.cooldownSeconds > 0 && rule.lastTriggeredAtMs > 0L &&
            ((nowMs - rule.lastTriggeredAtMs) / 1000L) < rule.cooldownSeconds
        val cooldownSec = if (inCooldown) {
            rule.cooldownSeconds - ((nowMs - rule.lastTriggeredAtMs) / 1000L)
        } else {
            0L
        }

        return when (rule.type) {
            StrategyRuleType.CONSECUTIVE_LOW_MULTIPLIERS -> {
                val needed = rule.consecutiveCount
                if (needed <= 0) {
                    return PatternProgress(
                        ruleName = rule.name,
                        ruleType = rule.type,
                        matchedCount = 0,
                        requiredCount = 0,
                        isMatched = false,
                        signalStage = PatternSignalStage.NONE,
                        progressPercent = 0f,
                        conditionDescription = "Invalid count ($needed)",
                        statusText = "Invalid rule: consecutive count must be >= 1"
                    )
                }
                val lowStreak = recentRoundsNewestFirst.takeWhile { it.crashMultiplier < rule.thresholdMultiplier }
                val streakCount = lowStreak.size
                val matchedCount = streakCount.coerceAtMost(needed)
                val progressPercent = (matchedCount.toFloat() / needed.toFloat()).coerceIn(0f, 1f)
                val isMatched = streakCount >= needed
                val matching = lowStreak.take(needed).map { it.crashMultiplier }
                val streakOrigin = lowStreak.lastOrNull()
                val streakOriginKey = streakOrigin?.let { originKeyOf(it) } ?: ""
                val sig = "${rule.id}_low_${streakOriginKey}"
                val isConsumed = isMatched && (sig in consumedSignatures)

                val signalStage = when {
                    isMatched && !inCooldown && !isConsumed -> PatternSignalStage.HIGH_SIGNAL
                    !isMatched && needed >= 2 && matchedCount == needed - 1 -> PatternSignalStage.PRE_SIGNAL
                    else -> PatternSignalStage.NONE
                }

                val statusText = when {
                    inCooldown -> "Cooldown: ${cooldownSec}s remaining"
                    isConsumed -> "Pattern previously triggered ($streakCount/${needed} low) — waiting for streak reset (>= ${rule.thresholdMultiplier}x)"
                    isMatched -> "PATTERN MET: $needed consecutive low crashes (<${rule.thresholdMultiplier}x) — Bet Ready!"
                    signalStage == PatternSignalStage.PRE_SIGNAL ->
                        "PRE-SIGNAL: 1 crash away! ($matchedCount/$needed low <${rule.thresholdMultiplier}x) — Next crash may trigger bet"
                    streakCount == 0 && recentRoundsNewestFirst.isNotEmpty() ->
                        "Waiting: Last crash ${formatMult(recentRoundsNewestFirst.first().crashMultiplier)}x >= ${rule.thresholdMultiplier}x broke streak"
                    recentRoundsNewestFirst.isEmpty() -> "Waiting for game screen / first crash"
                    else -> "$matchedCount of $needed low crashes (<${rule.thresholdMultiplier}x) — need ${needed - matchedCount} more"
                }

                PatternProgress(
                    ruleName = rule.name,
                    ruleType = rule.type,
                    matchedCount = matchedCount,
                    requiredCount = needed,
                    isMatched = isMatched && !inCooldown,
                    isConsumed = isConsumed,
                    signalStage = signalStage,
                    progressPercent = progressPercent,
                    conditionDescription = "$needed consecutive crashes < ${rule.thresholdMultiplier}x",
                    statusText = statusText,
                    triggerSignature = sig,
                    matchingMultipliers = matching
                )
            }

            StrategyRuleType.CONSECUTIVE_HIGH_MULTIPLIERS -> {
                val needed = rule.consecutiveCount
                if (needed <= 0) {
                    return PatternProgress(
                        ruleName = rule.name,
                        ruleType = rule.type,
                        matchedCount = 0,
                        requiredCount = 0,
                        isMatched = false,
                        signalStage = PatternSignalStage.NONE,
                        progressPercent = 0f,
                        conditionDescription = "Invalid count ($needed)",
                        statusText = "Invalid rule: consecutive count must be >= 1"
                    )
                }
                val highStreak = recentRoundsNewestFirst.takeWhile { it.crashMultiplier >= rule.thresholdMultiplier }
                val streakCount = highStreak.size
                val matchedCount = streakCount.coerceAtMost(needed)
                val progressPercent = (matchedCount.toFloat() / needed.toFloat()).coerceIn(0f, 1f)
                val isMatched = streakCount >= needed
                val matching = highStreak.take(needed).map { it.crashMultiplier }
                val streakOrigin = highStreak.lastOrNull()
                val streakOriginKey = streakOrigin?.let { originKeyOf(it) } ?: ""
                val sig = "${rule.id}_high_${streakOriginKey}"
                val isConsumed = isMatched && (sig in consumedSignatures)

                val signalStage = when {
                    isMatched && !inCooldown && !isConsumed -> PatternSignalStage.HIGH_SIGNAL
                    !isMatched && needed >= 2 && matchedCount == needed - 1 -> PatternSignalStage.PRE_SIGNAL
                    else -> PatternSignalStage.NONE
                }

                val statusText = when {
                    inCooldown -> "Cooldown: ${cooldownSec}s remaining"
                    isConsumed -> "Pattern previously triggered — waiting for streak reset (< ${rule.thresholdMultiplier}x)"
                    isMatched -> "PATTERN MET: $needed consecutive high crashes (>=${rule.thresholdMultiplier}x) — Bet Ready!"
                    signalStage == PatternSignalStage.PRE_SIGNAL ->
                        "PRE-SIGNAL: 1 crash away! ($matchedCount/$needed high >=${rule.thresholdMultiplier}x) — Next crash may trigger bet"
                    streakCount == 0 && recentRoundsNewestFirst.isNotEmpty() ->
                        "Waiting: Last crash ${formatMult(recentRoundsNewestFirst.first().crashMultiplier)}x < ${rule.thresholdMultiplier}x broke streak"
                    recentRoundsNewestFirst.isEmpty() -> "Waiting for game screen / first crash"
                    else -> "$matchedCount of $needed high crashes (>=${rule.thresholdMultiplier}x) — need ${needed - matchedCount} more"
                }

                PatternProgress(
                    ruleName = rule.name,
                    ruleType = rule.type,
                    matchedCount = matchedCount,
                    requiredCount = needed,
                    isMatched = isMatched && !inCooldown,
                    isConsumed = isConsumed,
                    signalStage = signalStage,
                    progressPercent = progressPercent,
                    conditionDescription = "$needed consecutive crashes >= ${rule.thresholdMultiplier}x",
                    statusText = statusText,
                    triggerSignature = sig,
                    matchingMultipliers = matching
                )
            }

            StrategyRuleType.COLOR_TIER_STREAK -> {
                val needed = rule.consecutiveCount
                if (needed <= 0) {
                    return PatternProgress(
                        ruleName = rule.name,
                        ruleType = rule.type,
                        matchedCount = 0,
                        requiredCount = 0,
                        isMatched = false,
                        signalStage = PatternSignalStage.NONE,
                        progressPercent = 0f,
                        conditionDescription = "Invalid count ($needed)",
                        statusText = "Invalid rule: consecutive count must be >= 1"
                    )
                }
                val targetTier = rule.colorTier.uppercase()
                val tierStreak = recentRoundsNewestFirst.takeWhile { colorTierOf(it.crashMultiplier) == targetTier }
                val streakCount = tierStreak.size
                val matchedCount = streakCount.coerceAtMost(needed)
                val progressPercent = (matchedCount.toFloat() / needed.toFloat()).coerceIn(0f, 1f)
                val isMatched = streakCount >= needed
                val matching = tierStreak.take(needed).map { it.crashMultiplier }
                val streakOrigin = tierStreak.lastOrNull()
                val streakOriginKey = streakOrigin?.let { originKeyOf(it) } ?: ""
                val sig = "${rule.id}_tier_${targetTier}_${streakOriginKey}"
                val isConsumed = isMatched && (sig in consumedSignatures)

                val signalStage = when {
                    isMatched && !inCooldown && !isConsumed -> PatternSignalStage.HIGH_SIGNAL
                    !isMatched && needed >= 2 && matchedCount == needed - 1 -> PatternSignalStage.PRE_SIGNAL
                    else -> PatternSignalStage.NONE
                }

                val statusText = when {
                    inCooldown -> "Cooldown: ${cooldownSec}s remaining"
                    isConsumed -> "Pattern previously triggered — waiting for $targetTier streak to reset"
                    isMatched -> "PATTERN MET: $needed consecutive $targetTier rounds — Bet Ready!"
                    signalStage == PatternSignalStage.PRE_SIGNAL ->
                        "PRE-SIGNAL: 1 crash away! ($matchedCount/$needed $targetTier rounds) — Next crash may trigger bet"
                    streakCount == 0 && recentRoundsNewestFirst.isNotEmpty() ->
                        "Waiting: Last crash ${formatMult(recentRoundsNewestFirst.first().crashMultiplier)}x (${colorTierOf(recentRoundsNewestFirst.first().crashMultiplier)}) not $targetTier"
                    recentRoundsNewestFirst.isEmpty() -> "Waiting for game screen / first crash"
                    else -> "$matchedCount of $needed $targetTier rounds — need ${needed - matchedCount} more"
                }

                PatternProgress(
                    ruleName = rule.name,
                    ruleType = rule.type,
                    matchedCount = matchedCount,
                    requiredCount = needed,
                    isMatched = isMatched && !inCooldown,
                    isConsumed = isConsumed,
                    signalStage = signalStage,
                    progressPercent = progressPercent,
                    conditionDescription = "$needed consecutive $targetTier rounds",
                    statusText = statusText,
                    triggerSignature = sig,
                    matchingMultipliers = matching
                )
            }

            StrategyRuleType.CUSTOM_PATTERN -> {
                val tokens = rule.customPattern
                    .split(',', ';', ' ')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val needed = tokens.size.coerceAtLeast(1)
                val offset = rule.waitRoundsAfterTrigger.coerceAtLeast(0)

                if (tokens.isEmpty()) {
                    return PatternProgress(
                        ruleName = rule.name,
                        ruleType = rule.type,
                        conditionDescription = "Empty pattern",
                        statusText = "Custom pattern is empty"
                    )
                }

                val availableRounds = recentRoundsNewestFirst.drop(offset)
                if (availableRounds.size < needed) {
                    val availableCount = availableRounds.size
                    val isPre = needed >= 2 && availableCount == needed - 1
                    return PatternProgress(
                        ruleName = rule.name,
                        ruleType = rule.type,
                        matchedCount = availableCount,
                        requiredCount = needed,
                        signalStage = if (isPre) PatternSignalStage.PRE_SIGNAL else PatternSignalStage.NONE,
                        conditionDescription = "Pattern [${tokens.joinToString(",")}]",
                        statusText = "Need $needed rounds for pattern (have $availableCount)"
                    )
                }

                val slice = availableRounds.take(needed).reversed()
                var matchCount = 0
                for (i in tokens.indices) {
                    if (matchesPatternToken(tokens[i], slice[i].crashMultiplier)) {
                        matchCount++
                    } else {
                        break
                    }
                }

                val allMatched = tokens.zip(slice).all { (tok, rec) -> matchesPatternToken(tok, rec.crashMultiplier) }
                val matching = slice.map { it.crashMultiplier }
                val patternOrigin = slice.firstOrNull()
                val patternOriginKey = patternOrigin?.let { "${it.roundId}_${formatMult(it.crashMultiplier)}" } ?: ""
                val sig = "${rule.id}_custom_${tokens.joinToString(",")}_${patternOriginKey}"
                val isConsumed = allMatched && (sig in consumedSignatures)

                val signalStage = when {
                    allMatched && !inCooldown && !isConsumed -> PatternSignalStage.HIGH_SIGNAL
                    !allMatched && needed >= 2 && matchCount == needed - 1 -> PatternSignalStage.PRE_SIGNAL
                    else -> PatternSignalStage.NONE
                }

                val statusText = when {
                    inCooldown -> "Cooldown: ${cooldownSec}s remaining"
                    isConsumed -> "Pattern [${tokens.joinToString(",")}] already triggered — waiting for new crash"
                    allMatched -> "PATTERN MET: Pattern [${tokens.joinToString(",")}] matched — Bet Ready!"
                    signalStage == PatternSignalStage.PRE_SIGNAL ->
                        "PRE-SIGNAL: 1 crash away! Pattern [${tokens.joinToString(",")}] ($matchCount/$needed matched)"
                    else -> "Pattern [${tokens.joinToString(",")}] not met ($matchCount/$needed matched)"
                }

                PatternProgress(
                    ruleName = rule.name,
                    ruleType = rule.type,
                    matchedCount = if (allMatched) needed else matchCount,
                    requiredCount = needed,
                    isMatched = allMatched && !inCooldown,
                    isConsumed = isConsumed,
                    signalStage = signalStage,
                    progressPercent = (matchCount.toFloat() / needed.toFloat()).coerceIn(0f, 1f),
                    conditionDescription = "Pattern [${tokens.joinToString(",")}]",
                    statusText = statusText,
                    triggerSignature = sig,
                    matchingMultipliers = matching
                )
            }

            StrategyRuleType.MULTIPLIER_THRESHOLD -> {
                val latest = recentRoundsNewestFirst.firstOrNull()
                val isMatched = latest != null && latest.crashMultiplier < rule.thresholdMultiplier
                val sig = if (latest != null) "thresh_${rule.id}_${latest.roundId}_${latest.crashMultiplier}" else ""
                val isConsumed = isMatched && (sig in consumedSignatures)

                val signalStage = when {
                    isMatched && !inCooldown && !isConsumed -> PatternSignalStage.HIGH_SIGNAL
                    else -> PatternSignalStage.NONE
                }

                val statusText = when {
                    inCooldown -> "Cooldown: ${cooldownSec}s remaining"
                    latest == null -> "No recent crash observed"
                    isConsumed -> "Triggered on crash ${formatMult(latest.crashMultiplier)}x — waiting for next crash"
                    isMatched -> "PATTERN MET: Last crash ${formatMult(latest.crashMultiplier)}x < ${rule.thresholdMultiplier}x"
                    else -> "Last crash ${formatMult(latest.crashMultiplier)}x >= ${rule.thresholdMultiplier}x"
                }

                PatternProgress(
                    ruleName = rule.name,
                    ruleType = rule.type,
                    matchedCount = if (isMatched) 1 else 0,
                    requiredCount = 1,
                    isMatched = isMatched && !inCooldown,
                    isConsumed = isConsumed,
                    signalStage = signalStage,
                    progressPercent = if (isMatched) 1f else 0f,
                    conditionDescription = "Last crash < ${rule.thresholdMultiplier}x",
                    statusText = statusText,
                    triggerSignature = sig,
                    matchingMultipliers = if (latest != null) listOf(latest.crashMultiplier) else emptyList()
                )
            }

            StrategyRuleType.TIME_COOLDOWN -> {
                val wait = rule.waitRoundsAfterTrigger
                if (wait <= 0) {
                    return PatternProgress(
                        ruleName = rule.name,
                        ruleType = rule.type,
                        matchedCount = 0,
                        requiredCount = 0,
                        isMatched = false,
                        signalStage = PatternSignalStage.NONE,
                        progressPercent = 0f,
                        conditionDescription = "Invalid wait rounds ($wait)",
                        statusText = "Invalid rule: wait rounds must be >= 1"
                    )
                }
                val hasEnough = recentRoundsNewestFirst.size >= wait + 1
                val triggerRound = if (hasEnough) recentRoundsNewestFirst[wait] else null
                val isMatched = triggerRound != null && triggerRound.crashMultiplier >= rule.thresholdMultiplier
                val sig = if (triggerRound != null) "wait_${rule.id}_${triggerRound.roundId}_${triggerRound.crashMultiplier}" else ""
                val isConsumed = isMatched && (sig in consumedSignatures)

                val signalStage = when {
                    isMatched && !inCooldown && !isConsumed -> PatternSignalStage.HIGH_SIGNAL
                    !isMatched && wait >= 2 && recentRoundsNewestFirst.size == wait -> PatternSignalStage.PRE_SIGNAL
                    else -> PatternSignalStage.NONE
                }

                val statusText = when {
                    inCooldown -> "Cooldown: ${cooldownSec}s remaining"
                    !hasEnough -> "Need ${wait + 1} rounds for cooldown pattern"
                    isConsumed -> "Cooldown elapsed — waiting for new >=${rule.thresholdMultiplier}x crash"
                    isMatched -> "PATTERN MET: $wait rounds elapsed after >=${rule.thresholdMultiplier}x"
                    else -> "Waiting for >=${rule.thresholdMultiplier}x + $wait rounds"
                }

                PatternProgress(
                    ruleName = rule.name,
                    ruleType = rule.type,
                    matchedCount = if (isMatched) 1 else 0,
                    requiredCount = 1,
                    isMatched = isMatched && !inCooldown,
                    isConsumed = isConsumed,
                    signalStage = signalStage,
                    progressPercent = if (isMatched) 1f else 0f,
                    conditionDescription = "Wait $wait rounds after >=${rule.thresholdMultiplier}x",
                    statusText = statusText,
                    triggerSignature = sig,
                    matchingMultipliers = if (triggerRound != null) listOf(triggerRound.crashMultiplier) else emptyList()
                )
            }
        }
    }

    private fun buildStreakSignature(
        ruleId: Long,
        kind: String,
        multipliers: List<Double>,
        recent: List<RoundRecord>
    ): String {
        val multStr = multipliers.joinToString(",") { formatMult(it) }
        val topId = recent.firstOrNull()?.roundId.orEmpty().ifBlank {
            recent.firstOrNull()?.let { "${it.crashMultiplier}_${it.timestampMs}" }.orEmpty()
        }
        return "${ruleId}_${kind}_${multStr}_${topId}"
    }

    private fun originKeyOf(record: RoundRecord): String =
        if (record.roundId.isNotBlank()) {
            "${record.roundId}_${formatMult(record.crashMultiplier)}"
        } else {
            "${record.timestampMs}_${formatMult(record.crashMultiplier)}"
        }

    private fun formatMult(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    fun evaluateRule(
        rule: StrategyRule,
        recentRoundsNewestFirst: List<RoundRecord>,
        nowMs: Long = System.currentTimeMillis()
    ): StrategyDecision {
        val progress = computeRuleProgress(rule, recentRoundsNewestFirst, emptySet(), nowMs)
        return StrategyDecision(
            shouldBet = progress.isMatched && !progress.isConsumed,
            triggeredRuleName = if (progress.isMatched) rule.name else null,
            reason = progress.statusText
        )
    }

    fun colorTierOf(multiplier: Double): String = when {
        multiplier < 2.0 -> "BLUE"
        multiplier < 10.0 -> "PURPLE"
        else -> "PINK"
    }

    fun matchesPatternToken(token: String, multiplier: Double): Boolean {
        var clean = token.trim().uppercase()
        if (clean.length > 1 && clean.endsWith("X")) {
            val withoutX = clean.dropLast(1).trimEnd()
            if (withoutX.any { it.isDigit() }) {
                clean = withoutX
            }
        }
        return when {
            clean == "B" || clean == "BLUE" || clean == "LOW" -> colorTierOf(multiplier) == "BLUE"
            clean == "P" || clean == "PURPLE" -> colorTierOf(multiplier) == "PURPLE"
            clean == "K" || clean == "PINK" -> colorTierOf(multiplier) == "PINK"
            clean == "HIGH" -> multiplier >= 2.0
            clean.startsWith(">=") -> clean.drop(2).trim().toDoubleOrNull()?.let { multiplier >= it } ?: false
            clean.startsWith("<=") -> clean.drop(2).trim().toDoubleOrNull()?.let { multiplier <= it } ?: false
            clean.startsWith(">") -> clean.drop(1).trim().toDoubleOrNull()?.let { multiplier > it } ?: false
            clean.startsWith("<") -> clean.drop(1).trim().toDoubleOrNull()?.let { multiplier < it } ?: false
            else -> clean.toDoubleOrNull()?.let { kotlin.math.abs(multiplier - it) <= 0.05 } ?: false
        }
    }
}
