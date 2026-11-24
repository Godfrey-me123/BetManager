package com.example.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * Lightweight node representation extracted from AccessibilityNodeInfo (or built in tests)
 * so scanning and disambiguation run cleanly off the main thread.
 */
data class ScannedNode(
    val id: String,
    val target: UiTarget,
    val parentId: String? = null,
    val treeOrder: Int = 0
)

/**
 * Scanning-only controller (Section 2.4, 2.5, 3, and Rule R2).
 * Never executes clicks, typing, or limit probing.
 */
object AccessibilityController {

    private val DECIMAL_MULTIPLIER_REGEX = Regex("(\\d+\\.\\d{1,2})\\s*x", RegexOption.IGNORE_CASE)
    private val ANY_MULTIPLIER_REGEX = Regex("(\\d+(?:\\.\\d+)?)\\s*x", RegexOption.IGNORE_CASE)

    private val IGNORED_PACKAGES = setOf(
        "com.android.systemui",
        "com.samsung.android.app.cocktailbarservice", // Samsung Edge panels
        "com.samsung.android.app.aodservice",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.swiftkey.swiftkeyconfigurator"
    )

    private val IGNORED_PACKAGE_SUBSTRINGS = listOf(
        "cocktailbar",
        "edgepanel",
        "inputmethod",
        "keyboard",
        "honeyboard",
        "systemui",
        "launcher"
    )

    fun isIgnoredSystemPackage(pkg: String?, ownPackage: String): Boolean {
        if (pkg.isNullOrBlank()) return true
        if (pkg == ownPackage) return true
        val lower = pkg.lowercase()
        if (lower in IGNORED_PACKAGES) return true
        return IGNORED_PACKAGE_SUBSTRINGS.any { lower.contains(it) }
    }

    fun captureNodesFromRoots(
        roots: List<AccessibilityNodeInfo>,
        ownPackage: String
    ): Pair<String, List<ScannedNode>> {
        val result = mutableListOf<ScannedNode>()
        var detectedPackage = ""
        var order = 0

        for (root in roots) {
            val pkg = root.packageName?.toString().orEmpty()
            if (isIgnoredSystemPackage(pkg, ownPackage)) continue
            if (detectedPackage.isBlank()) detectedPackage = pkg

            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, String?>>()
            queue.add(root to null)
            while (queue.isNotEmpty()) {
                val (node, parentId) = queue.removeFirst()
                val nodeId = "n_${order}"
                val rect = Rect().also { node.getBoundsInScreen(it) }
                val uiTarget = UiTarget(
                    nodeId = nodeId,
                    bounds = RectBounds.fromAndroidRect(rect),
                    text = node.text?.toString().orEmpty(),
                    contentDescription = node.contentDescription?.toString().orEmpty(),
                    hintText = node.hintText?.toString().orEmpty(),
                    viewId = node.viewIdResourceName?.substringAfter(":id/").orEmpty(),
                    className = node.className?.toString().orEmpty(),
                    isEditable = node.isEditable,
                    isEnabled = node.isEnabled,
                    isClickable = node.isClickable,
                    isVisible = node.isVisibleToUser,
                    isChecked = if (node.isCheckable) node.isChecked else null,
                    isCheckable = node.isCheckable,
                    isFocused = node.isFocused
                )
                result += ScannedNode(
                    id = nodeId,
                    target = uiTarget,
                    parentId = parentId,
                    treeOrder = order
                )
                order++
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        queue.add(child to nodeId)
                    }
                }
            }
        }
        return detectedPackage to result
    }

    fun scanNodes(
        packageName: String,
        nodes: List<ScannedNode>,
        hasImeWindow: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        preferredBetPanelBounds: RectBounds? = null
    ): ScreenMap {
        val visibleNodes = nodes.filter { it.target.isVisible && !it.target.bounds.isEmpty }
        if (visibleNodes.isEmpty()) {
            return ScreenMap(
                timestampMs = nowMs,
                packageName = packageName,
                hasImeWindow = hasImeWindow,
                conflictReason = "No visible game controls found"
            )
        }

        // Detect Bet buttons (exclude "Bet" tab headers by checking action wording or size/context).
        val betButtons = findBetActionButtons(visibleNodes)
            .sortedWith(compareBy<ScannedNode> { it.target.bounds.top }.thenBy { it.treeOrder })

        val matchedPreferred = if (preferredBetPanelBounds != null && betButtons.isNotEmpty()) {
            betButtons.firstOrNull {
                it.target.bounds.containsPoint(preferredBetPanelBounds.centerX, preferredBetPanelBounds.centerY) ||
                    it.target.bounds.overlaps(preferredBetPanelBounds)
            } ?: betButtons.minByOrNull { it.target.bounds.distanceSqTo(preferredBetPanelBounds) }
        } else {
            null
        }

        val selectedBetButton = matchedPreferred ?: betButtons.firstOrNull()
        val selectedIndex = if (selectedBetButton != null) {
            (betButtons.indexOfFirst { it.id == selectedBetButton.id }.takeIf { it >= 0 } ?: 0) + 1
        } else {
            1
        }

        val panelCount = betButtons.size.coerceAtLeast(
            if (visibleNodes.any { isStakeStepperMinus(it.target) }) 1 else 0
        )

        // Isolate the vertical band for the chosen Bet panel when multiple Bet panels exist
        val aboveBetBottom = betButtons
            .filter { selectedBetButton != null && it.target.bounds.bottom <= selectedBetButton.target.bounds.top }
            .maxOfOrNull { it.target.bounds.bottom }
        val belowBetTop = betButtons
            .filter { selectedBetButton != null && it.target.bounds.top >= selectedBetButton.target.bounds.bottom }
            .minOfOrNull { it.target.bounds.top }

        val panelMinY = if (selectedBetButton != null && aboveBetBottom != null) {
            (aboveBetBottom + selectedBetButton.target.bounds.top) / 2
        } else {
            Int.MIN_VALUE
        }
        val panelMaxY = if (selectedBetButton != null && belowBetTop != null) {
            (selectedBetButton.target.bounds.bottom + belowBetTop) / 2
        } else {
            Int.MAX_VALUE
        }

        val panelNodes = visibleNodes.filter {
            it.target.bounds.top in panelMinY until panelMaxY
        }

        val cancelButton = panelNodes.firstOrNull { isCancelButton(it.target) }?.target
        val autoTabButton = panelNodes.firstOrNull { isTabButton(it.target, "auto") }?.target
        val betTabButton = panelNodes.firstOrNull { isTabButton(it.target, "bet") }?.target

        val minusStepper = panelNodes.firstOrNull { isStakeStepperMinus(it.target) }?.target
        val plusStepper = panelNodes.firstOrNull { isStakeStepperPlus(it.target) }?.target

        val editableFields = panelNodes
            .map { it.target }
            .filter { it.isEditable || isNumericInputLike(it) }

        val stakeField = selectStakeField(
            editableFields = editableFields,
            betButton = selectedBetButton?.target,
            minusStepper = minusStepper,
            plusStepper = plusStepper
        )

        val siteUrl = detectSiteUrl(visibleNodes)
        val gameName = detectGameName(visibleNodes)
        val (balance, currency) = detectBalanceAndCurrency(visibleNodes, selectedBetButton?.target)
        val phaseAnalysis = detectPhaseAndMultipliers(
            visibleNodes = visibleNodes,
            betButton = selectedBetButton?.target,
            cancelButton = cancelButton,
            panelButtonsTopY = betButtons.firstOrNull()?.target?.bounds?.top
                ?: stakeField?.bounds?.top
        )

        return ScreenMap(
            timestampMs = nowMs,
            packageName = packageName,
            gameName = gameName,
            siteUrl = siteUrl,
            balance = balance,
            currency = currency,
            phase = phaseAnalysis.phase,
            liveMultiplier = phaseAnalysis.liveMultiplier,
            lastCrashMultiplier = phaseAnalysis.lastCrashMultiplier,
            ribbonMultipliers = phaseAnalysis.ribbonMultipliers,
            betButton = selectedBetButton?.target,
            cancelButton = cancelButton,
            stakeField = stakeField,
            stakeMinusButton = minusStepper,
            stakePlusButton = plusStepper,
            autoTabButton = autoTabButton,
            betTabButton = betTabButton,
            panelCount = panelCount,
            usingHandIndex = selectedIndex,
            hasImeWindow = hasImeWindow,
            conflictReason = null
        )
    }

    /**
     * Detects the game Bet button lying under or overlapping the user's dragged BET hand.
     * Never returns inert text containers, EditTexts, tabs, "2x" buttons, or oversized layout wrappers.
     */
    fun detectBetTargetAtHand(
        handBounds: RectBounds,
        nodes: List<ScannedNode>
    ): DetectedHandTarget? {
        if (handBounds.isEmpty) return null
        val visibleNodes = nodes.filter { it.target.isVisible && !it.target.bounds.isEmpty }
        if (visibleNodes.isEmpty()) return null

        val maxScreenWidth = visibleNodes.maxOfOrNull { it.target.bounds.right } ?: 1080
        val maxScreenHeight = visibleNodes.maxOfOrNull { it.target.bounds.bottom } ?: 1920
        val maxRootArea = maxScreenWidth.toLong() * maxScreenHeight.toLong()

        val sortedExplicitBetButtons = findBetActionButtons(visibleNodes)
            .sortedWith(compareBy<ScannedNode> { it.target.bounds.top }.thenBy { it.treeOrder })

        val handCenterX = handBounds.centerX
        val handCenterY = handBounds.centerY

        // 1) Explicit Bet action button containing the hand center or overlapping the hand bounds
        val explicitHit = sortedExplicitBetButtons
            .filter {
                it.target.bounds.containsPoint(handCenterX, handCenterY) ||
                    it.target.bounds.overlaps(handBounds)
            }
            .maxWithOrNull(
                compareBy<ScannedNode>(
                    { if (it.target.bounds.containsPoint(handCenterX, handCenterY)) 1 else 0 },
                    { it.target.bounds.overlapArea(handBounds) },
                    { -it.target.bounds.area }
                )
            )

        if (explicitHit != null) {
            val idx = (sortedExplicitBetButtons.indexOfFirst { it.id == explicitHit.id }.takeIf { it >= 0 } ?: 0) + 1
            val stakeField = resolveStakeFieldForBetButton(explicitHit.target, visibleNodes, sortedExplicitBetButtons)
            return DetectedHandTarget(
                betButton = explicitHit.target,
                stakeField = stakeField,
                panelIndex = idx,
                matchReason = "Bet button detected (${explicitHit.target.primaryText().replace('\n', ' ')})"
            )
        }

        // 2) Fallback: valid enabled game action button directly under the hand center (excluding EditTexts, tabs, cancel, cash, auto)
        val candidatesUnderHand = visibleNodes.filter { node ->
            val t = node.target
            val lower = t.primaryText().trim().lowercase()
            val idLower = t.viewId.lowercase()
            val oversized = t.bounds.width > (maxScreenWidth * 0.85f) ||
                (maxRootArea > 0 && t.bounds.area > (maxRootArea * 0.35f))
            val excludedText = lower == "2x" || lower == "x2" || lower == "×2" ||
                lower.contains("cancel") || lower.contains("cash") ||
                lower.contains("auto") || lower.contains("history") ||
                lower == "-" || lower == "+" || lower == "−"
            val isUrl = idLower.contains("url_bar") || idLower.contains("omnibox")
            val isButtonLike = t.isClickable ||
                t.className.contains("Button", ignoreCase = true) ||
                idLower.contains("bet") ||
                lower.contains("bet")

            !oversized &&
                !t.isEditable &&
                !t.className.contains("EditText", ignoreCase = true) &&
                !isTabButton(t, "bet", visibleNodes) &&
                !isTabButton(t, "auto", visibleNodes) &&
                !excludedText &&
                !isUrl &&
                isButtonLike &&
                t.bounds.height >= 36 &&
                t.bounds.width >= 48 &&
                (t.bounds.containsPoint(handCenterX, handCenterY) || t.bounds.overlapArea(handBounds) > (handBounds.area / 5))
        }

        val fallbackHit = candidatesUnderHand.minWithOrNull(
            compareBy<ScannedNode>(
                { if (it.target.bounds.containsPoint(handCenterX, handCenterY)) 0 else 1 },
                { it.target.bounds.area }
            )
        ) ?: return null

        val stakeField = resolveStakeFieldForBetButton(fallbackHit.target, visibleNodes, sortedExplicitBetButtons)
        return DetectedHandTarget(
            betButton = fallbackHit.target,
            stakeField = stakeField,
            panelIndex = 1,
            matchReason = "Target control detected under hand"
        )
    }

    private fun resolveStakeFieldForBetButton(
        betButton: UiTarget,
        visibleNodes: List<ScannedNode>,
        allBetButtons: List<ScannedNode>
    ): UiTarget? {
        val aboveBottom = allBetButtons
            .filter { it.target.bounds.bottom <= betButton.bounds.top }
            .maxOfOrNull { it.target.bounds.bottom }
        val belowTop = allBetButtons
            .filter { it.target.bounds.top >= betButton.bounds.bottom }
            .minOfOrNull { it.target.bounds.top }

        val minY = aboveBottom?.let { (it + betButton.bounds.top) / 2 } ?: (betButton.bounds.top - 220)
        val maxY = belowTop?.let { (betButton.bounds.bottom + it) / 2 } ?: (betButton.bounds.bottom + 140)

        val panelNodes = visibleNodes.filter { it.target.bounds.top in minY until maxY }
        val minusStepper = panelNodes.firstOrNull { isStakeStepperMinus(it.target) }?.target
        val plusStepper = panelNodes.firstOrNull { isStakeStepperPlus(it.target) }?.target
        val editableFields = panelNodes
            .map { it.target }
            .filter { it.isEditable || isNumericInputLike(it) }

        return selectStakeField(editableFields, betButton, minusStepper, plusStepper)
            ?: selectStakeField(
                visibleNodes.map { it.target }.filter { it.isEditable || isNumericInputLike(it) },
                betButton,
                minusStepper,
                plusStepper
            )
    }

    /**
     * Section 2.4 (Rule R2):
     * Stake box = editable field in same row as "-" / "+" steppers, or closest editable field
     * to the left of the Bet button.
     */
    fun selectStakeField(
        editableFields: List<UiTarget>,
        betButton: UiTarget?,
        minusStepper: UiTarget?,
        plusStepper: UiTarget?
    ): UiTarget? {
        if (editableFields.isEmpty()) return null

        // 1) Same row as "-" / "+" steppers
        val stepperCandidates = editableFields.filter { field ->
            val alignedMinus = minusStepper != null && field.bounds.verticalOverlap(minusStepper.bounds) > 0
            val alignedPlus = plusStepper != null && field.bounds.verticalOverlap(plusStepper.bounds) > 0
            alignedMinus || alignedPlus
        }
        val bySteppers = stepperCandidates.firstOrNull()

        // 2) Closest editable field to the left of the Bet button
        val byBetLeft = if (betButton != null) {
            editableFields
                .filter { it.bounds.centerX <= betButton.bounds.centerX }
                .minByOrNull { it.bounds.distanceSqTo(betButton.bounds) }
                ?: editableFields.minByOrNull { it.bounds.distanceSqTo(betButton.bounds) }
        } else {
            null
        }

        return bySteppers ?: byBetLeft ?: editableFields.firstOrNull()
    }

    private fun findBetActionButtons(nodes: List<ScannedNode>): List<ScannedNode> {
        return nodes.filter { node ->
            val target = node.target
            val txt = target.primaryText().trim()
            val lower = txt.lowercase()
            val viewLower = target.viewId.lowercase()
            val isExplicitTab = isTabButton(target, "bet", nodes) || isTabButton(target, "auto", nodes)
            if (isExplicitTab) return@filter false

            val looksLikeBetText = lower == "bet" && (
                target.bounds.height >= 44 ||
                    viewLower.contains("bet") ||
                    target.isClickable ||
                    target.className.contains("Button", ignoreCase = true)
                ) ||
                lower == "place bet" ||
                lower == "bet now" ||
                (lower.startsWith("bet\n") || lower.startsWith("bet ")) &&
                (lower.contains("tzs") || lower.contains("usd") || lower.contains("kes") ||
                    lower.contains("ugx") || lower.contains("ngn") || lower.contains("ghs") ||
                    lower.contains("zmw") || Regex("\\d").containsMatchIn(lower))

            val looksLikeBetId = viewLower.contains("bet_button") || viewLower == "btn_bet"
            (looksLikeBetText || looksLikeBetId) && !lower.contains("auto bet")
        }
    }

    private fun isTabButton(
        target: UiTarget,
        label: String,
        allNodes: List<ScannedNode> = emptyList()
    ): Boolean {
        val txt = target.primaryText().trim().lowercase()
        val id = target.viewId.lowercase()
        if (txt != label) return false
        if (id.contains("tab")) return true
        // Only treat small "bet"/"auto" labels as tabs when an "auto" / "bet" tab neighbor sits on the same row
        val peerLabel = if (label == "bet") "auto" else "bet"
        val hasRowPeer = allNodes.any { other ->
            other.target.nodeId != target.nodeId &&
                other.target.primaryText().trim().lowercase() == peerLabel &&
                abs(other.target.bounds.centerY - target.bounds.centerY) <= 40f
        }
        return hasRowPeer || target.bounds.height in 1..43
    }

    private fun isCancelButton(target: UiTarget): Boolean {
        val txt = target.primaryText().trim().lowercase()
        val id = target.viewId.lowercase()
        return txt == "cancel" || txt.startsWith("cancel\n") || id.contains("cancel")
    }

    private fun isStakeStepperMinus(target: UiTarget): Boolean {
        val txt = target.primaryText().trim()
        val id = target.viewId.lowercase()
        return txt == "-" || txt == "−" || id.contains("minus") || id.contains("decrease")
    }

    private fun isStakeStepperPlus(target: UiTarget): Boolean {
        val txt = target.primaryText().trim()
        val id = target.viewId.lowercase()
        return txt == "+" || id.contains("plus") || id.contains("increase")
    }

    private fun isNumericInputLike(target: UiTarget): Boolean {
        if (!target.className.contains("EditText", ignoreCase = true)) return false
        return parseNumericValue(target.primaryText()) != null || target.hintText.isNotBlank()
    }

    private fun detectSiteUrl(nodes: List<ScannedNode>): String {
        val urlNode = nodes.firstOrNull {
            val t = it.target.primaryText().lowercase()
            t.contains("betpawa") || t.contains("sportybet") || it.target.viewId.contains("url_bar")
        }
        val text = urlNode?.target?.primaryText().orEmpty()
        return when {
            text.contains("betpawa", ignoreCase = true) -> "BetPawa"
            text.contains("sportybet", ignoreCase = true) -> "SportyBet"
            text.isNotBlank() && text.contains(".") -> text.substringBefore("/").take(28)
            else -> "BetPawa"
        }
    }

    private fun detectGameName(nodes: List<ScannedNode>): String {
        val hasAviator = nodes.any {
            it.target.primaryText().contains("aviator", ignoreCase = true)
        }
        return if (hasAviator) "Aviator" else "Crash Game"
    }

    private fun detectBalanceAndCurrency(
        nodes: List<ScannedNode>,
        betButton: UiTarget?
    ): Pair<Double?, String> {
        val currencies = listOf("TZS", "KES", "UGX", "GHS", "ZMW", "NGN", "USD", "EUR")
        var detectedCurrency = "TZS"
        if (betButton != null) {
            val betText = betButton.primaryText().uppercase()
            currencies.firstOrNull { betText.contains(it) }?.let { detectedCurrency = it }
        }

        val headerNodes = nodes.filter {
            it.target.bounds.top < (betButton?.bounds?.top ?: 500)
        }
        for (node in headerNodes) {
            val text = node.target.primaryText().trim()
            val upper = text.uppercase()
            val matchedCurrency = currencies.firstOrNull { upper.contains(it) }
            if (matchedCurrency != null && !upper.contains("BET")) {
                detectedCurrency = matchedCurrency
                val parsed = parseNumericValue(text)
                if (parsed != null) return parsed to detectedCurrency
            }
            if (node.target.viewId.contains("balance", ignoreCase = true)) {
                val parsed = parseNumericValue(text)
                if (parsed != null) return parsed to detectedCurrency
            }
        }
        return null to detectedCurrency
    }

    private data class PhaseMultiplierAnalysis(
        val liveMultiplier: Double?,
        val lastCrashMultiplier: Double?,
        val phase: GamePhase,
        val ribbonMultipliers: List<Double>
    )

    private data class MultNodeCandidate(
        val value: Double,
        val bounds: RectBounds,
        val orderIndex: Int,
        val isHistoryHinted: Boolean
    )

    private fun detectPhaseAndMultipliers(
        visibleNodes: List<ScannedNode>,
        betButton: UiTarget?,
        cancelButton: UiTarget?,
        panelButtonsTopY: Int?
    ): PhaseMultiplierAnalysis {
        var flewAway = false
        var waitingForNext = false
        var liveMultiplier: Double? = null
        var lastCrash: Double? = null

        val stageFloorY = panelButtonsTopY ?: betButton?.bounds?.top ?: Int.MAX_VALUE
        val multCandidates = mutableListOf<MultNodeCandidate>()

        for (node in visibleNodes) {
            val txt = node.target.primaryText().trim()
            val lower = txt.lowercase()
            val viewId = node.target.viewId.lowercase()

            if (lower.contains("flew away") || lower.contains("crashed")) {
                flewAway = true
                ANY_MULTIPLIER_REGEX.find(txt)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let {
                    lastCrash = it
                }
            }
            if (lower.contains("waiting for next round") || lower.contains("place your bet") ||
                lower.contains("betting open")
            ) {
                waitingForNext = true
            }

            if (node.target.isEditable || lower.contains("auto") || lower == "2x" || lower == "x2") {
                continue
            }

            // Explicit live multiplier node (keeps T10 & center live multiplier detection accurate)
            if (viewId.contains("live_mult") || viewId.contains("live_multiplier")) {
                val m = ANY_MULTIPLIER_REGEX.find(txt)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                if (m != null && m >= 1.0) {
                    if (flewAway) lastCrash = lastCrash ?: m else liveMultiplier = m
                }
                continue
            }

            // Skip multipliers below the betting controls (e.g., player bet table)
            if (node.target.bounds.centerY >= stageFloorY) continue

            val exactOrCompact = txt.length <= 14
            val match = if (exactOrCompact) DECIMAL_MULTIPLIER_REGEX.find(txt) else null
            val parsedVal = match?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                ?: if (viewId.contains("multiplier") || viewId.contains("history")) {
                    ANY_MULTIPLIER_REGEX.find(txt)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                } else {
                    null
                }

            if (parsedVal != null && parsedVal >= 1.0) {
                val historyHint = viewId.contains("history") || viewId.contains("ribbon") || viewId.contains("payout")
                multCandidates += MultNodeCandidate(
                    value = parsedVal,
                    bounds = node.target.bounds,
                    orderIndex = node.treeOrder,
                    isHistoryHinted = historyHint
                )
            }
        }

        // Group horizontal ribbon rows (>=2 chips on the same Y band in upper area)
        val ribbonMaxY = if (stageFloorY < Int.MAX_VALUE) (stageFloorY * 0.58f).toInt() else Int.MAX_VALUE
        val horizontalRibbonNodes = mutableSetOf<Int>()
        for (anchor in multCandidates) {
            val anchorCy = anchor.bounds.centerY
            if (!anchor.isHistoryHinted && anchorCy > ribbonMaxY) continue
            val rowMatches = multCandidates.filter { other ->
                abs(other.bounds.centerY - anchorCy) <= 45f &&
                    other.bounds.height <= 110 &&
                    (other.isHistoryHinted || other.bounds.centerY <= ribbonMaxY)
            }
            if (rowMatches.size >= 2 || anchor.isHistoryHinted) {
                horizontalRibbonNodes.addAll(rowMatches.map { it.orderIndex })
                if (anchor.isHistoryHinted) horizontalRibbonNodes.add(anchor.orderIndex)
            }
        }

        val ribbonSorted = multCandidates
            .filter { it.orderIndex in horizontalRibbonNodes }
            .sortedWith(compareBy<MultNodeCandidate>({ (it.bounds.centerY / 45f).toInt() }, { it.bounds.left }, { it.orderIndex }))

        val ribbonMultipliers = ribbonSorted.map { it.value }
        val ribbonBottomY = ribbonSorted.maxOfOrNull { it.bounds.bottom } ?: Int.MIN_VALUE

        // Central live multiplier: non-ribbon candidate below ribbon (or largest height)
        if (liveMultiplier == null && !flewAway) {
            val centerPool = multCandidates
                .filter { it.orderIndex !in horizontalRibbonNodes }
                .filter { ribbonBottomY == Int.MIN_VALUE || it.bounds.centerY > ribbonBottomY }
            liveMultiplier = centerPool.maxWithOrNull(
                compareBy<MultNodeCandidate>({ it.bounds.height }, { it.bounds.area })
            )?.value
        } else if (flewAway && lastCrash == null) {
            val centerPool = multCandidates
                .filter { it.orderIndex !in horizontalRibbonNodes }
                .filter { ribbonBottomY == Int.MIN_VALUE || it.bounds.centerY > ribbonBottomY }
            lastCrash = centerPool.maxWithOrNull(
                compareBy<MultNodeCandidate>({ it.bounds.height }, { it.bounds.area })
            )?.value ?: ribbonMultipliers.firstOrNull()
        }

        val phase = when {
            flewAway -> GamePhase.CRASHED
            liveMultiplier != null && !waitingForNext -> GamePhase.IN_FLIGHT
            waitingForNext || betButton != null || cancelButton != null -> GamePhase.BETTING_OPEN
            else -> GamePhase.UNKNOWN
        }

        return PhaseMultiplierAnalysis(
            liveMultiplier = liveMultiplier,
            lastCrashMultiplier = lastCrash,
            phase = phase,
            ribbonMultipliers = ribbonMultipliers
        )
    }

    fun parseNumericValue(raw: String): Double? {
        val cleaned = raw.replace(",", "")
        val match = Regex("(\\d+(?:\\.\\d+)?)").find(cleaned) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }
}
