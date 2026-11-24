package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class LogCategory {
    DETECTION,
    STRATEGY,
    STAKE,
    BET,
    RESULT,
    SKIP,
    ERROR
}

@Entity(tableName = "diagnostic_logs")
data class DiagnosticLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val category: LogCategory,
    val message: String,
    val details: String = ""
)
