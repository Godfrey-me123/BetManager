package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AfterLossMode {
    DOUBLE,
    ADD,
    KEEP
}

enum class BettingMode {
    BET_EVERY_ROUND,
    USE_STRATEGY_RULES
}

@Entity(tableName = "strategy_config")
data class StrategyConfig(
    @PrimaryKey val id: Int = 1,
    val baseStake: Double = 20.0,
    val afterLoss: AfterLossMode = AfterLossMode.DOUBLE,
    val addAmount: Double = 10.0,
    val resetAfterWin: Boolean = true,
    val stopAfterLosses: Int = 3,
    val cashOutTarget: Double = 2.00,
    val useGameAutoCashOut: Boolean = true,
    val bettingMode: BettingMode = BettingMode.USE_STRATEGY_RULES,
    val dailyLossLimit: Double = 0.0,
    val dailyProfitTarget: Double = 0.0,
    val balanceFloor: Double = 0.0,
    val scanHeartbeatMs: Long = 5000L,
    val stakeTolerance: Double = 0.05,
    val showFloatingHand: Boolean = true,
    // Legacy schema columns retained strictly for database migration compatibility; never read/written by app logic.
    val minStake: Double = 0.0,
    val maxStake: Double = 0.0,
    val learnedLimitsJson: String = "",
    val queueBetDuringFlight: Boolean = false,
    val cashOutUntrackedBets: Boolean = false
)
