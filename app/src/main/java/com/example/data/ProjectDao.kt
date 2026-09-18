package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun getProjectById(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectDirect(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: String)

    @Query("UPDATE projects SET processStatus = 'STOPPED' WHERE processStatus IN ('RUNNING', 'STARTING')")
    suspend fun reconcileCrashedProcesses()

    @Query("UPDATE projects SET processStatus = :status, lastExitCode = :exitCode, lastStopTime = :stopTime WHERE id = :id")
    suspend fun updateProcessState(id: String, status: String, exitCode: Int?, stopTime: Long?)

    @Query("UPDATE projects SET dependencyStatus = :status, dependencyCount = :count, dependencySummary = :summary WHERE id = :id")
    suspend fun updateDependencyState(id: String, status: String, count: Int, summary: String)
}
