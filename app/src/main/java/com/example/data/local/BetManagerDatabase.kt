package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.AlarmProfileEntity
import com.example.data.model.CrashRecord
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.DiagnosticLogEntry
import com.example.data.model.MultiplierRecord
import com.example.data.model.NotificationHistoryRecord
import com.example.data.model.NotificationSettings
import com.example.data.model.PatternOccurrenceRecord
import com.example.data.model.RoundRecord
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyRule
import com.example.data.model.StrategyRuleType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        StrategyConfig::class,
        StrategyRule::class,
        RoundRecord::class,
        DiagnosticLogEntry::class,
        CustomPatternStrategy::class,
        PatternOccurrenceRecord::class,
        AlarmProfileEntity::class,
        NotificationSettings::class,
        NotificationHistoryRecord::class,
        CrashRecord::class,
        MultiplierRecord::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BetManagerDatabase : RoomDatabase() {
    abstract fun betManagerDao(): BetManagerDao

    companion object {
        @Volatile
        private var INSTANCE: BetManagerDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS custom_pattern_strategies (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, isEnabled INTEGER NOT NULL, strategyGroup TEXT NOT NULL, strategyMode TEXT NOT NULL, multiplierRuleSubtype TEXT NOT NULL, logicOperator TEXT NOT NULL, conditionsJson TEXT NOT NULL, aboveThresholdX REAL NOT NULL, aboveConsecutiveCount INTEGER NOT NULL, belowThresholdY REAL NOT NULL, belowConsecutiveCount INTEGER NOT NULL, inclusiveBounds INTEGER NOT NULL, postBigWaitMinutes INTEGER NOT NULL, bigMultiplierThreshold REAL NOT NULL, specificAlertTimesCsv TEXT NOT NULL, colorMatchDirection TEXT NOT NULL, gridColumnsForVertical INTEGER NOT NULL, customColorHexFilterCsv TEXT NOT NULL, waitingPeriodSeconds INTEGER NOT NULL, notificationLevel TEXT NOT NULL, approachingEnabled INTEGER NOT NULL, customMessage TEXT NOT NULL, repeatPolicy TEXT NOT NULL, repeatCooldownMinutes INTEGER NOT NULL, repeatLimit INTEGER NOT NULL, notificationEnabled INTEGER NOT NULL, useAlarmStyle INTEGER NOT NULL, vibrationEnabled INTEGER NOT NULL, sortOrder INTEGER NOT NULL, requiredOccurrencesPerHour INTEGER NOT NULL, analysisWindowMinutes INTEGER NOT NULL, minIntervalMinutes INTEGER NOT NULL, maxIntervalMinutes INTEGER NOT NULL, colorSequenceCsv TEXT NOT NULL, targetGameId TEXT NOT NULL, lastEvaluatedRecordKey TEXT, lastTriggeredOccurrenceKey TEXT, lastApproachedOccurrenceKey TEXT, lastNotifiedAtMs INTEGER NOT NULL, currentlyApproaching INTEGER NOT NULL, currentlyReached INTEGER NOT NULL, createdAtMs INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS pattern_occurrences (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, occurrenceKey TEXT NOT NULL, strategyId INTEGER NOT NULL, strategyName TEXT NOT NULL, gameId TEXT NOT NULL, gameName TEXT NOT NULL, patternType TEXT NOT NULL, triggerTimestampMs INTEGER NOT NULL, hourStartTimestamp INTEGER NOT NULL, detailsSummary TEXT NOT NULL, firstMatchedTimestampMs INTEGER NOT NULL, intervalSincePrevMs INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS alarm_profiles (categoryName TEXT PRIMARY KEY NOT NULL, enabled INTEGER NOT NULL, soundUri TEXT NOT NULL, soundLabel TEXT NOT NULL, vibrationEnabled INTEGER NOT NULL, vibrationPattern TEXT NOT NULL, repeatEnabled INTEGER NOT NULL, repeatCooldownSeconds INTEGER NOT NULL, maxRepeatsPerEvent INTEGER NOT NULL, useAlarmStyle INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS notification_settings (id INTEGER PRIMARY KEY NOT NULL, masterEnabled INTEGER NOT NULL, approachingEnabled INTEGER NOT NULL, reachedEnabled INTEGER NOT NULL, invalidatedEnabled INTEGER NOT NULL, historyUnavailableEnabled INTEGER NOT NULL, pausedEnabled INTEGER NOT NULL, hourlySummaryEnabled INTEGER NOT NULL, gameDetectionEnabled INTEGER NOT NULL, monitoringErrorsEnabled INTEGER NOT NULL, standardAlertEnabled INTEGER NOT NULL, standardSoundUri TEXT NOT NULL, standardVibrationPattern TEXT NOT NULL, standardRepeatedAlerts INTEGER NOT NULL, highPriorityAlarmEnabled INTEGER NOT NULL, alarmSoundUri TEXT NOT NULL, alarmVibrationPattern TEXT NOT NULL, alarmRepeated INTEGER NOT NULL, alarmRepeatIntervalMinutes INTEGER NOT NULL, alarmMaxDurationMinutes INTEGER NOT NULL, soundEnabled INTEGER NOT NULL, vibrationEnabled INTEGER NOT NULL, repeatIntervalMinutes INTEGER NOT NULL, quietHoursEnabled INTEGER NOT NULL, quietStartHour INTEGER NOT NULL, quietStartMinute INTEGER NOT NULL, quietEndHour INTEGER NOT NULL, quietEndMinute INTEGER NOT NULL, soundSourceType TEXT NOT NULL, deliveryMode TEXT NOT NULL, channelConfigVersion INTEGER NOT NULL, executionAlertsEnabled INTEGER NOT NULL, failureAlertsEnabled INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS notification_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, strategyId INTEGER NOT NULL, strategyName TEXT NOT NULL, notificationType TEXT NOT NULL, title TEXT NOT NULL, message TEXT NOT NULL, triggeringRecordKey TEXT, triggeringMultiplier REAL, timestampMs INTEGER NOT NULL, deliveryStatus TEXT NOT NULL, interactionStatus TEXT NOT NULL, channelId TEXT NOT NULL, gameId TEXT NOT NULL, gameName TEXT NOT NULL, occurrenceRecordId INTEGER)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS crash_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId TEXT NOT NULL, gameId TEXT NOT NULL, gameName TEXT NOT NULL, originalDetectedTitle TEXT NOT NULL, multiplier REAL NOT NULL, detectedColorHex TEXT NOT NULL, colorCategory TEXT NOT NULL, isPixelColorVerified INTEGER NOT NULL, crashTimestamp INTEGER NOT NULL, hourStartTimestamp INTEGER NOT NULL, sourcePackage TEXT NOT NULL, roundIdentifier TEXT, confidence REAL NOT NULL, manuallyCorrected INTEGER NOT NULL, isConfirmed INTEGER NOT NULL, observationOrder INTEGER NOT NULL, collectionSource TEXT NOT NULL, dedupKey TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_crash_records_dedupKey ON crash_records (dedupKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_crash_records_sessionId ON crash_records (sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_crash_records_gameId ON crash_records (gameId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_crash_records_crashTimestamp ON crash_records (crashTimestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_crash_records_hourStartTimestamp ON crash_records (hourStartTimestamp)")

                db.execSQL("CREATE TABLE IF NOT EXISTS multiplier_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId TEXT NOT NULL, gameId TEXT NOT NULL, roundId TEXT, multiplier REAL NOT NULL, gameTimestampMs INTEGER, observationTimestampMs INTEGER NOT NULL, crashTimestamp INTEGER NOT NULL, platform TEXT NOT NULL, isConfirmed INTEGER NOT NULL, timestampSource TEXT NOT NULL, collectionSource TEXT NOT NULL, collectionStatus TEXT NOT NULL, dedupKey TEXT NOT NULL, sequenceIndex INTEGER NOT NULL, hasGapBefore INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_multiplier_records_dedupKey ON multiplier_records (dedupKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_multiplier_records_sessionId ON multiplier_records (sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_multiplier_records_crashTimestamp ON multiplier_records (crashTimestamp)")
            }
        }

        fun getInstance(context: Context): BetManagerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BetManagerDatabase::class.java,
                    "betmanager_db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { db ->
                        INSTANCE = db
                        CoroutineScope(Dispatchers.IO).launch {
                            seedDefaultsIfNeeded(db.betManagerDao())
                        }
                    }
            }
        }

        val LEGACY_SEEDED_RULE_NAMES = listOf(
            "3 Low Rounds Trigger",
            "Blue Streak Recovery",
            "Deep Dip Pattern",
            "Post-Pink Cooldown"
        )

        suspend fun seedDefaultsIfNeeded(dao: BetManagerDao) {
            if (dao.getConfig() == null) {
                dao.upsertConfig(StrategyConfig())
            }
            // Remove legacy default seeded rules so only user-configured strategies exist
            dao.deleteRulesByNames(LEGACY_SEEDED_RULE_NAMES)
            if (dao.getNotificationSettings() == null) {
                dao.upsertNotificationSettings(NotificationSettings())
            }
            if (dao.getAlarmProfiles().isEmpty()) {
                dao.upsertAlarmProfiles(AlarmProfileEntity.defaultProfiles())
            }
        }

        fun defaultStrategyRules(): List<StrategyRule> = emptyList()
    }
}
