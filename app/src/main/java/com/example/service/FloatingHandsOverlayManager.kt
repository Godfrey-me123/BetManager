package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.data.engine.FloatingHandState
import com.example.data.local.BetManagerRepository
import kotlin.math.abs

/** Everything the floating panel & BET hand show. Built by the accessibility service from engine + settings state. */
data class OverlayInfo(
    val isRunning: Boolean = false,
    val statusLine: String = "Ready",
    val gameLine: String = "",
    val balanceText: String = "--",
    val stakeText: String = "--",
    val lossStreak: Int = 0,
    val phaseText: String = "",
    val multiplierText: String = "",
    val latestCrashText: String = "--",
    val monitoredCrashes: Int = 0,
    val todayProfitText: String = "",
    /** Last results, oldest first: true = win, false = loss. */
    val lastResults: List<Boolean> = emptyList(),
    val handState: FloatingHandState = FloatingHandState.WAITING,
    val betTargetDetected: Boolean = false,
    val handAutoBetEnabled: Boolean = false,
    val keepScreenAwake: Boolean = false,
    /** Stake can be changed only while stopped. */
    val canEditStake: Boolean = true,
    val recordedMultipliers: List<Double> = emptyList(),
    val patternSummary: String = "",
    val isPatternMatched: Boolean = false
)

/**
 * The BetManager floating overlay system (TYPE_ACCESSIBILITY_OVERLAY, needs no extra permission):
 *  1) Draggable Floating BET Hand: user drags it over the game's Bet button to detect the target
 *     and run the verified edit-stake + real Bet click flow (or taps it / enables Auto Bet).
 *  2) Compact "BM" Bubble / Expanded Panel: shows status, monitored crashes, balance, stake,
 *     BET, Auto Bet ON/OFF, Start / Stop, Stake - / +, Open app, and EXIT (stops app & clears all activities).
 */
class FloatingHandsOverlayManager(
    private val context: Context,
    private val repository: BetManagerRepository,
    private val onBetHandTapped: () -> Unit,
    private val onBetHandDraggedTo: (RectBounds) -> Unit,
    private val onToggleHandAutoBet: () -> Unit,
    private val onToggleStartStop: () -> Unit,
    private val onStakeStep: (Int) -> Unit,
    private val onOpenApp: () -> Unit,
    private val onExitApp: () -> Unit,
    private val onToggleKeepScreenAwake: () -> Unit = {}
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var bubbleView: TextView? = null
    private var panelView: LinearLayout? = null
    private var betHandView: TextView? = null
    private var expanded = false
    private var lastInfo = OverlayInfo()
    private var lastTapAtMs = 0L
    private var wasRunning = false

    // panel widgets
    private var statusText: TextView? = null
    private var patternText: TextView? = null
    private var multipliersContainer: LinearLayout? = null
    private var gameText: TextView? = null
    private var crashText: TextView? = null
    private var balanceText: TextView? = null
    private var stakeText: TextView? = null
    private var liveText: TextView? = null
    private var resultsText: TextView? = null
    private var betButton: TextView? = null
    private var autoBetButton: TextView? = null
    private var keepAwakeButton: TextView? = null
    private var startStopButton: TextView? = null
    private var stakeMinus: TextView? = null
    private var stakePlus: TextView? = null

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val saved = repository.getOverlayPosition(dp(16), dp(140))
        x = saved.first
        y = saved.second
    }

    private val betHandSizePx = dp(60)
    private val betHandLayoutParams = WindowManager.LayoutParams(
        betHandSizePx,
        betHandSizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val dm = context.resources.displayMetrics
        val defaultX = (dm.widthPixels * 0.72f).toInt()
        val defaultY = (dm.heightPixels * 0.62f).toInt()
        val saved = repository.getBetHandPosition(defaultX, defaultY)
        x = saved.first
        y = saved.second
    }

    val isVisible: Boolean get() = bubbleView != null || panelView != null || betHandView != null

    // ------------------------------------------------------------------ public

    fun show() {
        if (!isVisible) {
            if (expanded) addPanel() else addBubble()
            addBetHand()
            applyInfo(lastInfo)
        } else if (betHandView == null) {
            addBetHand()
            applyInfo(lastInfo)
        }
    }

    fun hide() {
        applyKeepScreenAwakeFlag(false)
        removeMenuOverlay()
        removeBetHand()
    }

    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        if (bubbleView == null && panelView == null) return
        removeMenuOverlay()
        if (expanded) addPanel() else addBubble()
        applyInfo(lastInfo)
    }

    /**
     * Temporarily makes all floating overlays touch-transparent (FLAG_NOT_TOUCHABLE)
     * so Accessibility dispatchGesture clicks pass directly through the BET hand onto the game's Bet button.
     */
    fun setPassThrough(isPassThrough: Boolean) {
        val applyAction = Runnable {
            applyPassThroughToView(betHandView, betHandLayoutParams, isPassThrough)
            applyPassThroughToView(bubbleView, layoutParams, isPassThrough)
            applyPassThroughToView(panelView, layoutParams, isPassThrough)
            betHandView?.apply {
                alpha = if (isPassThrough) 0.25f else 1.0f
                scaleX = if (isPassThrough) 0.85f else 1.0f
                scaleY = if (isPassThrough) 0.85f else 1.0f
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyAction.run()
        } else {
            mainHandler.post(applyAction)
        }
    }

    fun update(info: OverlayInfo) {
        val startedNow = info.isRunning && !wasRunning
        wasRunning = info.isRunning
        lastInfo = info
        if (expanded && (startedNow || info.handState == FloatingHandState.READY && info.isRunning)) {
            setExpanded(false)
            return
        }
        applyInfo(info)
    }

    private fun applyPassThroughToView(
        view: View?,
        params: WindowManager.LayoutParams,
        isPassThrough: Boolean
    ) {
        if (view == null) return
        val nextFlags = if (isPassThrough) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (params.flags != nextFlags) {
            params.flags = nextFlags
            if (view.isAttachedToWindow) {
                runCatching { wm.updateViewLayout(view, params) }
            }
        }
    }

    private fun applyKeepScreenAwakeFlag(enabled: Boolean) {
        applyFlagToWindow(betHandView, betHandLayoutParams, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, enabled)
        applyFlagToWindow(bubbleView, layoutParams, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, enabled)
        applyFlagToWindow(panelView, layoutParams, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, enabled)
    }

    private fun applyFlagToWindow(
        view: View?,
        params: WindowManager.LayoutParams,
        flag: Int,
        enabled: Boolean
    ) {
        val nextFlags = if (enabled) {
            params.flags or flag
        } else {
            params.flags and flag.inv()
        }
        if (params.flags != nextFlags) {
            params.flags = nextFlags
            if (view != null && view.isAttachedToWindow) {
                runCatching { wm.updateViewLayout(view, params) }
            }
        }
    }

    // ------------------------------------------------------------------ building

    private fun stateColor(s: FloatingHandState): Int = when (s) {
        FloatingHandState.READY -> C_GREEN
        FloatingHandState.LOCKED -> C_GREY_DARK
        FloatingHandState.WAITING -> C_AMBER
    }

    private fun flat(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun button(label: String, fill: Int, textColor: Int, stroke: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(textColor)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = flat(fill, stroke, 12)
            setOnClickListener { onClick() }
        }

    private fun label(textValue: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = textValue
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun menuDragListener(target: View): View.OnTouchListener {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var dragging = false
        val threshold = dp(8).toFloat()
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x; startY = layoutParams.y
                    downX = event.rawX; downY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > threshold || abs(dy) > threshold) dragging = true
                    if (dragging) {
                        layoutParams.x = startX + dx.toInt()
                        layoutParams.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(target, layoutParams) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        repository.saveOverlayPosition(layoutParams.x, layoutParams.y)
                    } else {
                        setExpanded(!expanded)
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun betHandTouchListener(view: TextView): View.OnTouchListener {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var dragging = false
        val threshold = dp(8).toFloat()
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = betHandLayoutParams.x
                    startY = betHandLayoutParams.y
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > threshold || abs(dy) > threshold) {
                        dragging = true
                    }
                    if (dragging) {
                        betHandLayoutParams.x = startX + dx.toInt()
                        betHandLayoutParams.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, betHandLayoutParams) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        repository.saveBetHandPosition(betHandLayoutParams.x, betHandLayoutParams.y)
                        val loc = IntArray(2)
                        view.getLocationOnScreen(loc)
                        val left = if (view.isAttachedToWindow) loc[0] else betHandLayoutParams.x
                        val top = if (view.isAttachedToWindow) loc[1] else betHandLayoutParams.y
                        val w = view.width.takeIf { it > 0 } ?: betHandSizePx
                        val h = view.height.takeIf { it > 0 } ?: betHandSizePx
                        onBetHandDraggedTo(RectBounds(left, top, left + w, top + h))
                    } else {
                        guardedBetTap(allowWhenReadyOrManual = true)
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun addBetHand() {
        val hand = TextView(context).apply {
            text = "BET\nDRAG"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_GREEN_DARK)
            gravity = Gravity.CENTER
            contentDescription = "Floating BET hand. Drag onto game Bet button or tap to bet."
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C_GREEN_SOFT)
                setStroke(dp(3), C_GREEN)
            }
        }
        hand.setOnTouchListener(betHandTouchListener(hand))
        runCatching { wm.addView(hand, betHandLayoutParams) }
        betHandView = hand
    }

    @SuppressLint("SetTextI18n")
    private fun addBubble() {
        val bubble = TextView(context).apply {
            text = "BM"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_INK)
            gravity = Gravity.CENTER
            minWidth = dp(52)
            minHeight = dp(52)
            contentDescription = "BetManager overlay menu"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C_PANEL)
                setStroke(dp(3), C_AMBER)
            }
        }
        bubble.setOnTouchListener(menuDragListener(bubble))
        runCatching { wm.addView(bubble, layoutParams) }
        bubbleView = bubble
    }

    @SuppressLint("SetTextI18n")
    private fun addPanel() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = flat(C_PANEL, C_BORDER, 18)
            minimumWidth = dp(292)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = label("BetManager", 15f, C_INK, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val collapse = button("Hide", C_SOFT, C_INK, C_BORDER) { setExpanded(false) }.apply {
            minHeight = dp(36)
            textSize = 12f
        }
        header.addView(title)
        header.addView(collapse)
        header.setOnTouchListener(menuDragListener(panel))
        title.setOnTouchListener(menuDragListener(panel))
        panel.addView(header)

        statusText = label("Ready", 13f, C_INK).apply { setPadding(0, dp(8), 0, dp(2)); maxLines = 2 }
        patternText = label("Pattern: Idle", 12f, C_INK, bold = true).apply { setPadding(0, dp(2), 0, dp(2)); maxLines = 2 }
        multipliersContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(4))
        }
        gameText = label("", 12f, C_INK_SOFT)
        crashText = label("Crash --   Tracked: 0", 12f, C_INK, bold = true)
        balanceText = label("Balance --", 12f, C_INK_SOFT)
        stakeText = label("Stake --", 13f, C_INK, bold = true).apply { setPadding(0, dp(4), 0, 0) }
        liveText = label("", 12f, C_INK_SOFT)
        resultsText = label("", 13f, C_INK).apply { setPadding(0, dp(4), 0, dp(8)) }
        listOf(statusText, patternText, multipliersContainer, gameText, crashText, balanceText, stakeText, liveText, resultsText).forEach {
            panel.addView(it)
        }

        val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        betButton = button("BET", C_GREEN_SOFT, C_GREEN_DARK, C_GREEN_DARK) {
            guardedBetTap(allowWhenReadyOrManual = true)
        }
        autoBetButton = button("Auto: OFF", C_SOFT, C_INK, C_BORDER) {
            onToggleHandAutoBet()
        }
        row1.addView(betButton, weightParams(end = 4))
        row1.addView(autoBetButton, weightParams(start = 4))
        panel.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        startStopButton = button("Start", C_GREEN_SOFT, C_GREEN_DARK, C_GREEN_DARK) { onToggleStartStop() }
        stakeMinus = button("-", C_SOFT, C_INK, C_BORDER) { onStakeStep(-1) }
        stakePlus = button("+", C_SOFT, C_INK, C_BORDER) { onStakeStep(1) }
        row2.addView(startStopButton, weightParams(end = 4))
        row2.addView(stakeMinus, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })
        row2.addView(stakePlus, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(row2)

        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        keepAwakeButton = button("Awake: OFF", C_SOFT, C_INK, C_BORDER) {
            onToggleKeepScreenAwake()
        }.apply {
            contentDescription = "Toggle never sleep keep screen on"
        }
        val open = button("Open", C_SOFT, C_INK, C_BORDER) { onOpenApp() }
        val exitBtn = button("EXIT", C_RED_SOFT, C_RED_DARK, C_RED_DARK) { onExitApp() }.apply {
            contentDescription = "Exit BetManager and clear all activities"
        }
        row3.addView(keepAwakeButton, weightParams(end = 3))
        row3.addView(open, weightParams(start = 3, end = 3))
        row3.addView(exitBtn, weightParams(start = 3))
        panel.addView(row3)

        runCatching { wm.addView(panel, layoutParams) }
        panelView = panel
    }

    private fun weightParams(start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(start)
            marginEnd = dp(end)
        }

    private fun guardedBetTap(allowWhenReadyOrManual: Boolean = false) {
        val now = System.currentTimeMillis()
        val canTap = lastInfo.handState == FloatingHandState.READY ||
            (allowWhenReadyOrManual && lastInfo.handState != FloatingHandState.LOCKED)
        if (canTap && now - lastTapAtMs > 650L) {
            lastTapAtMs = now
            onBetHandTapped()
        }
    }

    private fun removeMenuOverlay() {
        bubbleView?.let { runCatching { wm.removeView(it) } }
        panelView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
        panelView = null
        statusText = null; patternText = null; multipliersContainer = null
        gameText = null; crashText = null; balanceText = null; stakeText = null
        liveText = null; resultsText = null; betButton = null; autoBetButton = null
        keepAwakeButton = null; startStopButton = null; stakeMinus = null; stakePlus = null
    }

    private fun removeBetHand() {
        betHandView?.let { runCatching { wm.removeView(it) } }
        betHandView = null
    }

    // ------------------------------------------------------------------ updating

    @SuppressLint("SetTextI18n")
    private fun applyInfo(info: OverlayInfo) {
        applyKeepScreenAwakeFlag(info.keepScreenAwake)
        (bubbleView?.background as? GradientDrawable)?.setStroke(dp(3), stateColor(info.handState))

        betHandView?.apply {
            val (fill, stroke, textCol) = when {
                info.handState == FloatingHandState.LOCKED -> Triple(C_SOFT, C_GREY_DARK, C_INK_SOFT)
                info.handState == FloatingHandState.READY -> Triple(C_GREEN_SOFT, C_GREEN, C_GREEN_DARK)
                info.handAutoBetEnabled && info.betTargetDetected -> Triple(C_GREEN_SOFT, C_GREEN_DARK, C_GREEN_DARK)
                info.betTargetDetected -> Triple(C_PANEL, C_GREEN, C_INK)
                else -> Triple(C_PANEL, C_AMBER, C_INK)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                setStroke(dp(3), stroke)
            }
            setTextColor(textCol)
            text = when (info.handState) {
                FloatingHandState.LOCKED -> "BET\nLOCK"
                FloatingHandState.READY -> if (info.handAutoBetEnabled) "BET\nAUTO" else "BET\nTAP"
                FloatingHandState.WAITING -> when {
                    info.handAutoBetEnabled && info.betTargetDetected -> "AUTO\nWAIT"
                    info.betTargetDetected -> "BET\nWAIT"
                    else -> "BET\nDRAG"
                }
            }
        }

        if (panelView == null) return
        statusText?.text = info.statusLine
        patternText?.apply {
            text = if (info.patternSummary.isNotBlank()) info.patternSummary else "Pattern: Waiting for data"
            setTextColor(if (info.isPatternMatched) C_GREEN_DARK else C_INK)
        }
        multipliersContainer?.apply {
            removeAllViews()
            val recent = info.recordedMultipliers.take(5)
            if (recent.isEmpty()) {
                val emptyLabel = label("No recorded crashes", 11f, C_INK_SOFT)
                addView(emptyLabel)
            } else {
                recent.forEach { mult ->
                    val (fill, stroke, textCol) = when {
                        mult < 2.0 -> Triple(0xFFD8E4F0.toInt(), 0xFF5B88C4.toInt(), 0xFF1C4278.toInt())
                        mult < 10.0 -> Triple(0xFFE8DCF0.toInt(), 0xFF9668B8.toInt(), 0xFF58287A.toInt())
                        else -> Triple(0xFFF2D8E6.toInt(), 0xFFC25B96.toInt(), 0xFF821A58.toInt())
                    }
                    val pill = TextView(context).apply {
                        text = "%.2fx".format(mult)
                        textSize = 10f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(textCol)
                        gravity = Gravity.CENTER
                        background = flat(fill, stroke, 8)
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(4) }
                    }
                    addView(pill)
                }
            }
        }
        gameText?.text = info.gameLine
        crashText?.text = "Crash ${info.latestCrashText}   Tracked: ${info.monitoredCrashes}"
        balanceText?.text = "Balance ${info.balanceText}" +
            if (info.todayProfitText.isNotBlank()) "   Today ${info.todayProfitText}" else ""
        stakeText?.text = "Stake ${info.stakeText}" + if (info.lossStreak > 0) "   Losses in a row: ${info.lossStreak}" else ""
        liveText?.text = listOf(info.phaseText, info.multiplierText).filter { it.isNotBlank() }.joinToString("   ")
        resultsText?.text = if (info.lastResults.isEmpty()) "No bets yet"
        else "Last: " + info.lastResults.joinToString(" ") { if (it) "W" else "L" }

        betButton?.apply {
            text = when (info.handState) {
                FloatingHandState.READY -> "BET"
                FloatingHandState.LOCKED -> "Bet placed"
                FloatingHandState.WAITING -> "BET (Wait)"
            }
            alpha = if (info.handState != FloatingHandState.LOCKED) 1f else 0.5f
            isEnabled = info.handState != FloatingHandState.LOCKED
        }
        autoBetButton?.apply {
            if (info.handAutoBetEnabled) {
                text = "Auto: ON"
                setTextColor(C_GREEN_DARK)
                background = flat(C_GREEN_SOFT, C_GREEN_DARK, 12)
            } else {
                text = "Auto: OFF"
                setTextColor(C_INK)
                background = flat(C_SOFT, C_BORDER, 12)
            }
        }
        keepAwakeButton?.apply {
            if (info.keepScreenAwake) {
                text = "Awake: ON"
                setTextColor(C_GREEN_DARK)
                background = flat(C_GREEN_SOFT, C_GREEN_DARK, 12)
            } else {
                text = "Awake: OFF"
                setTextColor(C_INK)
                background = flat(C_SOFT, C_BORDER, 12)
            }
        }
        startStopButton?.apply {
            if (info.isRunning) {
                text = "Stop"
                setTextColor(C_RED_DARK)
                background = flat(C_RED_SOFT, C_RED_DARK, 12)
            } else {
                text = "Start"
                setTextColor(C_GREEN_DARK)
                background = flat(C_GREEN_SOFT, C_GREEN_DARK, 12)
            }
        }
        val canEdit = info.canEditStake && !info.isRunning
        listOf(stakeMinus, stakePlus).forEach { it?.alpha = if (canEdit) 1f else 0.4f; it?.isEnabled = canEdit }
    }

    private companion object {
        // Flat, soft light-grey palette (no pure white / black, no gradients).
        val C_PANEL = 0xF2EEEFF1.toInt()
        val C_SOFT = 0xFFE1E3E6.toInt()
        val C_BORDER = 0xFFC2C6CC.toInt()
        val C_INK = 0xFF2D3138.toInt()
        val C_INK_SOFT = 0xFF626975.toInt()
        val C_GREEN = 0xFF4A8B6E.toInt()
        val C_GREEN_SOFT = 0xFFD8E6DE.toInt()
        val C_GREEN_DARK = 0xFF2F6650.toInt()
        val C_AMBER = 0xFFB07F2E.toInt()
        val C_RED_SOFT = 0xFFEBD3D0.toInt()
        val C_RED_DARK = 0xFFB0524A.toInt()
        val C_GREY_DARK = 0xFF7B828C.toInt()
    }
}
