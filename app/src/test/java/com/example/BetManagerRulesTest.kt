package com.example

import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.engine.AutomationEngine
import com.example.data.engine.BetSequence
import com.example.data.engine.CrashMonitorStatus
import com.example.data.engine.CrashMonitorTracker
import com.example.data.engine.PatternProgress
import com.example.data.engine.PatternSignalStage
import com.example.data.engine.StakeCalculator
import com.example.data.engine.StrategyDecision
import com.example.data.engine.StrategyEvaluator
import com.example.data.local.BetManagerDatabase
import com.example.data.local.BetManagerRepository
import com.example.data.model.AfterLossMode
import com.example.data.model.BettingMode
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyRule
import com.example.data.model.StrategyRuleType
import com.example.service.AccessibilityController
import com.example.service.AutoTouchDriver
import com.example.service.DetectedHandTarget
import com.example.service.DriverPlatform
import com.example.service.GamePhase
import com.example.service.PatternAlertManager
import com.example.service.RectBounds
import com.example.service.ScanScheduler
import com.example.service.ScannedNode
import com.example.service.ScreenMap
import com.example.service.UiTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BetManagerRulesTest {

    private class FakeDriverPlatform(
        var clockMs: () -> Long = { System.currentTimeMillis() },
        override val preferRealTouchGesture: Boolean = false
    ) : DriverPlatform {
        override val ownPackageName: String = "com.aistudio.betmanager.rtxpvw"

        var nodes: MutableList<ScannedNode> = mutableListOf()
        var focusedNodeId: String? = null
        var imeVisible: Boolean = false
        var delayedReadBackAfterMs: Long = 0L
        var forceWrongReadBackText: String? = null
        var moveBetBoundsOnKeyboardClose: RectBounds? = null
        var confirmOnPollNumber: Int = 1

        var scanCount: Int = 0
        var pasteCount: Int = 0
        val clickedNodeIds = mutableListOf<String>()
        val gestureTaps = mutableListOf<Pair<Float, Float>>()
        val setTextCalls = mutableListOf<Pair<String, String>>()
        private var lastSetTextAtMs: Long = -1L
        private var pendingSetTextValue: String = ""
        private var pendingSetNodeId: String = ""
        private var postBetScanPolls: Int = 0
        private var clickCheckOffset: Int = 0
        private var tapCheckOffset: Int = 0

        fun simulateNewRoundResetCancel() {
            clickCheckOffset = clickedNodeIds.size
            tapCheckOffset = gestureTaps.size
            postBetScanPolls = 0
            nodes.removeAll { it.id == "cancel_btn" }
        }

        override fun currentTimeMs(): Long = clockMs()

        override fun scanScreen(): ScreenMap = scanScreenWithPanel(null)

        override fun scanScreenWithPanel(preferredBetPanelBounds: RectBounds?): ScreenMap {
            scanCount++
            // Apply delayed read-back if configured (Test T2)
            if (pendingSetNodeId.isNotBlank() &&
                delayedReadBackAfterMs > 0L &&
                (currentTimeMs() - lastSetTextAtMs) >= delayedReadBackAfterMs
            ) {
                updateNodeText(pendingSetNodeId, pendingSetTextValue)
            }

            val betNodeIds = setOf("bet_btn_top", "bet_btn_bottom")
            val clickedBetId = clickedNodeIds.drop(clickCheckOffset).lastOrNull { it in betNodeIds }
            val tappedBetNode = gestureTaps.drop(tapCheckOffset).lastOrNull()?.let { (x, y) ->
                nodes.firstOrNull {
                    it.id in betNodeIds && it.target.bounds.containsPoint(x, y)
                }
            }
            if (clickedBetId != null || (tappedBetNode != null && postBetScanPolls >= 0)) {
                postBetScanPolls++
                if (postBetScanPolls >= confirmOnPollNumber &&
                    nodes.none { it.id == "cancel_btn" }
                ) {
                    val refBounds = tappedBetNode?.target?.bounds
                        ?: nodes.firstOrNull { it.id == clickedBetId }?.target?.bounds
                        ?: RectBounds(520, 1100, 980, 1240)
                    nodes.add(
                        ScannedNode(
                            id = "cancel_btn",
                            target = UiTarget(
                                nodeId = "cancel_btn",
                                bounds = refBounds,
                                text = "Cancel",
                                isEnabled = true,
                                isClickable = true,
                                isVisible = true
                            ),
                            treeOrder = 99
                        )
                    )
                }
            }

            return AccessibilityController.scanNodes(
                packageName = "com.android.chrome",
                nodes = nodes.toList(),
                hasImeWindow = imeVisible,
                nowMs = currentTimeMs(),
                preferredBetPanelBounds = preferredBetPanelBounds
            )
        }

        override fun detectBetTargetAt(handBounds: RectBounds): DetectedHandTarget? =
            AccessibilityController.detectBetTargetAtHand(handBounds, nodes.toList())

        override fun findNode(query: String, editableOnly: Boolean): UiTarget? =
            AutoTouchDriver.findBestMatchingTarget(
                nodes.map { it.target },
                query,
                editableOnly
            )

        override fun findFocusedInput(): UiTarget? {
            if (pendingSetNodeId.isNotBlank() &&
                delayedReadBackAfterMs > 0L &&
                (currentTimeMs() - lastSetTextAtMs) >= delayedReadBackAfterMs
            ) {
                updateNodeText(pendingSetNodeId, pendingSetTextValue)
            }
            return nodes.firstOrNull { it.id == focusedNodeId }?.target
        }

        override fun hasImeWindow(): Boolean = imeVisible

        override fun performNodeClick(nodeId: String): Boolean {
            val idx = nodes.indexOfFirst { it.id == nodeId }
            if (idx < 0) return false
            val target = nodes[idx].target
            if (!target.isClickable) return false

            clickedNodeIds += nodeId
            if (target.isEditable) {
                focusedNodeId = nodeId
                imeVisible = true
            }
            return true
        }

        override fun performSetText(nodeId: String, text: String): Boolean {
            val idx = nodes.indexOfFirst { it.id == nodeId }
            if (idx < 0) return false
            setTextCalls += nodeId to text
            lastSetTextAtMs = currentTimeMs()
            pendingSetNodeId = nodeId
            pendingSetTextValue = forceWrongReadBackText ?: text

            if (delayedReadBackAfterMs <= 0L) {
                updateNodeText(nodeId, pendingSetTextValue)
            }
            return true
        }

        override suspend fun dispatchTapGesture(x: Float, y: Float, durationMs: Long): Boolean {
            gestureTaps += x to y
            val hit = nodes.firstOrNull {
                val b = it.target.bounds
                x >= b.left && x <= b.right && y >= b.top && y <= b.bottom
            }
            if (hit != null && hit.target.isEditable) {
                focusedNodeId = hit.id
                imeVisible = true
            }
            return true
        }

        override fun performGlobalBack(): Boolean {
            if (imeVisible) {
                imeVisible = false
                moveBetBoundsOnKeyboardClose?.let { newBounds ->
                    val idx = nodes.indexOfFirst { it.id == "bet_btn_top" }
                    if (idx >= 0) {
                        nodes[idx] = nodes[idx].copy(target = nodes[idx].target.copy(bounds = newBounds))
                    }
                }
            }
            return true
        }

        private fun updateNodeText(nodeId: String, newText: String) {
            val idx = nodes.indexOfFirst { it.id == nodeId }
            if (idx >= 0) {
                nodes[idx] = nodes[idx].copy(target = nodes[idx].target.copy(text = newText))
            }
        }
    }

    private fun buildBetPawaTree(
        betClickable: Boolean = true,
        betEnabled: Boolean = true,
        stakeValue: String = "10.00",
        includeSecondPanel: Boolean = false,
        planeFlying: Boolean = false
    ): MutableList<ScannedNode> {
        val list = mutableListOf(
            ScannedNode(
                id = "site_url",
                target = UiTarget("site_url", RectBounds(40, 40, 700, 100), text = "betpawa.co.tz/aviator", viewId = "url_bar"),
                treeOrder = 1
            ),
            ScannedNode(
                id = "balance",
                target = UiTarget("balance", RectBounds(720, 40, 1040, 100), text = "1500.00 TZS", viewId = "balance_text"),
                treeOrder = 2
            ),
            ScannedNode(
                id = "minus_btn",
                target = UiTarget("minus_btn", RectBounds(60, 1120, 130, 1200), text = "-", isClickable = true),
                treeOrder = 6
            ),
            ScannedNode(
                id = "stake_field",
                target = UiTarget(
                    nodeId = "stake_field",
                    bounds = RectBounds(140, 1120, 380, 1200),
                    text = stakeValue,
                    className = "android.widget.EditText",
                    isEditable = true,
                    isClickable = true
                ),
                treeOrder = 7
            ),
            ScannedNode(
                id = "plus_btn",
                target = UiTarget("plus_btn", RectBounds(390, 1120, 460, 1200), text = "+", isClickable = true),
                treeOrder = 8
            ),
            ScannedNode(
                id = "bet_btn_top",
                target = UiTarget(
                    nodeId = "bet_btn_top",
                    bounds = RectBounds(520, 1100, 980, 1240),
                    text = "Bet\n10.00 TZS",
                    className = "android.widget.Button",
                    isEnabled = betEnabled,
                    isClickable = betClickable
                ),
                treeOrder = 9
            )
        )

        if (planeFlying) {
            list += ScannedNode(
                id = "live_mult",
                target = UiTarget("live_mult", RectBounds(350, 420, 700, 520), text = "1.75x", viewId = "live_multiplier"),
                treeOrder = 10
            )
        } else {
            list += ScannedNode(
                id = "waiting_phase",
                target = UiTarget("waiting_phase", RectBounds(300, 420, 750, 500), text = "WAITING FOR NEXT ROUND"),
                treeOrder = 10
            )
        }

        if (includeSecondPanel) {
            list += listOf(
                ScannedNode(
                    id = "minus_btn_2",
                    target = UiTarget("minus_btn_2", RectBounds(60, 1520, 130, 1600), text = "-", isClickable = true),
                    treeOrder = 20
                ),
                ScannedNode(
                    id = "stake_field_2",
                    target = UiTarget(
                        nodeId = "stake_field_2",
                        bounds = RectBounds(140, 1520, 380, 1600),
                        text = "50.00",
                        className = "android.widget.EditText",
                        isEditable = true,
                        isClickable = true
                    ),
                    treeOrder = 21
                ),
                ScannedNode(
                    id = "bet_btn_bottom",
                    target = UiTarget(
                        nodeId = "bet_btn_bottom",
                        bounds = RectBounds(520, 1500, 980, 1640),
                        text = "Bet\n50.00 TZS",
                        className = "android.widget.Button",
                        isEnabled = true,
                        isClickable = true
                    ),
                    treeOrder = 22
                )
            )
        }
        return list
    }

    @Test
    fun `T2 type flow click focus SET_TEXT delayed read-back 250ms accepted without paste`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeDriverPlatform(clockMs = { testScheduler.currentTime })
        platform.nodes = buildBetPawaTree(stakeValue = "10.00")
        platform.delayedReadBackAfterMs = 250L

        val driver = AutoTouchDriver(platform)
        val scheduler = ScanScheduler(platform, scanDispatcher = dispatcher, timeProvider = { testScheduler.currentTime })
        val sequence = BetSequence(driver, scheduler, timeProvider = { testScheduler.currentTime })

        val result = sequence.execute(
            config = StrategyConfig(baseStake = 40.0),
            stake = 40.0,
            isRunning = true,
            hasActiveBet = false,
            handLockedForRound = false,
            todayProfit = 0.0,
            decision = StrategyDecision(true, "Test", "ok")
        )

        assertTrue(result.placed)
        assertTrue(result.confirmed)
        assertEquals(0, platform.pasteCount)
        assertEquals(1, platform.setTextCalls.size)
        assertEquals("40.00", platform.setTextCalls.first().second)
    }

    @Test
    fun `T3 read-back stays wrong aborts bet with reason and never taps Bet`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeDriverPlatform(clockMs = { testScheduler.currentTime })
        platform.nodes = buildBetPawaTree(stakeValue = "10.00")
        platform.forceWrongReadBackText = "11.00"

        val driver = AutoTouchDriver(platform)
        val scheduler = ScanScheduler(platform, scanDispatcher = dispatcher, timeProvider = { testScheduler.currentTime })
        val sequence = BetSequence(driver, scheduler, timeProvider = { testScheduler.currentTime })

        val result = sequence.execute(
            config = StrategyConfig(baseStake = 40.0),
            stake = 40.0,
            isRunning = true,
            hasActiveBet = false,
            handLockedForRound = false,
            todayProfit = 0.0,
            decision = StrategyDecision(true, "Test", "ok")
        )

        assertFalse(result.placed)
        assertTrue(result.reason.contains("stake could not be set"))
        assertTrue(result.reason.contains("11.00"))
        assertFalse(platform.clickedNodeIds.contains("bet_btn_top"))
        assertTrue(platform.gestureTaps.isEmpty())
    }

    @Test
    fun `T4 Bet button not clickable but enabled is tapped by gesture`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeDriverPlatform(clockMs = { testScheduler.currentTime })
        platform.nodes = buildBetPawaTree(betClickable = false, betEnabled = true)

        val driver = AutoTouchDriver(platform)
        val scheduler = ScanScheduler(platform, scanDispatcher = dispatcher, timeProvider = { testScheduler.currentTime })
        val sequence = BetSequence(driver, scheduler, timeProvider = { testScheduler.currentTime })

        val result = sequence.execute(
            config = StrategyConfig(baseStake = 20.0),
            stake = 20.0,
            isRunning = true,
            hasActiveBet = false,
            handLockedForRound = false,
            todayProfit = 0.0,
            decision = StrategyDecision(true, "Test", "ok")
        )

        assertTrue(result.placed)
        assertTrue(result.confirmed)
        assertFalse(platform.clickedNodeIds.contains("bet_btn_top"))
        assertEquals(1, platform.gestureTaps.size)
        assertEquals(750f, platform.gestureTaps.first().first, 1f)
        assertEquals(1170f, platform.gestureTaps.first().second, 1f)
    }

    @Test
    fun `T5 Bet button disabled is refused`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeDriverPlatform(clockMs = { testScheduler.currentTime })
        platform.nodes = buildBetPawaTree(betEnabled = false)

        val driver = AutoTouchDriver(platform)
        val scheduler = ScanScheduler(platform, scanDispatcher = dispatcher, timeProvider = { testScheduler.currentTime })
        val sequence = BetSequence(driver, scheduler, timeProvider = { testScheduler.currentTime })

        val result = sequence.execute(
            config = StrategyConfig(baseStake = 20.0),
            stake = 20.0,
            isRunning = true,
            hasActiveBet = false,
            handLockedForRound = false,
            todayProfit = 0.0,
            decision = StrategyDecision(true, "Test", "ok")
        )

        assertFalse(result.placed)
        assertTrue(result.reason.contains("disabled", ignoreCase = true))
        assertTrue(platform.gestureTaps.isEmpty())
    }

    @Test
    fun `T6 layout moves after keyboard closes and Bet bounds re-found before tap`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeDriverPlatform(clockMs = { testScheduler.currentTime })
        platform.nodes = buildBetPawaTree(betClickable = false, betEnabled = true)
        val movedBounds = RectBounds(520, 900, 980, 1040)
        platform.moveBetBoundsOnKeyboardClose = movedBounds

        val driver = AutoTouchDriver(platform)
        val scheduler = ScanScheduler(platform, scanDispatcher = dispatcher, timeProvider = { testScheduler.currentTime })
        val sequence = BetSequence(driver, scheduler, timeProvider = { testScheduler.currentTime })

        val result = sequence.execute(
            config = StrategyConfig(baseStake = 20.0),
            stake = 20.0,
            isRunning = true,
            hasActiveBet = false,
            handLockedForRound = false,
            todayProfit = 0.0,
            decision = StrategyDecision(true, "Test", "ok")
        )

        assertTrue(result.placed)
        assertEquals(1, platform.gestureTaps.size)
        // Must tap the NEW moved center (y = 970f), not old (1170f)
        assertEquals(970f, platform.gestureTaps.first().second, 1f)
    }

    @Test
    fun `T8 StakeCalculator double add and keep progression`() {
        // Double: base 10, loss, loss, win -> 10, 20, 40, 10
        val d1 = StakeCalculator.onLoss(10.0, 10.0, AfterLossMode.DOUBLE, 5.0, 0, 0)
        assertEquals(20.0, d1.nextStake, 0.001)
        val d2 = StakeCalculator.onLoss(d1.nextStake, 10.0, AfterLossMode.DOUBLE, 5.0, d1.consecutiveLosses, 0)
        assertEquals(40.0, d2.nextStake, 0.001)
        val dWin = StakeCalculator.onWin(10.0)
        assertEquals(10.0, dWin.nextStake, 0.001)

        // Add 5: 10, 15, 20, 10
        val a1 = StakeCalculator.onLoss(10.0, 10.0, AfterLossMode.ADD, 5.0, 0, 0)
        assertEquals(15.0, a1.nextStake, 0.001)
        val a2 = StakeCalculator.onLoss(a1.nextStake, 10.0, AfterLossMode.ADD, 5.0, a1.consecutiveLosses, 0)
        assertEquals(20.0, a2.nextStake, 0.001)
        val aWin = StakeCalculator.onWin(10.0)
        assertEquals(10.0, aWin.nextStake, 0.001)

        // Keep: 10, 10, 10
        val k1 = StakeCalculator.onLoss(10.0, 10.0, AfterLossMode.KEEP, 5.0, 0, 0)
        assertEquals(10.0, k1.nextStake, 0.001)
        val k2 = StakeCalculator.onLoss(k1.nextStake, 10.0, AfterLossMode.KEEP, 5.0, k1.consecutiveLosses, 0)
        assertEquals(10.0, k2.nextStake, 0.001)

        // Stop after 2 losses -> resets to base & signals stop
        val stopRes = StakeCalculator.onLoss(20.0, 10.0, AfterLossMode.DOUBLE, 5.0, 1, 2)
        assertTrue(stopRes.shouldStop)
        assertEquals(10.0, stopRes.nextStake, 0.001)
    }

    @Test
    fun `T9 scan schedule performs at most 12 heartbeat scans in 60 simulated seconds`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeDriverPlatform(clockMs = { testScheduler.currentTime })
        platform.nodes = buildBetPawaTree()

        val scheduler = ScanScheduler(
            platform = platform,
            scanDispatcher = dispatcher,
            heartbeatIntervalMs = 5000L,
            timeProvider = { testScheduler.currentTime }
        )

        scheduler.startHeartbeat(
            scope = this,
            onHeartbeatScan = {}
        )

        // Simulate noisy content-changed events every second
        repeat(60) {
            scheduler.onWindowEvent(
                packageName = "com.android.chrome",
                isWindowStateChanged = false,
                isImeVisible = false
            )
            advanceTimeBy(1000L)
            runCurrent()
        }

        scheduler.stopHeartbeat()
        assertEquals(12, scheduler.totalScansExecuted)
    }

    @Test
    fun `T10 phase gating blocks bet when plane is flying even if Bet button is visible`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeDriverPlatform(clockMs = { testScheduler.currentTime })
        platform.nodes = buildBetPawaTree(planeFlying = true)

        val driver = AutoTouchDriver(platform)
        val scheduler = ScanScheduler(platform, scanDispatcher = dispatcher, timeProvider = { testScheduler.currentTime })
        val sequence = BetSequence(driver, scheduler, timeProvider = { testScheduler.currentTime })

        val result = sequence.execute(
            config = StrategyConfig(baseStake = 20.0),
            stake = 20.0,
            isRunning = true,
            hasActiveBet = false,
            handLockedForRound = false,
            todayProfit = 0.0,
            decision = StrategyDecision(true, "Test", "ok")
        )

        assertFalse(result.placed)
        assertTrue(result.reason.contains(GamePhase.IN_FLIGHT.name))
        assertTrue(platform.clickedNodeIds.isEmpty())
        assertTrue(platform.gestureTaps.isEmpty())
    }

    @Test
    fun `T11 two Bet panels selects top panel only`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeDriverPlatform(clockMs = { testScheduler.currentTime })
        platform.nodes = buildBetPawaTree(includeSecondPanel = true)

        val map = AccessibilityController.scanNodes("com.android.chrome", platform.nodes)
        assertEquals(2, map.panelCount)
        assertEquals(1, map.usingHandIndex)
        assertEquals("bet_btn_top", map.betButton?.nodeId)
        assertEquals("stake_field", map.stakeField?.nodeId)

        val driver = AutoTouchDriver(platform)
        val scheduler = ScanScheduler(platform, scanDispatcher = dispatcher, timeProvider = { testScheduler.currentTime })
        val sequence = BetSequence(driver, scheduler, timeProvider = { testScheduler.currentTime })

        val result = sequence.execute(
            config = StrategyConfig(baseStake = 20.0),
            stake = 20.0,
            isRunning = true,
            hasActiveBet = false,
            handLockedForRound = false,
            todayProfit = 0.0,
            decision = StrategyDecision(true, "Test", "ok")
        )

        assertTrue(result.placed)
        assertTrue(platform.clickedNodeIds.contains("bet_btn_top"))
        assertFalse(platform.clickedNodeIds.contains("bet_btn_bottom"))
    }

    @Test
    fun `T12 dragged BET hand detects Bet button under hand and runs verified edit and bet`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeDriverPlatform(clockMs = { testScheduler.currentTime })
        platform.nodes = buildBetPawaTree(includeSecondPanel = true)

        val driver = AutoTouchDriver(platform)
        val scheduler = ScanScheduler(platform, scanDispatcher = dispatcher, timeProvider = { testScheduler.currentTime })
        val sequence = BetSequence(driver, scheduler, timeProvider = { testScheduler.currentTime })

        // Dropping hand on empty area detects nothing
        val miss = driver.detectBetTargetAt(RectBounds(10, 200, 110, 300))
        assertNull(miss)

        // Dragging hand onto panel 2's Bet button (520..980, 1500..1640) detects panel 2 + stake_field_2
        val handOverBottomBet = RectBounds(680, 1520, 800, 1640)
        val detected = driver.detectBetTargetAt(handOverBottomBet)
        assertNotNull(detected)
        assertEquals("bet_btn_bottom", detected!!.betButton.nodeId)
        assertEquals("stake_field_2", detected.stakeField?.nodeId)
        assertEquals(2, detected.panelIndex)

        val result = sequence.execute(
            config = StrategyConfig(baseStake = 35.0),
            stake = 35.0,
            isRunning = false,
            hasActiveBet = false,
            handLockedForRound = false,
            todayProfit = 0.0,
            decision = StrategyDecision(true, "BET Hand Drag", detected.matchReason),
            isManualTrigger = true,
            preferredBetBounds = detected.betButton.bounds
        )

        assertTrue(result.placed)
        assertTrue(result.confirmed)
        assertEquals(2, result.usingHandIndex)
        assertEquals("stake_field_2" to "35.00", platform.setTextCalls.first())
        assertTrue(platform.clickedNodeIds.contains("bet_btn_bottom"))
        assertFalse(platform.clickedNodeIds.contains("bet_btn_top"))
    }

    @Test
    fun `T13 crash monitor captures baseline cleanly and records only new current crashes without duplicates`() {
        val tracker = CrashMonitorTracker()

        fun ribbonMap(
            ts: Long,
            phase: GamePhase,
            ribbon: List<Double>,
            lastCrash: Double? = null,
            liveMult: Double? = null
        ): ScreenMap = ScreenMap(
            timestampMs = ts,
            packageName = "com.android.chrome",
            phase = phase,
            ribbonMultipliers = ribbon,
            lastCrashMultiplier = lastCrash,
            liveMultiplier = liveMult,
            betButton = UiTarget("bet_btn", RectBounds(520, 1100, 980, 1240), text = "Bet")
        )

        // 1. Initial scan shows existing history ribbon -> baseline captured, 0 old crashes imported
        val initUpdate = tracker.onScreenSnapshot(
            ribbonMap(1000L, GamePhase.BETTING_OPEN, listOf(2.45, 1.32, 8.10, 1.15))
        )
        assertTrue(initUpdate.newCrashMultipliers.isEmpty())
        assertEquals(CrashMonitorStatus.BASELINE_CAPTURED, initUpdate.status)
        assertEquals(0, initUpdate.monitoredCrashCount)

        // 2. Same ribbon -> 0 new crashes
        val sameUpdate = tracker.onScreenSnapshot(
            ribbonMap(6000L, GamePhase.BETTING_OPEN, listOf(2.45, 1.32, 8.10, 1.15))
        )
        assertTrue(sameUpdate.newCrashMultipliers.isEmpty())

        // 3. History panel tail expansion (older items appear at end) -> 0 new crashes
        val expandUpdate = tracker.onScreenSnapshot(
            ribbonMap(11000L, GamePhase.BETTING_OPEN, listOf(2.45, 1.32, 8.10, 1.15, 3.50, 1.09))
        )
        assertTrue(expandUpdate.newCrashMultipliers.isEmpty())

        // 4. Live CRASHED banner appears at 1.48x -> records 1.48 ONCE
        val crashBanner = tracker.onScreenSnapshot(
            ribbonMap(16000L, GamePhase.CRASHED, listOf(2.45, 1.32, 8.10, 1.15), lastCrash = 1.48)
        )
        assertEquals(listOf(1.48), crashBanner.newCrashMultipliers)
        assertEquals(1, crashBanner.monitoredCrashCount)

        // 5. Ribbon shifts to prepend 1.48x moments later -> absorbed without duplicating 1.48
        val ribbonSynced = tracker.onScreenSnapshot(
            ribbonMap(17000L, GamePhase.BETTING_OPEN, listOf(1.48, 2.45, 1.32, 8.10, 1.15))
        )
        assertTrue(ribbonSynced.newCrashMultipliers.isEmpty())
        assertEquals(1, ribbonSynced.monitoredCrashCount)

        // 6. Next round finishes between scans -> ribbon shifts with 3.20x at head -> records ONLY current 3.20
        val nextCrash = tracker.onScreenSnapshot(
            ribbonMap(25000L, GamePhase.BETTING_OPEN, listOf(3.20, 1.48, 2.45, 1.32, 8.10))
        )
        assertEquals(listOf(3.20), nextCrash.newCrashMultipliers)
        assertEquals(2, nextCrash.monitoredCrashCount)
        assertEquals(3.20, nextCrash.latestCrashMultiplier ?: 0.0, 0.001)
    }

    @Test
    fun `T14 MainActivity ACTION_EXIT_APP finishes activity immediately`() {
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(MainActivity.ACTION_EXIT_APP)
        ).create()
        val activity = controller.get()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `T15 preferRealTouchGesture sends real coordinate gesture even on clickable Bet button`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeDriverPlatform(
            clockMs = { testScheduler.currentTime },
            preferRealTouchGesture = true
        )
        platform.nodes = buildBetPawaTree(betClickable = true, betEnabled = true, stakeValue = "10.00")

        val driver = AutoTouchDriver(platform)
        val scheduler = ScanScheduler(platform, scanDispatcher = dispatcher, timeProvider = { testScheduler.currentTime })
        val sequence = BetSequence(driver, scheduler, timeProvider = { testScheduler.currentTime })

        val result = sequence.execute(
            config = StrategyConfig(baseStake = 25.0),
            stake = 25.0,
            isRunning = true,
            hasActiveBet = false,
            handLockedForRound = false,
            todayProfit = 0.0,
            decision = StrategyDecision(true, "Auto BET Hand", "Bet every round"),
            preferredBetBounds = RectBounds(520, 1100, 980, 1240)
        )

        assertTrue(result.placed)
        assertTrue(result.confirmed)
        assertTrue(platform.gestureTaps.any { (x, y) -> x in 520f..980f && y in 1100f..1240f })
        assertEquals("stake_field" to "25.00", platform.setTextCalls.first())
    }

    @Test
    fun `T16 multiple patterns trigger and bet repeatedly without getting stuck locked after first bet`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(context, BetManagerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.betManagerDao()
            val repo = BetManagerRepository(context, dao)
            repo.saveConfig(StrategyConfig(baseStake = 20.0, bettingMode = BettingMode.USE_STRATEGY_RULES))
            dao.insertRules(
                listOf(
                    StrategyRule(
                        name = "Two Low X Pattern",
                        description = "Pattern <2.0x,<2.0x",
                        type = StrategyRuleType.CUSTOM_PATTERN,
                        enabled = true,
                        customPattern = "<2.0x,<2.0x",
                        priority = 1
                    )
                )
            )

            val dispatcher = StandardTestDispatcher(testScheduler)
            val engineScope = CoroutineScope(SupervisorJob() + dispatcher)
            val platform = FakeDriverPlatform(clockMs = { testScheduler.currentTime }, preferRealTouchGesture = true)
            platform.nodes = buildBetPawaTree(betClickable = true, betEnabled = true, stakeValue = "20.00")

            val driver = AutoTouchDriver(platform)
            val scheduler = ScanScheduler(platform, scanDispatcher = dispatcher, timeProvider = { testScheduler.currentTime })
            val engine = AutomationEngine(repo, engineScope)
            engine.attachAccessibility(driver, scheduler)
            engine.start()
            runCurrent()

            fun mapWithRibbon(ts: Long, phase: GamePhase, ribbon: List<Double>, hasCancel: Boolean = false): ScreenMap =
                ScreenMap(
                    timestampMs = ts,
                    packageName = "com.android.chrome",
                    phase = phase,
                    ribbonMultipliers = ribbon,
                    betButton = if (hasCancel) null else UiTarget("bet_btn_top", RectBounds(520, 1100, 980, 1240), text = "Bet\n20.00 TZS", isEnabled = true, isClickable = true),
                    cancelButton = if (hasCancel) UiTarget("cancel_btn", RectBounds(520, 1100, 980, 1240), text = "Cancel", isEnabled = true, isClickable = true) else null,
                    stakeField = UiTarget("stake_field", RectBounds(140, 1120, 380, 1200), text = "20.00", className = "android.widget.EditText", isEditable = true, isClickable = true)
                )

            fun betTapCount(): Int = platform.gestureTaps.count { (x, y) ->
                x in 520f..980f && y in 1100f..1240f
            }

            // Baseline capture: [3.10, 4.20] -> no bet yet
            engine.handleScreenEvent(mapWithRibbon(1000L, GamePhase.BETTING_OPEN, listOf(3.10, 4.20)))
            assertEquals(0, betTapCount())

            // Two fast low crashes arrive in ribbon [1.45, 1.80, 3.10, 4.20] -> pattern <2.0x,<2.0x matches -> BET #1
            engine.handleScreenEvent(mapWithRibbon(2000L, GamePhase.BETTING_OPEN, listOf(1.45, 1.80, 3.10, 4.20)))
            assertEquals(1, betTapCount())

            // Round settles (2.50x) and next rounds return with new pattern [1.20, 1.50, 2.50, 1.45, 1.80]
            platform.simulateNewRoundResetCancel()
            engine.handleScreenEvent(mapWithRibbon(10_000L, GamePhase.BETTING_OPEN, listOf(2.50, 1.45, 1.80, 3.10, 4.20)))
            // Next round has only [2.50, 1.45] -> not matching <2.0x,<2.0x -> still 1 tap
            assertEquals(1, betTapCount())

            // Two more low crashes arrive -> [1.20, 1.50, 2.50, 1.45, 1.80] -> MUST unlock & place BET #2!
            engine.handleScreenEvent(mapWithRibbon(16_000L, GamePhase.BETTING_OPEN, listOf(1.20, 1.50, 2.50, 1.45, 1.80)))
            assertEquals(2, betTapCount())
        } finally {
            db.close()
        }
    }

    @Test
    fun `T17 StrategyEvaluator pattern x suffix and Never Sleep repository persistence work correctly`() {
        assertTrue(StrategyEvaluator.matchesPatternToken("<2.0x", 1.45))
        assertFalse(StrategyEvaluator.matchesPatternToken("<2.0x", 2.10))
        assertTrue(StrategyEvaluator.matchesPatternToken(">=1.5x", 1.50))
        assertTrue(StrategyEvaluator.matchesPatternToken("LOW", 1.80))
        assertTrue(StrategyEvaluator.matchesPatternToken("HIGH", 12.0))

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(context, BetManagerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = BetManagerRepository(context, db.betManagerDao())
            repo.setKeepScreenAwakeEnabled(true)
            assertTrue(repo.isKeepScreenAwakeEnabled())
            assertTrue(repo.keepScreenAwakeFlow.value)

            repo.setKeepScreenAwakeEnabled(false)
            assertFalse(repo.isKeepScreenAwakeEnabled())
            assertFalse(repo.keepScreenAwakeFlow.value)
        } finally {
            db.close()
        }
    }

    @Test
    fun `T18 StrategyEvaluator detects PRE_SIGNAL at 1 crash away and HIGH_SIGNAL when fully met`() {
        val config = StrategyConfig(bettingMode = BettingMode.USE_STRATEGY_RULES)
        val rule3Lows = StrategyRule(
            id = 1L,
            name = "Three Consecutive Lows",
            description = "3 crashes < 2.0x",
            type = StrategyRuleType.CONSECUTIVE_LOW_MULTIPLIERS,
            thresholdMultiplier = 2.0,
            consecutiveCount = 3,
            enabled = true
        )

        // 1 crash < 2.0: stage = NONE (matched 1/3)
        val res1 = StrategyEvaluator.evaluateFromMultipliers(config, listOf(rule3Lows), listOf(1.30, 4.50))
        assertEquals(PatternSignalStage.NONE, res1.primaryProgress.signalStage)
        assertFalse(res1.decision.shouldBet)
        assertEquals(1, res1.primaryProgress.matchedCount)

        // 2 crashes < 2.0: stage = PRE_SIGNAL (matched 2/3 - exactly 1 away from high signal!)
        val res2 = StrategyEvaluator.evaluateFromMultipliers(config, listOf(rule3Lows), listOf(1.40, 1.30, 4.50))
        assertEquals(PatternSignalStage.PRE_SIGNAL, res2.primaryProgress.signalStage)
        assertFalse(res2.decision.shouldBet)
        assertEquals(2, res2.primaryProgress.matchedCount)
        assertTrue(res2.primaryProgress.statusText.contains("PRE-SIGNAL: 1 crash away"))

        // 3 crashes < 2.0: stage = HIGH_SIGNAL (matched 3/3 - exact time to bet!)
        val res3 = StrategyEvaluator.evaluateFromMultipliers(config, listOf(rule3Lows), listOf(1.25, 1.40, 1.30, 4.50))
        assertEquals(PatternSignalStage.HIGH_SIGNAL, res3.primaryProgress.signalStage)
        assertTrue(res3.decision.shouldBet)
        assertEquals(3, res3.primaryProgress.matchedCount)
        assertTrue(res3.primaryProgress.statusText.contains("PATTERN MET"))
    }

    @Test
    fun `T19 streak consumption prevents re-betting on passed patterns until streak resets`() {
        val config = StrategyConfig(bettingMode = BettingMode.USE_STRATEGY_RULES)
        val rule2Lows = StrategyRule(
            id = 2L,
            name = "Two Lows",
            description = "2 crashes < 2.0x",
            type = StrategyRuleType.CONSECUTIVE_LOW_MULTIPLIERS,
            thresholdMultiplier = 2.0,
            consecutiveCount = 2,
            enabled = true
        )

        // Pattern hits: [1.30, 1.40] -> matches!
        val res = StrategyEvaluator.evaluateFromMultipliers(config, listOf(rule2Lows), listOf(1.30, 1.40))
        assertTrue(res.primaryProgress.isMatched)
        assertFalse(res.primaryProgress.isConsumed)
        assertTrue(res.decision.shouldBet)

        val triggerSig = res.primaryProgress.triggerSignature
        assertTrue(triggerSig.isNotBlank())

        // Same streak continues with another low [1.10, 1.30, 1.40] but previous trigger signature is consumed
        val consumed = setOf(triggerSig)
        val resContinued = StrategyEvaluator.evaluateFromMultipliers(
            config,
            listOf(rule2Lows),
            listOf(1.10, 1.30, 1.40),
            consumedSignatures = consumed
        )
        // Must NOT bet again on the passed/active streak!
        assertFalse(resContinued.decision.shouldBet)
        assertTrue(resContinued.primaryProgress.statusText.contains("waiting for streak reset", ignoreCase = true) ||
            resContinued.primaryProgress.statusText.contains("broke streak", ignoreCase = true) ||
            resContinued.primaryProgress.statusText.contains("previously triggered", ignoreCase = true))
    }

    @Test
    fun `T20 BetManagerRepository persists pattern alert settings correctly`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(context, BetManagerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = BetManagerRepository(context, db.betManagerDao())

            // Default values are true
            assertTrue(repo.isAlarmHighSignalEnabled())
            assertTrue(repo.isPreSignalNotificationEnabled())
            assertTrue(repo.isAlertSoundEnabled())
            assertTrue(repo.isAlertVibrationEnabled())

            // Toggle them off
            repo.setAlarmHighSignalEnabled(false)
            repo.setPreSignalNotificationEnabled(false)
            repo.setAlertSoundEnabled(false)
            repo.setAlertVibrationEnabled(false)

            assertFalse(repo.isAlarmHighSignalEnabled())
            assertFalse(repo.isPreSignalNotificationEnabled())
            assertFalse(repo.isAlertSoundEnabled())
            assertFalse(repo.isAlertVibrationEnabled())

            assertFalse(repo.alarmHighSignalFlow.value)
            assertFalse(repo.preSignalNotificationFlow.value)
            assertFalse(repo.alertSoundFlow.value)
            assertFalse(repo.alertVibrationFlow.value)
        } finally {
            db.close()
        }
    }

    @Test
    fun `T21 Double Add and Keep after-loss progression settles accurately even when BETTING_OPEN precedes crash ribbon`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(context, BetManagerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.betManagerDao()
            val repo = BetManagerRepository(context, dao)
            repo.saveConfig(
                StrategyConfig(
                    baseStake = 20.0,
                    afterLoss = AfterLossMode.DOUBLE,
                    addAmount = 15.0,
                    stopAfterLosses = 0,
                    cashOutTarget = 2.0,
                    bettingMode = BettingMode.BET_EVERY_ROUND
                )
            )

            val dispatcher = StandardTestDispatcher(testScheduler)
            val engineScope = CoroutineScope(SupervisorJob() + dispatcher)
            val platform = FakeDriverPlatform(clockMs = { testScheduler.currentTime }, preferRealTouchGesture = true)
            platform.nodes = buildBetPawaTree(planeFlying = true, stakeValue = "20.00")

            val driver = AutoTouchDriver(platform)
            val scheduler = ScanScheduler(platform, scanDispatcher = dispatcher, timeProvider = { testScheduler.currentTime })
            val engine = AutomationEngine(repo, engineScope)
            engine.attachAccessibility(driver, scheduler)
            engine.start()
            runCurrent()

            // Switch game UI to BETTING_OPEN
            platform.nodes = buildBetPawaTree(betClickable = true, betEnabled = true, stakeValue = "20.00")

            fun mapWithRibbon(ts: Long, phase: GamePhase, ribbon: List<Double>): ScreenMap =
                ScreenMap(
                    timestampMs = ts,
                    packageName = "com.android.chrome",
                    phase = phase,
                    ribbonMultipliers = ribbon,
                    autoCashOutState = com.example.service.SwitchState.ON,
                    autoCashOutTarget = 2.0,
                    betButton = if (phase == GamePhase.BETTING_OPEN) {
                        UiTarget("bet_btn_top", RectBounds(520, 1100, 980, 1240), text = "Bet", isEnabled = true, isClickable = true)
                    } else null,
                    stakeField = UiTarget("stake_field", RectBounds(140, 1120, 380, 1200), text = "20.00", className = "android.widget.EditText", isEditable = true, isClickable = true)
                )

            // 1. Baseline & first auto bet at 20.00
            engine.handleScreenEvent(mapWithRibbon(1000L, GamePhase.BETTING_OPEN, listOf(3.10, 4.20)))
            assertEquals(20.0, engine.state.value.currentStake, 0.01)
            val diagLogs = dao.observeLogs().firstOrNull().orEmpty().joinToString(" | ") { "${it.category}:${it.message}" }
            val diagCfg = repo.getConfig()
            assertTrue(
                "Expected active bet lock; status='${engine.state.value.statusLine}', mode=${diagCfg.bettingMode}, isRunning=${engine.state.value.isRunning}, taps=${platform.gestureTaps.size}, scans=${platform.scanCount}, logs=[$diagLogs]",
                engine.hasActiveBetLock()
            )

            // 2. Plane flies, then BETTING_OPEN appears 1 scan BEFORE ribbon updates with crash 1.35x!
            platform.simulateNewRoundResetCancel()
            engine.handleScreenEvent(mapWithRibbon(4000L, GamePhase.IN_FLIGHT, listOf(3.10, 4.20)))
            engine.handleScreenEvent(mapWithRibbon(6000L, GamePhase.BETTING_OPEN, listOf(3.10, 4.20)))

            // 3. Now ribbon updates with 1.35x (< 2.00x loss) -> pendingSettlementBet settles -> stake MUST double to 40.0 & type 40.00!
            engine.handleScreenEvent(mapWithRibbon(7000L, GamePhase.BETTING_OPEN, listOf(1.35, 3.10, 4.20)))
            assertEquals(40.0, engine.state.value.currentStake, 0.01)
            assertEquals(1, engine.state.value.consecutiveLosses)
            assertEquals("40.00", platform.setTextCalls.last().second)

            // 4. Next round also crashes at 1.50x -> doubles again 40.0 -> 80.0 & types 80.00
            platform.simulateNewRoundResetCancel()
            engine.handleScreenEvent(mapWithRibbon(14_000L, GamePhase.BETTING_OPEN, listOf(1.50, 1.35, 3.10, 4.20)))
            assertEquals(80.0, engine.state.value.currentStake, 0.01)
            assertEquals(2, engine.state.value.consecutiveLosses)
            assertEquals("80.00", platform.setTextCalls.last().second)

            // 5. Next round wins at 2.40x (>= 2.0x) -> resets to base 20.0
            platform.simulateNewRoundResetCancel()
            engine.handleScreenEvent(mapWithRibbon(21_000L, GamePhase.BETTING_OPEN, listOf(2.40, 1.50, 1.35, 3.10)))
            assertEquals(20.0, engine.state.value.currentStake, 0.01)
            assertEquals(0, engine.state.value.consecutiveLosses)

            // 6. Verify ADD mode (20.0 + 15.0 = 35.0)
            repo.saveConfig(repo.getConfig().copy(afterLoss = AfterLossMode.ADD, addAmount = 15.0))
            engine.syncStakeWithConfig()
            runCurrent()
            platform.simulateNewRoundResetCancel()
            engine.handleScreenEvent(mapWithRibbon(28_000L, GamePhase.BETTING_OPEN, listOf(1.10, 2.40, 1.50, 1.35)))
            assertEquals(35.0, engine.state.value.currentStake, 0.01)
            assertEquals("35.00", platform.setTextCalls.last().second)

            // 7. Verify KEEP mode stays at baseStake (20.0) on loss
            repo.saveConfig(repo.getConfig().copy(afterLoss = AfterLossMode.KEEP))
            engine.syncStakeWithConfig(resetProgression = true)
            runCurrent()
            platform.simulateNewRoundResetCancel()
            engine.handleScreenEvent(mapWithRibbon(35_000L, GamePhase.BETTING_OPEN, listOf(1.05, 1.10, 2.40, 1.50)))
            assertEquals(20.0, engine.state.value.currentStake, 0.01)
        } finally {
            db.close()
        }
    }

    @Test
    fun `T22 PatternAlertManager and AutomationEngine cancelActiveAlarm silence active alarms immediately`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(context, BetManagerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = BetManagerRepository(context, db.betManagerDao())
            val dispatcher = StandardTestDispatcher(testScheduler)
            val engineScope = CoroutineScope(SupervisorJob() + dispatcher)
            val engine = AutomationEngine(repo, engineScope)
            val alertManager = PatternAlertManager(context, repo)
            engine.attachPatternAlertManager(alertManager)
            runCurrent()

            alertManager.processPatternProgress(
                progress = PatternProgress(
                    ruleName = "Three Consecutive Lows",
                    matchedCount = 3,
                    requiredCount = 3,
                    isMatched = true,
                    signalStage = PatternSignalStage.HIGH_SIGNAL,
                    statusText = "PATTERN MET",
                    triggerSignature = "sig_alarm_test"
                ),
                gameSite = "BetPawa"
            )
            runCurrent()

            assertTrue(alertManager.isAlarmActiveFlow.value)
            assertTrue(engine.state.value.isAlarmActive)
            assertNotNull(engine.state.value.activeAlarmTitle)

            engine.cancelActiveAlarm()
            runCurrent()

            assertFalse(alertManager.isAlarmActiveFlow.value)
            assertFalse(engine.state.value.isAlarmActive)
            assertNull(engine.state.value.activeAlarmTitle)
        } finally {
            db.close()
        }
    }
}
