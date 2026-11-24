package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "round_records")
data class RoundRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roundId: String,
    val timestampMs: Long,
    val crashMultiplier: Double,
    val stakePlaced: Double? = null,
    val targetMultiplier: Double? = null,
    val won: Boolean? = null,
    val profitDelta: Double = 0.0,
    val strategyName: String? = null,
    val siteName: String = "BetPawa"
)
