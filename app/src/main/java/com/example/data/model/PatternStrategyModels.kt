package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class CrashColorCategory(val displayName: String, val defaultHex: String) {
    Blue("Blue", "#2563EB"),
    Purple("Purple", "#7C3AED"),
    Pink("Pink", "#EC4899"),
    Red("Red", "#DC2626"),
    Green("Green", "#16A34A"),
    Yellow("Yellow", "#CA8A04"),
    Orange("Orange", "#EA580C"),
    Other("Other", "#64748B")
}

fun parseSafeCrashColor(hex: String): Color {
    val argb = ColorClassifier.parseHexToArgb(hex)
    return if (argb != null) Color(argb) else Color.Gray
}

object ColorClassifier {
    fun formatArgbHex(argb: Int): String {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return String.format(Locale.US, "#%02X%02X%02X", r, g, b)
    }

    fun parseHexToArgb(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        val clean = hex.trim().removePrefix("#")
        return when (clean.length) {
            6 -> clean.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
            8 -> clean.toLongOrNull(16)?.toInt()
            else -> null
        }
    }

    fun normalizeHex(hex: String?): String {
        val argb = parseHexToArgb(hex) ?: return CrashColorCategory.Other.defaultHex
        return formatArgbHex(argb)
    }

    fun classifyArgb(argb: Int): CrashColorCategory {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC
        val saturation = if (maxC <= 0f) 0f else delta / maxC
        val brightness = maxC

        if (saturation < 0.16f || brightness < 0.12f) {
            return CrashColorCategory.Other
        }

        val hue = when {
            delta <= 0.0001f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        return when {
            hue in 312f..348f -> CrashColorCategory.Pink
            hue >= 348f || hue < 15f -> {
                if (b > 0.45f && r > 0.7f && g < 0.45f) CrashColorCategory.Pink else CrashColorCategory.Red
            }
            hue in 15f..38f -> CrashColorCategory.Orange
            hue in 38f..70f -> CrashColorCategory.Yellow
            hue in 70f..165f -> CrashColorCategory.Green
            hue in 165f..252f -> CrashColorCategory.Blue
            hue in 252f..312f -> CrashColorCategory.Purple
            else -> CrashColorCategory.Other
        }
    }

    fun classifyHex(hex: String?): CrashColorCategory {
        val argb = parseHexToArgb(hex) ?: return CrashColorCategory.Other
        return classifyArgb(argb)
    }

    fun fallbackCategoryForMultiplier(
        multiplier: Double,
        lowThreshold: Double = 2.00,
        bigThreshold: Double = 10.00
    ): CrashColorCategory {
        return when {
            multiplier < lowThreshold -> CrashColorCategory.Blue
            abs(multiplier - lowThreshold) < 0.0001 -> CrashColorCategory.Purple
            multiplier < bigThreshold -> CrashColorCategory.Purple
            else -> CrashColorCategory.Pink
        }
    }
}

enum class HistoryCollectionStatus(val displayLabel: String) {
    HISTORY_NOT_STARTED("Not Started"),
    COLLECTING_VISIBLE_HISTORY("Reading Visible Ribbon"),
    EXPANDING_HISTORY("Expanding History Panel"),
    SCROLLING_HISTORY("Scrolling History Panel"),
    HISTORY_PARTIAL("Partial History"),
    HISTORY_COMPLETE("30m History Complete"),
    HISTORY_UNAVAILABLE("History Unavailable"),
    HISTORY_ERROR("Collection Error")
}

enum class TimestampSource(val displayLabel: String) {
    GAME_PROVIDED("Game Time"),
    LOCAL_OBSERVATION("Observed Time")
}

enum class CollectionSource(val displayLabel: String) {
    VISIBLE_RIBBON("Visible Ribbon"),
    EXPANDED_PANEL("Expanded Panel"),
    SCROLLED_HISTORY("Scrolled History"),
    LIVE_CRASH("Live Crash"),
    LIVE("Live"),
    HISTORY_IMPORT("History Import")
}

@Entity(
    tableName = "crash_records",
    indices = [
        Index(value = ["dedupKey"], unique = true),
        Index(value = ["sessionId"]),
        Index(value = ["gameId"]),
        Index(value = ["crashTimestamp"]),
        Index(value = ["hourStartTimestamp"])
    ]
)
data class CrashRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String = "",
    val gameId: String,
    val gameName: String,
    val originalDetectedTitle: String = "",
    val multiplier: Double,
    val detectedColorHex: String,
    val colorCategory: CrashColorCategory,
    val isPixelColorVerified: Boolean = false,
    val crashTimestamp: Long,
    val hourStartTimestamp: Long,
    val sourcePackage: String = "",
    val roundIdentifier: String? = null,
    val confidence: Float = 0.85f,
    val manuallyCorrected: Boolean = false,
    val isConfirmed: Boolean = true,
    val observationOrder: Int = 0,
    val collectionSource: CollectionSource = CollectionSource.LIVE_CRASH,
    val dedupKey: String
) {
    companion object {
        fun buildDedupKey(
            gameId: String,
            roundIdentifier: String?,
            crashTimestamp: Long,
            multiplier: Double,
            sequenceIndex: Int = 0
        ): String {
            val cleanGame = gameId.lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "_")
            val multStr = String.format(Locale.US, "%.2f", multiplier)
            if (!roundIdentifier.isNullOrBlank()) {
                return "${cleanGame}_rid_${roundIdentifier.trim()}"
            }
            return "${cleanGame}_ts_${crashTimestamp}_${multStr}_s$sequenceIndex"
        }
    }
}

@Entity(
    tableName = "multiplier_records",
    indices = [
        Index(value = ["dedupKey"], unique = true),
        Index(value = ["sessionId"]),
        Index(value = ["crashTimestamp"])
    ]
)
data class MultiplierRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String = "",
    val gameId: String = "unknown",
    val roundId: String? = null,
    val multiplier: Double,
    val gameTimestampMs: Long? = null,
    val observationTimestampMs: Long = System.currentTimeMillis(),
    val crashTimestamp: Long = gameTimestampMs ?: observationTimestampMs,
    val platform: String = "Unknown",
    val isConfirmed: Boolean = true,
    val timestampSource: TimestampSource = if (gameTimestampMs != null) {
        TimestampSource.GAME_PROVIDED
    } else {
        TimestampSource.LOCAL_OBSERVATION
    },
    val collectionSource: CollectionSource = CollectionSource.LIVE_CRASH,
    val collectionStatus: HistoryCollectionStatus = HistoryCollectionStatus.HISTORY_PARTIAL,
    val dedupKey: String = buildFallbackDedupKey(
        gameId = gameId,
        platform = platform,
        roundId = roundId,
        gameTimestampMs = gameTimestampMs,
        observationTimestampMs = observationTimestampMs,
        crashTimestamp = crashTimestamp,
        multiplier = multiplier,
        sequenceIndex = 0
    ),
    val sequenceIndex: Int = 0,
    val hasGapBefore: Boolean = false
) {
    companion object {
        fun buildFallbackDedupKey(
            gameId: String,
            platform: String,
            roundId: String?,
            gameTimestampMs: Long?,
            observationTimestampMs: Long,
            crashTimestamp: Long,
            multiplier: Double,
            sequenceIndex: Int
        ): String {
            val cleanPlatform = platform.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "_")
            val multStr = String.format(Locale.US, "%.2f", multiplier)
            if (!roundId.isNullOrBlank()) {
                return "${gameId}_${cleanPlatform}_rid_${roundId.trim()}"
            }
            if (gameTimestampMs != null && gameTimestampMs > 0L) {
                return "${gameId}_${cleanPlatform}_gt_${gameTimestampMs}_${multStr}_s$sequenceIndex"
            }
            val effectiveTs = if (crashTimestamp > 0L) crashTimestamp else observationTimestampMs
            return "${gameId}_${cleanPlatform}_obs_${effectiveTs}_${multStr}_s$sequenceIndex"
        }
    }
}

object CalendarHourUtils {
    fun hourStartFor(timestampMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestampMs
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun formatDateTime(timestampMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return sdf.format(Date(timestampMs))
    }

    fun formatExactTime(timestampMs: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        return sdf.format(Date(timestampMs))
    }
}

@Entity(tableName = "pattern_occurrences")
data class PatternOccurrenceRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val occurrenceKey: String,
    val strategyId: Long,
    val strategyName: String,
    val gameId: String,
    val gameName: String,
    val patternType: String,
    val triggerTimestampMs: Long,
    val hourStartTimestamp: Long,
    val detailsSummary: String,
    val firstMatchedTimestampMs: Long,
    val intervalSincePrevMs: Long? = null
)



enum class StrategyGroup(val displayTitle: String, val subtitle: String) {
    MULTIPLIER_INTERVAL(
        "A. Multiplier Interval",
        "LOW, BIG, Above-X then Below-Y, & Custom Thresholds"
    ),
    TIME_INTERVAL(
        "B. Time Interval",
        "Wait-before-bet timers (2m–55m) & 1h/3h/6h/Custom frequency"
    ),
    COLOR_SEQUENCE(
        "C. Color Sequence",
        "Horizontal & Vertical grid color sequences & HEX rules"
    )
}

enum class MultiplierRuleSubtype(val label: String) {
    STANDARD_THRESHOLD("Threshold / Consecutive Rule"),
    LOW_MULTIPLIER("LOW Multiplier Rule"),
    BIG_MULTIPLIER("BIG Multiplier Rule"),
    ABOVE_THEN_BELOW("Above X (N times) -> Below Y (M times)")
}

enum class TimePeriodPreset(val label: String, val minutes: Int) {
    ONE_HOUR("1 Hour", 60),
    THREE_HOURS("3 Hours", 180),
    SIX_HOURS("6 Hours", 360),
    CUSTOM("Custom", 0)
}

enum class ColorMatchDirection(val label: String) {
    HORIZONTAL("Horizontal (Chronological Right -> Left)"),
    VERTICAL("Vertical (Stacked Grid Column)"),
    BOTH("Both Horizontal & Vertical")
}

enum class PatternComparator(val symbol: String, val label: String) {
    LESS_THAN("<", "Below (< exclusive)"),
    LESS_OR_EQUAL("<=", "At or Below (<= inclusive)"),
    GREATER_THAN(">", "Above (> exclusive)"),
    GREATER_OR_EQUAL(">=", "At or Above (>= inclusive)"),
    BETWEEN("between", "Between Range");

    fun matches(value: Double, threshold: Double, upperThreshold: Double? = null): Boolean {
        if (!value.isFinite() || !threshold.isFinite()) return false
        return when (this) {
            LESS_THAN -> value < threshold
            LESS_OR_EQUAL -> value <= threshold
            GREATER_THAN -> value > threshold
            GREATER_OR_EQUAL -> value >= threshold
            BETWEEN -> {
                val upper = upperThreshold?.takeIf { it.isFinite() && it >= threshold } ?: threshold
                value in threshold..upper
            }
        }
    }
}

enum class PatternLogicOperator(val label: String) {
    AND("ALL conditions match (AND)"),
    OR("ANY condition matches (OR)")
}

enum class NotificationPriorityLevel(val label: String) {
    STANDARD("Standard"),
    HIGH("High"),
    URGENT("Urgent")
}

enum class RepeatNotificationPolicy(val label: String) {
    ONCE_PER_OCCURRENCE("Once per occurrence"),
    COOLDOWN_MINUTES("Repeat after cooldown")
}

enum class StrategyMode(val label: String) {
    STRATEGY_A_CONSECUTIVE_LOWS("Multiplier Interval · Consecutive / Composite"),
    STRATEGY_B_BIG_INTERVALS("Time Interval · Post-Big Wait & Frequency"),
    STRATEGY_C_COLOR_PATTERNS("Color Sequence · Horizontal & Vertical"),
    CUSTOM_CONDITION("Custom Multiplier Rules");

    fun defaultGroup(): StrategyGroup = when (this) {
        STRATEGY_A_CONSECUTIVE_LOWS, CUSTOM_CONDITION -> StrategyGroup.MULTIPLIER_INTERVAL
        STRATEGY_B_BIG_INTERVALS -> StrategyGroup.TIME_INTERVAL
        STRATEGY_C_COLOR_PATTERNS -> StrategyGroup.COLOR_SEQUENCE
    }
}

data class PatternCondition(
    val comparator: PatternComparator = PatternComparator.LESS_THAN,
    val threshold: Double = 2.00,
    val upperThreshold: Double? = null,
    val consecutiveRounds: Int = 1,
    val timeWindowMinutes: Int? = null,
    val sequenceMultipliers: List<Double> = emptyList()
) {
    fun summaryText(): String {
        val multFormatted = String.format(Locale.US, "%.2f", threshold)
        val rangeText = if (comparator == PatternComparator.BETWEEN && upperThreshold != null) {
            val upperFormatted = String.format(Locale.US, "%.2f", upperThreshold)
            "${multFormatted}x–${upperFormatted}x"
        } else {
            "${comparator.symbol} ${multFormatted}x"
        }
        val windowSuffix = if (timeWindowMinutes != null && timeWindowMinutes > 0) {
            " within ${timeWindowMinutes}m"
        } else {
            ""
        }
        return if (sequenceMultipliers.isNotEmpty()) {
            "Sequence [${sequenceMultipliers.joinToString(", ") { "${String.format(Locale.US, "%.2f", it)}x" }}]$windowSuffix"
        } else {
            "$consecutiveRounds consecutive rounds $rangeText$windowSuffix"
        }
    }
}

@Entity(tableName = "custom_pattern_strategies")
data class CustomPatternStrategy(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val isEnabled: Boolean = true,
    val strategyGroup: StrategyGroup = StrategyGroup.MULTIPLIER_INTERVAL,
    val strategyMode: StrategyMode = StrategyMode.CUSTOM_CONDITION,
    val multiplierRuleSubtype: MultiplierRuleSubtype = MultiplierRuleSubtype.STANDARD_THRESHOLD,
    val logicOperator: PatternLogicOperator = PatternLogicOperator.AND,
    val conditionsJson: String = serializeConditions(emptyList()),
    val aboveThresholdX: Double = 2.00,
    val aboveConsecutiveCount: Int = 1,
    val belowThresholdY: Double = 2.00,
    val belowConsecutiveCount: Int = 1,
    val inclusiveBounds: Boolean = false,
    val postBigWaitMinutes: Int = 5,
    val bigMultiplierThreshold: Double = 10.00,
    val specificAlertTimesCsv: String = "",
    val colorMatchDirection: ColorMatchDirection = ColorMatchDirection.HORIZONTAL,
    val gridColumnsForVertical: Int = 8,
    val customColorHexFilterCsv: String = "",
    val waitingPeriodSeconds: Int = 0,
    val notificationLevel: NotificationPriorityLevel = NotificationPriorityLevel.HIGH,
    val approachingEnabled: Boolean = true,
    val customMessage: String = "",
    val repeatPolicy: RepeatNotificationPolicy = RepeatNotificationPolicy.ONCE_PER_OCCURRENCE,
    val repeatCooldownMinutes: Int = 0,
    val repeatLimit: Int = 0,
    val notificationEnabled: Boolean = true,
    val useAlarmStyle: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val sortOrder: Int = 0,
    val requiredOccurrencesPerHour: Int = 1,
    val analysisWindowMinutes: Int = 60,
    val minIntervalMinutes: Int = 5,
    val maxIntervalMinutes: Int = 15,
    val colorSequenceCsv: String = "",
    val targetGameId: String = "ALL",
    val lastEvaluatedRecordKey: String? = null,
    val lastTriggeredOccurrenceKey: String? = null,
    val lastApproachedOccurrenceKey: String? = null,
    val lastNotifiedAtMs: Long = 0L,
    val currentlyApproaching: Boolean = false,
    val currentlyReached: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun effectiveGroup(): StrategyGroup = when (strategyMode) {
        StrategyMode.STRATEGY_B_BIG_INTERVALS -> StrategyGroup.TIME_INTERVAL
        StrategyMode.STRATEGY_C_COLOR_PATTERNS -> StrategyGroup.COLOR_SEQUENCE
        else -> strategyGroup
    }

    fun resolveAlarmProfileCategory(): AlarmProfileCategory {
        if (!useAlarmStyle) return AlarmProfileCategory.STANDARD_ALERTS
        return when (effectiveGroup()) {
            StrategyGroup.TIME_INTERVAL -> AlarmProfileCategory.TIME_BASED_ALERTS
            StrategyGroup.COLOR_SEQUENCE -> AlarmProfileCategory.COLOR_SEQUENCE_ALERTS
            StrategyGroup.MULTIPLIER_INTERVAL -> {
                if (multiplierRuleSubtype != MultiplierRuleSubtype.STANDARD_THRESHOLD) {
                    AlarmProfileCategory.MULTIPLIER_CONDITIONS
                } else if (notificationLevel == NotificationPriorityLevel.URGENT) {
                    AlarmProfileCategory.HIGH_PRIORITY_ALERTS
                } else {
                    AlarmProfileCategory.MULTIPLIER_CONDITIONS
                }
            }
        }
    }

    fun getConditions(): List<PatternCondition> = deserializeConditions(conditionsJson)

    fun withConditions(conditions: List<PatternCondition>): CustomPatternStrategy {
        return copy(conditionsJson = serializeConditions(conditions))
    }

    fun getColorSequence(): List<CrashColorCategory> {
        if (colorSequenceCsv.isBlank()) return emptyList()
        return colorSequenceCsv.split(",")
            .mapNotNull { token ->
                val clean = token.trim()
                CrashColorCategory.entries.firstOrNull {
                    it.name.equals(clean, ignoreCase = true) ||
                        it.displayName.equals(clean, ignoreCase = true)
                }
            }
    }

    fun getCustomHexSequence(): List<String> {
        if (customColorHexFilterCsv.isBlank()) return emptyList()
        return customColorHexFilterCsv.split(",")
            .map { it.trim() }
            .mapNotNull { token ->
                when {
                    token.equals("ANY", ignoreCase = true) -> "ANY"
                    token.startsWith("#") && (token.length == 7 || token.length == 9) ->
                        ColorClassifier.normalizeHex(token)
                    token.length == 6 || token.length == 8 ->
                        ColorClassifier.parseHexToArgb(token)?.let { ColorClassifier.formatArgbHex(it) }
                    else -> null
                }
            }
    }

    fun getSpecificAlertTimes(): List<Pair<Int, Int>> {
        if (specificAlertTimesCsv.isBlank()) return emptyList()
        return specificAlertTimesCsv.split(",")
            .mapNotNull { token ->
                val parts = token.trim().split(":")
                if (parts.size != 2) return@mapNotNull null
                val h = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return@mapNotNull null
                val m = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return@mapNotNull null
                h to m
            }
    }

    fun summaryDescription(): String {
        if (multiplierRuleSubtype == MultiplierRuleSubtype.ABOVE_THEN_BELOW) {
            val aboveOp = if (inclusiveBounds) ">=" else ">"
            val belowOp = if (inclusiveBounds) "<=" else "<"
            return "After ${aboveConsecutiveCount}x $aboveOp ${String.format(Locale.US, "%.2f", aboveThresholdX)}x, " +
                "then ${belowConsecutiveCount}x $belowOp ${String.format(Locale.US, "%.2f", belowThresholdY)}x -> Ring Alarm"
        }
        return when (strategyMode) {
            StrategyMode.STRATEGY_A_CONSECUTIVE_LOWS -> {
                val conds = getConditions()
                if (conds.size > 1) {
                    val joined = conds.joinToString(if (logicOperator == PatternLogicOperator.AND) " AND " else " OR ") {
                        it.summaryText()
                    }
                    "$joined · ${requiredOccurrencesPerHour}/${analysisWindowMinutes}m"
                } else {
                    val c = conds.firstOrNull()
                    if (c == null) {
                        "No condition configured · ${requiredOccurrencesPerHour}/${analysisWindowMinutes}m"
                    } else {
                        val th = c.threshold
                        val count = c.consecutiveRounds
                        val op = c.comparator.symbol
                        "$count consecutive $op ${String.format(Locale.US, "%.2f", th)}x · ${requiredOccurrencesPerHour}/${analysisWindowMinutes}m"
                    }
                }
            }
            StrategyMode.STRATEGY_B_BIG_INTERVALS -> {
                val th = bigMultiplierThreshold.takeIf { it >= 1.0 }
                    ?: getConditions().firstOrNull()?.threshold
                    ?: 10.00
                val waitPart = if (postBigWaitMinutes > 0) "Wait ${postBigWaitMinutes}m after >=${String.format(Locale.US, "%.2f", th)}x" else "Gap ${minIntervalMinutes}–${maxIntervalMinutes}m"
                val schedPart = if (specificAlertTimesCsv.isNotBlank()) " @ $specificAlertTimesCsv" else ""
                "$waitPart · ${requiredOccurrencesPerHour}x/${analysisWindowMinutes}m$schedPart"
            }
            StrategyMode.STRATEGY_C_COLOR_PATTERNS -> {
                val colors = getColorSequence().joinToString(" -> ") { it.displayName }
                    .ifBlank { customColorHexFilterCsv.ifBlank { colorSequenceCsv } }
                "${colorMatchDirection.name}: $colors (${analysisWindowMinutes}m)"
            }
            StrategyMode.CUSTOM_CONDITION -> {
                val conds = getConditions()
                if (conds.isEmpty()) return "No conditions configured"
                val joined = conds.joinToString(if (logicOperator == PatternLogicOperator.AND) " AND " else " OR ") {
                    it.summaryText()
                }
                if (waitingPeriodSeconds > 0) "$joined · Wait ${waitingPeriodSeconds}s" else joined
            }
        }
    }

    companion object {
        fun serializeConditions(conditions: List<PatternCondition>): String {
            if (conditions.isEmpty()) return ""
            return conditions.joinToString(";") { c ->
                val seqStr = c.sequenceMultipliers.joinToString(",") {
                    String.format(Locale.US, "%.2f", it)
                }
                listOf(
                    c.comparator.name,
                    c.threshold.toString(),
                    c.upperThreshold?.toString() ?: "",
                    c.consecutiveRounds.toString(),
                    c.timeWindowMinutes?.toString() ?: "",
                    seqStr
                ).joinToString("|")
            }
        }

        fun deserializeConditions(encoded: String?): List<PatternCondition> {
            if (encoded.isNullOrBlank()) return emptyList()
            return encoded.split(";").mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size < 4) return@mapNotNull null
                val comp = runCatching { PatternComparator.valueOf(parts[0]) }.getOrNull()
                    ?: return@mapNotNull null
                val threshold = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1.0 }
                    ?: return@mapNotNull null
                val upper = parts.getOrNull(2)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= threshold }
                val consecutive = parts.getOrNull(3)?.toIntOrNull() ?: 1
                val timeWindow = parts.getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 }
                val seq = parts.getOrNull(5)
                    ?.split(",")
                    ?.mapNotNull { it.trim().toDoubleOrNull()?.takeIf { v -> v.isFinite() && v >= 1.0 } }
                    ?: emptyList()
                PatternCondition(
                    comparator = comp,
                    threshold = threshold,
                    upperThreshold = upper,
                    consecutiveRounds = consecutive,
                    timeWindowMinutes = timeWindow,
                    sequenceMultipliers = seq
                )
            }
        }
    }
}

data class StrategyValidationResult(
    val isValid: Boolean,
    val warnings: List<String> = emptyList(),
    val blockingErrors: List<String> = emptyList()
)

object StrategyRangeValidator {
    fun validate(
        candidate: CustomPatternStrategy,
        existing: List<CustomPatternStrategy>,
        configuredLowThreshold: Double = 2.00,
        configuredBigThreshold: Double = 10.00
    ): StrategyValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (candidate.name.isBlank()) {
            errors.add("Strategy name cannot be empty.")
        }
        if (configuredLowThreshold >= configuredBigThreshold) {
            warnings.add("Global LOW threshold (${configuredLowThreshold}x) >= BIG threshold (${configuredBigThreshold}x) causes range overlap.")
        }

        val conds = candidate.getConditions()
        if (candidate.multiplierRuleSubtype == MultiplierRuleSubtype.ABOVE_THEN_BELOW) {
            if (candidate.aboveThresholdX < 1.0 || candidate.belowThresholdY < 1.0) {
                errors.add("Both Above X and Below Y thresholds must be >= 1.00x.")
            }
            if (candidate.aboveConsecutiveCount < 1 || candidate.belowConsecutiveCount < 1) {
                errors.add("Consecutive counts must be at least 1.")
            }
            if (candidate.belowThresholdY > candidate.aboveThresholdX) {
                warnings.add("Below threshold (${candidate.belowThresholdY}x) is higher than Above threshold (${candidate.aboveThresholdX}x) — overlapping zone will trigger on mid-range values.")
            }
        } else if (candidate.effectiveGroup() == StrategyGroup.MULTIPLIER_INTERVAL) {
            if (conds.isEmpty()) {
                errors.add("At least one multiplier condition is required.")
            }
            conds.forEachIndexed { idx, cond ->
                if (cond.sequenceMultipliers.isEmpty() && cond.consecutiveRounds < 1) {
                    errors.add("Condition #${idx + 1} consecutive rounds must be at least 1.")
                }
                if (cond.threshold < 1.0) {
                    errors.add("Condition #${idx + 1} threshold must be >= 1.00x.")
                }
                if (cond.comparator == PatternComparator.BETWEEN) {
                    val up = cond.upperThreshold
                    if (up == null || up <= cond.threshold) {
                        errors.add("Condition #${idx + 1} Between upper bound must exceed lower bound.")
                    }
                }
            }
        } else if (candidate.effectiveGroup() == StrategyGroup.TIME_INTERVAL) {
            if (candidate.bigMultiplierThreshold < 1.5) {
                warnings.add("BIG threshold (${candidate.bigMultiplierThreshold}x) is unusually low; consider >= 5.00x.")
            }
            if (candidate.analysisWindowMinutes < 5) {
                errors.add("Analysis time interval must be at least 5 minutes.")
            }
            if (candidate.minIntervalMinutes > candidate.maxIntervalMinutes && candidate.maxIntervalMinutes > 0) {
                errors.add("Minimum interval minutes cannot exceed maximum interval minutes.")
            }
        } else if (candidate.effectiveGroup() == StrategyGroup.COLOR_SEQUENCE) {
            if (candidate.getColorSequence().isEmpty() && candidate.getCustomHexSequence().isEmpty()) {
                errors.add("Select at least one color or custom HEX color in the sequence.")
            }
        }

        existing.filter { it.id != candidate.id && it.isEnabled }.forEach { other ->
            val sameGame = other.targetGameId == candidate.targetGameId ||
                other.targetGameId == "ALL" || candidate.targetGameId == "ALL"
            if (sameGame && other.effectiveGroup() == candidate.effectiveGroup() &&
                other.multiplierRuleSubtype == candidate.multiplierRuleSubtype &&
                other.summaryDescription() == candidate.summaryDescription()
            ) {
                warnings.add("Duplicates active rule '${other.name}' for target ${other.targetGameId}.")
            }
        }

        return StrategyValidationResult(
            isValid = errors.isEmpty(),
            warnings = warnings.distinct(),
            blockingErrors = errors.distinct()
        )
    }
}

enum class AlarmProfileCategory(
    val displayTitle: String,
    val subtitle: String,
    val defaultUseAlarmStyle: Boolean
) {
    STANDARD_ALERTS(
        "1. Standard Strategy Alerts",
        "Everyday pattern notifications (normal alert profile)",
        false
    ),
    HIGH_PRIORITY_ALERTS(
        "2. High-Priority Strategy Alerts",
        "Urgent full-volume pattern alarms",
        true
    ),
    MULTIPLIER_CONDITIONS(
        "3. Specific Multiplier Conditions",
        "LOW, BIG, and Above-X -> Below-Y alarms",
        true
    ),
    TIME_BASED_ALERTS(
        "4. Time-Based Alerts",
        "Post-Big countdown waits & 1h/3h/6h frequency alerts",
        true
    ),
    COLOR_SEQUENCE_ALERTS(
        "5. Color-Sequence Alerts",
        "Horizontal & Vertical grid color pattern alarms",
        true
    )
}

@Entity(tableName = "alarm_profiles")
data class AlarmProfileEntity(
    @PrimaryKey
    val categoryName: String,
    val enabled: Boolean = true,
    val soundUri: String = "",
    val soundLabel: String = "No sound selected",
    val vibrationEnabled: Boolean = true,
    val vibrationPattern: String = "DISTINCTIVE",
    val repeatEnabled: Boolean = true,
    val repeatCooldownSeconds: Int = 0,
    val maxRepeatsPerEvent: Int = 1,
    val useAlarmStyle: Boolean = true
) {
    fun category(): AlarmProfileCategory =
        runCatching { AlarmProfileCategory.valueOf(categoryName) }
            .getOrDefault(AlarmProfileCategory.STANDARD_ALERTS)

    companion object {
        fun defaultProfiles(): List<AlarmProfileEntity> =
            AlarmProfileCategory.entries.map { cat ->
                AlarmProfileEntity(
                    categoryName = cat.name,
                    enabled = true,
                    soundUri = "",
                    soundLabel = "No sound selected",
                    vibrationEnabled = true,
                    vibrationPattern = if (cat.defaultUseAlarmStyle) "DISTINCTIVE" else "DEFAULT",
                    repeatEnabled = true,
                    repeatCooldownSeconds = 0,
                    maxRepeatsPerEvent = 1,
                    useAlarmStyle = cat.defaultUseAlarmStyle
                )
            }
    }
}

@Entity(tableName = "notification_settings")
data class NotificationSettings(
    @PrimaryKey
    val id: Int = 1,
    val masterEnabled: Boolean = true,
    val approachingEnabled: Boolean = true,
    val reachedEnabled: Boolean = true,
    val invalidatedEnabled: Boolean = true,
    val historyUnavailableEnabled: Boolean = true,
    val pausedEnabled: Boolean = true,
    val hourlySummaryEnabled: Boolean = true,
    val gameDetectionEnabled: Boolean = true,
    val monitoringErrorsEnabled: Boolean = true,
    val standardAlertEnabled: Boolean = true,
    val standardSoundUri: String = "",
    val standardVibrationPattern: String = "DEFAULT",
    val standardRepeatedAlerts: Boolean = true,
    val highPriorityAlarmEnabled: Boolean = true,
    val alarmSoundUri: String = "",
    val alarmVibrationPattern: String = "DISTINCTIVE",
    val alarmRepeated: Boolean = true,
    val alarmRepeatIntervalMinutes: Int = 0,
    val alarmMaxDurationMinutes: Int = 5,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val repeatIntervalMinutes: Int = 0,
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 23,
    val quietStartMinute: Int = 0,
    val quietEndHour: Int = 7,
    val quietEndMinute: Int = 0,
    val soundSourceType: String = "SYSTEM",
    val deliveryMode: String = "ALARM",
    val channelConfigVersion: Int = 1,
    val executionAlertsEnabled: Boolean = true,
    val failureAlertsEnabled: Boolean = true
)

enum class StrategyNotificationType(val displayLabel: String) {
    STRATEGY_APPROACHING("Approaching"),
    STRATEGY_REACHED("Pattern Matched"),
    STRATEGY_INVALIDATED("Pattern Reset"),
    HISTORY_UNAVAILABLE("Detection Unavailable"),
    STRATEGY_PAUSED("Monitoring Paused"),
    HOURLY_SUMMARY("Hourly Summary"),
    NEW_HOUR_STARTED("New Hour Started"),
    GAME_DETECTED("Game Detected"),
    MONITORING_ERROR("Monitoring Error"),
    AUTOMATION_ACTION("Automation Action")
}

enum class NotificationDeliveryStatus(val displayLabel: String) {
    DELIVERED("Delivered"),
    PERMISSION_DENIED("Permission Denied"),
    SUPPRESSED_MASTER_OFF("Suppressed (Master Off)"),
    SUPPRESSED_TYPE_OFF("Suppressed (Type Disabled)"),
    SUPPRESSED_STRATEGY_OFF("Suppressed (Strategy Notification Off)"),
    SUPPRESSED_COOLDOWN("Suppressed (Cooldown)"),
    SUPPRESSED_QUIET_HOURS("Suppressed (Quiet Hours)"),
    SUPPRESSED_DUPLICATE("Suppressed (Duplicate)"),
    FAILED("Delivery Failed")
}

enum class NotificationInteractionStatus(val displayLabel: String) {
    UNREAD("Unread"),
    OPENED_BY_USER("Opened by User"),
    DISMISSED("Dismissed")
}

@Entity(tableName = "notification_history")
data class NotificationHistoryRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val strategyId: Long = 0,
    val strategyName: String,
    val notificationType: StrategyNotificationType,
    val title: String,
    val message: String,
    val triggeringRecordKey: String? = null,
    val triggeringMultiplier: Double? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val deliveryStatus: NotificationDeliveryStatus,
    val interactionStatus: NotificationInteractionStatus = NotificationInteractionStatus.UNREAD,
    val channelId: String = "AP_CRITICAL_PATTERNS",
    val gameId: String = "",
    val gameName: String = "",
    val occurrenceRecordId: Long? = null
)
