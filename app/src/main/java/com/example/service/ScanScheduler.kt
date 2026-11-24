package com.example.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ScanReason {
    START_S1,
    PRE_BET_S2,
    CONFIRM_S3,
    HEARTBEAT_S4,
    CONTEXT_CHANGE_S5
}

/**
 * Controlled ScanScheduler (Section 3, Rule R4, Test T9).
 * Full scans happen ONLY on:
 * - S1: User presses Start
 * - S2: Right before each bet attempt
 * - S3: Confirming bet acceptance (max 3 polls @ 150ms)
 * - S4: Slow heartbeat every 5 seconds while running
 * - S5: Foreground package change, rotation, or keyboard close
 *
 * Ignores per-event content-changed storms and runs scanning off the main thread.
 */
class ScanScheduler(
    private val platform: DriverPlatform,
    private val scanDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val heartbeatIntervalMs: Long = 5000L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private var heartbeatJob: Job? = null
    private var lastObservedPackage: String = ""
    private var lastImeState: Boolean = false
    private var lastScanAtMs: Long = 0L

    var lastScreenMap: ScreenMap? = null
        private set

    var totalScansExecuted: Int = 0
        private set

    fun startHeartbeat(
        scope: CoroutineScope,
        intervalMs: Long = heartbeatIntervalMs,
        preferredBetPanelBoundsProvider: (() -> RectBounds?)? = null,
        onHeartbeatScan: suspend (ScreenMap) -> Unit
    ) {
        stopHeartbeat()
        val effectiveInterval = intervalMs.coerceAtLeast(1500L)
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(effectiveInterval)
                val map = runScan(
                    reason = ScanReason.HEARTBEAT_S4,
                    preferredBetPanelBounds = preferredBetPanelBoundsProvider?.invoke()
                )
                onHeartbeatScan(map)
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    suspend fun runScan(
        reason: ScanReason,
        preferredBetPanelBounds: RectBounds? = null
    ): ScreenMap = withContext(scanDispatcher) {
        val map = if (preferredBetPanelBounds != null) {
            platform.scanScreenWithPanel(preferredBetPanelBounds)
        } else {
            platform.scanScreen()
        }
        lastScreenMap = map
        lastObservedPackage = map.packageName
        lastImeState = map.hasImeWindow
        lastScanAtMs = timeProvider()
        totalScansExecuted++
        map
    }

    private var lastContentDispatchAtMs: Long = 0L

    /**
     * Evaluates incoming AccessibilityEvents without causing scan storms (Section 3).
     * Content-changed events are ignored in onWindowEvent unless a context shift (package/keyboard) occurred.
     */
    suspend fun onWindowEvent(
        packageName: String?,
        isWindowStateChanged: Boolean,
        isImeVisible: Boolean,
        preferredBetPanelBounds: RectBounds? = null
    ): ScreenMap? {
        if (AccessibilityController.isIgnoredSystemPackage(packageName, platform.ownPackageName)) {
            return null
        }

        val packageChanged = !packageName.isNullOrBlank() && packageName != lastObservedPackage
        val keyboardClosed = lastImeState && !isImeVisible

        return if (isWindowStateChanged && (packageChanged || keyboardClosed)) {
            runScan(ScanReason.CONTEXT_CHANGE_S5, preferredBetPanelBounds)
        } else {
            // Section 3: Ignore content-changed events and avoid continuous scanning.
            null
        }
    }

    /**
     * Live throttled scan for TYPE_WINDOW_CONTENT_CHANGED while actively monitoring or locked on a bet,
     * ensuring fast crash multipliers and pattern triggers aren't skipped between 5s heartbeats.
     */
    suspend fun onContentChangedThrottled(
        packageName: String?,
        preferredBetPanelBounds: RectBounds? = null,
        minIntervalMs: Long = 900L
    ): ScreenMap? {
        if (AccessibilityController.isIgnoredSystemPackage(packageName, platform.ownPackageName)) {
            return null
        }
        val now = timeProvider()
        if ((now - lastScanAtMs) < minIntervalMs || (now - lastContentDispatchAtMs) < minIntervalMs) {
            return null
        }
        lastContentDispatchAtMs = now
        return runScan(ScanReason.CONTEXT_CHANGE_S5, preferredBetPanelBounds)
    }

    suspend fun onOrientationChanged(): ScreenMap =
        runScan(ScanReason.CONTEXT_CHANGE_S5)
}
