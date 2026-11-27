package com.example.data.engine

import com.example.data.model.AfterLossMode
import kotlin.math.roundToLong

data class StakeProgressionResult(
    val nextStake: Double,
    val consecutiveLosses: Int,
    val shouldStop: Boolean,
    val stopReason: String? = null,
    val recoveryDeficit: Double = 0.0
)

/**
 * Pure stake progression calculator (Section 4.1).
 * - After a win: reduces recovery deficit or returns to base stake if fully recovered.
 * - After a loss: DOUBLE (previous * 2), ADD (previous + addAmount), or KEEP (holds previous stake).
 * - Stop after N losses in a row: resets to base stake and pauses.
 */
object StakeCalculator {

    fun onWin(
        baseStake: Double,
        previousStake: Double = baseStake,
        previousConsecutiveLosses: Int = 0,
        currentDeficit: Double = 0.0,
        targetMultiplier: Double = 2.0,
        resetAfterWin: Boolean = true,
        actualProfit: Double? = null
    ): StakeProgressionResult {
        val safeBase = roundCurrency(baseStake.coerceAtLeast(0.01))
        val safePrev = roundCurrency(previousStake.coerceAtLeast(safeBase))
        val winProfit = (actualProfit ?: (safePrev * (targetMultiplier.coerceAtLeast(1.01) - 1.0))).coerceAtLeast(0.0)

        val newDeficit = (currentDeficit - winProfit).coerceAtLeast(0.0)
        val fullyRecovered = newDeficit <= 0.01 || (previousConsecutiveLosses <= 0 && currentDeficit <= 0.01)

        val nextStake = if (fullyRecovered) {
            if (resetAfterWin) safeBase else safePrev.coerceAtLeast(safeBase)
        } else {
            safePrev.coerceAtLeast(safeBase)
        }

        return StakeProgressionResult(
            nextStake = roundCurrency(nextStake),
            consecutiveLosses = if (fullyRecovered) 0 else previousConsecutiveLosses,
            shouldStop = false,
            recoveryDeficit = roundCurrency(newDeficit)
        )
    }

    fun onLoss(
        previousStake: Double,
        baseStake: Double,
        mode: AfterLossMode = AfterLossMode.DOUBLE,
        addAmount: Double = 10.0,
        previousConsecutiveLosses: Int = 0,
        stopAfterLosses: Int = 3,
        currentDeficit: Double = 0.0
    ): StakeProgressionResult {
        val safeBase = roundCurrency(baseStake.coerceAtLeast(0.01))
        val safePrev = roundCurrency(previousStake.coerceAtLeast(safeBase))
        val newLossStreak = previousConsecutiveLosses + 1
        val newDeficit = roundCurrency(currentDeficit + safePrev)

        if (stopAfterLosses > 0 && newLossStreak >= stopAfterLosses) {
            return StakeProgressionResult(
                nextStake = safeBase,
                consecutiveLosses = 0,
                shouldStop = true,
                stopReason = "Stopped: $newLossStreak losses in a row",
                recoveryDeficit = 0.0
            )
        }

        val computed = when (mode) {
            AfterLossMode.DOUBLE -> safePrev * 2.0
            AfterLossMode.ADD -> safePrev + addAmount.coerceAtLeast(0.0)
            AfterLossMode.KEEP -> safeBase
        }

        return StakeProgressionResult(
            nextStake = roundCurrency(computed),
            consecutiveLosses = newLossStreak,
            shouldStop = false,
            recoveryDeficit = newDeficit
        )
    }

    fun formatStake(value: Double): String = String.format(java.util.Locale.US, "%.2f", roundCurrency(value))

    private fun roundCurrency(value: Double): Double = (value * 100.0).roundToLong() / 100.0
}
