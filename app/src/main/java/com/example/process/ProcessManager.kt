package com.example.process

import android.content.Context
import com.example.data.AppDatabase
import com.example.runtime.node.NodeRuntimeEngine
import com.example.runtime.python.PythonRuntimeEngine
import com.example.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ProcessManager(
    private val context: Context,
    val logManager: LogManager,
    private val storageManager: StorageManager
) {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val processInfos = ConcurrentHashMap<String, MutableStateFlow<ActiveProcessInfo>>()
    private val pidCounter = AtomicLong(1000)

    private val nodeEngine = NodeRuntimeEngine()
    private val pythonEngine = PythonRuntimeEngine()
    private val db by lazy { AppDatabase.getInstance(context) }

    fun getProcessInfoFlow(projectId: String): StateFlow<ActiveProcessInfo> {
        return processInfos.computeIfAbsent(projectId) {
            MutableStateFlow(
                ActiveProcessInfo(
                    projectId = projectId,
                    pid = 0,
                    status = ProcessStatus.IDLE,
                    startTime = 0,
                    entryPoint = "",
                    workingDirectory = "",
                    runtime = ""
                )
            )
        }.asStateFlow()
    }

    fun isRunning(projectId: String): Boolean {
        val state = processInfos[projectId]?.value?.status
        return state == ProcessStatus.RUNNING || state == ProcessStatus.STARTING
    }

    suspend fun startProject(
        projectId: String,
        name: String,
        runtimeType: String,
        rootPath: String,
        entryPoint: String
    ): Result<Unit> {
        if (isRunning(projectId)) {
            return Result.failure(IllegalStateException("Project is already running!"))
        }

        val workingDir = File(rootPath)
        if (!workingDir.exists() || !workingDir.isDirectory) {
            return Result.failure(IllegalArgumentException("Working directory not found: $rootPath"))
        }

        val entryFile = File(workingDir, entryPoint)
        if (!entryFile.exists()) {
            return Result.failure(IllegalArgumentException("Entry point '$entryPoint' does not exist in project directory."))
        }

        val logFile = storageManager.getLogFile(workingDir)
        val pid = pidCounter.incrementAndGet()
        val startTime = System.currentTimeMillis()

        updateState(
            projectId = projectId,
            info = ActiveProcessInfo(
                projectId = projectId,
                pid = pid,
                status = ProcessStatus.STARTING,
                startTime = startTime,
                entryPoint = entryPoint,
                workingDirectory = rootPath,
                runtime = runtimeType
            )
        )

        db.projectDao().updateProcessState(projectId, "STARTING", null, null)
        logManager.appendLog(projectId, "==> Initializing process [PID $pid]...", false, logFile)

        val job = processScope.launch {
            updateState(
                projectId = projectId,
                info = ActiveProcessInfo(
                    projectId = projectId,
                    pid = pid,
                    status = ProcessStatus.RUNNING,
                    startTime = startTime,
                    entryPoint = entryPoint,
                    workingDirectory = rootPath,
                    runtime = runtimeType
                )
            )
            db.projectDao().updateProcessState(projectId, "RUNNING", null, null)

            val exitCode: Int = try {
                if (runtimeType == "NODE") {
                    nodeEngine.execute(
                        entryFile = entryFile,
                        workingDir = workingDir,
                        scope = this,
                        logCallback = { msg, isErr ->
                            logManager.appendLog(projectId, msg, isErr, logFile)
                        }
                    )
                } else {
                    pythonEngine.execute(
                        entryFile = entryFile,
                        workingDir = workingDir,
                        customPythonBin = null,
                        logCallback = { msg, isErr ->
                            logManager.appendLog(projectId, msg, isErr, logFile)
                        }
                    )
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                logManager.appendLog(projectId, "[Process SIGTERM received - terminating]", false, logFile)
                130
            } catch (t: Throwable) {
                logManager.appendLog(projectId, "Fatal execution error: ${t.message}", true, logFile)
                1
            }

            val stopTime = System.currentTimeMillis()
            val finalStatus = if (exitCode == 0) ProcessStatus.STOPPED else ProcessStatus.FAILED

            updateState(
                projectId = projectId,
                info = ActiveProcessInfo(
                    projectId = projectId,
                    pid = pid,
                    status = finalStatus,
                    startTime = startTime,
                    entryPoint = entryPoint,
                    workingDirectory = rootPath,
                    runtime = runtimeType,
                    exitCode = exitCode
                )
            )

            db.projectDao().updateProcessState(projectId, finalStatus.name, exitCode, stopTime)
            activeJobs.remove(projectId)
        }

        activeJobs[projectId] = job
        return Result.success(Unit)
    }

    suspend fun stopProject(projectId: String): Result<Unit> {
        val job = activeJobs[projectId]
        if (job == null || !job.isActive) {
            val current = processInfos[projectId]?.value
            if (current != null && (current.status == ProcessStatus.RUNNING || current.status == ProcessStatus.STARTING)) {
                updateState(projectId, current.copy(status = ProcessStatus.STOPPED))
                db.projectDao().updateProcessState(projectId, "STOPPED", null, System.currentTimeMillis())
            }
            return Result.success(Unit)
        }

        val current = processInfos[projectId]?.value
        if (current != null) {
            updateState(projectId, current.copy(status = ProcessStatus.STOPPING))
        }

        logManager.appendLog(projectId, "==> Requesting process stop...", false)
        job.cancel()
        job.join()
        activeJobs.remove(projectId)

        if (current != null) {
            updateState(projectId, current.copy(status = ProcessStatus.STOPPED, exitCode = 130))
        }
        db.projectDao().updateProcessState(projectId, "STOPPED", 130, System.currentTimeMillis())
        logManager.appendLog(projectId, "[Process stopped successfully]", false)
        return Result.success(Unit)
    }

    suspend fun restartProject(
        projectId: String,
        name: String,
        runtimeType: String,
        rootPath: String,
        entryPoint: String
    ): Result<Unit> {
        logManager.appendLog(projectId, "==> Restarting process...", false)
        if (isRunning(projectId)) {
            stopProject(projectId)
            delay(300)
        }
        return startProject(projectId, name, runtimeType, rootPath, entryPoint)
    }

    private fun updateState(projectId: String, info: ActiveProcessInfo) {
        val flow = processInfos.computeIfAbsent(projectId) {
            MutableStateFlow(info)
        }
        flow.value = info
    }
}
