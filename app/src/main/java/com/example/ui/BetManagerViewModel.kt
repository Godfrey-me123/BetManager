package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.engine.AutomationEngine
import com.example.data.engine.EngineUiState
import com.example.data.engine.PatternDetectionEngine
import com.example.data.engine.SmartAgentExplanation
import com.example.data.engine.SmartAnalysisAgent
import com.example.data.engine.StakeCalculator
import com.example.data.engine.StatisticalAnalysisEngine
import com.example.data.engine.StatisticalReport
import com.example.data.local.BetManagerDatabase
import com.example.data.local.BetManagerRepository
import com.example.data.model.AfterLossMode
import com.example.data.model.BettingMode
import com.example.data.model.CrashRecord
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.DiagnosticLogEntry
import com.example.data.model.LogCategory
import com.example.data.model.NotificationSettings
import com.example.data.model.PatternOccurrenceRecord
import com.example.data.model.RoundRecord
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyRule
import com.example.service.ScreenEarnerAccessibilityService
import com.example.service.StrategyNotificationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BetManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = BetManagerDatabase.getInstance(application).betManagerDao()
    val repository = BetManagerRepository(application, dao)
    private val engine: AutomationEngine = ScreenEarnerAccessibilityService.getOrCreateEngine(repository)
    private val notificationManager = StrategyNotificationManager(application.applicationContext, repository)
    private val patternDetectionEngine = PatternDetectionEngine()

    val engineState: StateFlow<EngineUiState> = engine.state

    val config: StateFlow<StrategyConfig> = repository.configFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StrategyConfig())

    val rules: StateFlow<List<StrategyRule>> = repository.rulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customStrategies: StateFlow<List<CustomPatternStrategy>> = repository.customStrategiesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val patternOccurrences: StateFlow<List<PatternOccurrenceRecord>> = repository.patternOccurrencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val crashRecords: StateFlow<List<CrashRecord>> = repository.crashRecordsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notificationSettings: StateFlow<NotificationSettings> = repository.notificationSettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotificationSettings())

    private val _selectedPatternGameId = MutableStateFlow("ALL")
    val selectedPatternGameId: StateFlow<String> = _selectedPatternGameId.asStateFlow()

    val statisticalReport: StateFlow<StatisticalReport> = combine(
        crashRecords,
        config,
        _selectedPatternGameId
    ) { records, cfg, gameId ->
        val gameName = if (gameId == "ALL") {
            "All Games"
        } else {
            records.lastOrNull { it.gameId == gameId }?.gameName ?: gameId
        }
        StatisticalAnalysisEngine.analyzeCompletedCrashes(
            records = records,
            config = cfg,
            gameId = gameId,
            gameName = gameName
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatisticalReport())

    private val reportInputPairFlow = combine(
        statisticalReport,
        patternOccurrences,
        crashRecords
    ) { report, occs, records ->
        Triple(report, occs, records)
    }

    val agentExplanation: StateFlow<SmartAgentExplanation> = combine(
        engineState,
        customStrategies,
        reportInputPairFlow,
        config
    ) { ui, strategies, reportInputs, cfg ->
        SmartAnalysisAgent.generateExplanation(
            gameName = ui.gameName,
            isRunning = ui.isRunning,
            statusLine = ui.statusLine,
            strategies = strategies,
            report = reportInputs.first,
            occurrences = reportInputs.second,
            records = reportInputs.third,
            config = cfg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SmartAgentExplanation())

    val lastFiveResults: StateFlow<List<RoundRecord>> = repository.lastFiveBetResultsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todayProfit: StateFlow<Double> = repository.todayProfitFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val logs: StateFlow<List<DiagnosticLogEntry>> = repository.logsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val keepScreenAwake: StateFlow<Boolean> = repository.keepScreenAwakeFlow

    val alarmHighSignalEnabled: StateFlow<Boolean> = repository.alarmHighSignalFlow
    val preSignalNotificationEnabled: StateFlow<Boolean> = repository.preSignalNotificationFlow
    val alertSoundEnabled: StateFlow<Boolean> = repository.alertSoundFlow
    val alertVibrationEnabled: StateFlow<Boolean> = repository.alertVibrationFlow

    init {
        engine.attachStrategyNotificationManager(notificationManager)
        viewModelScope.launch {
            repository.ensureInitialized()
        }
    }

    fun selectPatternGameId(gameId: String) {
        _selectedPatternGameId.value = gameId
    }

    fun saveCustomStrategy(strategy: CustomPatternStrategy) {
        viewModelScope.launch {
            val crashes = repository.getCrashRecords()
            val latestCrashKey = crashes.lastOrNull()?.dedupKey
            val baselineKey = if (latestCrashKey != null) "baseline_${strategy.id}_$latestCrashKey" else null
            val stratToSave = strategy.copy(
                currentlyReached = false,
                currentlyApproaching = false,
                lastEvaluatedRecordKey = latestCrashKey,
                lastTriggeredOccurrenceKey = baselineKey ?: strategy.lastTriggeredOccurrenceKey
            )
            val savedId = repository.saveCustomStrategy(stratToSave)
            val resolvedId = if (strategy.id == 0L) savedId else strategy.id
            repository.log(
                LogCategory.STRATEGY,
                "Saved strategy: ${strategy.name}",
                strategy.summaryDescription()
            )
            // Re-evaluate against current crash history for display and establish safe live baseline
            if (crashes.isNotEmpty()) {
                val cfg = repository.getConfig()
                val allStrategies = repository.getCustomStrategies()
                val batch = patternDetectionEngine.evaluateStrategiesOnCrashRecords(
                    strategies = allStrategies,
                    crashRecords = crashes,
                    lowThreshold = cfg.lowThreshold,
                    bigThreshold = cfg.bigThreshold
                )
                batch.updatedStrategies.filter { it.id == resolvedId }.forEach {
                    repository.updateCustomStrategy(it)
                }
                repository.recordPatternOccurrences(batch.newOccurrences)
                // Consume past occurrence signatures so they cannot trigger automatic bets
                batch.events.forEach { ev ->
                    repository.saveConsumedTriggerSignature(ev.occurrenceKey)
                    engine.markSignatureConsumed(ev.occurrenceKey)
                }
            }
            baselineKey?.let { engine.markSignatureConsumed(it) }
        }
    }

    fun toggleCustomStrategyEnabled(strategy: CustomPatternStrategy, enabled: Boolean) {
        viewModelScope.launch {
            engine.setCustomStrategyEnabledWithBaseline(strategy, enabled)
        }
    }

    fun toggleCustomStrategyNotifications(strategy: CustomPatternStrategy, enabled: Boolean) {
        viewModelScope.launch {
            repository.setCustomStrategyNotificationsEnabled(strategy.id, enabled)
            repository.log(
                LogCategory.STRATEGY,
                "${strategy.name} notifications ${if (enabled) "enabled" else "disabled"}"
            )
        }
    }

    fun deleteCustomStrategy(strategyId: Long) {
        viewModelScope.launch {
            repository.deleteCustomStrategy(strategyId)
            repository.log(LogCategory.STRATEGY, "Deleted custom strategy #$strategyId")
        }
    }

    fun reorderCustomStrategies(first: CustomPatternStrategy, second: CustomPatternStrategy) {
        viewModelScope.launch {
            repository.swapStrategyOrder(first.id, second.id)
        }
    }

    fun saveStrategyConfig(updated: StrategyConfig) {
        val synced = updated.normalized()
        viewModelScope.launch {
            repository.saveConfig(synced)
            engine.syncStakeWithConfig(resetProgression = false)
            repository.log(
                LogCategory.STRATEGY,
                "Saved strategy configuration",
                "Low < ${synced.lowThreshold}x · Big >= ${synced.bigThreshold}x · Mode=${synced.afterLoss.name}"
            )
        }
    }

    fun toggleStartStop() {
        if (engineState.value.isRunning) {
            engine.stop("paused by user")
        } else {
            if (config.value.showFloatingHand) {
                ScreenEarnerAccessibilityService.instance?.showFloatingHand()
            }
            engine.start()
        }
    }

    fun cancelActiveAlarm() {
        engine.cancelActiveAlarm()
    }

    fun switchToOverlayMode(onMinimized: () -> Unit = {}) {
        updateConfig { it.copy(showFloatingHand = true) }
        ScreenEarnerAccessibilityService.instance?.showAndExpandOverlay()
        onMinimized()
    }

    fun toggleFloatingHandOverlay(show: Boolean) {
        updateConfig { it.copy(showFloatingHand = show) }
        val service = ScreenEarnerAccessibilityService.instance
        if (show) {
            service?.showFloatingHand()
        } else {
            service?.hideFloatingHand()
        }
    }

    fun toggleHandAutoBet(enabled: Boolean) {
        engine.setHandAutoBetEnabled(enabled)
    }

    fun toggleKeepScreenAwake(enabled: Boolean) {
        repository.setKeepScreenAwakeEnabled(enabled)
    }

    fun toggleAlarmHighSignal(enabled: Boolean) {
        repository.setAlarmHighSignalEnabled(enabled)
        viewModelScope.launch {
            val s = repository.getNotificationSettings()
            repository.saveNotificationSettings(s.copy(highPriorityAlarmEnabled = enabled))
        }
    }

    fun togglePreSignalNotification(enabled: Boolean) {
        repository.setPreSignalNotificationEnabled(enabled)
        viewModelScope.launch {
            val s = repository.getNotificationSettings()
            repository.saveNotificationSettings(s.copy(approachingEnabled = enabled))
        }
    }

    fun toggleAlertSound(enabled: Boolean) {
        repository.setAlertSoundEnabled(enabled)
        viewModelScope.launch {
            val s = repository.getNotificationSettings()
            repository.saveNotificationSettings(s.copy(soundEnabled = enabled))
        }
    }

    fun toggleAlertVibration(enabled: Boolean) {
        repository.setAlertVibrationEnabled(enabled)
        viewModelScope.launch {
            val s = repository.getNotificationSettings()
            repository.saveNotificationSettings(s.copy(vibrationEnabled = enabled))
        }
    }

    fun exitApp() {
        ScreenEarnerAccessibilityService.performFullExit(getApplication())
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(intent)
    }

    fun updateBaseStake(value: Double) {
        val safe = value.coerceAtLeast(0.01)
        updateConfig { it.copy(baseStake = safe).normalized() }
    }

    fun updateAfterLossMode(mode: AfterLossMode) {
        updateConfig { it.copy(afterLoss = mode).normalized() }
    }

    fun updateAddAmount(value: Double) {
        updateConfig { it.copy(addAmount = value.coerceAtLeast(0.0)).normalized() }
    }

    fun updateStopAfterLosses(count: Int) {
        updateConfig { it.copy(stopAfterLosses = count.coerceAtLeast(0)).normalized() }
    }

    fun updateCashOutTarget(target: Double) {
        val safe = target.coerceAtLeast(1.01)
        updateConfig { it.copy(cashOutTarget = safe, targetMultiplier = safe) }
    }

    fun updateBettingMode(mode: BettingMode) {
        updateConfig { it.copy(bettingMode = mode) }
    }

    fun updateDailyLossLimit(limit: Double) {
        updateConfig { it.copy(dailyLossLimit = limit.coerceAtLeast(0.0)) }
    }

    fun updateDailyProfitTarget(target: Double) {
        updateConfig { it.copy(dailyProfitTarget = target.coerceAtLeast(0.0)) }
    }

    fun updateBalanceFloor(floor: Double) {
        updateConfig { it.copy(balanceFloor = floor.coerceAtLeast(0.0)) }
    }

    fun updateHeartbeatMs(ms: Long) {
        updateConfig { it.copy(scanHeartbeatMs = ms.coerceIn(2000L, 15000L)) }
    }

    fun updateStakeTolerance(tol: Double) {
        updateConfig { it.copy(stakeTolerance = tol.coerceIn(0.01, 1.0)) }
    }

    fun resetFloatingHandPosition() {
        repository.resetOverlayPosition()
        viewModelScope.launch {
            repository.log(LogCategory.DETECTION, "Reset floating BET hand position")
        }
    }

    fun resetStakeToBase() {
        viewModelScope.launch {
            val base = engine.resetProgressionToBase()
            repository.log(LogCategory.STAKE, "Stake manually reset to ${StakeCalculator.formatStake(base)}")
        }
    }

    fun toggleRule(rule: StrategyRule, enabled: Boolean) {
        viewModelScope.launch {
            val updated = rule.copy(enabled = enabled)
            repository.updateRule(updated)
            if (enabled) {
                engine.consumeBaselineForRule(updated)
            }
            repository.log(
                LogCategory.STRATEGY,
                "${rule.name} ${if (enabled) "enabled" else "disabled"}"
            )
        }
    }

    fun updateRule(rule: StrategyRule) {
        viewModelScope.launch {
            repository.updateRule(rule)
            if (rule.enabled) {
                engine.consumeBaselineForRule(rule)
            }
            repository.log(LogCategory.STRATEGY, "Updated rule: ${rule.name}")
        }
    }

    fun addRule(rule: StrategyRule) {
        viewModelScope.launch {
            repository.insertRule(rule)
            repository.log(LogCategory.STRATEGY, "Added rule: ${rule.name}")
        }
    }

    fun deleteRule(rule: StrategyRule) {
        viewModelScope.launch {
            repository.deleteRule(rule)
            repository.log(LogCategory.STRATEGY, "Deleted rule: ${rule.name}")
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    fun formatDiagnosticReport(limit: Int = 150): String {
        val state = engineState.value
        val cfg = config.value
        val logsList = logs.value.take(limit)
        val sb = StringBuilder()
        sb.appendLine("=== BETMANAGER DIAGNOSTIC EXPORT ===")
        sb.appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("Engine Running: ${state.isRunning}")
        sb.appendLine("Current Stake: ${StakeCalculator.formatStake(state.currentStake)}")
        sb.appendLine("Consecutive Losses: ${state.consecutiveLosses}")
        sb.appendLine("Recovery Deficit: ${StakeCalculator.formatStake(state.recoveryDeficit)}")
        sb.appendLine("Balance: ${state.balance?.let { StakeCalculator.formatStake(it) } ?: "null (last known)"}")
        sb.appendLine("Latest Crash Multiplier: ${state.latestCrashMultiplier?.let { StakeCalculator.formatStake(it) + "x" } ?: "N/A"}")
        sb.appendLine("Game Phase: ${state.phase.name}")
        sb.appendLine("Active Strategy: ${state.activeStrategyName?.takeIf { it.isNotBlank() } ?: "None"}")
        sb.appendLine("Config: Base ${StakeCalculator.formatStake(cfg.baseStake)} · Target ${StakeCalculator.formatStake(cfg.cashOutTarget)}x · Mode ${cfg.afterLoss.name} · StopLosses ${cfg.stopAfterLosses}")
        sb.appendLine("=== RECENT DIAGNOSTIC LOGS (${logsList.size}) ===")
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        for (entry in logsList) {
            sb.appendLine("[${sdf.format(Date(entry.timestampMs))}] [${entry.category.name}] ${entry.message}")
            if (entry.details.isNotBlank()) {
                sb.appendLine("   Details: ${entry.details}")
            }
        }
        sb.appendLine("=== END DIAGNOSTIC EXPORT ===")
        return sb.toString()
    }

    fun copyLogsToClipboard() {
        val text = formatDiagnosticReport()
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("BetManager Diagnostic Logs", text)
        clipboard?.setPrimaryClip(clip)
        viewModelScope.launch {
            repository.log(LogCategory.STRATEGY, "Exported diagnostic logs to clipboard")
        }
    }

    fun shareLogs() {
        val text = formatDiagnosticReport()
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "BetManager Diagnostic Logs")
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(sendIntent, "Share BetManager Logs").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(chooser)
        viewModelScope.launch {
            repository.log(LogCategory.STRATEGY, "Shared diagnostic logs via share sheet")
        }
    }

    private fun updateConfig(
        resetEngineProgression: Boolean = false,
        transform: (StrategyConfig) -> StrategyConfig
    ) {
        viewModelScope.launch {
            val current = repository.getConfig()
            repository.saveConfig(transform(current))
            engine.syncStakeWithConfig(resetProgression = resetEngineProgression)
        }
    }
}
