package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class StrategyRuleType {
    CONSECUTIVE_LOW_MULTIPLIERS,
    CONSECUTIVE_HIGH_MULTIPLIERS,
    COLOR_TIER_STREAK,
    MULTIPLIER_THRESHOLD,
    TIME_COOLDOWN,
    CUSTOM_PATTERN
}

@Entity(tableName = "strategy_rules")
data class StrategyRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val type: StrategyRuleType,
    val enabled: Boolean = true,
    val thresholdMultiplier: Double = 2.0,
    val consecutiveCount: Int = 3,
    val colorTier: String = "BLUE", // BLUE (<2x), PURPLE (2x-9.99x), PINK (>=10x)
    val customPattern: String = "", // e.g. "B,B,B" or "<2.0,<2.0,>=2.0"
    val waitRoundsAfterTrigger: Int = 0,
    val cooldownSeconds: Int = 0,
    val lastTriggeredAtMs: Long = 0L,
    val priority: Int = 0
)
