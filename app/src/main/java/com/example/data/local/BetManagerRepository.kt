package com.example.data.local

import android.content.Context
import com.example.data.model.AlarmProfileCategory
import com.example.data.model.AlarmProfileEntity
import com.example.data.model.CrashRecord
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.DiagnosticLogEntry
import com.example.data.model.LogCategory
import com.example.data.model.MultiplierRecord
import com.example.data.model.NotificationHistoryRecord
import com.example.data.model.NotificationInteractionStatus
import com.example.data.model.NotificationSettings
import com.example.data.model.PatternOccurrenceRecord
import com.example.data.model.RoundRecord
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.Calendar

class BetManagerRepository(
    private val context: Context,
    private val dao: BetManagerDao
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("betmanager_state", Context.MODE_PRIVATE)

    private val _handAutoBetFlow = MutableStateFlow(prefs.getBoolean(KEY_HAND_AUTO_BET, false))
    val handAutoBetFlow: StateFlow<Boolean> = _handAutoBetFlow.asStateFlow()

    private val _keepScreenAwakeFlow = MutableStateFlow(prefs.getBoolean(KEY_KEEP_SCREEN_AWAKE, false))
    val keepScreenAwakeFlow: StateFlow<Boolean> = _keepScreenAwakeFlow.asStateFlow()

    private val _alarmHighSignalFlow = MutableStateFlow(prefs.getBoolean(KEY_ALARM_HIGH_SIGNAL, true))
    val alarmHighSignalFlow: StateFlow<Boolean> = _alarmHighSignalFlow.asStateFlow()

    private val _preSignalNotificationFlow = MutableStateFlow(prefs.getBoolean(KEY_PRESIGNAL_NOTIFICATION, true))
    val preSignalNotificationFlow: StateFlow<Boolean> = _preSignalNotificationFlow.asStateFlow()

    private val _alertSoundFlow = MutableStateFlow(prefs.getBoolean(KEY_ALERT_SOUND, true))
    val alertSoundFlow: StateFlow<Boolean> = _alertSoundFlow.asStateFlow()

    private val _alertVibrationFlow = MutableStateFlow(prefs.getBoolean(KEY_ALERT_VIBRATION, true))
    val alertVibrationFlow: StateFlow<Boolean> = _alertVibrationFlow.asStateFlow()

    val configFlow: Flow<StrategyConfig> = dao.observeConfig().map { it ?: StrategyConfig() }
    val rulesFlow: Flow<List<StrategyRule>> = dao.observeRules()
    val recentRoundsFlow: Flow<List<RoundRecord>> = dao.observeRecentRounds()
    val lastFiveBetResultsFlow: Flow<List<RoundRecord>> = dao.observeLastFiveBetResults()
    val logsFlow: Flow<List<DiagnosticLogEntry>> = dao.observeLogs()

    val customStrategiesFlow: Flow<List<CustomPatternStrategy>> = dao.observeCustomStrategies()
    val patternOccurrencesFlow: Flow<List<PatternOccurrenceRecord>> = dao.observePatternOccurrences()
    val crashRecordsFlow: Flow<List<CrashRecord>> = dao.observeCrashRecords()
    val multiplierRecordsFlow: Flow<List<MultiplierRecord>> = dao.observeMultiplierRecords()
    val alarmProfilesFlow: Flow<List<AlarmProfileEntity>> = dao.observeAlarmProfiles()
    val notificationSettingsFlow: Flow<NotificationSettings> =
        dao.observeNotificationSettings().map { it ?: NotificationSettings() }
    val notificationHistoryFlow: Flow<List<NotificationHistoryRecord>> = dao.observeNotificationHistory()

    fun todayProfitFlow(): Flow<Double> = dao.observeTodayProfit(startOfTodayMs())

    suspend fun ensureInitialized() {
        BetManagerDatabase.seedDefaultsIfNeeded(dao)
    }

    suspend fun getConfig(): StrategyConfig = dao.getConfig() ?: StrategyConfig()

    suspend fun saveConfig(config: StrategyConfig) {
        dao.upsertConfig(config)
    }

    suspend fun getRules(): List<StrategyRule> = dao.getRules()

    suspend fun insertRule(rule: StrategyRule): Long = dao.insertRule(rule)

    suspend fun updateRule(rule: StrategyRule) = dao.updateRule(rule)

    suspend fun deleteRule(rule: StrategyRule) = dao.deleteRule(rule)

    // --- 4-Strategy CustomPatternStrategy & PatternTelemetry ---

    suspend fun getCustomStrategies(): List<CustomPatternStrategy> = dao.getCustomStrategies()

    suspend fun saveCustomStrategy(strategy: CustomPatternStrategy): Long {
        val assignedOrder = if (strategy.id == 0L && strategy.sortOrder == 0) {
            dao.getMaxCustomStrategySortOrder() + 1
        } else {
            strategy.sortOrder
        }
        return dao.upsertCustomStrategy(strategy.copy(sortOrder = assignedOrder))
    }

    suspend fun updateCustomStrategy(strategy: CustomPatternStrategy) {
        dao.updateCustomStrategy(strategy)
    }

    suspend fun setCustomStrategyEnabled(id: Long, enabled: Boolean) {
        dao.setCustomStrategyEnabled(id, enabled)
    }

    suspend fun setCustomStrategyNotificationsEnabled(id: Long, enabled: Boolean) {
        dao.setCustomStrategyNotificationsEnabled(id, enabled)
    }

    suspend fun deleteCustomStrategy(id: Long) {
        dao.deleteCustomStrategyById(id)
    }

    suspend fun swapStrategyOrder(firstId: Long, secondId: Long) {
        val list = dao.getCustomStrategies()
        val firstIdx = list.indexOfFirst { it.id == firstId }
        val secondIdx = list.indexOfFirst { it.id == secondId }
        if (firstIdx < 0 || secondIdx < 0) return

        val reordered = list.mapIndexed { idx, s ->
            when (idx) {
                firstIdx -> s.copy(sortOrder = secondIdx)
                secondIdx -> s.copy(sortOrder = firstIdx)
                else -> s.copy(sortOrder = idx)
            }
        }
        reordered.forEach { dao.upsertCustomStrategy(it) }
    }

    suspend fun getCrashRecords(): List<CrashRecord> = dao.getCrashRecords()

    suspend fun insertConfirmedCrashIfAbsent(
        crashRecord: CrashRecord,
        multiplierRecord: MultiplierRecord
    ): Long {
        dao.insertMultiplierRecord(multiplierRecord)
        return dao.insertCrashRecord(crashRecord)
    }

    suspend fun getMultiplierRecords(): List<MultiplierRecord> = dao.getMultiplierRecords()

    suspend fun recordPatternOccurrences(records: List<PatternOccurrenceRecord>) {
        if (records.isNotEmpty()) {
            dao.insertPatternOccurrences(records)
        }
    }

    suspend fun recordPatternOccurrence(record: PatternOccurrenceRecord): Long {
        return dao.insertPatternOccurrence(record)
    }

    suspend fun getNotificationSettings(): NotificationSettings {
        return dao.getNotificationSettings() ?: NotificationSettings()
    }

    suspend fun saveNotificationSettings(settings: NotificationSettings) {
        dao.upsertNotificationSettings(settings)
    }

    suspend fun getAlarmProfiles(): List<AlarmProfileEntity> {
        val existing = dao.getAlarmProfiles()
        return if (existing.isEmpty()) {
            AlarmProfileEntity.defaultProfiles().also { dao.upsertAlarmProfiles(it) }
        } else {
            existing
        }
    }

    suspend fun getAlarmProfile(category: AlarmProfileCategory): AlarmProfileEntity? {
        return dao.getAlarmProfile(category.name)
    }

    suspend fun saveAlarmProfile(profile: AlarmProfileEntity) {
        dao.upsertAlarmProfile(profile)
    }

    suspend fun addNotificationHistory(record: NotificationHistoryRecord): Long {
        return dao.insertNotificationHistory(record)
    }

    suspend fun markNotificationInteraction(id: Long, status: NotificationInteractionStatus) {
        dao.updateNotificationInteractionStatus(id, status)
    }

    suspend fun getRecentRounds(): List<RoundRecord> = dao.getRecentRoundsOnce()

    suspend fun recordRound(round: RoundRecord): Long = dao.insertRound(round)

    suspend fun getTodayProfit(): Double = dao.getTodayProfitOnce(startOfTodayMs())

    suspend fun log(category: LogCategory, message: String, details: String = "") {
        dao.insertLog(
            DiagnosticLogEntry(
                timestampMs = System.currentTimeMillis(),
                category = category,
                message = message,
                details = details
            )
        )
        dao.trimOldLogs()
    }

    suspend fun clearLogs() = dao.clearLogs()

    fun getSavedCurrentStake(defaultBase: Double): Double {
        val raw = prefs.getFloat(KEY_CURRENT_STAKE, -1f)
        return if (raw > 0f) raw.toDouble() else defaultBase
    }

    fun getSavedRecoveryDeficit(): Double {
        val raw = prefs.getFloat(KEY_RECOVERY_DEFICIT, 0f)
        return if (raw > 0f) raw.toDouble() else 0.0
    }

    fun saveSessionProgression(currentStake: Double, consecutiveLosses: Int, recoveryDeficit: Double = 0.0) {
        prefs.edit()
            .putFloat(KEY_CURRENT_STAKE, currentStake.toFloat())
            .putInt(KEY_CONSECUTIVE_LOSSES, consecutiveLosses)
            .putFloat(KEY_RECOVERY_DEFICIT, recoveryDeficit.toFloat())
            .apply()
    }

    fun getConsecutiveLosses(): Int = prefs.getInt(KEY_CONSECUTIVE_LOSSES, 0)

    fun getConsumedTriggerSignatures(): Set<String> {
        return prefs.getStringSet(KEY_CONSUMED_TRIGGERS, emptySet()) ?: emptySet()
    }

    fun saveConsumedTriggerSignature(signature: String) {
        if (signature.isBlank()) return
        val current = prefs.getStringSet(KEY_CONSUMED_TRIGGERS, emptySet()) ?: emptySet()
        val updated = (current.filter { it != signature } + signature).takeLast(200).toSet()
        prefs.edit().putStringSet(KEY_CONSUMED_TRIGGERS, updated).apply()
    }

    fun getOverlayPosition(defaultX: Int, defaultY: Int): Pair<Int, Int> {
        val x = prefs.getInt(KEY_HAND_X, defaultX)
        val y = prefs.getInt(KEY_HAND_Y, defaultY)
        return x to y
    }

    fun saveOverlayPosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_HAND_X, x).putInt(KEY_HAND_Y, y).apply()
    }

    fun resetOverlayPosition() {
        prefs.edit()
            .remove(KEY_HAND_X)
            .remove(KEY_HAND_Y)
            .remove(KEY_BET_HAND_X)
            .remove(KEY_BET_HAND_Y)
            .remove(KEY_TARGET_BOUNDS)
            .apply()
    }

    fun getBetHandPosition(defaultX: Int, defaultY: Int): Pair<Int, Int> {
        val x = prefs.getInt(KEY_BET_HAND_X, defaultX)
        val y = prefs.getInt(KEY_BET_HAND_Y, defaultY)
        return x to y
    }

    fun saveBetHandPosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_BET_HAND_X, x).putInt(KEY_BET_HAND_Y, y).apply()
    }

    fun saveCalibratedBetBounds(left: Int, top: Int, right: Int, bottom: Int) {
        prefs.edit().putString(KEY_TARGET_BOUNDS, "$left,$top,$right,$bottom").apply()
    }

    fun getCalibratedBetBounds(): com.example.service.RectBounds? {
        val raw = prefs.getString(KEY_TARGET_BOUNDS, null) ?: return null
        val parts = raw.split(',').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return null
        val b = com.example.service.RectBounds(parts[0], parts[1], parts[2], parts[3])
        return if (b.isEmpty) null else b
    }

    fun saveCalibratedHandBounds(left: Int, top: Int, right: Int, bottom: Int) {
        prefs.edit().putString(KEY_HAND_DROP_BOUNDS, "$left,$top,$right,$bottom").apply()
    }

    fun getCalibratedHandBounds(): com.example.service.RectBounds? {
        val raw = prefs.getString(KEY_HAND_DROP_BOUNDS, null) ?: return null
        val parts = raw.split(',').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return null
        val b = com.example.service.RectBounds(parts[0], parts[1], parts[2], parts[3])
        return if (b.isEmpty) null else b
    }

    fun isHandAutoBetEnabled(): Boolean = prefs.getBoolean(KEY_HAND_AUTO_BET, false)

    fun setHandAutoBetEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAND_AUTO_BET, enabled).apply()
        _handAutoBetFlow.value = enabled
    }

    fun isKeepScreenAwakeEnabled(): Boolean = prefs.getBoolean(KEY_KEEP_SCREEN_AWAKE, false)

    fun setKeepScreenAwakeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_AWAKE, enabled).apply()
        _keepScreenAwakeFlow.value = enabled
    }

    fun isAlarmHighSignalEnabled(): Boolean = prefs.getBoolean(KEY_ALARM_HIGH_SIGNAL, true)

    fun setAlarmHighSignalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALARM_HIGH_SIGNAL, enabled).apply()
        _alarmHighSignalFlow.value = enabled
    }

    fun isPreSignalNotificationEnabled(): Boolean = prefs.getBoolean(KEY_PRESIGNAL_NOTIFICATION, true)

    fun setPreSignalNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PRESIGNAL_NOTIFICATION, enabled).apply()
        _preSignalNotificationFlow.value = enabled
    }

    fun isAlertSoundEnabled(): Boolean = prefs.getBoolean(KEY_ALERT_SOUND, true)

    fun setAlertSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALERT_SOUND, enabled).apply()
        _alertSoundFlow.value = enabled
    }

    fun isAlertVibrationEnabled(): Boolean = prefs.getBoolean(KEY_ALERT_VIBRATION, true)

    fun setAlertVibrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALERT_VIBRATION, enabled).apply()
        _alertVibrationFlow.value = enabled
    }

    private fun startOfTodayMs(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    companion object {
        private const val KEY_CURRENT_STAKE = "current_stake"
        private const val KEY_CONSECUTIVE_LOSSES = "consecutive_losses"
        private const val KEY_RECOVERY_DEFICIT = "recovery_deficit"
        private const val KEY_CONSUMED_TRIGGERS = "consumed_trigger_signatures"
        private const val KEY_HAND_X = "overlay_hand_x"
        private const val KEY_HAND_Y = "overlay_hand_y"
        private const val KEY_BET_HAND_X = "bet_hand_x"
        private const val KEY_BET_HAND_Y = "bet_hand_y"
        private const val KEY_TARGET_BOUNDS = "calibrated_bet_bounds"
        private const val KEY_HAND_DROP_BOUNDS = "calibrated_hand_drop_bounds"
        private const val KEY_HAND_AUTO_BET = "hand_auto_bet_enabled"
        private const val KEY_KEEP_SCREEN_AWAKE = "keep_screen_awake"
        private const val KEY_ALARM_HIGH_SIGNAL = "alarm_high_signal_enabled"
        private const val KEY_PRESIGNAL_NOTIFICATION = "presignal_notification_enabled"
        private const val KEY_ALERT_SOUND = "alert_sound_enabled"
        private const val KEY_ALERT_VIBRATION = "alert_vibration_enabled"
    }
}
