package com.example.data

import android.content.Context
import android.net.Uri
import com.example.analyzer.ProjectAnalysisResult
import com.example.analyzer.ProjectAnalyzer
import com.example.dependency.DependencyManager
import com.example.storage.ArchiveManager
import com.example.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ProjectRepository(
    private val context: Context,
    private val projectDao: ProjectDao,
    private val storageManager: StorageManager,
    private val dependencyManager: DependencyManager
) {

    val allProjects: Flow<List<ProjectEntity>> = projectDao.getAllProjects()

    fun getProject(id: String): Flow<ProjectEntity?> = projectDao.getProjectById(id)

    suspend fun getProjectDirect(id: String): ProjectEntity? = projectDao.getProjectDirect(id)

    suspend fun importProjectFromZip(uri: Uri, chosenName: String?): Result<ProjectEntity> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val inputStream = resolver.openInputStream(uri)
            ?: return@withContext Result.failure(IllegalArgumentException("Unable to read selected ZIP file."))

        val targetName = chosenName?.ifBlank { null } ?: "imported_project"
        val projectDir = storageManager.createNewProjectDirectory(targetName)

        val extractionResult = ArchiveManager.extractZipSafely(inputStream, projectDir)
        when (extractionResult) {
            is ArchiveManager.ExtractionResult.SecurityError -> {
                storageManager.deleteProjectDirectory(projectDir.path)
                return@withContext Result.failure(SecurityException(extractionResult.reason))
            }
            is ArchiveManager.ExtractionResult.Failure -> {
                storageManager.deleteProjectDirectory(projectDir.path)
                return@withContext Result.failure(Exception(extractionResult.error, extractionResult.cause))
            }
            is ArchiveManager.ExtractionResult.Success -> {
                // Analyze extracted project
                val analysis = ProjectAnalyzer.analyze(projectDir)
                val finalName = if (!chosenName.isNullOrBlank()) chosenName else analysis.detectedName

                val entity = ProjectEntity(
                    id = UUID.randomUUID().toString(),
                    name = finalName,
                    runtimeType = analysis.runtimeType,
                    rootPath = projectDir.absolutePath,
                    entryPoint = analysis.entryPoint,
                    packageManager = analysis.packageManager,
                    dependencyStatus = if (analysis.dependencies.isNotEmpty()) "DEPENDENCIES_FOUND" else "NONE_REQUIRED",
                    dependencyCount = analysis.dependencies.size,
                    dependencySummary = "${analysis.dependencies.size} packages defined",
                    processStatus = "IDLE",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )

                projectDao.insertProject(entity)
                Result.success(entity)
            }
        }
    }

    suspend fun createSampleProject(type: String): Result<ProjectEntity> = withContext(Dispatchers.IO) {
        val projectDir = if (type == "NODE") {
            storageManager.createSampleNodeProject()
        } else {
            storageManager.createSamplePythonProject()
        }

        val analysis = ProjectAnalyzer.analyze(projectDir)
        val entity = ProjectEntity(
            id = UUID.randomUUID().toString(),
            name = if (type == "NODE") "Sample Node.js App" else "Sample Python Script",
            runtimeType = analysis.runtimeType,
            rootPath = projectDir.absolutePath,
            entryPoint = analysis.entryPoint,
            packageManager = analysis.packageManager,
            dependencyStatus = if (analysis.dependencies.isNotEmpty()) "DEPENDENCIES_FOUND" else "NONE_REQUIRED",
            dependencyCount = analysis.dependencies.size,
            dependencySummary = "${analysis.dependencies.size} packages defined",
            processStatus = "IDLE",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        projectDao.insertProject(entity)
        Result.success(entity)
    }

    suspend fun reanalyzeProject(project: ProjectEntity): ProjectAnalysisResult = withContext(Dispatchers.IO) {
        val dir = File(project.rootPath)
        val analysis = ProjectAnalyzer.analyze(dir)

        val updated = project.copy(
            runtimeType = analysis.runtimeType,
            entryPoint = if (project.entryPoint.isEmpty() || !File(dir, project.entryPoint).exists()) analysis.entryPoint else project.entryPoint,
            packageManager = analysis.packageManager,
            dependencyStatus = if (analysis.dependencies.isNotEmpty()) {
                if (File(dir, "node_modules").exists() || File(dir, "site-packages").exists()) "INSTALLED" else "DEPENDENCIES_FOUND"
            } else "NONE_REQUIRED",
            dependencyCount = analysis.dependencies.size,
            dependencySummary = "${analysis.dependencies.size} packages declared",
            updatedAt = System.currentTimeMillis()
        )
        projectDao.updateProject(updated)
        analysis
    }

    suspend fun installDependencies(
        project: ProjectEntity,
        onProgress: (String, Float) -> Unit,
        logCallback: (String, Boolean) -> Unit
    ): DependencyManager.InstallResult = withContext(Dispatchers.IO) {
        val dir = File(project.rootPath)
        val analysis = ProjectAnalyzer.analyze(dir)

        projectDao.updateDependencyState(project.id, "INSTALLING", analysis.dependencies.size, "Installing packages...")

        val result = dependencyManager.installDependencies(
            projectDir = dir,
            runtimeType = project.runtimeType,
            dependencies = analysis.dependencies,
            onProgress = onProgress,
            logCallback = logCallback
        )

        when (result) {
            is DependencyManager.InstallResult.Success -> {
                projectDao.updateDependencyState(
                    project.id,
                    "INSTALLED",
                    result.installedCount,
                    "Installed ${result.installedCount} packages"
                )
            }
            is DependencyManager.InstallResult.Failed -> {
                projectDao.updateDependencyState(
                    project.id,
                    "FAILED",
                    analysis.dependencies.size,
                    "Error: ${result.error}"
                )
            }
        }
        result
    }

    suspend fun updateEntryPoint(projectId: String, newEntryPoint: String) {
        val project = projectDao.getProjectDirect(projectId) ?: return
        projectDao.updateProject(project.copy(entryPoint = newEntryPoint, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteProject(project: ProjectEntity) = withContext(Dispatchers.IO) {
        storageManager.deleteProjectDirectory(project.rootPath)
        projectDao.deleteProjectById(project.id)
    }

    suspend fun reconcileStartupState() = withContext(Dispatchers.IO) {
        projectDao.reconcileCrashedProcesses()
    }
}
