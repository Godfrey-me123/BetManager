package com.example.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.MainActivity
import com.example.data.engine.AutomationEngine
import com.example.data.local.BetManagerDatabase
import com.example.data.local.BetManagerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * BetManager AccessibilityService.
 * Throttles events via ScanScheduler, ignores system surfaces (Edge panels, SystemUI, launchers, IMEs),
 * runs scanning off the main thread, and hosts the draggable floating BET hand + BM control overlay.
 */
class ScreenEarnerAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ScreenEarnerAccessibilityService? = null
            private set

        @Volatile
        var sharedEngine: AutomationEngine? = null
            private set

        fun getOrCreateEngine(repository: BetManagerRepository): AutomationEngine {
            return sharedEngine ?: synchronized(this) {
                sharedEngine ?: AutomationEngine(repository).also { sharedEngine = it }
            }
        }

        /**
         * Stops monitoring & automation, hides all floating hands/panels, cancels notifications,
         * and launches ACTION_EXIT_APP to finish and clear every Activity in the task stack.
         */
        fun performFullExit(context: Context) {
            val appCtx = context.applicationContext
            runCatching { sharedEngine?.stopForExit() }
            runCatching { instance?.hideFloatingHand() }
            runCatching { ScreenEarnerOverlayService.clearStatusNotification(appCtx) }
            runCatching {
                val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancelAll()
            }
            runCatching {
                val exitIntent = Intent(appCtx, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_EXIT_APP
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                appCtx.startActivity(exitIntent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null

    private lateinit var repository: BetManagerRepository
    private lateinit var engine: AutomationEngine
    private lateinit var driver: AutoTouchDriver
    private lateinit var scheduler: ScanScheduler
    private var overlayManager: FloatingHandsOverlayManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val db = BetManagerDatabase.getInstance(applicationContext)
        repository = BetManagerRepository(applicationContext, db.betManagerDao())
        engine = getOrCreateEngine(repository)

        val platform = AndroidAccessibilityPlatform(
            service = this,
            onPassThroughRequested = { passThrough ->
                overlayManager?.setPassThrough(passThrough)
            }
        )
        driver = AutoTouchDriver(platform)
        scheduler = ScanScheduler(platform)
        val alertManager = PatternAlertManager(applicationContext, repository)
        engine.patternAlertManager = alertManager
        engine.attachAccessibility(driver, scheduler)

        overlayManager = FloatingHandsOverlayManager(
            context = this,
            repository = repository,
            onBetHandTapped = {
                engine.triggerManualHandBet { _, message ->
                    serviceScope.launch(Dispatchers.Main) {
                        Toast.makeText(this@ScreenEarnerAccessibilityService, message, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onBetHandDraggedTo = { handBounds ->
                engine.onBetHandDraggedAndDropped(handBounds) { _, message ->
                    serviceScope.launch(Dispatchers.Main) {
                        Toast.makeText(this@ScreenEarnerAccessibilityService, message, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onToggleHandAutoBet = {
                val next = !engine.state.value.handAutoBetEnabled
                engine.setHandAutoBetEnabled(next)
                serviceScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@ScreenEarnerAccessibilityService,
                        if (next) "BET hand Auto Bet ON" else "BET hand Auto Bet OFF",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onToggleStartStop = {
                if (engine.state.value.isRunning) engine.stop() else engine.start()
            },
            onStakeStep = { dir -> stepBaseStake(dir) },
            onOpenApp = {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            },
            onExitApp = {
                performFullExit(this)
            },
            onToggleKeepScreenAwake = {
                val next = !repository.isKeepScreenAwakeEnabled()
                repository.setKeepScreenAwakeEnabled(next)
                serviceScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@ScreenEarnerAccessibilityService,
                        if (next) "Never Sleep ON" else "Never Sleep OFF",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            combine(
                engine.state,
                repository.configFlow,
                repository.lastFiveBetResultsFlow,
                repository.todayProfitFlow(),
                repository.keepScreenAwakeFlow
            ) { ui, config, last, profit, keepAwake ->
                OverlayData(ui, config, last, profit, keepAwake)
            }.collectLatest { d ->
                val ui = d.ui
                if (d.config.showFloatingHand) overlayManager?.show() else overlayManager?.hide()
                val stake = if (ui.isRunning) ui.currentStake else d.config.baseStake
                overlayManager?.update(
                    OverlayInfo(
                        isRunning = ui.isRunning,
                        statusLine = ui.statusLine,
                        gameLine = "${ui.gameName} · ${ui.siteUrl}",
                        balanceText = ui.balance?.let { "%.2f %s".format(it, ui.currency) } ?: "--",
                        stakeText = "%.2f %s".format(stake, ui.currency),
                        lossStreak = ui.consecutiveLosses,
                        phaseText = ui.phase.name.lowercase().replace('_', ' '),
                        multiplierText = ui.liveMultiplier?.let { "%.2fx".format(it) } ?: "",
                        latestCrashText = ui.latestCrashMultiplier?.let { "%.2fx".format(it) } ?: "--",
                        monitoredCrashes = ui.monitoredCrashCount,
                        todayProfitText = "%+.2f".format(d.profit),
                        lastResults = d.last.mapNotNull { it.won }.reversed(),
                        handState = ui.handState,
                        betTargetDetected = ui.betTargetDetected,
                        handAutoBetEnabled = ui.handAutoBetEnabled,
                        keepScreenAwake = d.keepScreenAwake,
                        canEditStake = !ui.isRunning,
                        recordedMultipliers = ui.recordedMultipliers,
                        patternSummary = ui.patternProgress.statusText,
                        isPatternMatched = ui.isPatternMatched
                    )
                )
            }
        }
    }

    private class OverlayData(
        val ui: com.example.data.engine.EngineUiState,
        val config: com.example.data.model.StrategyConfig,
        val last: List<com.example.data.model.RoundRecord>,
        val profit: Double,
        val keepScreenAwake: Boolean
    )

    /** Overlay stake -/+ : only while stopped. Step scales with the base stake. */
    private fun stepBaseStake(dir: Int) {
        if (engine.state.value.isRunning) return
        serviceScope.launch {
            val c = repository.getConfig()
            val step = when {
                c.baseStake >= 100 -> 50.0
                c.baseStake >= 20 -> 10.0
                else -> 1.0
            }
            val newBase = maxOf(1.0, c.baseStake + dir * step)
            repository.saveConfig(c.copy(baseStake = newBase))
            repository.saveSessionProgression(newBase, 0)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::scheduler.isInitialized) return
        val pkg = event.packageName?.toString()
        if (AccessibilityController.isIgnoredSystemPackage(pkg, packageName)) return

        val isWindowStateChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isWindowContentChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (!isWindowStateChanged && !isWindowContentChanged) return

        val targetBounds = if (::engine.isInitialized) engine.activeTargetBounds() else null

        if (isWindowStateChanged) {
            serviceScope.launch {
                val imeVisible = AndroidAccessibilityPlatform(this@ScreenEarnerAccessibilityService).hasImeWindow()
                val scan = scheduler.onWindowEvent(
                    packageName = pkg,
                    isWindowStateChanged = true,
                    isImeVisible = imeVisible,
                    preferredBetPanelBounds = targetBounds
                )
                if (scan != null) {
                    engine.handleScreenEvent(scan)
                }
            }
            return
        }

        // Fast throttled live monitoring on TYPE_WINDOW_CONTENT_CHANGED while running or clearing a bet lock
        val shouldScanContent = ::engine.isInitialized && (
            engine.state.value.isRunning ||
                engine.state.value.handAutoBetEnabled ||
                engine.hasActiveBetLock()
            )
        if (!shouldScanContent) return

        serviceScope.launch {
            val scan = scheduler.onContentChangedThrottled(
                packageName = pkg,
                preferredBetPanelBounds = targetBounds,
                minIntervalMs = 900L
            )
            if (scan != null) {
                engine.handleScreenEvent(scan)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::scheduler.isInitialized) return
        serviceScope.launch {
            val scan = scheduler.onOrientationChanged()
            engine.handleScreenEvent(scan)
        }
    }

    fun showFloatingHand() {
        overlayManager?.show()
    }

    fun hideFloatingHand() {
        overlayManager?.hide()
    }

    override fun onInterrupt() {
        if (::engine.isInitialized) {
            engine.stop("accessibility interrupted")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun cleanup() {
        stateObserverJob?.cancel()
        overlayManager?.hide()
        if (::engine.isInitialized) {
            engine.detachAccessibility()
        }
        ScreenEarnerOverlayService.clearStatusNotification(this)
        instance = null
    }
}
