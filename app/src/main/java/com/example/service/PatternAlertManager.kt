package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.engine.PatternProgress
import com.example.data.engine.PatternSignalStage
import com.example.data.local.BetManagerRepository

class PatternAlertManager(
    private val context: Context,
    private val repository: BetManagerRepository
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var activeRingtone: Ringtone? = null
    private var lastDispatchedSignature: String? = null
    private var lastDispatchedStage: PatternSignalStage = PatternSignalStage.NONE

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM,
                "Strategy Alarm (High Signal)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent alarm indicating strategy pattern is met and it's time to bet"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 150, 350, 150, 500)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }

            val preSignalChannel = NotificationChannel(
                CHANNEL_PRESIGNAL,
                "Strategy Pre-Signal (Approaching)",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Pre-signal warning indicating 1 crash away from strategy pattern"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
            }

            notificationManager.createNotificationChannel(alarmChannel)
            notificationManager.createNotificationChannel(preSignalChannel)
        }
    }

    fun processPatternProgress(progress: PatternProgress, gameSite: String = "") {
        if (progress.signalStage == PatternSignalStage.NONE) {
            if (lastDispatchedStage != PatternSignalStage.NONE) {
                lastDispatchedStage = PatternSignalStage.NONE
                lastDispatchedSignature = null
            }
            return
        }

        // Prevent duplicate trigger for the same signature & stage
        val signatureKey = "${progress.signalStage.name}_${progress.triggerSignature}"
        if (signatureKey == lastDispatchedSignature && lastDispatchedStage == progress.signalStage) {
            return
        }

        lastDispatchedSignature = signatureKey
        lastDispatchedStage = progress.signalStage

        when (progress.signalStage) {
            PatternSignalStage.HIGH_SIGNAL -> {
                dispatchHighSignalAlarm(progress, gameSite)
            }
            PatternSignalStage.PRE_SIGNAL -> {
                dispatchPreSignalWarning(progress, gameSite)
            }
            PatternSignalStage.NONE -> {}
        }
    }

    private fun dispatchHighSignalAlarm(progress: PatternProgress, gameSite: String) {
        if (!repository.isAlarmHighSignalEnabled()) return

        // Sound alert
        if (repository.isAlertSoundEnabled()) {
            playAlertSound(isUrgent = true)
        }

        // Vibration alert
        if (repository.isAlertVibrationEnabled()) {
            vibratePattern(longArrayOf(0, 350, 150, 350, 150, 500))
        }

        // High priority notification
        val title = "🔔 TIME TO BET! ${progress.ruleName}"
        val content = if (gameSite.isNotBlank()) {
            "Pattern MET on $gameSite: ${progress.statusText}. Bet immediately!"
        } else {
            "Pattern MET: ${progress.statusText}. Bet immediately!"
        }

        showNotification(
            id = NOTIFICATION_ID_ALARM,
            channelId = CHANNEL_ALARM,
            title = title,
            content = content,
            priority = NotificationCompat.PRIORITY_MAX
        )
    }

    private fun dispatchPreSignalWarning(progress: PatternProgress, gameSite: String) {
        if (!repository.isPreSignalNotificationEnabled()) return

        // Sound alert
        if (repository.isAlertSoundEnabled()) {
            playAlertSound(isUrgent = false)
        }

        // Vibration alert
        if (repository.isAlertVibrationEnabled()) {
            vibratePattern(longArrayOf(0, 200, 100, 200))
        }

        // Pre-signal notification
        val title = "⚠️ Pre-Signal: 1 Crash Away! (${progress.ruleName})"
        val content = if (gameSite.isNotBlank()) {
            "Approaching on $gameSite: ${progress.statusText}. Prepare next round bet!"
        } else {
            "Approaching: ${progress.statusText}. Prepare next round bet!"
        }

        showNotification(
            id = NOTIFICATION_ID_PRESIGNAL,
            channelId = CHANNEL_PRESIGNAL,
            title = title,
            content = content,
            priority = NotificationCompat.PRIORITY_HIGH
        )
    }

    private fun playAlertSound(isUrgent: Boolean) {
        try {
            stopSound()
            val uri = if (isUrgent) {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            if (uri != null) {
                activeRingtone = RingtoneManager.getRingtone(context, uri)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        audioAttributes = AudioAttributes.Builder()
                            .setUsage(if (isUrgent) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    }
                    play()
                }
            }
        } catch (_: Exception) {}
    }

    private fun vibratePattern(pattern: LongArray) {
        try {
            vibrator?.let { v ->
                if (!v.hasVibrator()) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = VibrationEffect.createWaveform(pattern, -1)
                    v.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {}
    }

    private fun showNotification(
        id: Int,
        channelId: String,
        title: String,
        content: String,
        priority: Int
    ) {
        try {
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(priority)
                .setAutoCancel(true)

            notificationManager?.notify(id, builder.build())
        } catch (_: Exception) {}
    }

    fun stopSound() {
        try {
            activeRingtone?.stop()
            activeRingtone = null
        } catch (_: Exception) {}
    }

    fun clearNotifications() {
        try {
            stopSound()
            notificationManager?.cancel(NOTIFICATION_ID_ALARM)
            notificationManager?.cancel(NOTIFICATION_ID_PRESIGNAL)
        } catch (_: Exception) {}
    }

    companion object {
        const val CHANNEL_ALARM = "bet_manager_alarm_channel"
        const val CHANNEL_PRESIGNAL = "bet_manager_presignal_channel"
        private const val NOTIFICATION_ID_ALARM = 9001
        private const val NOTIFICATION_ID_PRESIGNAL = 9002
    }
}
