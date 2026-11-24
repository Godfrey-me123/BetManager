package com.example.data.local

import androidx.room.TypeConverter
import com.example.data.model.AfterLossMode
import com.example.data.model.BettingMode
import com.example.data.model.LogCategory
import com.example.data.model.StrategyRuleType

class Converters {
    @TypeConverter
    fun fromAfterLossMode(value: AfterLossMode): String = value.name

    @TypeConverter
    fun toAfterLossMode(value: String): AfterLossMode =
        runCatching { AfterLossMode.valueOf(value) }.getOrDefault(AfterLossMode.DOUBLE)

    @TypeConverter
    fun fromBettingMode(value: BettingMode): String = value.name

    @TypeConverter
    fun toBettingMode(value: String): BettingMode =
        runCatching { BettingMode.valueOf(value) }.getOrDefault(BettingMode.USE_STRATEGY_RULES)

    @TypeConverter
    fun fromStrategyRuleType(value: StrategyRuleType): String = value.name

    @TypeConverter
    fun toStrategyRuleType(value: String): StrategyRuleType =
        runCatching { StrategyRuleType.valueOf(value) }
            .getOrDefault(StrategyRuleType.CONSECUTIVE_LOW_MULTIPLIERS)

    @TypeConverter
    fun fromLogCategory(value: LogCategory): String = value.name

    @TypeConverter
    fun toLogCategory(value: String): LogCategory =
        runCatching { LogCategory.valueOf(value) }.getOrDefault(LogCategory.DETECTION)
}
