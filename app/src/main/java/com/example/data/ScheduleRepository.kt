package com.example.data

import kotlinx.coroutines.flow.Flow

class ScheduleRepository(private val dao: BlockScheduleDao) {
    val allSchedules: Flow<List<BlockSchedule>> = dao.getAllSchedules()

    suspend fun getActiveSchedules(): List<BlockSchedule> = dao.getActiveSchedules()

    suspend fun insert(schedule: BlockSchedule): Long = dao.insertSchedule(schedule)

    suspend fun update(schedule: BlockSchedule) = dao.updateSchedule(schedule)

    suspend fun delete(schedule: BlockSchedule) = dao.deleteSchedule(schedule)

    suspend fun getById(id: Int): BlockSchedule? = dao.getScheduleById(id)
}
