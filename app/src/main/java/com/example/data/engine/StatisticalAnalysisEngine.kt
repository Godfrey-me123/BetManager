package com.example.data.engine

import com.example.data.model.CalendarHourUtils
import com.example.data.model.CrashColorCategory
import com.example.data.model.CrashRecord
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.MultiplierRuleSubtype
import com.example.data.model.PatternOccurrenceRecord
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyMode
import java.util.Locale
import kotlin.math.abs

enum class MultiplierBucket(val label: String) {
    LOW("Low (< threshold)"),
    BOUNDARY("Boundary (== threshold)"),
    MEDIUM("Medium"),
    BIG("Big Multiplier")
}

data class BigMultiplierGap(
    val firstTimestampMs: Long,
    val firstMultiplier: Double,
    val secondTimestampMs: Long,
    val secondMultiplier: Double,
    val gapMinutes: Double,
    val isWithinQualifyingRange: Boolean
)

data class StrategyOccurrenceTelemetry(
    val strategyId: Long,
    val strategyName: String,
    val gameId: String,
    val totalOccurrences: Int,
    val currentHourOccurrences: Int,
    val requiredPerHour: Int,
    val firstOccurrenceMs: Long?,
    val mostRecentOccurrenceMs: Long?,
    val averageIntervalMs: Long?,
    val shortestIntervalMs: Long?,
    val longestIntervalMs: Long?,
    val currentConsecutiveSequence: Int,
    val targetSequenceLength: Int,
    val remainingToTrigger: Int
)

data class StatisticalReport(
    val gameId: String = "ALL",
    val gameName: String = "All Games",
    val totalCompletedRounds: Int = 0,
    val lowCount: Int = 0,
    val boundaryCount: Int = 0,
    val mediumCount: Int = 0,
    val bigCount: Int = 0,
    val currentConsecutiveLows: Int = 0,
    val longestConsecutiveLows: Int = 0,
    val bigMultiplierCount: Int = 0,
    val bigMultiplierClusters: Int = 0,
    val bigMultiplierGaps: List<BigMultiplierGap> = emptyList(),
    val currentAlternatingLowMedLength: Int = 0,
    val maxAlternatingLowMedLength: Int = 0,
    val lowsBeforeBigCounts: List<Int> = emptyList(),
    val trailingColorCategories: List<CrashColorCategory> = emptyList(),
    val colorDistribution: Map<CrashColorCategory, Int> = emptyMap(),
    val topRepeatedColorSequences: List<Pair<String, Int>> = emptyList(),
    val sampleSufficient: Boolean = false,
    val factualSummary: String = "INSUFFICIENT DATA — Waiting for completed crash rounds."
)

data class StrategyBreakdownItem(
    val strategyName: String,
    val hourlyProgressText: String,
    val ruleExplanation: String,
    val streakProgressText: String,
    val remainingToTriggerText: String,
    val timestampsSummary: String
)

data class SmartAgentExplanation(
    val gameName: String = "Aviator",
    val liveStatusLine: String = "Monitoring active",
    val sampleConfidenceLabel: String = "Confidence: Waiting for rounds",
    val windowComparisonText: String = "Observation window ready",
    val strategyBreakdowns: List<StrategyBreakdownItem> = emptyList(),
    val integrityDisclaimer: String = "Statistical observation only — patterns summarize completed rounds and never guarantee future outcomes."
)

object StatisticalAnalysisEngine {

    private const val MIN_SUFFICIENT_SAMPLE = 5

    fun classifyBucket(
        multiplier: Double,
        lowThreshold: Double = 2.00,
        boundaryValue: Double = 2.00,
        bigThreshold: Double = 10.00
    ): MultiplierBucket {
        return when {
            abs(multiplier - boundaryValue) <= 0.0001 -> MultiplierBucket.BOUNDARY
            multiplier < lowThreshold -> MultiplierBucket.LOW
            multiplier >= bigThreshold -> MultiplierBucket.BIG
            else -> MultiplierBucket.MEDIUM
        }
    }

    fun analyzeCompletedCrashes(
        records: List<CrashRecord>,
        config: StrategyConfig = StrategyConfig(),
        minBigGapMinutes: Int = 5,
        maxBigGapMinutes: Int = 15,
        gameId: String = "ALL",
        gameName: String = "All Games"
    ): StatisticalReport {
        val lowTh = config.lowThreshold.takeIf { it.isFinite() && it >= 1.01 } ?: 2.00
        val boundaryVal = config.boundaryValue.takeIf { it.isFinite() && it >= 1.00 } ?: 2.00
        val bigTh = config.bigThreshold.takeIf { it.isFinite() && it > lowTh } ?: 10.00

        val scoped = if (gameId == "ALL") records else records.filter { it.gameId == gameId }
        val sorted = scoped.sortedWith(compareBy({ it.crashTimestamp }, { it.id }))

        if (sorted.isEmpty()) {
            return StatisticalReport(gameId = gameId, gameName = gameName)
        }

        var lowCount = 0
        var boundaryCount = 0
        var mediumCount = 0
        var bigCount = 0

        var runningLowStreak = 0
        var longestLowStreak = 0
        var lowsBeforeCurrentBig = 0
        val lowsBeforeBig = mutableListOf<Int>()

        var bigClusters = 0
        var prevWasBig = false
        val bigRecords = mutableListOf<CrashRecord>()
        val colorCounts = linkedMapOf<CrashColorCategory, Int>()

        var altLength = 0
        var maxAltLength = 0
        var lastAltWasLow: Boolean? = null

        for (rec in sorted) {
            colorCounts[rec.colorCategory] = (colorCounts[rec.colorCategory] ?: 0) + 1
            when (classifyBucket(rec.multiplier, lowTh, boundaryVal, bigTh)) {
                MultiplierBucket.LOW -> {
                    lowCount++
                    runningLowStreak++
                    if (runningLowStreak > longestLowStreak) longestLowStreak = runningLowStreak
                    lowsBeforeCurrentBig++
                    prevWasBig = false
                    altLength = if (lastAltWasLow == false) altLength + 1 else 1
                    lastAltWasLow = true
                    if (altLength > maxAltLength) maxAltLength = altLength
                }
                MultiplierBucket.BOUNDARY -> {
                    boundaryCount++
                    runningLowStreak = 0
                    lowsBeforeCurrentBig = 0
                    prevWasBig = false
                    altLength = 0
                    lastAltWasLow = null
                }
                MultiplierBucket.MEDIUM -> {
                    mediumCount++
                    runningLowStreak = 0
                    lowsBeforeCurrentBig = 0
                    prevWasBig = false
                    altLength = if (lastAltWasLow == true) altLength + 1 else 1
                    lastAltWasLow = false
                    if (altLength > maxAltLength) maxAltLength = altLength
                }
                MultiplierBucket.BIG -> {
                    bigCount++
                    bigRecords.add(rec)
                    if (!prevWasBig) {
                        lowsBeforeBig.add(lowsBeforeCurrentBig)
                    } else {
                        bigClusters++
                    }
                    prevWasBig = true
                    runningLowStreak = 0
                    lowsBeforeCurrentBig = 0
                    altLength = 0
                    lastAltWasLow = null
                }
            }
        }

        val bigGaps = mutableListOf<BigMultiplierGap>()
        for (i in 1 until bigRecords.size) {
            val prev = bigRecords[i - 1]
            val curr = bigRecords[i]
            val gapMin = ((curr.crashTimestamp - prev.crashTimestamp).coerceAtLeast(0L)) / 60_000.0
            bigGaps.add(
                BigMultiplierGap(
                    firstTimestampMs = prev.crashTimestamp,
                    firstMultiplier = prev.multiplier,
                    secondTimestampMs = curr.crashTimestamp,
                    secondMultiplier = curr.multiplier,
                    gapMinutes = gapMin,
                    isWithinQualifyingRange = gapMin in minBigGapMinutes.toDouble()..maxBigGapMinutes.toDouble()
                )
            )
        }

        val colorTriples = linkedMapOf<String, Int>()
        if (sorted.size >= 3) {
            for (i in 0..(sorted.size - 3)) {
                val seqKey = "${sorted[i].colorCategory.displayName}->${sorted[i + 1].colorCategory.displayName}->${sorted[i + 2].colorCategory.displayName}"
                colorTriples[seqKey] = (colorTriples[seqKey] ?: 0) + 1
            }
        }
        val topColors = colorTriples.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        val sampleOk = sorted.size >= MIN_SUFFICIENT_SAMPLE
        val summary = buildString {
            append("Observed ${sorted.size} completed rounds: ")
            append("$lowCount low (<${String.format(Locale.US, "%.2fx", lowTh)}), ")
            append("$mediumCount medium, $bigCount big (>=${String.format(Locale.US, "%.2fx", bigTh)}). ")
            append("Current consecutive low chain: $runningLowStreak (longest observed: $longestLowStreak).")
        }

        return StatisticalReport(
            gameId = gameId,
            gameName = gameName,
            totalCompletedRounds = sorted.size,
            lowCount = lowCount,
            boundaryCount = boundaryCount,
            mediumCount = mediumCount,
            bigCount = bigCount,
            currentConsecutiveLows = runningLowStreak,
            longestConsecutiveLows = longestLowStreak,
            bigMultiplierCount = bigCount,
            bigMultiplierClusters = bigClusters,
            bigMultiplierGaps = bigGaps,
            currentAlternatingLowMedLength = altLength,
            maxAlternatingLowMedLength = maxAltLength,
            lowsBeforeBigCounts = lowsBeforeBig,
            trailingColorCategories = sorted.takeLast(8).map { it.colorCategory },
            colorDistribution = colorCounts,
            topRepeatedColorSequences = topColors,
            sampleSufficient = sampleOk,
            factualSummary = summary
        )
    }

    fun buildStrategyTelemetry(
        strategy: CustomPatternStrategy,
        occurrences: List<PatternOccurrenceRecord>,
        report: StatisticalReport,
        nowMs: Long = System.currentTimeMillis()
    ): StrategyOccurrenceTelemetry {
        val currentHourStart = CalendarHourUtils.hourStartFor(nowMs)
        val scopedOccurrences = occurrences
            .filter { it.strategyId == strategy.id && (strategy.targetGameId == "ALL" || it.gameId == strategy.targetGameId) }
            .sortedBy { it.triggerTimestampMs }

        val hourOccurrences = scopedOccurrences.count { it.hourStartTimestamp == currentHourStart }
        val firstTs = scopedOccurrences.firstOrNull()?.triggerTimestampMs
        val lastTs = scopedOccurrences.lastOrNull()?.triggerTimestampMs

        val gaps = mutableListOf<Long>()
        for (i in 1 until scopedOccurrences.size) {
            gaps.add((scopedOccurrences[i].triggerTimestampMs - scopedOccurrences[i - 1].triggerTimestampMs).coerceAtLeast(0L))
        }

        val targetSeq = when {
            strategy.multiplierRuleSubtype == MultiplierRuleSubtype.ABOVE_THEN_BELOW ->
                (strategy.aboveConsecutiveCount + strategy.belowConsecutiveCount).coerceAtLeast(1)
            strategy.strategyMode == StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS ->
                strategy.getConditions().firstOrNull()?.consecutiveRounds?.coerceAtLeast(1) ?: 1
            strategy.strategyMode == StrategyMode.STRATEGY_B_BIG_INTERVALS ->
                if (strategy.postBigWaitMinutes > 0) 1 else 2
            strategy.strategyMode == StrategyMode.STRATEGY_C_COLOR_PATTERNS ->
                maxOf(strategy.getColorSequence().size, strategy.getCustomHexSequence().size, 1)
            else ->
                strategy.getConditions().firstOrNull()?.consecutiveRounds?.coerceAtLeast(1) ?: 1
        }

        val currentSeq = when (strategy.strategyMode) {
            StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS ->
                if (strategy.currentlyReached) targetSeq else report.currentConsecutiveLows.coerceAtMost(targetSeq)
            StrategyMode.STRATEGY_C_COLOR_PATTERNS -> {
                val pattern = strategy.getColorSequence()
                val tail = report.trailingColorCategories
                var matched = 0
                if (pattern.isNotEmpty() && tail.isNotEmpty()) {
                    for (len in minOf(pattern.size, tail.size) downTo 1) {
                        if (tail.takeLast(len) == pattern.take(len)) {
                            matched = len
                            break
                        }
                    }
                }
                if (strategy.currentlyReached) targetSeq else matched
            }
            StrategyMode.STRATEGY_B_BIG_INTERVALS ->
                if (strategy.currentlyReached) targetSeq else report.bigMultiplierGaps.count {
                    it.gapMinutes in strategy.minIntervalMinutes.toDouble()..strategy.maxIntervalMinutes.toDouble()
                }.coerceAtMost(targetSeq)
            StrategyMode.CUSTOM_CONDITION ->
                if (strategy.currentlyReached) targetSeq else 0
        }

        val remaining = (targetSeq - currentSeq).coerceAtLeast(0)

        return StrategyOccurrenceTelemetry(
            strategyId = strategy.id,
            strategyName = strategy.name,
            gameId = report.gameId,
            totalOccurrences = scopedOccurrences.size,
            currentHourOccurrences = hourOccurrences,
            requiredPerHour = strategy.requiredOccurrencesPerHour.coerceAtLeast(1),
            firstOccurrenceMs = firstTs,
            mostRecentOccurrenceMs = lastTs,
            averageIntervalMs = if (gaps.isEmpty()) null else gaps.average().toLong(),
            shortestIntervalMs = gaps.minOrNull(),
            longestIntervalMs = gaps.maxOrNull(),
            currentConsecutiveSequence = currentSeq,
            targetSequenceLength = targetSeq,
            remainingToTrigger = remaining
        )
    }
}

object SmartAnalysisAgent {

    fun generateExplanation(
        gameName: String,
        isRunning: Boolean,
        statusLine: String,
        strategies: List<CustomPatternStrategy>,
        report: StatisticalReport,
        occurrences: List<PatternOccurrenceRecord>,
        records: List<CrashRecord>,
        config: StrategyConfig,
        nowMs: Long = System.currentTimeMillis()
    ): SmartAgentExplanation {
        val enabledStrategies = strategies.filter { it.isEnabled }
        val breakdowns = enabledStrategies.map { strat ->
            val telemetry = StatisticalAnalysisEngine.buildStrategyTelemetry(
                strategy = strat,
                occurrences = occurrences,
                report = report,
                nowMs = nowMs
            )
            val firstStr = telemetry.firstOccurrenceMs?.let { CalendarHourUtils.formatExactTime(it) } ?: "None yet"
            val lastStr = telemetry.mostRecentOccurrenceMs?.let { CalendarHourUtils.formatExactTime(it) } ?: "None yet"

            StrategyBreakdownItem(
                strategyName = strat.name,
                hourlyProgressText = "${telemetry.currentHourOccurrences} of ${telemetry.requiredPerHour} observed in current hour",
                ruleExplanation = strat.summaryDescription(),
                streakProgressText = "${telemetry.currentConsecutiveSequence} of ${telemetry.targetSequenceLength} active sequence",
                remainingToTriggerText = if (telemetry.remainingToTrigger == 0) {
                    "Condition threshold met in completed rounds"
                } else {
                    "${telemetry.remainingToTrigger} remaining to match rule"
                },
                timestampsSummary = "First: $firstStr · Latest: $lastStr (Total recorded: ${telemetry.totalOccurrences})"
            )
        }

        val cutoff30m = nowMs - 30 * 60_000L
        val last30m = records.filter { it.crashTimestamp >= cutoff30m && (report.gameId == "ALL" || it.gameId == report.gameId) }
        val curHourStart = CalendarHourUtils.hourStartFor(nowMs)
        val curHour = records.filter { it.hourStartTimestamp == curHourStart && (report.gameId == "ALL" || it.gameId == report.gameId) }

        val lowPct30m = if (last30m.isEmpty()) 0.0 else last30m.count { it.multiplier < config.lowThreshold } * 100.0 / last30m.size
        val lowPctHr = if (curHour.isEmpty()) 0.0 else curHour.count { it.multiplier < config.lowThreshold } * 100.0 / curHour.size
        val comparisonText = String.format(
            Locale.US,
            "30m Rolling: %d rounds (%.1f%% < %.2fx). Current Hour: %d rounds (%.1f%% low).",
            last30m.size, lowPct30m, config.lowThreshold, curHour.size, lowPctHr
        )

        val sampleConfidence = when {
            report.totalCompletedRounds < 5 -> "INSUFFICIENT DATA (${report.totalCompletedRounds} completed rounds)"
            report.totalCompletedRounds < 20 -> "Low sample (${report.totalCompletedRounds} rounds) — building baseline"
            report.totalCompletedRounds < 50 -> "Moderate sample (${report.totalCompletedRounds} rounds)"
            else -> "High sample (${report.totalCompletedRounds} completed rounds)"
        }

        val liveStatus = if (isRunning) {
            "$gameName · $statusLine"
        } else {
            "$gameName · Idle ($statusLine)"
        }

        return SmartAgentExplanation(
            gameName = gameName,
            liveStatusLine = liveStatus,
            sampleConfidenceLabel = sampleConfidence,
            windowComparisonText = comparisonText,
            strategyBreakdowns = breakdowns
        )
    }
}
