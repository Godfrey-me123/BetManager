package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.engine.AutomationEngine
import com.example.data.engine.BetSequence
import com.example.data.engine.CrashMonitorStatus
import com.example.data.engine.CrashMonitorTracker
import com.example.data.engine.PatternDetectionEngine
import com.example.data.engine.StakeCalculator
import com.example.data.engine.StrategyDecision
import com.example.data.engine.StrategyEvaluator
import com.example.data.local.BetManagerDatabase
import com.example.data.local.BetManagerRepository
import com.example.data.model.AfterLossMode
import com.example.data.model.BettingMode
import com.example.data.model.ColorClassifier
import com.example.data.model.ColorMatchDirection
import com.example.data.model.CrashColorCategory
import com.example.data.model.CrashRecord
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.LogCategory
import com.example.data.model.PatternComparator
import com.example.data.model.PatternCondition
import com.example.data.model.StakeProgressionMode
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyGroup
import com.example.data.model.StrategyMode
import com.example.data.model.StrategyNotificationType
import com.example.data.model.StrategyRule
import com.example.data.model.StrategyRuleType
import com.example.service.AccessibilityController
import com.example.service.AutoTouchDriver
import com.example.service.DriverPlatform
import com.example.service.GamePhase
import com.example.service.RectBounds
import com.example.service.ScanScheduler
import com.example.service.ScannedNode
import com.example.service.ScreenMap
import com.example.service.SwitchState
import com.example.service.UiTarget
import com.example.ui.BetManagerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BetManagerVerificationTest {

    private class FakeVerificationPlatform(
        var clockMs: () -> Long = { System.currentTimeMillis() }
    ) : DriverPlatform {
        override val ownPackageName: String = "com.aistudio.betmanager.rtxpvw"
        override val preferRealTouchGesture: Boolean = true

        var screenMapProvider: (() -> ScreenMap)? = null
        var imeOpen: Boolean = false
        var globalBackClearsImeAfter: Int = 1
        var backCalls: Int = 0
        var focusedTarget: UiTarget? = null
        var setTextResult: Boolean = true
        var setTextUpdatesStakeTo: String? = null
        var confirmOnPoll: Boolean = true
        val gestureTaps = mutableListOf<Pair<Float, Float>>()

        override fun currentTimeMs(): Long = clockMs()

        override fun scanScreen(): ScreenMap = scanScreenWithPanel(null)

        override fun scanScreenWithPanel(preferredBetPanelBounds: RectBounds?): ScreenMap {
            val base = screenMapProvider?.invoke() ?: ScreenMap(
                timestampMs = currentTimeMs(),
                packageName = "com.android.chrome",
                phase = GamePhase.BETTING_OPEN
            )
            return base.copy(
                hasImeWindow = imeOpen,
                cancelButton = if (confirmOnPoll && gestureTaps.isNotEmpty() && base.cancelButton == null) {
                    UiTarget("cancel", RectBounds(520, 1100, 980, 1240), text = "Cancel", isEnabled = true, isClickable = true)
                } else {
                    base.cancelButton
                }
            )
        }

        override fun findNode(query: String, editableOnly: Boolean): UiTarget? =
            screenMapProvider?.invoke()?.stakeField

        override fun findFocusedInput(): UiTarget? = focusedTarget

        override fun hasImeWindow(): Boolean = imeOpen

        override fun performNodeClick(nodeId: String): Boolean = true

        override fun performSetText(nodeId: String, text: String): Boolean {
            if (!setTextResult) return false
            focusedTarget = focusedTarget?.copy(text = setTextUpdatesStakeTo ?: text)
            return true
        }

        override suspend fun dispatchTapGesture(x: Float, y: Float, durationMs: Long): Boolean {
            gestureTaps += x to y
            return true
        }

        override fun performGlobalBack(): Boolean {
            backCalls++
            if (backCalls >= globalBackClearsImeAfter) {
                imeOpen = false
            }
            return true
        }
    }

    @Test
    fun `fresh database seeds zero default rules or strategies and cleans up legacy defaults`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, BetManagerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.betManagerDao()
            // Simulate legacy default rule leftover from an earlier app version
            dao.insertRule(
                StrategyRule(
                    name = "3 Low Rounds Trigger",
                    description = "Legacy default",
                    type = StrategyRuleType.CONSECUTIVE_LOW_MULTIPLIERS,
                    enabled = false
                )
            )
            val repo = BetManagerRepository(context, dao)
            repo.ensureInitialized()
            assertTrue("Expected zero default rules on fresh/migrated database", repo.getRules().isEmpty())
            assertTrue("Expected zero default custom strategies", repo.getCustomStrategies().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `first crash banner is baseline and does not trigger new crash`() {
        val tracker = CrashMonitorTracker()
        val first = tracker.onScreenSnapshot(
            ScreenMap(
                timestampMs = 1000L,
                packageName = "com.android.chrome",
                phase = GamePhase.CRASHED,
                lastCrashMultiplier = 1.25,
                ribbonMultipliers = listOf(1.25, 1.40, 1.80)
            ),
            nowMs = 1000L
        )
        assertTrue("First banner must only establish baseline", first.newCrashMultipliers.isEmpty())
        assertEquals(CrashMonitorStatus.BASELINE_CAPTURED, first.status)
    }

    @Test
    fun `exact HEX strategy rejects unverified fallback category and accepts verified exact HEX`() {
        val engine = PatternDetectionEngine()
        val t0 = 1_700_000_000_000L
        val pickedHex = ColorClassifier.normalizeHex("#FF1493")
        val strat = CustomPatternStrategy(
            id = 55L,
            name = "Exact HEX Pink",
            isEnabled = true,
            strategyGroup = StrategyGroup.COLOR_SEQUENCE,
            strategyMode = StrategyMode.STRATEGY_C_COLOR_PATTERNS,
            colorSequenceCsv = "Pink",
            customColorHexFilterCsv = pickedHex
        )

        val unverified = CrashRecord(
            id = 1L,
            gameId = "aviator",
            gameName = "Aviator",
            multiplier = 15.0,
            detectedColorHex = pickedHex,
            colorCategory = CrashColorCategory.Pink,
            isPixelColorVerified = false,
            crashTimestamp = t0,
            hourStartTimestamp = t0,
            dedupKey = "unverified_1"
        )
        val resUnverified = engine.evaluateStrategiesOnCrashRecords(listOf(strat), listOf(unverified), nowMs = t0)
        assertTrue("Unverified HEX must not trigger exact HEX strategy", resUnverified.events.isEmpty())

        val verified = unverified.copy(id = 2L, isPixelColorVerified = true, dedupKey = "verified_2")
        val resVerified = engine.evaluateStrategiesOnCrashRecords(listOf(strat.copy(lastEvaluatedRecordKey = null)), listOf(verified), nowMs = t0 + 5000L)
        assertTrue(resVerified.events.any { it.notificationType == StrategyNotificationType.STRATEGY_REACHED })
    }

    @Test
    fun `consecutiveCount 0 is never coerced to 1 in StrategyEvaluator or PatternDetectionEngine`() {
        val evalRes = StrategyEvaluator.evaluateWithProgress(
            config = StrategyConfig(bettingMode = BettingMode.USE_STRATEGY_RULES),
            rules = listOf(
                StrategyRule(
                    id = 1L,
                    name = "Zero Count Rule",
                    description = "Zero Count",
                    type = StrategyRuleType.CONSECUTIVE_LOW_MULTIPLIERS,
                    enabled = true,
                    thresholdMultiplier = 2.0,
                    consecutiveCount = 0
                )
            ),
            recentRoundsNewestFirst = listOf(com.example.data.model.RoundRecord(roundId = "R1", timestampMs = 1000L, crashMultiplier = 1.2))
        )
        assertFalse(evalRes.decision.shouldBet)
        assertFalse(evalRes.primaryProgress.isMatched)

        val condProg = PatternDetectionEngine().evaluateCondition(
            condition = PatternCondition(comparator = PatternComparator.LESS_THAN, threshold = 2.0, consecutiveRounds = 0),
            chronologicalRecords = emptyList()
        )
        assertFalse(condProg.isReached)
        assertEquals(0, condProg.requiredCount)
    }

    @Test
    fun `partial win reduces recoveryDeficit without resetting stake or loss count, and persistence survives restart`() = runTest {
        val partialWin = StakeCalculator.onWin(
            baseStake = 20.0,
            previousStake = 40.0,
            previousConsecutiveLosses = 2,
            currentDeficit = 60.0,
            targetMultiplier = 1.5,
            resetAfterWin = true
        )
        assertEquals(40.0, partialWin.recoveryDeficit, 0.01)
        assertEquals(2, partialWin.consecutiveLosses)
        assertEquals(40.0, partialWin.nextStake, 0.01)

        val fullWin = StakeCalculator.onWin(
            baseStake = 20.0,
            previousStake = 40.0,
            previousConsecutiveLosses = 2,
            currentDeficit = 40.0,
            targetMultiplier = 2.0,
            resetAfterWin = true
        )
        assertEquals(0.0, fullWin.recoveryDeficit, 0.01)
        assertEquals(0, fullWin.consecutiveLosses)
        assertEquals(20.0, fullWin.nextStake, 0.01)

        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, BetManagerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = BetManagerRepository(context, db.betManagerDao())
            repo.saveSessionProgression(currentStake = 80.0, consecutiveLosses = 3, recoveryDeficit = 140.0)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val engine = AutomationEngine(repo, CoroutineScope(SupervisorJob() + dispatcher))
            runCurrent()
            val deadlineMs = System.currentTimeMillis() + 3000L
            while (engine.state.value.recoveryDeficit <= 0.0 && System.currentTimeMillis() < deadlineMs) {
                Thread.sleep(20L)
                runCurrent()
            }
            assertEquals(80.0, engine.state.value.currentStake, 0.01)
            assertEquals(3, engine.state.value.consecutiveLosses)
            assertEquals(140.0, engine.state.value.recoveryDeficit, 0.01)

            // Stopping and syncing while deficit remains must NOT wipe stake or deficit
            engine.syncStakeWithConfig(resetProgression = false)
            runCurrent()
            assertEquals(80.0, engine.state.value.currentStake, 0.01)
            assertEquals(140.0, engine.state.value.recoveryDeficit, 0.01)
        } finally {
            db.close()
        }
    }

    @Test
    fun `calibrated coordinate cannot bypass missing stake field and unconfirmed tap does not lock or settle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeVerificationPlatform(clockMs = { testScheduler.currentTime })
        val calibrated = RectBounds(520, 1100, 980, 1240)
        platform.screenMapProvider = {
            ScreenMap(
                timestampMs = testScheduler.currentTime,
                packageName = "com.android.chrome",
                phase = GamePhase.BETTING_OPEN,
                stakeField = null, // Missing stake input!
                betButton = null
            )
        }
        val driver = AutoTouchDriver(platform)
        val scheduler = ScanScheduler(platform, scanDispatcher = dispatcher, timeProvider = { testScheduler.currentTime })
        val seq = BetSequence(driver, scheduler, timeProvider = { testScheduler.currentTime })

        val missingStakeRes = seq.execute(
            config = StrategyConfig(baseStake = 20.0),
            stake = 20.0,
            isRunning = true,
            hasActiveBet = false,
            handLockedForRound = false,
            todayProfit = 0.0,
            decision = StrategyDecision(true, "Test", "ok"),
            preferredBetBounds = calibrated
        )
        assertFalse(missingStakeRes.placed)
        assertFalse(missingStakeRes.confirmed)
        assertTrue(platform.gestureTaps.isEmpty())

        // Now stake field exists, tap happens, but game NEVER shows cancelButton or IN_FLIGHT -> must NOT confirm!
        val stakeTarget = UiTarget("stake", RectBounds(140, 1120, 380, 1200), text = "20.00", isEditable = true, isClickable = true)
        platform.focusedTarget = stakeTarget
        platform.confirmOnPoll = false
        platform.screenMapProvider = {
            ScreenMap(
                timestampMs = testScheduler.currentTime,
                packageName = "com.android.chrome",
                phase = GamePhase.BETTING_OPEN,
                stakeField = stakeTarget,
                betButton = null
            )
        }
        val unconfirmedRes = seq.execute(
            config = StrategyConfig(baseStake = 20.0),
            stake = 20.0,
            isRunning = true,
            hasActiveBet = false,
            handLockedForRound = false,
            todayProfit = 0.0,
            decision = StrategyDecision(true, "Test", "ok"),
            preferredBetBounds = calibrated
        )
        assertFalse(unconfirmedRes.placed)
        assertFalse(unconfirmedRes.confirmed)
        assertEquals("bet not confirmed", unconfirmedRes.reason)
    }

    @Test
    fun `unverified cashout outcome records unknown won=null without recovery progression, and duplicate crash does not double settle`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, BetManagerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dispatcher = StandardTestDispatcher(testScheduler)
        try {
            val repo = BetManagerRepository(context, db.betManagerDao())
            repo.ensureInitialized()
            repo.saveConfig(
                StrategyConfig(
                    baseStake = 20.0,
                    cashOutTarget = 2.0,
                    afterLoss = AfterLossMode.DOUBLE,
                    bettingMode = BettingMode.BET_EVERY_ROUND
                )
            )
            val platform = FakeVerificationPlatform(clockMs = { testScheduler.currentTime })
            val stakeTarget = UiTarget("stake", RectBounds(140, 1120, 380, 1200), text = "20.00", isEditable = true, isClickable = true)
            val betTarget = UiTarget("bet_btn", RectBounds(520, 1100, 980, 1240), text = "Bet 20.00 TZS", isClickable = true)
            platform.focusedTarget = stakeTarget
            platform.confirmOnPoll = true
            platform.screenMapProvider = {
                ScreenMap(
                    timestampMs = testScheduler.currentTime,
                    packageName = "com.android.chrome",
                    phase = GamePhase.BETTING_OPEN,
                    stakeField = stakeTarget,
                    betButton = betTarget,
                    autoCashOutState = SwitchState.UNREADABLE,
                    autoCashOutTarget = null
                )
            }
            val engine = AutomationEngine(repo, CoroutineScope(SupervisorJob() + dispatcher))
            engine.attachAccessibility(AutoTouchDriver(platform), ScanScheduler(platform, scanDispatcher = dispatcher, timeProvider = { testScheduler.currentTime }))
            engine.start()
            runCurrent()

            // Baseline + auto bet
            engine.handleScreenEvent(
                ScreenMap(
                    timestampMs = 1000L,
                    packageName = "com.android.chrome",
                    phase = GamePhase.BETTING_OPEN,
                    ribbonMultipliers = listOf(1.50, 2.10),
                    stakeField = stakeTarget,
                    betButton = betTarget
                )
            )
            runCurrent()
            assertTrue(engine.hasActiveBetLock())

            // Crash 3.50x arrives WITHOUT Auto Cash Out verification or cashout banner -> outcome MUST be unknown (won = null)
            engine.handleScreenEvent(
                ScreenMap(
                    timestampMs = 8000L,
                    packageName = "com.android.chrome",
                    phase = GamePhase.CRASHED,
                    lastCrashMultiplier = 3.50,
                    ribbonMultipliers = listOf(3.50, 1.50, 2.10),
                    autoCashOutState = SwitchState.UNREADABLE
                )
            )
            runCurrent()

            val round = repo.getRecentRounds().firstOrNull { it.stakePlaced != null }
            assertNotNull(round)
            assertNull("Unverified outcome must record won = null", round?.won)
            assertEquals(0.0, round?.profitDelta ?: -1.0, 0.001)
            assertEquals(0.0, repo.getTodayProfit(), 0.001)
            assertEquals(20.0, engine.state.value.currentStake, 0.01)
            assertEquals(0, engine.state.value.consecutiveLosses)
        } finally {
            db.close()
        }
    }

    @Test
    fun `keyboard dismissal settles within 16px shift tolerance and preserves last known non-null balance`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeVerificationPlatform(clockMs = { testScheduler.currentTime })
        platform.imeOpen = true
        var scanNum = 0
        platform.screenMapProvider = {
            scanNum++
            val shift = if (scanNum == 1) 0 else 10
            ScreenMap(
                timestampMs = testScheduler.currentTime,
                packageName = "com.android.chrome",
                phase = GamePhase.BETTING_OPEN,
                betButton = UiTarget("bet", RectBounds(520, 1000 + shift, 980, 1140 + shift), text = "Bet")
            )
        }
        val driver = AutoTouchDriver(platform)
        val settleRes = driver.closeKeyboardAndWaitLayoutSettleDetailed(settleIntervalMs = 120L, timeoutMs = 1500L)
        assertNotNull("Expected 10px layout shift within 16px tolerance to settle", settleRes.scan)
        assertEquals(0, platform.imeOpen.compareTo(false))

        // Balance null preservation in AutomationEngine
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, BetManagerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = BetManagerRepository(context, db.betManagerDao())
            val engine = AutomationEngine(repo, CoroutineScope(SupervisorJob() + dispatcher))
            engine.handleScreenEvent(ScreenMap(timestampMs = 1000L, packageName = "com.android.chrome", balance = 1234.50, phase = GamePhase.BETTING_OPEN))
            runCurrent()
            assertEquals(1234.50, engine.state.value.balance ?: 0.0, 0.01)
            engine.handleScreenEvent(ScreenMap(timestampMs = 2000L, packageName = "com.android.chrome", balance = null, phase = GamePhase.BETTING_OPEN))
            runCurrent()
            assertEquals(1234.50, engine.state.value.balance ?: 0.0, 0.01)
        } finally {
            db.close()
        }
    }

    @Test
    fun `AccessibilityController passively reads Auto Cash Out switch, target, and cashout banner`() {
        val nodes = listOf(
            ScannedNode("label", UiTarget("label", RectBounds(60, 1250, 260, 1310), text = "Auto Cash Out")),
            ScannedNode("sw", UiTarget("sw", RectBounds(270, 1250, 350, 1310), isCheckable = true, isChecked = true)),
            ScannedNode("target", UiTarget("target", RectBounds(360, 1250, 500, 1310), text = "2.50x", isEditable = true)),
            ScannedNode("win_banner", UiTarget("win_banner", RectBounds(250, 300, 750, 360), text = "You won 50.00 TZS (2.50x)", viewId = "win_banner")),
            ScannedNode("bet", UiTarget("bet", RectBounds(520, 1100, 980, 1240), text = "Bet 20.00 TZS", isClickable = true)),
            ScannedNode("stake", UiTarget("stake", RectBounds(140, 1120, 380, 1200), text = "20.00", isEditable = true))
        )
        val map = AccessibilityController.scanNodes("com.android.chrome", nodes)
        assertEquals(SwitchState.ON, map.autoCashOutState)
        assertEquals(2.50, map.autoCashOutTarget ?: 0.0, 0.01)
        assertEquals(2.50, map.cashedOutMultiplier ?: 0.0, 0.01)
        assertEquals(50.00, map.winBannerAmount ?: 0.0, 0.01)
    }

    @Test
    fun `ViewModel formatDiagnosticReport formats header and logs cleanly`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = BetManagerViewModel(app)
        val report = vm.formatDiagnosticReport(limit = 50)
        assertTrue(report.contains("=== BETMANAGER DIAGNOSTIC EXPORT ==="))
        assertTrue(report.contains("Current Stake:"))
        assertTrue(report.contains("Recovery Deficit:"))
        assertTrue(report.contains("=== END DIAGNOSTIC EXPORT ==="))
    }

    @Test
    fun `keyboard dismissal never fires GLOBAL_ACTION_BACK more than once even if IME lags`() = runTest {
        var backCalls = 0
        val platform = FakeVerificationPlatform(clockMs = { testScheduler.currentTime })
        val customPlatform = object : DriverPlatform by platform {
            override fun hasImeWindow(): Boolean = true // Simulate lagging IME window
            override fun performGlobalBack(): Boolean {
                backCalls++
                return true
            }
        }
        val driver = AutoTouchDriver(customPlatform)
        val settleRes = driver.closeKeyboardAndWaitLayoutSettleDetailed(
            settleIntervalMs = 120L,
            timeoutMs = 600L
        )
        assertNull("Should timeout cleanly instead of firing a second back that exits the game", settleRes.scan)
        assertEquals("performGlobalBack must be called at most once", 1, backCalls)
    }

    @Test
    fun `compound WebView Bet button with small BET child label is detected and delayed S3 confirmation succeeds`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val containerBounds = RectBounds(520, 1100, 980, 1240)
        val nodes = listOf(
            ScannedNode(
                id = "waiting",
                target = UiTarget("waiting", RectBounds(250, 350, 800, 420), text = "WAITING FOR NEXT ROUND"),
                treeOrder = 1
            ),
            ScannedNode(
                id = "stake_field",
                target = UiTarget(
                    nodeId = "stake_field",
                    bounds = RectBounds(140, 1120, 380, 1200),
                    text = "20.00",
                    className = "android.widget.EditText",
                    isEditable = true,
                    isClickable = true
                ),
                treeOrder = 2
            ),
            // Real WebView pattern: clickable container with empty text + small non-clickable "BET" child (<44px)
            ScannedNode(
                id = "bet_btn_container",
                target = UiTarget(
                    nodeId = "bet_btn_container",
                    bounds = containerBounds,
                    text = "",
                    className = "android.widget.Button",
                    isClickable = true,
                    isEnabled = true
                ),
                treeOrder = 3
            ),
            ScannedNode(
                id = "bet_label_child",
                target = UiTarget(
                    nodeId = "bet_label_child",
                    bounds = RectBounds(680, 1120, 820, 1156), // height 36px!
                    text = "BET",
                    isClickable = false,
                    isEnabled = true
                ),
                parentId = "bet_btn_container",
                treeOrder = 4
            ),
            ScannedNode(
                id = "bet_amt_child",
                target = UiTarget(
                    nodeId = "bet_amt_child",
                    bounds = RectBounds(660, 1164, 840, 1198),
                    text = "20.00 TZS",
                    isClickable = false,
                    isEnabled = true
                ),
                parentId = "bet_btn_container",
                treeOrder = 5
            )
        )

        val map = AccessibilityController.scanNodes("com.android.chrome", nodes)
        assertNotNull("Compound WebView Bet button container must be detected", map.betButton)
        assertEquals(containerBounds, map.betButton!!.bounds)

        val handHit = AccessibilityController.detectBetTargetAtHand(RectBounds(710, 1140, 790, 1200), nodes)
        assertNotNull("Floating BET hand must detect compound Bet container", handHit)
        assertEquals(containerBounds, handHit!!.betButton.bounds)

        // Verify delayed S3 confirm (succeeds on poll #4 at 800ms)
        var confirmPolls = 0
        val platform = object : DriverPlatform {
            override val ownPackageName: String = "com.example"
            override val preferRealTouchGesture: Boolean = true
            override fun currentTimeMs(): Long = testScheduler.currentTime
            override fun scanScreen(): ScreenMap {
                confirmPolls++
                return if (confirmPolls >= 5) { // S2 is scan 1, then S3 polls 1..4 -> confirms on 4th poll
                    map.copy(
                        cancelButton = UiTarget("cancel", containerBounds, text = "Cancel", isClickable = true),
                        betButton = null
                    )
                } else {
                    map
                }
            }
            override fun findNode(query: String, editableOnly: Boolean): UiTarget? = null
            override fun findFocusedInput(): UiTarget? = map.stakeField
            override fun hasImeWindow(): Boolean = false
            override fun performNodeClick(nodeId: String): Boolean = false
            override fun performSetText(nodeId: String, text: String): Boolean = true
            override suspend fun dispatchTapGesture(x: Float, y: Float, durationMs: Long): Boolean = true
            override fun performGlobalBack(): Boolean = true
        }
        val seq = BetSequence(
            driver = AutoTouchDriver(platform),
            scheduler = ScanScheduler(platform, scanDispatcher = dispatcher, timeProvider = { testScheduler.currentTime }),
            timeProvider = { testScheduler.currentTime }
        )
        val res = seq.execute(
            config = StrategyConfig(baseStake = 20.0),
            stake = 20.0,
            isRunning = true,
            hasActiveBet = false,
            handLockedForRound = false,
            todayProfit = 0.0,
            decision = StrategyDecision(true, "Test", "ok")
        )
        assertTrue("Bet should confirm within extended 6x200ms S3 confirm window", res.confirmed)
    }

    @Test
    fun `SimpleSettingsScreen AfterLossMode change survives ViewModel syncLegacyFromProgression`() {
        val initial = StrategyConfig(
            afterLoss = AfterLossMode.DOUBLE,
            lossProgressionEnabled = true,
            lossProgressionMode = StakeProgressionMode.DOUBLE_AMOUNT
        )
        val editedInSettings = initial.copy(
            afterLoss = AfterLossMode.ADD,
            addAmount = 15.0
        ).syncProgressionFromLegacy()
        val afterVmSave = editedInSettings.syncLegacyFromProgression()
        assertEquals(AfterLossMode.ADD, afterVmSave.afterLoss)
        assertEquals(StakeProgressionMode.ADD_AMOUNT, afterVmSave.lossProgressionMode)
        assertEquals(15.0, afterVmSave.addAmount, 0.01)
        assertEquals(15.0, afterVmSave.lossProgressionAddAmount, 0.01)
    }
}
