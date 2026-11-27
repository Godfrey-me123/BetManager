package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs

enum class ClickOutcome {
    CLICKED_NODE,
    TAPPED_GESTURE,
    SKIPPED_DEBOUNCE,
    REFUSED_DISABLED,
    FAILED
}

data class ClickResult(
    val outcome: ClickOutcome,
    val reason: String
) {
    val isProceedable: Boolean
        get() = outcome == ClickOutcome.CLICKED_NODE ||
            outcome == ClickOutcome.TAPPED_GESTURE ||
            outcome == ClickOutcome.SKIPPED_DEBOUNCE
}

data class FieldEntryResult(
    val success: Boolean,
    val readBackText: String = "",
    val readBackValue: Double? = null,
    val errorReason: String? = null
)

/**
 * Abstraction over AccessibilityService window/node/gesture operations so AutoTouchDriver
 * can run cleanly against real Android AccessibilityService AND Robolectric/unit fakes.
 */
interface DriverPlatform {
    val ownPackageName: String
    val preferRealTouchGesture: Boolean get() = false
    fun scanScreen(): ScreenMap
    fun scanScreenWithPanel(preferredBetPanelBounds: RectBounds?): ScreenMap = scanScreen()
    fun detectBetTargetAt(handBounds: RectBounds): DetectedHandTarget? = null
    fun hasExternalWindowAt(bounds: RectBounds): Boolean = false
    fun findNode(query: String, editableOnly: Boolean): UiTarget?
    fun findFocusedInput(): UiTarget?
    fun hasImeWindow(): Boolean
    fun performNodeClick(nodeId: String): Boolean
    fun performSetText(nodeId: String, text: String): Boolean
    suspend fun dispatchTapGesture(x: Float, y: Float, durationMs: Long = 75L): Boolean
    fun performGlobalBack(): Boolean
    fun currentTimeMs(): Long = System.currentTimeMillis()
}

/**
 * Real AccessibilityService implementation of DriverPlatform using AutoTouch's exact
 * node-finding, clickable-parent walk, ACTION_SET_TEXT, and real dispatchGesture mechanics.
 */
class AndroidAccessibilityPlatform(
    private val service: AccessibilityService,
    private val onPassThroughRequested: ((Boolean) -> Unit)? = null
) : DriverPlatform {

    override val ownPackageName: String get() = service.packageName
    override val preferRealTouchGesture: Boolean get() = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nodeRegistry = mutableMapOf<String, AccessibilityNodeInfo>()

    override fun scanScreen(): ScreenMap = scanScreenWithPanel(null)

    override fun scanScreenWithPanel(preferredBetPanelBounds: RectBounds?): ScreenMap {
        val (detectedPkg, captured, hasIme) = captureCurrentNodes()
        return AccessibilityController.scanNodes(
            packageName = detectedPkg,
            nodes = captured,
            hasImeWindow = hasIme,
            preferredBetPanelBounds = preferredBetPanelBounds
        )
    }

    override fun detectBetTargetAt(handBounds: RectBounds): DetectedHandTarget? {
        val (_, captured, _) = captureCurrentNodes()
        return AccessibilityController.detectBetTargetAtHand(handBounds, captured)
    }

    override fun hasExternalWindowAt(bounds: RectBounds): Boolean {
        if (bounds.isEmpty) return false
        val roots = service.windows.orEmpty().mapNotNull { it.root }
            .ifEmpty { listOfNotNull(service.rootInActiveWindow) }
            .filter { !AccessibilityController.isIgnoredSystemPackage(it.packageName?.toString(), ownPackageName) }
        val cx = bounds.centerX.toInt()
        val cy = bounds.centerY.toInt()
        return roots.any { root ->
            val r = Rect().also { root.getBoundsInScreen(it) }
            !r.isEmpty && (r.contains(cx, cy) || RectBounds.fromAndroidRect(r).overlaps(bounds))
        }
    }

    private fun captureCurrentNodes(): Triple<String, List<ScannedNode>, Boolean> {
        val windows = service.windows.orEmpty()
        val hasIme = windows.any { isVisibleImeWindow(it) }
        val roots = windows.mapNotNull { it.root }
            .ifEmpty { listOfNotNull(service.rootInActiveWindow) }

        nodeRegistry.clear()
        val captured = mutableListOf<ScannedNode>()
        var detectedPkg = ""
        var order = 0

        for (root in roots) {
            val pkg = root.packageName?.toString().orEmpty()
            if (AccessibilityController.isIgnoredSystemPackage(pkg, ownPackageName)) continue
            if (detectedPkg.isBlank()) detectedPkg = pkg

            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, String?>>()
            queue.add(root to null)
            while (queue.isNotEmpty()) {
                val (node, parentId) = queue.removeFirst()
                val nodeId = "n_${order}"
                nodeRegistry[nodeId] = node
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
                captured += ScannedNode(nodeId, uiTarget, parentId, order)
                order++
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to nodeId) }
                }
            }
        }
        return Triple(detectedPkg, captured, hasIme)
    }

    override fun findNode(query: String, editableOnly: Boolean): UiTarget? {
        val q = query.trim().lowercase()
        val roots = service.windows.orEmpty().mapNotNull { it.root }
            .ifEmpty { listOfNotNull(service.rootInActiveWindow) }
            .filter { !AccessibilityController.isIgnoredSystemPackage(it.packageName?.toString(), ownPackageName) }

        var partial: UiTarget? = null
        var order = 0
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
                if (editableOnly && !n.isEditable) continue
                if (!n.isVisibleToUser) continue

                val id = "find_${order++}"
                nodeRegistry[id] = n
                val rect = Rect().also(n::getBoundsInScreen)
                val target = UiTarget(
                    nodeId = id,
                    bounds = RectBounds.fromAndroidRect(rect),
                    text = n.text?.toString().orEmpty(),
                    contentDescription = n.contentDescription?.toString().orEmpty(),
                    hintText = n.hintText?.toString().orEmpty(),
                    viewId = n.viewIdResourceName?.substringAfter(":id/").orEmpty(),
                    className = n.className?.toString().orEmpty(),
                    isEditable = n.isEditable,
                    isEnabled = n.isEnabled,
                    isClickable = n.isClickable,
                    isVisible = n.isVisibleToUser,
                    isChecked = if (n.isCheckable) n.isChecked else null,
                    isCheckable = n.isCheckable,
                    isFocused = n.isFocused
                )

                val candidates = listOf(
                    target.text,
                    target.contentDescription,
                    target.hintText,
                    target.viewId
                ).filter { it.isNotBlank() }.map { it.trim().lowercase() }

                if (candidates.any { it == q }) return target
                if (partial == null && candidates.any { it.contains(q) }) partial = target
            }
        }
        return partial
    }

    override fun findFocusedInput(): UiTarget? {
        val roots = service.windows.orEmpty().mapNotNull { it.root }
            .ifEmpty { listOfNotNull(service.rootInActiveWindow) }
            .filter { !AccessibilityController.isIgnoredSystemPackage(it.packageName?.toString(), ownPackageName) }

        for (root in roots) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFocusedEditableFallback(root)
                ?: continue
            val id = "focused_${System.nanoTime()}"
            nodeRegistry[id] = focused
            val rect = Rect().also(focused::getBoundsInScreen)
            return UiTarget(
                nodeId = id,
                bounds = RectBounds.fromAndroidRect(rect),
                text = focused.text?.toString().orEmpty(),
                contentDescription = focused.contentDescription?.toString().orEmpty(),
                hintText = focused.hintText?.toString().orEmpty(),
                viewId = focused.viewIdResourceName?.substringAfter(":id/").orEmpty(),
                className = focused.className?.toString().orEmpty(),
                isEditable = focused.isEditable,
                isEnabled = focused.isEnabled,
                isClickable = focused.isClickable,
                isVisible = focused.isVisibleToUser,
                isFocused = true
            )
        }
        return null
    }

    private fun findFocusedEditableFallback(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser && n.isEditable && n.isFocused) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    override fun hasImeWindow(): Boolean =
        service.windows.orEmpty().any { isVisibleImeWindow(it) }

    private fun isVisibleImeWindow(window: AccessibilityWindowInfo): Boolean {
        if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return false
        val bounds = Rect().also { window.getBoundsInScreen(it) }
        if (bounds.isEmpty) return false
        val displayHeight = runCatching { service.resources.displayMetrics.heightPixels }.getOrDefault(1920)
        val minHeight = maxOf(150, (displayHeight * 0.12f).toInt())
        return bounds.width() >= 200 && bounds.height() >= minHeight
    }

    override fun performNodeClick(nodeId: String): Boolean {
        val node = nodeRegistry[nodeId] ?: return false
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        return target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun performSetText(nodeId: String, text: String): Boolean {
        val node = nodeRegistry[nodeId] ?: return false
        node.refresh()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override suspend fun dispatchTapGesture(x: Float, y: Float, durationMs: Long): Boolean =
        withContext(Dispatchers.Main) {
            val metrics = runCatching { service.resources.displayMetrics }.getOrNull()
            val maxW = (metrics?.widthPixels ?: 0).toFloat()
            val maxH = (metrics?.heightPixels ?: 0).toFloat()
            val tapX = if (maxW > 4f) x.coerceIn(1f, maxW - 2f) else x.coerceAtLeast(1f)
            val tapY = if (maxH > 4f) y.coerceIn(1f, maxH - 2f) else y.coerceAtLeast(1f)

            onPassThroughRequested?.invoke(true)
            if (onPassThroughRequested != null) {
                // Allow SurfaceFlinger / WindowManager enough time to commit FLAG_NOT_TOUCHABLE
                delay(120L)
            }
            try {
                suspendCancellableCoroutine { cont ->
                    val safeDuration = durationMs.coerceIn(70L, GestureDescription.getMaxGestureDuration())
                    val path = Path().apply {
                        moveTo(tapX, tapY)
                        lineTo(tapX, tapY)
                    }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0L, safeDuration))
                        .build()
                    val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    }, mainHandler)
                    if (!accepted && cont.isActive) cont.resume(false)
                }
            } finally {
                delay(50L)
                onPassThroughRequested?.invoke(false)
            }
        }

    override fun performGlobalBack(): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
}

/**
 * The ONLY class that performs taps, clicks, focus, and text entry (Section 2).
 */
class AutoTouchDriver(
    private val platform: DriverPlatform
) {
    private var lastTapX: Float = Float.NaN
    private var lastTapY: Float = Float.NaN
    private var lastTapTimeMs: Long = 0L

    /** Detects a Bet button & paired stake box underneath the dragged BET hand. */
    suspend fun detectBetTargetAt(handBounds: RectBounds): DetectedHandTarget? =
        withContext(Dispatchers.Default) {
            platform.detectBetTargetAt(handBounds)
        }

    /** Checks whether an external interactive app/browser window exists at [bounds]. */
    suspend fun hasExternalWindowAt(bounds: RectBounds): Boolean =
        withContext(Dispatchers.Default) {
            platform.hasExternalWindowAt(bounds)
        }

    /** Performs a real screen tap gesture directly at ([x], [y]) passing through overlays. */
    suspend fun tapCoordinates(
        x: Float,
        y: Float,
        allowDebounceSkip: Boolean = true,
        debounceMs: Long = 220L
    ): ClickResult {
        val now = platform.currentTimeMs()
        if (allowDebounceSkip &&
            !lastTapX.isNaN() &&
            abs(x - lastTapX) <= 3f &&
            abs(y - lastTapY) <= 3f &&
            (now - lastTapTimeMs) < debounceMs
        ) {
            return ClickResult(ClickOutcome.SKIPPED_DEBOUNCE, "Skipped duplicate same-coordinate tap")
        }
        val tapped = platform.dispatchTapGesture(x, y, 75L)
        return if (tapped) {
            recordTap(x, y, platform.currentTimeMs())
            ClickResult(ClickOutcome.TAPPED_GESTURE, "Tapped coordinates (${x.toInt()},${y.toInt()})")
        } else {
            ClickResult(ClickOutcome.FAILED, "Gesture tap cancelled at (${x.toInt()},${y.toInt()})")
        }
    }

    /** Section 2.1: Wait up to timeoutMs (default 3000ms) polling every 250ms. */
    suspend fun waitForNode(
        query: String,
        editableOnly: Boolean = false,
        timeoutMs: Long = 3000L,
        pollMs: Long = 250L
    ): UiTarget? {
        val deadline = platform.currentTimeMs() + timeoutMs
        while (true) {
            platform.findNode(query, editableOnly)?.let { return it }
            if (platform.currentTimeMs() >= deadline) return null
            delay(pollMs)
        }
    }

    /**
     * Section 2.2:
     * - Refuse ONLY when disabled (isEnabled=false) or not visible.
     * - NEVER refuse because isClickable=false.
     * - On real Android (preferRealTouchGesture = true): dispatch real touch gesture first
     *   through pass-through overlays so WebView/Chrome/Canvas buttons receive real clicks,
     *   falling back to ACTION_CLICK if gesture dispatch is rejected.
     * - Same-coordinate debounce skips duplicate tap as SKIPPED_DEBOUNCE, never FAILED.
     */
    suspend fun clickTarget(
        target: UiTarget,
        allowDebounceSkip: Boolean = true,
        debounceMs: Long = 220L
    ): ClickResult {
        if (!target.isVisible) {
            return ClickResult(ClickOutcome.REFUSED_DISABLED, "Target not visible")
        }
        if (!target.isEnabled) {
            return ClickResult(ClickOutcome.REFUSED_DISABLED, "Target disabled")
        }
        if (target.bounds.isEmpty) {
            return ClickResult(ClickOutcome.FAILED, "Target has empty bounds")
        }

        val cx = target.bounds.centerX
        val cy = target.bounds.centerY
        val now = platform.currentTimeMs()

        if (allowDebounceSkip &&
            !lastTapX.isNaN() &&
            abs(cx - lastTapX) <= 3f &&
            abs(cy - lastTapY) <= 3f &&
            (now - lastTapTimeMs) < debounceMs
        ) {
            return ClickResult(ClickOutcome.SKIPPED_DEBOUNCE, "Skipped duplicate same-coordinate tap")
        }

        if (platform.preferRealTouchGesture) {
            val tapped = platform.dispatchTapGesture(cx, cy, 75L)
            if (tapped) {
                recordTap(cx, cy, platform.currentTimeMs())
                return ClickResult(ClickOutcome.TAPPED_GESTURE, "Tapped center via real touch (${cx.toInt()},${cy.toInt()})")
            }
            if (target.nodeId.isNotBlank() && platform.performNodeClick(target.nodeId)) {
                recordTap(cx, cy, platform.currentTimeMs())
                return ClickResult(ClickOutcome.CLICKED_NODE, "Clicked node fallback")
            }
            return ClickResult(ClickOutcome.FAILED, "Gesture tap cancelled")
        }

        if (target.nodeId.isNotBlank() && platform.performNodeClick(target.nodeId)) {
            recordTap(cx, cy, now)
            return ClickResult(ClickOutcome.CLICKED_NODE, "Clicked node")
        }

        val tapped = platform.dispatchTapGesture(cx, cy, 50L)
        return if (tapped) {
            recordTap(cx, cy, platform.currentTimeMs())
            ClickResult(ClickOutcome.TAPPED_GESTURE, "Tapped center via gesture (${cx.toInt()},${cy.toInt()})")
        } else {
            ClickResult(ClickOutcome.FAILED, "Gesture tap cancelled")
        }
    }

    /**
     * Section 2.3 (a–e):
     * a. Click the field so the page focuses it, wait 200ms.
     * b. Take FOCUS_INPUT. Use only if its bounds overlap the target field bounds;
     *    otherwise stop with "wrong field focused".
     * c. ACTION_SET_TEXT with full new text. Do NOT clear first. Never paste.
     * d. Poll read-back up to 5 times, 100ms apart. Accept when |value - expected| <= tolerance.
     * e. If still wrong after 600ms: one retry of a–d. Then stop with "stake could not be set (field shows X)".
     */
    suspend fun setVerifiedNumericField(
        fieldBounds: RectBounds,
        initialTarget: UiTarget,
        expectedValue: Double,
        formattedText: String,
        tolerance: Double = 0.05,
        fieldLabel: String = "stake",
        preferredBetPanelBounds: RectBounds? = null
    ): FieldEntryResult {
        var lastShownText = initialTarget.primaryText()

        for (attempt in 1..2) {
            val clickRes = clickTarget(initialTarget, allowDebounceSkip = false)
            if (!clickRes.isProceedable) {
                return FieldEntryResult(false, lastShownText, null, clickRes.reason)
            }
            delay(200L)

            var focused = platform.findFocusedInput()
            if (focused == null) {
                delay(150L)
                focused = platform.findFocusedInput()
            }
            val horizOverlap = (minOf(focused?.bounds?.right ?: 0, fieldBounds.right) -
                maxOf(focused?.bounds?.left ?: 0, fieldBounds.left)).coerceAtLeast(0)
            val focusMatchesField = focused != null && (
                focused.bounds.overlaps(fieldBounds) || (
                    focused.isEditable &&
                        !focused.viewId.lowercase().contains("cash") &&
                        horizOverlap >= minOf(fieldBounds.width, focused.bounds.width) / 2 &&
                        abs(focused.bounds.centerY - fieldBounds.centerY) <= 450f
                    )
                )
            if (!focusMatchesField || focused == null) {
                if (attempt == 2) {
                    return FieldEntryResult(false, lastShownText, null, "wrong field focused")
                }
                delay(600L)
                continue
            }

            if (!platform.performSetText(focused.nodeId, formattedText)) {
                if (attempt == 2) {
                    return FieldEntryResult(
                        false,
                        lastShownText,
                        AccessibilityController.parseNumericValue(lastShownText),
                        "$fieldLabel could not be set (field shows $lastShownText)"
                    )
                }
                delay(600L)
                continue
            }

            for (poll in 1..5) {
                delay(100L)
                val currentFocused = platform.findFocusedInput()
                val textNow = currentFocused?.primaryText().orEmpty().ifBlank {
                    findNodeMatchingBounds(fieldBounds, preferredBetPanelBounds)?.primaryText().orEmpty()
                }
                if (textNow.isNotBlank()) {
                    lastShownText = textNow
                }
                val parsed = AccessibilityController.parseNumericValue(lastShownText)
                if (parsed != null && abs(parsed - expectedValue) <= tolerance) {
                    return FieldEntryResult(true, lastShownText, parsed)
                }
            }

            if (attempt == 1) {
                delay(600L)
            }
        }

        val finalParsed = AccessibilityController.parseNumericValue(lastShownText)
        return FieldEntryResult(
            success = false,
            readBackText = lastShownText,
            readBackValue = finalParsed,
            errorReason = "$fieldLabel could not be set (field shows $lastShownText)"
        )
    }

    data class LayoutSettleResult(
        val scan: ScreenMap?,
        val failureReason: String? = null
    )

    /**
     * Section 2.3 (f):
     * Close keyboard (GLOBAL_ACTION_BACK only if an input-method window exists),
     * verify that IME has actually closed, wait until Bet button bounds settle within 16px tolerance
     * on two scans 120ms apart (timeout 1500ms), then return the fresh post-dismissal ScreenMap.
     */
    suspend fun closeKeyboardAndWaitLayoutSettle(
        settleIntervalMs: Long = 120L,
        timeoutMs: Long = 1500L,
        preferredBetPanelBounds: RectBounds? = null
    ): ScreenMap? = closeKeyboardAndWaitLayoutSettleDetailed(
        settleIntervalMs = settleIntervalMs,
        timeoutMs = timeoutMs,
        preferredBetPanelBounds = preferredBetPanelBounds
    ).scan

    suspend fun closeKeyboardAndWaitLayoutSettleDetailed(
        settleIntervalMs: Long = 120L,
        timeoutMs: Long = 1500L,
        preferredBetPanelBounds: RectBounds? = null
    ): LayoutSettleResult {
        var imeDismissAttempts = 0
        if (platform.hasImeWindow()) {
            platform.performGlobalBack()
            imeDismissAttempts++
            delay(maxOf(settleIntervalMs, 240L))
        }

        val deadline = platform.currentTimeMs() + timeoutMs
        var previousBetBounds: RectBounds? = null
        var previousStakeBounds: RectBounds? = null
        var lastValidPostDismissScan: ScreenMap? = null

        while (true) {
            val scan = platform.scanScreenWithPanel(preferredBetPanelBounds)
            val imeStillOpen = scan.hasImeWindow || platform.hasImeWindow()
            if (imeStillOpen) {
                // Never fire a second GLOBAL_ACTION_BACK — Samsung/Android back animations can lag
                // slightly and a second back exits Chrome/the game to the home screen.
                if (imeDismissAttempts == 0) {
                    platform.performGlobalBack()
                    imeDismissAttempts++
                }
                previousBetBounds = null
                previousStakeBounds = null
                if (platform.currentTimeMs() >= deadline) {
                    return LayoutSettleResult(
                        scan = null,
                        failureReason = "Keyboard did not close before layout settle timeout"
                    )
                }
                delay(settleIntervalMs)
                continue
            }

            val liveBetBounds = scan.betButton?.bounds
            val currentBetOrCalibrated = liveBetBounds
                ?: if (preferredBetPanelBounds != null && !preferredBetPanelBounds.isEmpty && scan.stakeField != null) {
                    preferredBetPanelBounds
                } else {
                    null
                }
            val currentStakeBounds = scan.stakeField?.bounds

            if (currentBetOrCalibrated != null) {
                lastValidPostDismissScan = scan
                val betSettled = previousBetBounds != null && boundsWithinTolerance(currentBetOrCalibrated, previousBetBounds, 16)
                val stakeSettled = if (liveBetBounds != null) {
                    true
                } else {
                    currentStakeBounds != null && previousStakeBounds != null &&
                        boundsWithinTolerance(currentStakeBounds, previousStakeBounds, 16)
                }
                if (betSettled && stakeSettled) {
                    return LayoutSettleResult(scan = scan)
                }
            }
            previousBetBounds = currentBetOrCalibrated
            previousStakeBounds = currentStakeBounds
            if (platform.currentTimeMs() >= deadline) {
                return if (lastValidPostDismissScan != null) {
                    LayoutSettleResult(scan = lastValidPostDismissScan)
                } else {
                    LayoutSettleResult(
                        scan = null,
                        failureReason = "Layout did not settle within ${timeoutMs}ms after keyboard close"
                    )
                }
            }
            delay(settleIntervalMs)
        }
    }

    private fun boundsWithinTolerance(a: RectBounds, b: RectBounds, tolerancePx: Int): Boolean {
        return a == b || (
            abs(a.left - b.left) <= tolerancePx &&
                abs(a.top - b.top) <= tolerancePx &&
                abs(a.right - b.right) <= tolerancePx &&
                abs(a.bottom - b.bottom) <= tolerancePx
            )
    }

    private fun findNodeMatchingBounds(
        bounds: RectBounds,
        preferredBetPanelBounds: RectBounds? = null
    ): UiTarget? {
        val scan = platform.scanScreenWithPanel(preferredBetPanelBounds)
        val candidates = listOfNotNull(scan.stakeField)
        return candidates.firstOrNull { it.bounds.overlaps(bounds) }
    }

    private fun recordTap(x: Float, y: Float, timeMs: Long) {
        lastTapX = x
        lastTapY = y
        lastTapTimeMs = timeMs
    }

    companion object {
        /**
         * Pure helper based on AutoTouch MacroParser/AutoService exact vs partial match priority.
         */
        fun findBestMatchingTarget(
            targets: List<UiTarget>,
            query: String,
            editableOnly: Boolean = false
        ): UiTarget? {
            val q = query.trim().lowercase()
            var partial: UiTarget? = null
            for (target in targets) {
                if (editableOnly && !target.isEditable) continue
                if (!target.isVisible) continue
                val candidates = listOf(
                    target.text,
                    target.contentDescription,
                    target.hintText,
                    target.viewId
                ).filter { it.isNotBlank() }.map { it.trim().lowercase() }
                if (candidates.any { it == q }) return target
                if (partial == null && candidates.any { it.contains(q) }) partial = target
            }
            return partial
        }
    }
}
