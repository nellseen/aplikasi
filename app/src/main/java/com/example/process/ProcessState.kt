package com.example.process

enum class ProcessStatus {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}

data class ActiveProcessInfo(
    val projectId: String,
    val pid: Long,
    val status: ProcessStatus,
    val startTime: Long,
    val entryPoint: String,
    val workingDirectory: String,
    val runtime: String,
    val exitCode: Int? = null,
    val errorMessage: String? = null
)

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val isError: Boolean = false
)
