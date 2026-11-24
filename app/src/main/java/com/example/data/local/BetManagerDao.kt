package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.DiagnosticLogEntry
import com.example.data.model.RoundRecord
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyRule
import kotlinx.coroutines.flow.Flow

@Dao
interface BetManagerDao {
    @Query("SELECT * FROM strategy_config WHERE id = 1")
    fun observeConfig(): Flow<StrategyConfig?>

    @Query("SELECT * FROM strategy_config WHERE id = 1")
    suspend fun getConfig(): StrategyConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: StrategyConfig)

    @Query("SELECT * FROM strategy_rules ORDER BY priority ASC, id ASC")
    fun observeRules(): Flow<List<StrategyRule>>

    @Query("SELECT * FROM strategy_rules ORDER BY priority ASC, id ASC")
    suspend fun getRules(): List<StrategyRule>

    @Query("SELECT COUNT(*) FROM strategy_rules")
    suspend fun countRules(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: StrategyRule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<StrategyRule>)

    @Update
    suspend fun updateRule(rule: StrategyRule)

    @Delete
    suspend fun deleteRule(rule: StrategyRule)

    @Query("SELECT * FROM round_records ORDER BY timestampMs DESC, id DESC LIMIT 200")
    fun observeRecentRounds(): Flow<List<RoundRecord>>

    @Query("SELECT * FROM round_records WHERE stakePlaced IS NOT NULL ORDER BY timestampMs DESC, id DESC LIMIT 5")
    fun observeLastFiveBetResults(): Flow<List<RoundRecord>>

    @Query("SELECT * FROM round_records ORDER BY timestampMs DESC, id DESC LIMIT 50")
    suspend fun getRecentRoundsOnce(): List<RoundRecord>

    @Query("SELECT COALESCE(SUM(profitDelta), 0.0) FROM round_records WHERE timestampMs >= :dayStartMs AND stakePlaced IS NOT NULL")
    fun observeTodayProfit(dayStartMs: Long): Flow<Double>

    @Query("SELECT COALESCE(SUM(profitDelta), 0.0) FROM round_records WHERE timestampMs >= :dayStartMs AND stakePlaced IS NOT NULL")
    suspend fun getTodayProfitOnce(dayStartMs: Long): Double

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRound(round: RoundRecord): Long

    @Query("SELECT * FROM diagnostic_logs ORDER BY timestampMs DESC, id DESC LIMIT 300")
    fun observeLogs(): Flow<List<DiagnosticLogEntry>>

    @Insert
    suspend fun insertLog(entry: DiagnosticLogEntry)

    @Query("DELETE FROM diagnostic_logs WHERE id NOT IN (SELECT id FROM diagnostic_logs ORDER BY timestampMs DESC, id DESC LIMIT 500)")
    suspend fun trimOldLogs()

    @Query("DELETE FROM diagnostic_logs")
    suspend fun clearLogs()
}
