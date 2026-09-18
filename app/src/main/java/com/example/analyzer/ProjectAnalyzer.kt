package com.example.analyzer

import org.json.JSONObject
import java.io.File

data class DependencyItem(
    val name: String,
    val versionSpec: String,
    val type: String // "prod", "dev", "peer", "optional"
)

data class ProjectAnalysisResult(
    val runtimeType: String, // "NODE", "PYTHON", "UNKNOWN"
    val packageManager: String, // "npm", "yarn", "pnpm", "pip", "poetry", "pipenv", "builtin"
    val detectedName: String,
    val entryPoint: String,
    val entryPointCandidates: List<String>,
    val dependencies: List<DependencyItem>,
    val scripts: Map<String, String>,
    val lockfileFound: String?,
    val metadataFile: String?
)

object ProjectAnalyzer {

    fun analyze(projectDir: File): ProjectAnalysisResult {
        val files = projectDir.listFiles()?.toList() ?: emptyList()

        // 1. Check for Node.js signatures
        val packageJson = files.find { it.name.equals("package.json", ignoreCase = true) }
        val packageLock = files.find { it.name.equals("package-lock.json", ignoreCase = true) }
        val yarnLock = files.find { it.name.equals("yarn.lock", ignoreCase = true) }
        val pnpmLock = files.find { it.name.equals("pnpm-lock.yaml", ignoreCase = true) }

        // 2. Check for Python signatures
        val requirementsTxt = files.find { it.name.equals("requirements.txt", ignoreCase = true) }
        val pyprojectToml = files.find { it.name.equals("pyproject.toml", ignoreCase = true) }
        val pipfile = files.find { it.name.equals("Pipfile", ignoreCase = true) }
        val pipfileLock = files.find { it.name.equals("Pipfile.lock", ignoreCase = true) }
        val setupPy = files.find { it.name.equals("setup.py", ignoreCase = true) }
        val setupCfg = files.find { it.name.equals("setup.cfg", ignoreCase = true) }

        val hasNodeFiles = packageJson != null || packageLock != null || yarnLock != null || pnpmLock != null
        val hasPythonFiles = requirementsTxt != null || pyprojectToml != null || pipfile != null || setupPy != null || setupCfg != null

        // Count .js vs .py files
        val jsFileCount = files.count { it.extension.equals("js", ignoreCase = true) || it.extension.equals("mjs", ignoreCase = true) }
        val pyFileCount = files.count { it.extension.equals("py", ignoreCase = true) }

        return if (hasNodeFiles || (jsFileCount > pyFileCount && pyFileCount == 0 && jsFileCount > 0)) {
            analyzeNodeProject(projectDir, packageJson, packageLock, yarnLock, pnpmLock)
        } else if (hasPythonFiles || pyFileCount > 0) {
            analyzePythonProject(projectDir, requirementsTxt, pyprojectToml, pipfile, setupPy, setupCfg)
        } else {
            // Unknown or generic
            ProjectAnalysisResult(
                runtimeType = "UNKNOWN",
                packageManager = "none",
                detectedName = projectDir.name,
                entryPoint = "",
                entryPointCandidates = emptyList(),
                dependencies = emptyList(),
                scripts = emptyMap(),
                lockfileFound = null,
                metadataFile = null
            )
        }
    }

    private fun analyzeNodeProject(
        dir: File,
        packageJson: File?,
        packageLock: File?,
        yarnLock: File?,
        pnpmLock: File?
    ): ProjectAnalysisResult {
        var detectedName = dir.name
        var specifiedMain = ""
        val scripts = mutableMapOf<String, String>()
        val deps = mutableListOf<DependencyItem>()

        if (packageJson != null && packageJson.exists()) {
            try {
                val json = JSONObject(packageJson.readText(Charsets.UTF_8))
                if (json.has("name")) {
                    detectedName = json.getString("name")
                }
                if (json.has("main")) {
                    specifiedMain = json.getString("main")
                }
                if (json.has("scripts")) {
                    val sObj = json.getJSONObject("scripts")
                    sObj.keys().forEach { key ->
                        scripts[key] = sObj.getString(key)
                    }
                }
                parseNodeDeps(json, "dependencies", "prod", deps)
                parseNodeDeps(json, "devDependencies", "dev", deps)
                parseNodeDeps(json, "peerDependencies", "peer", deps)
                parseNodeDeps(json, "optionalDependencies", "optional", deps)
            } catch (_: Exception) {
                // Ignore parse failure, proceed to file scanning
            }
        }

        // Package manager detection
        val packageManager = when {
            pnpmLock != null -> "pnpm"
            yarnLock != null -> "yarn"
            packageLock != null -> "npm"
            else -> "npm"
        }

        val lockfileName = when {
            pnpmLock != null -> "pnpm-lock.yaml"
            yarnLock != null -> "yarn.lock"
            packageLock != null -> "package-lock.json"
            else -> null
        }

        // Entry point resolution
        val candidates = mutableListOf<String>()
        if (specifiedMain.isNotEmpty()) {
            candidates.add(specifiedMain)
        }
        val commonEntryFiles = listOf(
            "index.js", "server.js", "app.js", "main.js", "src/index.js", "src/main.js", "src/server.js", "src/app.js"
        )
        for (cand in commonEntryFiles) {
            val f = File(dir, cand)
            if (f.exists() && !candidates.contains(cand)) {
                candidates.add(cand)
            }
        }
        if (candidates.isEmpty()) {
            // Find any .js file
            dir.walkTopDown().maxDepth(3).filter { it.isFile && it.extension == "js" }.firstOrNull()?.let {
                candidates.add(it.relativeTo(dir).path)
            }
        }

        val selectedEntry = candidates.firstOrNull() ?: "index.js"

        return ProjectAnalysisResult(
            runtimeType = "NODE",
            packageManager = packageManager,
            detectedName = detectedName,
            entryPoint = selectedEntry,
            entryPointCandidates = candidates,
            dependencies = deps,
            scripts = scripts,
            lockfileFound = lockfileName,
            metadataFile = packageJson?.name
        )
    }

    private fun parseNodeDeps(
        json: JSONObject,
        key: String,
        type: String,
        out: MutableList<DependencyItem>
    ) {
        if (json.has(key)) {
            val obj = json.getJSONObject(key)
            obj.keys().forEach { depName ->
                out.add(DependencyItem(depName, obj.optString(depName, "*"), type))
            }
        }
    }

    private fun analyzePythonProject(
        dir: File,
        requirementsTxt: File?,
        pyprojectToml: File?,
        pipfile: File?,
        setupPy: File?,
        setupCfg: File?
    ): ProjectAnalysisResult {
        val detectedName = dir.name
        val deps = mutableListOf<DependencyItem>()
        var packageManager = "pip"
        var metadataFile: String? = null

        if (requirementsTxt != null && requirementsTxt.exists()) {
            metadataFile = "requirements.txt"
            packageManager = "pip"
            requirementsTxt.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("==", ">=", "<=", "~=", ">", "<")
                    val name = parts[0].trim()
                    val version = if (parts.size > 1) trimmed.substring(name.length).trim() else "*"
                    deps.add(DependencyItem(name, version, "prod"))
                }
            }
        } else if (pyprojectToml != null && pyprojectToml.exists()) {
            metadataFile = "pyproject.toml"
            packageManager = if (pyprojectToml.readText().contains("[tool.poetry]")) "poetry" else "pip"
            parsePyprojectToml(pyprojectToml, deps)
        } else if (pipfile != null && pipfile.exists()) {
            metadataFile = "Pipfile"
            packageManager = "pipenv"
            parsePipfile(pipfile, deps)
        } else if (setupPy != null && setupPy.exists()) {
            metadataFile = "setup.py"
            packageManager = "pip"
        }

        // Candidates for Python entry points
        val candidates = mutableListOf<String>()
        val commonPythonEntryFiles = listOf(
            "main.py", "app.py", "server.py", "run.py", "manage.py", "wsgi.py", "src/main.py", "src/app.py"
        )
        for (cand in commonPythonEntryFiles) {
            val f = File(dir, cand)
            if (f.exists() && !candidates.contains(cand)) {
                candidates.add(cand)
            }
        }
        if (candidates.isEmpty()) {
            dir.walkTopDown().maxDepth(3).filter { it.isFile && it.extension == "py" }.firstOrNull()?.let {
                candidates.add(it.relativeTo(dir).path)
            }
        }

        val selectedEntry = candidates.firstOrNull() ?: "main.py"

        return ProjectAnalysisResult(
            runtimeType = "PYTHON",
            packageManager = packageManager,
            detectedName = detectedName,
            entryPoint = selectedEntry,
            entryPointCandidates = candidates,
            dependencies = deps,
            scripts = emptyMap(),
            lockfileFound = if (File(dir, "Pipfile.lock").exists()) "Pipfile.lock" else null,
            metadataFile = metadataFile
        )
    }

    private fun parsePyprojectToml(file: File, out: MutableList<DependencyItem>) {
        var inDepsSection = false
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[tool.poetry.dependencies]") || trimmed.startsWith("dependencies = [")) {
                inDepsSection = true
            } else if (trimmed.startsWith("[") && inDepsSection) {
                inDepsSection = false
            } else if (inDepsSection) {
                if (trimmed.contains("=") && !trimmed.startsWith("#")) {
                    val key = trimmed.substringBefore("=").trim().replace("\"", "").replace("'", "")
                    val value = trimmed.substringAfter("=").trim().replace("\"", "").replace("'", "")
                    if (key.isNotEmpty() && key != "python") {
                        out.add(DependencyItem(key, value, "prod"))
                    }
                }
            }
        }
    }

    private fun parsePipfile(file: File, out: MutableList<DependencyItem>) {
        var inPackages = false
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.equals("[packages]", ignoreCase = true)) {
                inPackages = true
            } else if (trimmed.startsWith("[") && inPackages) {
                inPackages = false
            } else if (inPackages && trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val key = trimmed.substringBefore("=").trim().replace("\"", "").replace("'", "")
                val value = trimmed.substringAfter("=").trim().replace("\"", "").replace("'", "")
                if (key.isNotEmpty()) {
                    out.add(DependencyItem(key, value, "prod"))
                }
            }
        }
    }
}
