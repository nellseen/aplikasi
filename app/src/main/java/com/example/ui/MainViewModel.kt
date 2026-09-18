package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.analyzer.ProjectAnalysisResult
import com.example.analyzer.ProjectAnalyzer
import com.example.data.AppDatabase
import com.example.data.ProjectEntity
import com.example.data.ProjectRepository
import com.example.dependency.DependencyManager
import com.example.process.ActiveProcessInfo
import com.example.process.LogEntry
import com.example.process.LogManager
import com.example.process.ProcessManager
import com.example.process.ProcessStatus
import com.example.storage.ArchiveManager
import com.example.storage.StorageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val storageManager = StorageManager(application)
    val logManager = LogManager()
    val dependencyManager = DependencyManager()
    val processManager = ProcessManager(application, logManager, storageManager)

    private val db = AppDatabase.getInstance(application)
    val repository = ProjectRepository(application, db.projectDao(), storageManager, dependencyManager)

    val projects: StateFlow<List<ProjectEntity>> = repository.allProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedProjectId = MutableStateFlow<String?>(null)
    val selectedProjectId = _selectedProjectId.asStateFlow()

    val selectedProject: StateFlow<ProjectEntity?> = _selectedProjectId.flatMapLatest { id ->
        if (id == null) flowOf(null) else repository.getProject(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _projectAnalysis = MutableStateFlow<ProjectAnalysisResult?>(null)
    val projectAnalysis = _projectAnalysis.asStateFlow()

    private val _projectFiles = MutableStateFlow<List<ArchiveManager.FileSummary>>(emptyList())
    val projectFiles = _projectFiles.asStateFlow()

    private val _installationProgress = MutableStateFlow<Pair<String, Float>?>(null)
    val installationProgress = _installationProgress.asStateFlow()

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage = _operationMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repository.reconcileStartupState()
        }
    }

    fun selectProject(project: ProjectEntity?) {
        _selectedProjectId.value = project?.id
        if (project != null) {
            refreshProjectDetails(project)
        } else {
            _projectAnalysis.value = null
            _projectFiles.value = emptyList()
        }
    }

    fun refreshProjectDetails(project: ProjectEntity) {
        viewModelScope.launch {
            val dir = File(project.rootPath)
            if (dir.exists()) {
                val analysis = ProjectAnalyzer.analyze(dir)
                _projectAnalysis.value = analysis
                _projectFiles.value = ArchiveManager.listProjectFiles(dir)
            }
        }
    }

    fun importZip(uri: Uri, chosenName: String?) {
        viewModelScope.launch {
            _operationMessage.value = "Extracting & analyzing project..."
            val result = repository.importProjectFromZip(uri, chosenName)
            result.onSuccess { entity ->
                _operationMessage.value = "Project '${entity.name}' imported successfully!"
                selectProject(entity)
            }.onFailure { error ->
                _operationMessage.value = "Import failed: ${error.message}"
            }
        }
    }

    fun createSampleProject(type: String) {
        viewModelScope.launch {
            _operationMessage.value = "Generating $type sample project..."
            val result = repository.createSampleProject(type)
            result.onSuccess { entity ->
                _operationMessage.value = "Created '${entity.name}'"
                selectProject(entity)
            }.onFailure { error ->
                _operationMessage.value = "Creation failed: ${error.message}"
            }
        }
    }

    fun startProject(project: ProjectEntity) {
        viewModelScope.launch {
            val res = processManager.startProject(
                projectId = project.id,
                name = project.name,
                runtimeType = project.runtimeType,
                rootPath = project.rootPath,
                entryPoint = project.entryPoint
            )
            res.onFailure { err ->
                _operationMessage.value = "Start failed: ${err.message}"
            }
        }
    }

    fun stopProject(project: ProjectEntity) {
        viewModelScope.launch {
            processManager.stopProject(project.id)
        }
    }

    fun restartProject(project: ProjectEntity) {
        viewModelScope.launch {
            processManager.restartProject(
                projectId = project.id,
                name = project.name,
                runtimeType = project.runtimeType,
                rootPath = project.rootPath,
                entryPoint = project.entryPoint
            )
        }
    }

    fun installDependencies(project: ProjectEntity) {
        viewModelScope.launch {
            _installationProgress.value = Pair("Starting dependency manager...", 0f)
            val logFile = storageManager.getLogFile(File(project.rootPath))
            val result = repository.installDependencies(
                project = project,
                onProgress = { msg, frac ->
                    _installationProgress.value = Pair(msg, frac)
                },
                logCallback = { msg, isErr ->
                    logManager.appendLog(project.id, msg, isErr, logFile)
                }
            )
            when (result) {
                is DependencyManager.InstallResult.Success -> {
                    _operationMessage.value = result.message
                }
                is DependencyManager.InstallResult.Failed -> {
                    _operationMessage.value = "Installation error: ${result.error}"
                }
            }
            _installationProgress.value = null
            refreshProjectDetails(project)
        }
    }

    fun updateEntryPoint(project: ProjectEntity, newEntryPoint: String) {
        viewModelScope.launch {
            repository.updateEntryPoint(project.id, newEntryPoint)
        }
    }

    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch {
            if (processManager.isRunning(project.id)) {
                processManager.stopProject(project.id)
            }
            repository.deleteProject(project)
            if (_selectedProjectId.value == project.id) {
                selectProject(null)
            }
            _operationMessage.value = "Project '${project.name}' deleted."
        }
    }

    fun clearLogs(projectId: String, rootPath: String) {
        val logFile = storageManager.getLogFile(File(rootPath))
        logManager.clearLogs(projectId, logFile)
    }

    fun clearOperationMessage() {
        _operationMessage.value = null
    }

    fun getProcessInfoFlow(projectId: String): StateFlow<ActiveProcessInfo> {
        return processManager.getProcessInfoFlow(projectId)
    }

    fun getLogFlow(projectId: String): StateFlow<List<LogEntry>> {
        return logManager.getLogFlow(projectId)
    }
}
