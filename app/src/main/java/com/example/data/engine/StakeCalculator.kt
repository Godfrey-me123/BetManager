package com.example.data.engine

import com.example.data.model.AfterLossMode
import kotlin.math.roundToLong

data class StakeProgressionResult(
    val nextStake: Double,
    val consecutiveLosses: Int,
    val shouldStop: Boolean,
    val stopReason: String? = null
)

/**
 * Pure stake progression calculator (Section 4.1).
 * - After a win: always return to base stake.
 * - After a loss: DOUBLE (previous * 2), ADD (previous + addAmount), or KEEP (base).
 * - Stop after N losses in a row: resets to base stake and pauses.
 */
object StakeCalculator {

    fun onWin(baseStake: Double): StakeProgressionResult {
        val normalized = roundCurrency(baseStake.coerceAtLeast(0.01))
        return StakeProgressionResult(
            nextStake = normalized,
            consecutiveLosses = 0,
            shouldStop = false
        )
    }

    fun onLoss(
        previousStake: Double,
        baseStake: Double,
        mode: AfterLossMode,
        addAmount: Double,
        previousConsecutiveLosses: Int,
        stopAfterLosses: Int
    ): StakeProgressionResult {
        val safeBase = roundCurrency(baseStake.coerceAtLeast(0.01))
        val safePrev = roundCurrency(previousStake.coerceAtLeast(safeBase))
        val newLossStreak = previousConsecutiveLosses + 1

        if (stopAfterLosses > 0 && newLossStreak >= stopAfterLosses) {
            return StakeProgressionResult(
                nextStake = safeBase,
                consecutiveLosses = 0,
                shouldStop = true,
                stopReason = "Stopped: $newLossStreak losses in a row"
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
            shouldStop = false
        )
    }

    fun formatStake(value: Double): String = String.format(java.util.Locale.US, "%.2f", roundCurrency(value))

    private fun roundCurrency(value: Double): Double = (value * 100.0).roundToLong() / 100.0
}
