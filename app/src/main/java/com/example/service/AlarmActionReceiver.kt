package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles notification bar actions (e.g., Cancel Alarm, Expand Floating Overlay)
 * without ever launching a full-screen Activity that would obstruct the game screen.
 */
class AlarmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_STOP_ALARM -> {
                PatternAlertManager.cancelActiveAlarm(context)
                ScreenEarnerAccessibilityService.sharedEngine?.cancelActiveAlarm()
            }
            ACTION_OPEN_OVERLAY -> {
                ScreenEarnerAccessibilityService.instance?.showAndExpandOverlay()
            }
        }
    }

    companion object {
        const val ACTION_STOP_ALARM = "com.example.action.STOP_ALARM"
        const val ACTION_OPEN_OVERLAY = "com.example.action.OPEN_OVERLAY"
    }
}
