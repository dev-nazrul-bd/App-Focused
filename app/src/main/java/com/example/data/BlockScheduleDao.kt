package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockScheduleDao {
    @Query("SELECT * FROM block_schedules ORDER BY id DESC")
    fun getAllSchedules(): Flow<List<BlockSchedule>>

    @Query("SELECT * FROM block_schedules WHERE isActive = 1")
    suspend fun getActiveSchedules(): List<BlockSchedule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: BlockSchedule): Long

    @Update
    suspend fun updateSchedule(schedule: BlockSchedule)

    @Delete
    suspend fun deleteSchedule(schedule: BlockSchedule)

    @Query("SELECT * FROM block_schedules WHERE id = :id")
    suspend fun getScheduleById(id: Int): BlockSchedule?
}
