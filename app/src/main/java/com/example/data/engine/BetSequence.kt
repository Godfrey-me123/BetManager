package com.example.data.engine

import com.example.data.model.LogCategory
import com.example.data.model.StrategyConfig
import com.example.service.AccessibilityController
import com.example.service.AutoTouchDriver
import com.example.service.GamePhase
import com.example.service.RectBounds
import com.example.service.ScanReason
import com.example.service.ScanScheduler
import kotlinx.coroutines.delay
import kotlin.math.abs

data class BetAttemptResult(
    val placed: Boolean,
    val confirmed: Boolean,
    val stakeWanted: Double,
    val stakeReadBack: String = "",
    val reason: String,
    val usingHandIndex: Int = 1
)

/**
 * Single verified bet sequence (Section 5 & Section 6) with a 6-second execution budget.
 * Focused purely on placing bets (no cash out logic).
 */
class BetSequence(
    private val driver: AutoTouchDriver,
    private val scheduler: ScanScheduler,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val onLog: suspend (LogCategory, String, String) -> Unit = { _, _, _ -> }
) {
    companion object {
        const val BUDGET_MS = 6000L
    }

    suspend fun execute(
        config: StrategyConfig,
        stake: Double,
        isRunning: Boolean,
        hasActiveBet: Boolean,
        handLockedForRound: Boolean,
        todayProfit: Double,
        decision: StrategyDecision,
        isManualTrigger: Boolean = false,
        preferredBetBounds: RectBounds? = null
    ): BetAttemptResult {
        val startMs = timeProvider()
        fun budgetExpired(): Boolean = (timeProvider() - startMs) >= BUDGET_MS

        // 1. Pre-checks
        if (!isRunning && !isManualTrigger) {
            return abort(stake, "Engine is paused", LogCategory.SKIP)
        }
        if (hasActiveBet || handLockedForRound) {
            return abort(stake, "Bet already active for this round", LogCategory.SKIP)
        }
        if (stake <= 0.0) {
            return abort(stake, "Invalid stake amount", LogCategory.ERROR)
        }
        if (config.dailyLossLimit > 0.0 && todayProfit <= -config.dailyLossLimit) {
            return abort(stake, "Stopped: daily loss limit reached", LogCategory.SKIP)
        }
        if (config.dailyProfitTarget > 0.0 && todayProfit >= config.dailyProfitTarget) {
            return abort(stake, "Stopped: daily profit target reached", LogCategory.SKIP)
        }

        // 2. Strategy decision
        if (!decision.shouldBet) {
            return abort(stake, decision.reason, LogCategory.SKIP)
        }

        // 3. Fresh scan (S2), targeting calibrated BET hand panel if specified
        val scan = scheduler.runScan(ScanReason.PRE_BET_S2, preferredBetBounds)
        if (!scan.gameWindowFound) {
            return abort(stake, "Game window not found", LogCategory.SKIP)
        }
        if (scan.panelCount > 1) {
            onLog(
                LogCategory.DETECTION,
                "using hand ${scan.usingHandIndex}",
                "Detected ${scan.panelCount} bet panels; targeting panel ${scan.usingHandIndex}"
            )
        }
        if (scan.conflictReason != null) {
            return abort(stake, scan.conflictReason, LogCategory.ERROR)
        }
        if (scan.phase != GamePhase.BETTING_OPEN) {
            return abort(stake, "Phase is ${scan.phase.name}, betting not open", LogCategory.SKIP)
        }
        val initialBetButton = scan.betButton
            ?: return abort(stake, "Bet button not found", LogCategory.SKIP)
        if (!initialBetButton.isEnabled || !initialBetButton.isVisible) {
            return abort(stake, "Bet button disabled or hidden", LogCategory.SKIP)
        }
        val initialStakeField = scan.stakeField
            ?: return abort(stake, "Stake box not found", LogCategory.ERROR)

        // Balance checks
        if (config.balanceFloor > 0.0) {
            val bal = scan.balance
            if (bal == null) {
                return abort(stake, "Balance unreadable while balance floor is active", LogCategory.SKIP)
            }
            if (bal < config.balanceFloor) {
                return abort(stake, "Stopped: balance ${StakeCalculator.formatStake(bal)} below floor", LogCategory.SKIP)
            }
        }
        if (scan.balance != null && scan.balance < stake) {
            return abort(
                stake,
                "Insufficient balance (${StakeCalculator.formatStake(scan.balance)} < ${StakeCalculator.formatStake(stake)})",
                LogCategory.SKIP
            )
        }

        if (budgetExpired()) {
            return abort(stake, "Bet window budget expired", LogCategory.SKIP)
        }

        // 4. Set stake with AutoTouchDriver
        val formattedStake = StakeCalculator.formatStake(stake)
        onLog(LogCategory.STAKE, "Setting stake $formattedStake", "Target bounds ${initialStakeField.bounds}")

        val stakeEntry = driver.setVerifiedNumericField(
            fieldBounds = initialStakeField.bounds,
            initialTarget = initialStakeField,
            expectedValue = stake,
            formattedText = formattedStake,
            tolerance = config.stakeTolerance,
            fieldLabel = "stake",
            preferredBetPanelBounds = preferredBetBounds
        )
        if (!stakeEntry.success) {
            return abort(
                stake,
                stakeEntry.errorReason ?: "stake could not be set (field shows ${stakeEntry.readBackText})",
                LogCategory.ERROR,
                stakeReadBack = stakeEntry.readBackText
            )
        }

        if (budgetExpired()) {
            return abort(stake, "Bet window budget expired before layout settle", LogCategory.SKIP, stakeEntry.readBackText)
        }

        // 5. Close keyboard if present, settle layout, re-find Bet & verify stake
        val settledScan = driver.closeKeyboardAndWaitLayoutSettle(
            settleIntervalMs = 120L,
            timeoutMs = 1500L,
            preferredBetPanelBounds = preferredBetBounds
        ) ?: return abort(stake, "Layout did not settle after keyboard close", LogCategory.ERROR, stakeEntry.readBackText)

        if (settledScan.phase != GamePhase.BETTING_OPEN) {
            return abort(
                stake,
                "Phase closed before Bet tap (${settledScan.phase.name})",
                LogCategory.SKIP,
                stakeEntry.readBackText
            )
        }

        val freshStakeField = settledScan.stakeField
            ?: return abort(stake, "Stake box vanished after layout settle", LogCategory.ERROR, stakeEntry.readBackText)
        val reVerifiedValue = AccessibilityController.parseNumericValue(freshStakeField.primaryText())
        if (reVerifiedValue == null || abs(reVerifiedValue - stake) > config.stakeTolerance) {
            return abort(
                stake,
                "stake could not be verified after settle (field shows ${freshStakeField.primaryText()})",
                LogCategory.ERROR,
                freshStakeField.primaryText()
            )
        }

        val freshBetButton = settledScan.betButton
            ?: return abort(stake, "Bet button vanished after settle", LogCategory.ERROR, freshStakeField.primaryText())
        if (!freshBetButton.isEnabled || !freshBetButton.isVisible) {
            return abort(stake, "Bet button disabled after settle", LogCategory.SKIP, freshStakeField.primaryText())
        }

        if (budgetExpired()) {
            return abort(stake, "Bet window budget expired before tap", LogCategory.SKIP, freshStakeField.primaryText())
        }

        // 6. Tap the Bet button (overlay pass-through handled by driver)
        val tapResult = driver.clickTarget(freshBetButton, allowDebounceSkip = true)
        if (!tapResult.isProceedable) {
            return abort(stake, "Bet tap failed: ${tapResult.reason}", LogCategory.ERROR, freshStakeField.primaryText())
        }

        onLog(
            LogCategory.BET,
            "Bet tapped $formattedStake",
            "Wanted $formattedStake, read back '${freshStakeField.primaryText()}', method=${tapResult.outcome}"
        )

        // 7. Confirm (S3): Cancel button visible or round started (max 3 polls @ 150ms)
        var confirmed = false
        for (poll in 1..3) {
            delay(150L)
            val confirmScan = scheduler.runScan(ScanReason.CONFIRM_S3, preferredBetBounds)
            if (confirmScan.cancelButton != null || confirmScan.phase == GamePhase.IN_FLIGHT) {
                confirmed = true
                break
            }
        }

        return if (confirmed) {
            onLog(
                LogCategory.BET,
                "Bet placed $formattedStake",
                "Bet confirmed successfully"
            )
            BetAttemptResult(
                placed = true,
                confirmed = true,
                stakeWanted = stake,
                stakeReadBack = freshStakeField.primaryText(),
                reason = "Bet placed $formattedStake",
                usingHandIndex = settledScan.usingHandIndex
            )
        } else {
            onLog(
                LogCategory.SKIP,
                "bet not confirmed",
                "Cancel button or flight start not detected; waiting for next round"
            )
            BetAttemptResult(
                placed = false,
                confirmed = false,
                stakeWanted = stake,
                stakeReadBack = freshStakeField.primaryText(),
                reason = "bet not confirmed",
                usingHandIndex = settledScan.usingHandIndex
            )
        }
    }

    private suspend fun abort(
        stake: Double,
        reason: String,
        category: LogCategory,
        stakeReadBack: String = ""
    ): BetAttemptResult {
        onLog(category, reason, "Wanted ${StakeCalculator.formatStake(stake)}")
        return BetAttemptResult(
            placed = false,
            confirmed = false,
            stakeWanted = stake,
            stakeReadBack = stakeReadBack,
            reason = reason
        )
    }
}
