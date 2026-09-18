package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val runtimeType: String, // "NODE", "PYTHON"
    val rootPath: String,
    val entryPoint: String,
    val packageManager: String, // "npm", "yarn", "pnpm", "pip", "pipenv", "poetry", "builtin"
    val dependencyStatus: String, // "NOT_ANALYZED", "DEPENDENCIES_FOUND", "INSTALLING", "INSTALLED", "FAILED", "NONE_REQUIRED"
    val dependencyCount: Int = 0,
    val dependencySummary: String = "",
    val processStatus: String = "IDLE", // "IDLE", "STARTING", "RUNNING", "STOPPING", "STOPPED", "FAILED"
    val lastExitCode: Int? = null,
    val lastStartTime: Long? = null,
    val lastStopTime: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
