package com.example.data.engine

import com.example.data.local.BetManagerRepository
import com.example.data.model.LogCategory
import com.example.data.model.RoundRecord
import com.example.data.model.StrategyConfig
import com.example.service.AutoTouchDriver
import com.example.service.GamePhase
import com.example.service.RectBounds
import com.example.service.ScanReason
import com.example.service.ScanScheduler
import com.example.service.ScreenMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FloatingHandState {
    WAITING,
    READY,
    LOCKED
}

data class ActiveBetRecord(
    val roundSignature: String,
    val stake: Double,
    val targetMultiplier: Double = 2.0,
    val strategyName: String?,
    val placedAtMs: Long = System.currentTimeMillis()
)

data class EngineUiState(
    val isRunning: Boolean = false,
    val serviceConnected: Boolean = false,
    val statusLine: String = "Stopped: press Start to watch the game",
    val gameName: String = "Aviator",
    val siteUrl: String = "BetPawa",
    val balance: Double? = null,
    val currency: String = "TZS",
    val currentStake: Double = 20.0,
    val consecutiveLosses: Int = 0,
    val phase: GamePhase = GamePhase.UNKNOWN,
    val liveMultiplier: Double? = null,
    val latestCrashMultiplier: Double? = null,
    val monitoredCrashCount: Int = 0,
    val monitorStatusLabel: String = "Idle",
    val betTargetDetected: Boolean = false,
    val betTargetSummary: String = "Drag BET hand onto game Bet button",
    val handAutoBetEnabled: Boolean = false,
    val handState: FloatingHandState = FloatingHandState.WAITING,
    val handStatusText: String = "Waiting",
    val lastRoundId: String? = null,
    val recordedMultipliers: List<Double> = emptyList(),
    val patternProgress: PatternProgress = PatternProgress(),
    val patternSignalStage: PatternSignalStage = PatternSignalStage.NONE,
    val isPatternMatched: Boolean = false,
    val activeStrategyName: String? = null
)

/**
 * Coordinates live crash multiplier monitoring, strategy evaluation, BetSequence execution,
 * round settlement, and draggable floating BET hand detection/locking/auto-betting.
 * Never wipes saved history/rules/configuration on stop or restart.
 */
class AutomationEngine(
    private val repository: BetManagerRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _state = MutableStateFlow(EngineUiState())
    val state: StateFlow<EngineUiState> = _state.asStateFlow()

    var patternAlertManager: com.example.service.PatternAlertManager? = null
    private val liveRecordedMultipliers = mutableListOf<Double>()
    private val consumedPatternSignatures = mutableSetOf<String>()

    private val sequenceMutex = Mutex()
    private val scanMutex = Mutex()
    private val crashTracker = CrashMonitorTracker()

    private var scheduler: ScanScheduler? = null
    private var driver: AutoTouchDriver? = null
    private var betSequence: BetSequence? = null

    private var activeBet: ActiveBetRecord? = null
    private var lockedRoundKey: String? = null
    private var lockedAtMs: Long = 0L
    private var sawCancelOrFlightAfterBet: Boolean = false
    private var previousPhase: GamePhase = GamePhase.UNKNOWN
    private var roundCounter: Long = 0L
    private var calibratedBetBounds: RectBounds? = repository.getCalibratedBetBounds()
    private var calibratedHandBounds: RectBounds? = repository.getCalibratedHandBounds()

    fun activeTargetBounds(): RectBounds? = calibratedBetBounds ?: calibratedHandBounds

    fun hasActiveBetLock(): Boolean = activeBet != null || lockedRoundKey != null

    init {
        scope.launch {
            repository.ensureInitialized()
            val config = repository.getConfig()
            val savedStake = repository.getSavedCurrentStake(config.baseStake)
            val savedLosses = repository.getConsecutiveLosses()
            val handAutoBet = repository.isHandAutoBetEnabled()
            val hasTarget = (calibratedBetBounds ?: calibratedHandBounds) != null
            _state.update {
                it.copy(
                    currentStake = savedStake,
                    consecutiveLosses = savedLosses,
                    handAutoBetEnabled = handAutoBet,
                    betTargetDetected = hasTarget,
                    betTargetSummary = if (hasTarget) {
                        "Bet target calibrated"
                    } else {
                        "Drag BET hand onto game Bet button"
                    }
                )
            }
        }
    }

    fun attachAccessibility(driver: AutoTouchDriver, scheduler: ScanScheduler) {
        this.driver = driver
        this.scheduler = scheduler
        this.betSequence = BetSequence(
            driver = driver,
            scheduler = scheduler,
            onLog = { cat, msg, details -> repository.log(cat, msg, details) }
        )
        _state.update { it.copy(serviceConnected = true) }
    }

    fun detachAccessibility() {
        stop("accessibility detached")
        this.scheduler = null
        this.driver = null
        this.betSequence = null
        _state.update {
            it.copy(
                serviceConnected = false,
                handState = FloatingHandState.WAITING,
                handStatusText = "Disconnected"
            )
        }
    }

    fun start() {
        val currentScheduler = scheduler
        val currentBetSeq = betSequence
        if (currentScheduler == null || currentBetSeq == null) {
            _state.update { it.copy(statusLine = "Error: Accessibility service not connected") }
            return
        }
        if (_state.value.isRunning) return

        scope.launch {
            val config = repository.getConfig()
            val savedStake = repository.getSavedCurrentStake(config.baseStake)
            crashTracker.resetSession()
            if (activeBet == null) {
                lockedRoundKey = null
                lockedAtMs = 0L
                sawCancelOrFlightAfterBet = false
            }

            _state.update {
                it.copy(
                    isRunning = true,
                    currentStake = savedStake,
                    monitoredCrashCount = 0,
                    monitorStatusLabel = "Capturing baseline",
                    statusLine = "Looking at the game...",
                    handState = FloatingHandState.WAITING,
                    handStatusText = "Scanning"
                )
            }
            repository.log(
                LogCategory.DETECTION,
                "BetManager monitoring started",
                "Base stake ${StakeCalculator.formatStake(config.baseStake)}"
            )

            // S1 full scan on start (captures initial ribbon baseline without importing old crashes)
            val targetPanel = calibratedBetBounds ?: calibratedHandBounds
            val firstMap = currentScheduler.runScan(ScanReason.START_S1, targetPanel)
            processScreenMap(firstMap, allowAutoBet = true)

            currentScheduler.startHeartbeat(
                scope = scope,
                intervalMs = config.scanHeartbeatMs,
                preferredBetPanelBoundsProvider = { calibratedBetBounds ?: calibratedHandBounds },
                onHeartbeatScan = { map ->
                    processScreenMap(map, allowAutoBet = true)
                }
            )
        }
    }

    fun setHandAutoBetEnabled(enabled: Boolean) {
        repository.setHandAutoBetEnabled(enabled)
        _state.update { it.copy(handAutoBetEnabled = enabled) }
        scope.launch {
            repository.log(
                LogCategory.STRATEGY,
                "BET hand Auto Bet ${if (enabled) "enabled" else "disabled"}"
            )
        }
        if (enabled && _state.value.serviceConnected && !_state.value.isRunning) {
            start()
        }
    }

    fun stop(reason: String = "paused by user") {
        scheduler?.stopHeartbeat()
        _state.update {
            it.copy(
                isRunning = false,
                monitorStatusLabel = "Paused",
                statusLine = "Stopped: $reason",
                handState = if (activeBet != null) FloatingHandState.LOCKED else FloatingHandState.WAITING,
                handStatusText = "Paused"
            )
        }
        scope.launch {
            repository.log(LogCategory.STRATEGY, "Stopped: $reason")
        }
    }

    fun stopForExit() {
        scheduler?.stopHeartbeat()
        releaseBetLock()
        crashTracker.resetSession()
        patternAlertManager?.clearNotifications()
        liveRecordedMultipliers.clear()
        consumedPatternSignatures.clear()
        _state.update {
            it.copy(
                isRunning = false,
                monitorStatusLabel = "Exited",
                statusLine = "Exited",
                handState = FloatingHandState.WAITING,
                handStatusText = "Exited"
            )
        }
    }

    private fun releaseBetLock() {
        activeBet = null
        lockedRoundKey = null
        lockedAtMs = 0L
        sawCancelOrFlightAfterBet = false
    }

    /**
     * Releases leftover activeBet / lockedRoundKey when the live game UI or phase transition
     * confirms no bet is currently active (e.g., Cancel is gone & Bet is enabled in BETTING_OPEN,
     * plane flew & returned to BETTING_OPEN without a readable crash text, or fallback timeout).
     */
    private fun clearStaleBetLockIfNeeded(
        map: ScreenMap,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (activeBet == null && lockedRoundKey == null) return false

        if (map.cancelButton != null || map.phase == GamePhase.IN_FLIGHT || map.phase == GamePhase.CRASHED) {
            sawCancelOrFlightAfterBet = true
        }
        // If Cancel button is actively visible for the panel, the bet is genuinely still active.
        if (map.cancelButton != null) return false

        val refTs = activeBet?.placedAtMs ?: lockedAtMs
        val elapsedMs = if (refTs > 0L) nowMs - refTs else Long.MAX_VALUE
        val transitionedToNewBetting = map.phase == GamePhase.BETTING_OPEN &&
            (previousPhase == GamePhase.IN_FLIGHT || previousPhase == GamePhase.CRASHED)
        val openBetButtonProvesIdle = map.phase == GamePhase.BETTING_OPEN &&
            map.betButton?.isEnabled == true &&
            (sawCancelOrFlightAfterBet || elapsedMs >= 2_500L)
        val canvasFallbackCleared = map.betButton == null &&
            map.phase != GamePhase.IN_FLIGHT &&
            ((sawCancelOrFlightAfterBet && map.phase == GamePhase.BETTING_OPEN) || elapsedMs >= 12_000L)

        if (transitionedToNewBetting || openBetButtonProvesIdle || canvasFallbackCleared) {
            releaseBetLock()
            return true
        }
        return false
    }

    suspend fun handleScreenEvent(map: ScreenMap) {
        processScreenMap(map, allowAutoBet = _state.value.isRunning)
    }

    /**
     * Called when user drags the floating BET hand over the game screen and drops it.
     * Scans accessibility nodes under [handBounds] to detect the game's Bet button,
     * saves calibration (or falls back to hand coordinates over active game windows),
     * and executes the verified Edit Stake & Bet real touch click.
     */
    fun onBetHandDraggedAndDropped(
        handBounds: RectBounds,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        val currentDriver = driver
        val currentScheduler = scheduler
        if (!_state.value.serviceConnected || currentDriver == null || currentScheduler == null) {
            onResult?.invoke(false, "Enable BetManager Accessibility Service first")
            return
        }

        scope.launch {
            val detected = currentDriver.detectBetTargetAt(handBounds)
            if (detected == null) {
                val hasExternalSurface = currentDriver.hasExternalWindowAt(handBounds)
                if (!hasExternalSurface) {
                    repository.log(
                        LogCategory.SKIP,
                        "No Bet button under BET hand",
                        "Dropped at ${handBounds.centerX.toInt()},${handBounds.centerY.toInt()}"
                    )
                    _state.update {
                        it.copy(
                            betTargetDetected = (calibratedBetBounds ?: calibratedHandBounds) != null,
                            betTargetSummary = "No Bet button under hand — drag over Bet button"
                        )
                    }
                    onResult?.invoke(false, "Drag BET hand directly over the game Bet button")
                    return@launch
                }

                // Canvas / WebView surface without exposed button node: calibrate exact drop coordinates
                calibratedHandBounds = handBounds
                calibratedBetBounds = handBounds
                repository.saveCalibratedHandBounds(handBounds.left, handBounds.top, handBounds.right, handBounds.bottom)
                repository.saveCalibratedBetBounds(handBounds.left, handBounds.top, handBounds.right, handBounds.bottom)
                repository.log(
                    LogCategory.DETECTION,
                    "BET hand coordinate target calibrated",
                    "Center=${handBounds.centerX.toInt()},${handBounds.centerY.toInt()}"
                )

                val freshMap = currentScheduler.runScan(ScanReason.PRE_BET_S2, handBounds)
                processScreenMap(freshMap, allowAutoBet = false)
                _state.update {
                    it.copy(
                        betTargetDetected = true,
                        betTargetSummary = "Hand target calibrated (${handBounds.centerX.toInt()}, ${handBounds.centerY.toInt()})"
                    )
                }

                if (activeBet != null || freshMap.cancelButton != null) {
                    onResult?.invoke(false, "Hand calibrated — round bet already active")
                    return@launch
                }
                if (freshMap.phase == GamePhase.IN_FLIGHT) {
                    onResult?.invoke(true, "Hand calibrated — waiting for next round")
                    return@launch
                }

                executeDirectCoordinateOrButtonClick(
                    betButton = null,
                    fallbackBounds = handBounds,
                    map = freshMap,
                    strategyName = "BET Hand Drag",
                    onResult = onResult
                )
                return@launch
            }

            calibratedBetBounds = detected.betButton.bounds
            calibratedHandBounds = handBounds
            repository.saveCalibratedBetBounds(
                detected.betButton.bounds.left,
                detected.betButton.bounds.top,
                detected.betButton.bounds.right,
                detected.betButton.bounds.bottom
            )
            repository.saveCalibratedHandBounds(handBounds.left, handBounds.top, handBounds.right, handBounds.bottom)
            repository.log(
                LogCategory.DETECTION,
                detected.matchReason,
                "Panel ${detected.panelIndex} bounds=${detected.betButton.bounds}"
            )

            val freshMap = currentScheduler.runScan(ScanReason.PRE_BET_S2, detected.betButton.bounds)
            processScreenMap(freshMap, allowAutoBet = false)

            _state.update {
                it.copy(
                    betTargetDetected = true,
                    betTargetSummary = detected.matchReason
                )
            }

            if (activeBet != null || freshMap.cancelButton != null) {
                onResult?.invoke(false, "Bet button detected — round bet already active")
                return@launch
            }

            if (freshMap.phase == GamePhase.IN_FLIGHT || freshMap.betButton?.isEnabled == false) {
                onResult?.invoke(true, "Bet button detected — waiting for betting window")
                return@launch
            }

            if (freshMap.stakeField != null && freshMap.phase == GamePhase.BETTING_OPEN) {
                executeBetForMap(
                    map = freshMap,
                    manualOverrideDecision = StrategyDecision(true, "BET Hand Drag", detected.matchReason),
                    isManualTrigger = true,
                    preferredBetBounds = detected.betButton.bounds,
                    onResult = onResult
                )
            } else {
                executeDirectCoordinateOrButtonClick(
                    betButton = freshMap.betButton ?: detected.betButton,
                    fallbackBounds = handBounds,
                    map = freshMap,
                    strategyName = "BET Hand Drag",
                    onResult = onResult
                )
            }
        }
    }

    /**
     * Triggered when user manually taps the floating BET hand.
     * Enforces the verified BetSequence when Bet + Stake box are exposed, or real coordinate tap
     * on the calibrated hand target when the game renders on Canvas without an editable node.
     */
    fun triggerManualHandBet(onResult: ((Boolean, String) -> Unit)? = null) {
        if (!_state.value.serviceConnected) {
            onResult?.invoke(false, "Accessibility service not connected")
            return
        }
        scope.launch {
            val targetBounds = calibratedBetBounds ?: calibratedHandBounds
            val map = scheduler?.runScan(ScanReason.PRE_BET_S2, targetBounds)
                ?: return@launch

            processScreenMap(map, allowAutoBet = false)

            if (activeBet != null || map.cancelButton != null) {
                repository.log(LogCategory.SKIP, "Floating BET hand locked for current round")
                onResult?.invoke(false, "Bet already active for current round")
                return@launch
            }

            if (map.phase == GamePhase.IN_FLIGHT) {
                onResult?.invoke(false, "Plane in flight — wait for next betting window")
                return@launch
            }

            if (map.betButton != null && map.stakeField != null && map.phase == GamePhase.BETTING_OPEN) {
                executeBetForMap(
                    map = map,
                    manualOverrideDecision = StrategyDecision(true, "Manual BET Hand", "Floating BET hand tapped"),
                    isManualTrigger = true,
                    preferredBetBounds = targetBounds,
                    onResult = onResult
                )
            } else if (map.betButton != null && map.betButton.isEnabled) {
                executeDirectCoordinateOrButtonClick(
                    betButton = map.betButton,
                    fallbackBounds = targetBounds ?: map.betButton.bounds,
                    map = map,
                    strategyName = "Manual BET Hand",
                    onResult = onResult
                )
            } else if (calibratedHandBounds != null) {
                executeDirectCoordinateOrButtonClick(
                    betButton = null,
                    fallbackBounds = calibratedHandBounds!!,
                    map = map,
                    strategyName = "Manual BET Hand",
                    onResult = onResult
                )
            } else {
                executeBetForMap(
                    map = map,
                    manualOverrideDecision = StrategyDecision(true, "Manual BET Hand", "Floating BET hand tapped"),
                    isManualTrigger = true,
                    preferredBetBounds = targetBounds,
                    onResult = onResult
                )
            }
        }
    }

    suspend fun processScreenMap(map: ScreenMap, allowAutoBet: Boolean) = scanMutex.withLock {
        val nowMs = System.currentTimeMillis()
        val config = repository.getConfig()
        val rules = repository.getRules()

        // Track crashed multipliers & record ONLY newly occurred current crash(es)
        val crashUpdate = if (_state.value.isRunning) {
            crashTracker.onScreenSnapshot(map, nowMs)
        } else {
            CrashMonitorUpdate(
                newCrashMultipliers = emptyList(),
                status = crashTracker.status,
                monitoredCrashCount = crashTracker.monitoredCrashCount,
                latestCrashMultiplier = crashTracker.latestCrashMultiplier,
                summary = "Paused"
            )
        }

        if (liveRecordedMultipliers.isEmpty() && map.ribbonMultipliers.isNotEmpty()) {
            liveRecordedMultipliers.addAll(map.ribbonMultipliers)
        }

        crashUpdate.newCrashMultipliers.forEachIndexed { idx, crashMult ->
            liveRecordedMultipliers.add(0, crashMult)
            while (liveRecordedMultipliers.size > 50) {
                liveRecordedMultipliers.removeAt(liveRecordedMultipliers.size - 1)
            }
            recordCurrentCrashAndSettle(
                crashMult = crashMult,
                siteUrl = map.siteUrl,
                config = config,
                recordTimestampMs = nowMs + idx
            )
        }

        // Release stale bet/round locks whenever live UI or phase confirms no bet is active
        clearStaleBetLockIfNeeded(map, nowMs)
        previousPhase = map.phase

        val recentRounds = repository.getRecentRounds()
        val evalResult = if (liveRecordedMultipliers.isNotEmpty()) {
            StrategyEvaluator.evaluateFromMultipliers(
                config = config,
                rules = rules,
                multipliersNewestFirst = liveRecordedMultipliers,
                consumedSignatures = consumedPatternSignatures,
                nowMs = nowMs
            )
        } else {
            StrategyEvaluator.evaluateWithProgress(
                config = config,
                rules = rules,
                recentRoundsNewestFirst = recentRounds,
                consumedSignatures = consumedPatternSignatures,
                nowMs = nowMs
            )
        }
        val decision = evalResult.decision

        // Dispatch pattern alerts: High Signal (time to bet alarm) or Pre-Signal (approaching warning)
        patternAlertManager?.processPatternProgress(
            progress = evalResult.primaryProgress,
            gameSite = map.siteUrl.ifBlank { map.gameName }
        )

        val calibratedTarget = calibratedBetBounds ?: calibratedHandBounds
        val betButtonReady = map.phase == GamePhase.BETTING_OPEN &&
            map.cancelButton == null &&
            (map.betButton?.isEnabled == true || (_state.value.handAutoBetEnabled && calibratedTarget != null))
        val handState = when {
            activeBet != null || map.cancelButton != null -> FloatingHandState.LOCKED
            betButtonReady && (decision.shouldBet || !_state.value.isRunning) -> FloatingHandState.READY
            else -> FloatingHandState.WAITING
        }
        val handStatusText = when (handState) {
            FloatingHandState.LOCKED -> "Locked"
            FloatingHandState.READY -> if (evalResult.primaryProgress.isMatched) "Pattern Ready" else if (calibratedTarget != null) "Target Ready" else "Ready to Bet"
            FloatingHandState.WAITING -> "Waiting"
        }

        val monitorLabel = when (crashUpdate.status) {
            CrashMonitorStatus.IDLE -> if (_state.value.isRunning) "Scanning" else "Idle"
            CrashMonitorStatus.BASELINE_CAPTURED -> "Baseline set (${crashUpdate.monitoredCrashCount} new)"
            CrashMonitorStatus.TRACKING_LIVE -> "Live (${crashUpdate.monitoredCrashCount} crashes)"
        }

        _state.update {
            it.copy(
                gameName = map.gameName,
                siteUrl = map.siteUrl,
                balance = map.balance,
                currency = map.currency,
                phase = map.phase,
                liveMultiplier = map.liveMultiplier,
                latestCrashMultiplier = crashUpdate.latestCrashMultiplier ?: it.latestCrashMultiplier,
                monitoredCrashCount = crashUpdate.monitoredCrashCount,
                monitorStatusLabel = monitorLabel,
                handState = handState,
                handStatusText = handStatusText,
                lastRoundId = if (crashUpdate.newCrashMultipliers.isNotEmpty()) {
                    "R-$roundCounter"
                } else {
                    it.lastRoundId
                },
                recordedMultipliers = liveRecordedMultipliers.toList(),
                patternProgress = evalResult.primaryProgress,
                patternSignalStage = evalResult.primaryProgress.signalStage,
                isPatternMatched = evalResult.primaryProgress.isMatched && !evalResult.primaryProgress.isConsumed,
                activeStrategyName = evalResult.primaryProgress.ruleName,
                statusLine = buildStatusLine(map, decision, crashUpdate)
            )
        }

        val roundKey = computeRoundKey(map)
        // Auto-bet strictly gated on running state, betting open, no active bet, round unattempted, and pattern matched
        val canAutoBet = allowAutoBet &&
            _state.value.isRunning &&
            map.phase == GamePhase.BETTING_OPEN &&
            map.cancelButton == null &&
            activeBet == null &&
            lockedRoundKey != roundKey &&
            decision.shouldBet

        if (canAutoBet) {
            if (map.betButton != null && map.stakeField != null) {
                executeBetForMap(
                    map = map,
                    manualOverrideDecision = null,
                    isManualTrigger = false,
                    preferredBetBounds = calibratedTarget
                )
            } else if (_state.value.handAutoBetEnabled && (map.betButton != null || calibratedHandBounds != null)) {
                executeDirectCoordinateOrButtonClick(
                    betButton = map.betButton,
                    fallbackBounds = calibratedHandBounds ?: map.betButton!!.bounds,
                    map = map,
                    strategyName = decision.triggeredRuleName ?: "Auto BET Hand"
                )
            }
        }
    }

    private suspend fun executeBetForMap(
        map: ScreenMap,
        manualOverrideDecision: StrategyDecision?,
        isManualTrigger: Boolean = false,
        preferredBetBounds: RectBounds? = null,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        val seq = betSequence ?: return
        val config = repository.getConfig()
        val rules = repository.getRules()
        val recentRounds = repository.getRecentRounds()

        sequenceMutex.withLock {
            val nowMs = System.currentTimeMillis()
            clearStaleBetLockIfNeeded(map, nowMs)
            if (activeBet != null || map.cancelButton != null) {
                onResult?.invoke(false, "Bet already active for this round")
                return
            }

            val evalResult = if (liveRecordedMultipliers.isNotEmpty()) {
                StrategyEvaluator.evaluateFromMultipliers(
                    config = config,
                    rules = rules,
                    multipliersNewestFirst = liveRecordedMultipliers,
                    consumedSignatures = consumedPatternSignatures,
                    nowMs = nowMs
                )
            } else {
                StrategyEvaluator.evaluateWithProgress(
                    config = config,
                    rules = rules,
                    recentRoundsNewestFirst = recentRounds,
                    consumedSignatures = consumedPatternSignatures,
                    nowMs = nowMs
                )
            }
            val decision = manualOverrideDecision ?: evalResult.decision

            val roundKey = computeRoundKey(map)
            if (lockedRoundKey == roundKey) {
                onResult?.invoke(false, "Bet already attempted for this round")
                return
            }

            val stake = _state.value.currentStake
            val todayProfit = repository.getTodayProfit()

            val result = seq.execute(
                config = config,
                stake = stake,
                isRunning = _state.value.isRunning,
                hasActiveBet = activeBet != null,
                handLockedForRound = lockedRoundKey == roundKey,
                todayProfit = todayProfit,
                decision = decision,
                isManualTrigger = isManualTrigger,
                preferredBetBounds = preferredBetBounds
            )

            if (result.confirmed) {
                val placedAt = System.currentTimeMillis()
                if (evalResult.primaryProgress.triggerSignature.isNotBlank()) {
                    consumedPatternSignatures.add(evalResult.primaryProgress.triggerSignature)
                }
                activeBet = ActiveBetRecord(
                    roundSignature = roundKey,
                    stake = stake,
                    targetMultiplier = 2.0,
                    strategyName = decision.triggeredRuleName,
                    placedAtMs = placedAt
                )
                lockedRoundKey = roundKey
                lockedAtMs = placedAt
                sawCancelOrFlightAfterBet = false
                markRuleTriggeredIfNeeded(rules, decision.triggeredRuleName, placedAt)
                _state.update {
                    it.copy(
                        handState = FloatingHandState.LOCKED,
                        handStatusText = "Locked",
                        statusLine = "Bet placed ${StakeCalculator.formatStake(stake)}"
                    )
                }
                onResult?.invoke(true, result.reason)
            } else {
                val stoppedByLimit = result.reason.startsWith("Stopped:")
                if (stoppedByLimit) {
                    stop(result.reason.removePrefix("Stopped:").trim())
                } else {
                    _state.update { it.copy(statusLine = result.reason) }
                }
                onResult?.invoke(false, result.reason)
            }
        }
    }

    private suspend fun executeDirectCoordinateOrButtonClick(
        betButton: com.example.service.UiTarget?,
        fallbackBounds: RectBounds,
        map: ScreenMap,
        strategyName: String,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        val currentDriver = driver ?: return
        val currentScheduler = scheduler ?: return
        val config = repository.getConfig()
        val stake = _state.value.currentStake
        val todayProfit = repository.getTodayProfit()

        if (config.dailyLossLimit > 0.0 && todayProfit <= -config.dailyLossLimit) {
            stop("daily loss limit reached")
            onResult?.invoke(false, "Stopped: daily loss limit reached")
            return
        }
        if (config.dailyProfitTarget > 0.0 && todayProfit >= config.dailyProfitTarget) {
            stop("daily profit target reached")
            onResult?.invoke(false, "Stopped: daily profit target reached")
            return
        }

        sequenceMutex.withLock {
            val nowMs = System.currentTimeMillis()
            clearStaleBetLockIfNeeded(map, nowMs)
            val roundKey = computeRoundKey(map)
            if (activeBet != null || map.cancelButton != null) {
                onResult?.invoke(false, "Bet already active for this round")
                return
            }
            if (lockedRoundKey == roundKey) {
                onResult?.invoke(false, "Bet already attempted for this round")
                return
            }

            val clickResult = if (betButton != null) {
                currentDriver.clickTarget(betButton, allowDebounceSkip = false)
            } else {
                currentDriver.tapCoordinates(fallbackBounds.centerX, fallbackBounds.centerY, allowDebounceSkip = false)
            }

            if (!clickResult.isProceedable) {
                _state.update { it.copy(statusLine = "BET hand tap failed: ${clickResult.reason}") }
                repository.log(LogCategory.ERROR, "BET hand tap failed", clickResult.reason)
                onResult?.invoke(false, clickResult.reason)
                return
            }

            // Poll briefly for Cancel / flight confirmation; for canvas targets without Cancel node, lock round if tap succeeded
            var confirmed = false
            for (poll in 1..2) {
                kotlinx.coroutines.delay(140L)
                val confirmScan = currentScheduler.runScan(ScanReason.CONFIRM_S3, betButton?.bounds ?: fallbackBounds)
                if (confirmScan.cancelButton != null || confirmScan.phase == GamePhase.IN_FLIGHT) {
                    confirmed = true
                    break
                }
            }
            val lockOnTap = confirmed || betButton == null
            if (lockOnTap) {
                val placedAt = System.currentTimeMillis()
                _state.value.patternProgress.triggerSignature.takeIf { it.isNotBlank() }?.let {
                    consumedPatternSignatures.add(it)
                }
                activeBet = ActiveBetRecord(
                    roundSignature = roundKey,
                    stake = stake,
                    targetMultiplier = 2.0,
                    strategyName = strategyName,
                    placedAtMs = placedAt
                )
                lockedRoundKey = roundKey
                lockedAtMs = placedAt
                sawCancelOrFlightAfterBet = confirmed
                _state.update {
                    it.copy(
                        handState = FloatingHandState.LOCKED,
                        handStatusText = "Locked",
                        statusLine = "Bet tapped ${StakeCalculator.formatStake(stake)}"
                    )
                }
                repository.log(LogCategory.BET, "BET hand clicked", clickResult.reason)
                onResult?.invoke(true, "BET hand clicked (${clickResult.reason})")
            } else {
                _state.update { it.copy(statusLine = "Bet tapped — waiting for game confirm") }
                onResult?.invoke(true, "BET hand tapped")
            }
        }
    }

    private suspend fun markRuleTriggeredIfNeeded(
        rules: List<com.example.data.model.StrategyRule>,
        triggeredRuleName: String?,
        nowMs: Long
    ) {
        if (triggeredRuleName.isNullOrBlank()) return
        val matched = rules.firstOrNull {
            it.enabled && it.cooldownSeconds > 0 && it.name == triggeredRuleName
        } ?: return
        repository.updateRule(matched.copy(lastTriggeredAtMs = nowMs))
    }

    private suspend fun recordCurrentCrashAndSettle(
        crashMult: Double,
        siteUrl: String,
        config: StrategyConfig,
        recordTimestampMs: Long = System.currentTimeMillis()
    ) {
        roundCounter++
        val roundId = "R-$roundCounter"
        val bet = activeBet
        if (bet == null) {
            lockedRoundKey = null
            lockedAtMs = 0L
            sawCancelOrFlightAfterBet = false
            repository.recordRound(
                RoundRecord(
                    roundId = roundId,
                    timestampMs = recordTimestampMs,
                    crashMultiplier = crashMult,
                    siteName = siteUrl
                )
            )
            repository.log(
                LogCategory.DETECTION,
                "Current crash recorded: ${StakeCalculator.formatStake(crashMult)}x",
                "Round $roundId (observation)"
            )
            return
        }

        val won = crashMult >= bet.targetMultiplier
        val profitDelta = if (won) {
            bet.stake * (bet.targetMultiplier - 1.0)
        } else {
            -bet.stake
        }

        repository.recordRound(
            RoundRecord(
                roundId = roundId,
                timestampMs = recordTimestampMs,
                crashMultiplier = crashMult,
                stakePlaced = bet.stake,
                targetMultiplier = bet.targetMultiplier,
                won = won,
                profitDelta = profitDelta,
                strategyName = bet.strategyName,
                siteName = siteUrl
            )
        )

        releaseBetLock()
        val prevLosses = _state.value.consecutiveLosses
        val progression = if (won) {
            StakeCalculator.onWin(config.baseStake)
        } else {
            StakeCalculator.onLoss(
                previousStake = bet.stake,
                baseStake = config.baseStake,
                mode = config.afterLoss,
                addAmount = config.addAmount,
                previousConsecutiveLosses = prevLosses,
                stopAfterLosses = config.stopAfterLosses
            )
        }

        repository.saveSessionProgression(progression.nextStake, progression.consecutiveLosses)

        val resultLine = if (won) {
            "Won +${StakeCalculator.formatStake(profitDelta)} (${StakeCalculator.formatStake(crashMult)}x)"
        } else {
            "Lost -${StakeCalculator.formatStake(bet.stake)} (${StakeCalculator.formatStake(crashMult)}x), next ${StakeCalculator.formatStake(progression.nextStake)}"
        }

        repository.log(LogCategory.RESULT, resultLine, "Crash ${crashMult}x")

        _state.update {
            it.copy(
                currentStake = progression.nextStake,
                consecutiveLosses = progression.consecutiveLosses,
                statusLine = resultLine,
                handState = FloatingHandState.WAITING,
                handStatusText = "Waiting"
            )
        }

        if (progression.shouldStop) {
            stop(progression.stopReason ?: "loss streak limit reached")
        }
    }

    private fun buildStatusLine(
        map: ScreenMap,
        decision: StrategyDecision,
        crashUpdate: CrashMonitorUpdate
    ): String {
        val active = activeBet
        return when {
            !map.gameWindowFound -> "Looking at the game..."
            active != null && map.phase == GamePhase.IN_FLIGHT ->
                "Round running (${map.liveMultiplier?.let { "${it}x" } ?: "in flight"})"
            active != null ->
                "Bet placed ${StakeCalculator.formatStake(active.stake)}"
            map.phase == GamePhase.IN_FLIGHT ->
                "Round running (${map.liveMultiplier?.let { "${it}x" } ?: "in flight"})"
            crashUpdate.newCrashMultipliers.isNotEmpty() ->
                "Crashed at ${StakeCalculator.formatStake(crashUpdate.newCrashMultipliers.last())}x"
            !decision.shouldBet -> decision.reason
            else -> "Ready to bet ${StakeCalculator.formatStake(_state.value.currentStake)}"
        }
    }

    private fun computeRoundKey(map: ScreenMap): String =
        "${map.packageName}_${roundCounter}_${crashTracker.latestCrashMultiplier ?: map.lastCrashMultiplier ?: 0.0}_${map.ribbonMultipliers.firstOrNull() ?: 0.0}"
}
