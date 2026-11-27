package com.example.data.engine

import com.example.service.GamePhase
import com.example.service.ScreenMap
import kotlin.math.abs
import kotlin.math.min

enum class CrashMonitorStatus {
    IDLE,
    BASELINE_CAPTURED,
    TRACKING_LIVE
}

data class CrashMonitorUpdate(
    val newCrashMultipliers: List<Double>,
    val status: CrashMonitorStatus,
    val monitoredCrashCount: Int,
    val latestCrashMultiplier: Double?,
    val summary: String
)

/**
 * Tracks live crashed multipliers from phase transitions and the horizontal history ribbon,
 * recording ONLY newly occurred current crashes (never bulk-importing pre-existing history,
 * never re-recording the same crash, and never misreading expanded history / scrolls).
 */
class CrashMonitorTracker {

    var status: CrashMonitorStatus = CrashMonitorStatus.IDLE
        private set

    var monitoredCrashCount: Int = 0
        private set

    var latestCrashMultiplier: Double? = null
        private set

    private var baselineRibbon: List<Double> = emptyList()
    private var previousPhase: GamePhase = GamePhase.UNKNOWN
    private var pendingCrashFromBanner: Double? = null
    private var lastBannerCrashRecordedAtMs: Long = 0L
    private var initialBannerObserved: Boolean = false

    fun resetSession() {
        status = CrashMonitorStatus.IDLE
        monitoredCrashCount = 0
        latestCrashMultiplier = null
        baselineRibbon = emptyList()
        previousPhase = GamePhase.UNKNOWN
        pendingCrashFromBanner = null
        lastBannerCrashRecordedAtMs = 0L
        initialBannerObserved = false
    }

    fun onScreenSnapshot(
        map: ScreenMap,
        nowMs: Long = map.timestampMs
    ): CrashMonitorUpdate {
        if (!map.gameWindowFound) {
            return CrashMonitorUpdate(
                newCrashMultipliers = emptyList(),
                status = status,
                monitoredCrashCount = monitoredCrashCount,
                latestCrashMultiplier = latestCrashMultiplier,
                summary = "Waiting for game screen"
            )
        }

        val currentRibbon = map.ribbonMultipliers.filter { it.isFinite() && it >= 1.0 }
        val newlyRecorded = mutableListOf<Double>()
        var summary = "Watching live rounds"

        // 1. Handle ribbon baseline capture & forward shift detection
        if (currentRibbon.isNotEmpty()) {
            if (baselineRibbon.isEmpty()) {
                // First observation: capture baseline ONLY — 0 historical rows imported
                baselineRibbon = currentRibbon
                status = CrashMonitorStatus.BASELINE_CAPTURED
                summary = "Baseline captured (${currentRibbon.size} shown, 0 imported)"
            } else {
                val shiftResult = alignRibbon(baselineRibbon, currentRibbon)
                when (shiftResult) {
                    is RibbonAlignment.Unchanged -> {
                        // Nothing changed on the ribbon
                    }
                    is RibbonAlignment.TailExtendedOrScroll -> {
                        // History expanded or scrolled without a new head crash — keep baseline safe
                        summary = shiftResult.reason
                    }
                    is RibbonAlignment.DisjointJump -> {
                        // Completely different screen / panel reset: re-anchor baseline with 0 imports
                        baselineRibbon = currentRibbon
                        status = CrashMonitorStatus.BASELINE_CAPTURED
                        summary = "Ribbon re-anchored (0 imported)"
                    }
                    is RibbonAlignment.NewHead -> {
                        val chronologicalNew = currentRibbon.take(shiftResult.shift).reversed()
                        baselineRibbon = currentRibbon
                        val pending = pendingCrashFromBanner
                        val withinWindow = pending != null && (nowMs - lastBannerCrashRecordedAtMs) < 30_000L
                        var consumedPending = false

                        for (crashMult in chronologicalNew) {
                            if (withinWindow && !consumedPending && pending != null && abs(pending - crashMult) < 0.009) {
                                consumedPending = true
                                continue
                            }
                            newlyRecorded += crashMult
                            monitoredCrashCount++
                            latestCrashMultiplier = crashMult
                            status = CrashMonitorStatus.TRACKING_LIVE
                        }
                        if (consumedPending || !withinWindow) {
                            pendingCrashFromBanner = null
                        }
                        if (newlyRecorded.isNotEmpty()) {
                            summary = "Recorded ${newlyRecorded.size} crash(es), latest ${formatMult(newlyRecorded.last())}x"
                        }
                    }
                }
            }
        }

        // 2. Detect live CRASHED banner / IN_FLIGHT -> BETTING_OPEN transition immediately
        val bannerCrash = map.lastCrashMultiplier?.takeIf { it.isFinite() && it >= 1.0 }

        if (!initialBannerObserved) {
            initialBannerObserved = true
            if (bannerCrash != null) {
                // First crash-banner observation is a baseline only — not automatically a new crash
                pendingCrashFromBanner = bannerCrash
                lastBannerCrashRecordedAtMs = nowMs
                latestCrashMultiplier = bannerCrash
            }
        } else {
            val isCrashTransition = map.phase == GamePhase.CRASHED ||
                (previousPhase == GamePhase.IN_FLIGHT && map.phase == GamePhase.BETTING_OPEN)

            if (isCrashTransition && newlyRecorded.isEmpty()) {
                val alreadyPending = bannerCrash != null &&
                    pendingCrashFromBanner != null &&
                    abs(pendingCrashFromBanner!! - bannerCrash) < 0.009 &&
                    (nowMs - lastBannerCrashRecordedAtMs) < 15_000L

                if (bannerCrash != null && !alreadyPending &&
                    (previousPhase != GamePhase.CRASHED || pendingCrashFromBanner == null)
                ) {
                    pendingCrashFromBanner = bannerCrash
                    lastBannerCrashRecordedAtMs = nowMs
                    newlyRecorded += bannerCrash
                    monitoredCrashCount++
                    latestCrashMultiplier = bannerCrash
                    status = CrashMonitorStatus.TRACKING_LIVE
                    summary = "Recorded current crash ${formatMult(bannerCrash)}x"
                }
            } else if (map.phase == GamePhase.IN_FLIGHT) {
                // New round started flying -> clear any prior round banner marker
                pendingCrashFromBanner = null
            }
        }

        previousPhase = map.phase

        return CrashMonitorUpdate(
            newCrashMultipliers = newlyRecorded,
            status = status,
            monitoredCrashCount = monitoredCrashCount,
            latestCrashMultiplier = latestCrashMultiplier,
            summary = summary
        )
    }

    private sealed interface RibbonAlignment {
        data object Unchanged : RibbonAlignment
        data class TailExtendedOrScroll(val reason: String) : RibbonAlignment
        data object DisjointJump : RibbonAlignment
        data class NewHead(val shift: Int) : RibbonAlignment
    }

    private fun alignRibbon(previous: List<Double>, current: List<Double>): RibbonAlignment {
        if (previous.size == current.size && previous.indices.all { approxEq(previous[it], current[it]) }) {
            return RibbonAlignment.Unchanged
        }

        // Pure tail expansion/truncation when prefix matches (e.g. expanded history drawer or clipped rightmost badge)
        val minLen = min(previous.size, current.size)
        if (minLen >= 2 && (0 until minLen).all { approxEq(previous[it], current[it]) }) {
            return RibbonAlignment.TailExtendedOrScroll("History tail changed without new crash")
        }

        // Valid forward shift when 1..6 newest crash(es) pushed onto the left/head of the ribbon
        val maxShift = min(6, current.size - 1)
        for (shift in 1..maxShift) {
            val remainingCurrent = current.size - shift
            val maxOverlap = min(remainingCurrent, previous.size)
            val requiredOverlap = if (previous.size >= 3 && current.size >= 3) min(maxOverlap, 2) else 1
            if (maxOverlap >= requiredOverlap && requiredOverlap >= 1) {
                val matches = (0 until maxOverlap).all { idx ->
                    approxEq(current[shift + idx], previous[idx])
                }
                if (matches) {
                    return RibbonAlignment.NewHead(shift)
                }
            }
        }

        // Reverse scroll detection (older multipliers shifted into front)
        for (k in 1 until previous.size) {
            val remainingPrev = previous.size - k
            val maxOverlap = min(remainingPrev, current.size)
            val requiredOverlap = min(maxOverlap, 2)
            if (maxOverlap >= requiredOverlap && requiredOverlap >= 2) {
                val reverseMatches = (0 until maxOverlap).all { idx ->
                    approxEq(current[idx], previous[k + idx])
                }
                if (reverseMatches) {
                    return RibbonAlignment.TailExtendedOrScroll("Reverse scroll ignored")
                }
            }
        }

        return RibbonAlignment.DisjointJump
    }

    private fun approxEq(a: Double, b: Double): Boolean = abs(a - b) < 0.009

    private fun formatMult(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
