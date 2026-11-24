package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.DiagnosticLogEntry
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
        DiagnosticLogEntry::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class BetManagerDatabase : RoomDatabase() {
    abstract fun betManagerDao(): BetManagerDao

    companion object {
        @Volatile
        private var INSTANCE: BetManagerDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ensure new BetManager StrategyConfig columns exist if upgrading from legacy v1.
                runCatching {
                    db.execSQL("ALTER TABLE strategy_config ADD COLUMN afterLoss TEXT NOT NULL DEFAULT 'DOUBLE'")
                }
                runCatching {
                    db.execSQL("ALTER TABLE strategy_config ADD COLUMN addAmount REAL NOT NULL DEFAULT 10.0")
                }
                runCatching {
                    db.execSQL("ALTER TABLE strategy_config ADD COLUMN stopAfterLosses INTEGER NOT NULL DEFAULT 3")
                }
                runCatching {
                    db.execSQL("ALTER TABLE strategy_config ADD COLUMN useGameAutoCashOut INTEGER NOT NULL DEFAULT 1")
                }
                runCatching {
                    db.execSQL("ALTER TABLE strategy_config ADD COLUMN scanHeartbeatMs INTEGER NOT NULL DEFAULT 5000")
                }
                runCatching {
                    db.execSQL("ALTER TABLE strategy_config ADD COLUMN stakeTolerance REAL NOT NULL DEFAULT 0.05")
                }
                runCatching {
                    db.execSQL("ALTER TABLE strategy_config ADD COLUMN showFloatingHand INTEGER NOT NULL DEFAULT 1")
                }
            }
        }

        fun getInstance(context: Context): BetManagerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BetManagerDatabase::class.java,
                    "betmanager_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { db ->
                        INSTANCE = db
                        CoroutineScope(Dispatchers.IO).launch {
                            seedDefaultsIfNeeded(db.betManagerDao())
                        }
                    }
            }
        }

        suspend fun seedDefaultsIfNeeded(dao: BetManagerDao) {
            if (dao.getConfig() == null) {
                dao.upsertConfig(StrategyConfig())
            }
            if (dao.countRules() == 0) {
                dao.insertRules(defaultStrategyRules())
            }
        }

        fun defaultStrategyRules(): List<StrategyRule> = listOf(
            StrategyRule(
                name = "3 Low Rounds Trigger",
                description = "Bet after 3 consecutive crashes below 2.00x",
                type = StrategyRuleType.CONSECUTIVE_LOW_MULTIPLIERS,
                enabled = true,
                thresholdMultiplier = 2.0,
                consecutiveCount = 3,
                priority = 1
            ),
            StrategyRule(
                name = "Blue Streak Recovery",
                description = "Trigger after 4 Blue rounds (<2.00x) in a row",
                type = StrategyRuleType.COLOR_TIER_STREAK,
                enabled = false,
                colorTier = "BLUE",
                consecutiveCount = 4,
                priority = 2
            ),
            StrategyRule(
                name = "Deep Dip Pattern",
                description = "Custom pattern: <1.50,<1.50,<2.00",
                type = StrategyRuleType.CUSTOM_PATTERN,
                enabled = false,
                customPattern = "<1.50,<1.50,<2.00",
                waitRoundsAfterTrigger = 0,
                priority = 3
            ),
            StrategyRule(
                name = "Post-Pink Cooldown",
                description = "Wait 2 rounds and 15s cooldown after a >=10x crash",
                type = StrategyRuleType.TIME_COOLDOWN,
                enabled = false,
                thresholdMultiplier = 10.0,
                cooldownSeconds = 15,
                waitRoundsAfterTrigger = 2,
                priority = 4
            )
        )
    }
}
