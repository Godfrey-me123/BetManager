package com.example.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.engine.AutomationEngine
import com.example.data.engine.EngineUiState
import com.example.data.engine.StakeCalculator
import com.example.data.local.BetManagerDatabase
import com.example.data.local.BetManagerRepository
import com.example.data.model.AfterLossMode
import com.example.data.model.BettingMode
import com.example.data.model.DiagnosticLogEntry
import com.example.data.model.LogCategory
import com.example.data.model.RoundRecord
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyRule
import com.example.service.ScreenEarnerAccessibilityService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BetManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = BetManagerDatabase.getInstance(application).betManagerDao()
    val repository = BetManagerRepository(application, dao)
    private val engine: AutomationEngine = ScreenEarnerAccessibilityService.getOrCreateEngine(repository)

    val engineState: StateFlow<EngineUiState> = engine.state

    val config: StateFlow<StrategyConfig> = repository.configFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StrategyConfig())

    val rules: StateFlow<List<StrategyRule>> = repository.rulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        viewModelScope.launch {
            repository.ensureInitialized()
        }
    }

    fun toggleStartStop() {
        if (engineState.value.isRunning) {
            engine.stop("paused by user")
            ScreenEarnerAccessibilityService.instance?.hideFloatingHand()
        } else {
            if (config.value.showFloatingHand) {
                ScreenEarnerAccessibilityService.instance?.showFloatingHand()
            }
            engine.start()
        }
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
    }

    fun togglePreSignalNotification(enabled: Boolean) {
        repository.setPreSignalNotificationEnabled(enabled)
    }

    fun toggleAlertSound(enabled: Boolean) {
        repository.setAlertSoundEnabled(enabled)
    }

    fun toggleAlertVibration(enabled: Boolean) {
        repository.setAlertVibrationEnabled(enabled)
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
        updateConfig { it.copy(baseStake = safe) }
        if (!engineState.value.isRunning && engineState.value.consecutiveLosses == 0) {
            repository.saveSessionProgression(safe, 0)
        }
    }

    fun updateAfterLossMode(mode: AfterLossMode) {
        updateConfig { it.copy(afterLoss = mode) }
    }

    fun updateAddAmount(value: Double) {
        updateConfig { it.copy(addAmount = value.coerceAtLeast(0.0)) }
    }

    fun updateStopAfterLosses(count: Int) {
        updateConfig { it.copy(stopAfterLosses = count.coerceAtLeast(0)) }
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
            val base = config.value.baseStake
            repository.saveSessionProgression(base, 0)
            repository.log(LogCategory.STAKE, "Stake manually reset to ${StakeCalculator.formatStake(base)}")
        }
    }

    fun toggleRule(rule: StrategyRule, enabled: Boolean) {
        viewModelScope.launch {
            repository.updateRule(rule.copy(enabled = enabled))
            repository.log(
                LogCategory.STRATEGY,
                "${rule.name} ${if (enabled) "enabled" else "disabled"}"
            )
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

    private fun updateConfig(transform: (StrategyConfig) -> StrategyConfig) {
        viewModelScope.launch {
            val current = repository.getConfig()
            repository.saveConfig(transform(current))
        }
    }
}
