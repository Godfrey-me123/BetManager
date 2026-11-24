package com.example.service

import android.graphics.Rect

enum class GamePhase {
    BETTING_OPEN,
    IN_FLIGHT,
    CRASHED,
    UNKNOWN
}

enum class SwitchState {
    ON,
    OFF,
    UNREADABLE
}

data class RectBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun containsPoint(x: Float, y: Float): Boolean {
        if (isEmpty) return false
        return x >= left.toFloat() && x <= right.toFloat() && y >= top.toFloat() && y <= bottom.toFloat()
    }

    fun overlaps(other: RectBounds): Boolean {
        if (isEmpty || other.isEmpty) return false
        return left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    fun overlapArea(other: RectBounds): Long {
        if (!overlaps(other)) return 0L
        val w = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0)
        val h = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
        return w.toLong() * h.toLong()
    }

    fun verticalOverlap(other: RectBounds): Int {
        val topMax = maxOf(top, other.top)
        val bottomMin = minOf(bottom, other.bottom)
        return (bottomMin - topMax).coerceAtLeast(0)
    }

    fun distanceSqTo(other: RectBounds): Long {
        val dx = (centerX - other.centerX).toLong()
        val dy = (centerY - other.centerY).toLong()
        return dx * dx + dy * dy
    }

    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)

    companion object {
        fun fromAndroidRect(rect: Rect): RectBounds =
            RectBounds(rect.left, rect.top, rect.right, rect.bottom)
    }
}

data class UiTarget(
    val nodeId: String = "",
    val bounds: RectBounds,
    val text: String = "",
    val contentDescription: String = "",
    val hintText: String = "",
    val viewId: String = "",
    val className: String = "",
    val isEditable: Boolean = false,
    val isEnabled: Boolean = true,
    val isClickable: Boolean = true,
    val isVisible: Boolean = true,
    val isChecked: Boolean? = null,
    val isCheckable: Boolean = false,
    val isFocused: Boolean = false
) {
    fun primaryText(): String = text.ifBlank { contentDescription.ifBlank { hintText } }
}

/**
 * Result of dragging the floating BET hand onto a game control on screen.
 */
data class DetectedHandTarget(
    val betButton: UiTarget,
    val stakeField: UiTarget?,
    val panelIndex: Int = 1,
    val matchReason: String = "Detected under BET hand"
)

/**
 * Snapshot of the S1/S2 screen scan stored by ScanScheduler.
 */
data class ScreenMap(
    val timestampMs: Long,
    val packageName: String,
    val gameName: String = "Aviator",
    val siteUrl: String = "BetPawa",
    val balance: Double? = null,
    val currency: String = "TZS",
    val phase: GamePhase = GamePhase.UNKNOWN,
    val liveMultiplier: Double? = null,
    val lastCrashMultiplier: Double? = null,
    val ribbonMultipliers: List<Double> = emptyList(),
    val betButton: UiTarget? = null,
    val cancelButton: UiTarget? = null,
    val stakeField: UiTarget? = null,
    val stakeMinusButton: UiTarget? = null,
    val stakePlusButton: UiTarget? = null,
    val autoTabButton: UiTarget? = null,
    val betTabButton: UiTarget? = null,
    val panelCount: Int = 0,
    val usingHandIndex: Int = 1,
    val hasImeWindow: Boolean = false,
    val conflictReason: String? = null
) {
    val gameWindowFound: Boolean
        get() = packageName.isNotBlank() &&
            (betButton != null || cancelButton != null ||
                stakeField != null || liveMultiplier != null ||
                ribbonMultipliers.isNotEmpty() || phase != GamePhase.UNKNOWN)

    fun isStale(nowMs: Long, maxAgeMs: Long = 5000L): Boolean =
        (nowMs - timestampMs) > maxAgeMs
}
