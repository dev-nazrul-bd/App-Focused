package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "block_schedules")
data class BlockSchedule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String = "Focus Schedule",
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val blockedApps: List<String> = emptyList(),
    val isActive: Boolean = true,
    val blockType: String = "DEFAULT", // "DEFAULT", "IMAGE", "PDF", "VIDEO", "WEBSITE"
    val blockContent: String = "" // Message, URL or file path
)
