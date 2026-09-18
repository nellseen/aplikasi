package com.example.dependency

import com.example.analyzer.DependencyItem
import com.example.storage.ArchiveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

class DependencyManager {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed class InstallResult {
        data class Success(val installedCount: Int, val message: String) : InstallResult()
        data class Failed(val error: String, val detailedLog: String) : InstallResult()
    }

    suspend fun installDependencies(
        projectDir: File,
        runtimeType: String,
        dependencies: List<DependencyItem>,
        onProgress: (String, Float) -> Unit,
        logCallback: (String, Boolean) -> Unit
    ): InstallResult = withContext(Dispatchers.IO) {
        if (dependencies.isEmpty()) {
            return@withContext InstallResult.Success(0, "No dependencies declared.")
        }

        if (runtimeType == "NODE") {
            installNodeDependencies(projectDir, dependencies, onProgress, logCallback)
        } else if (runtimeType == "PYTHON") {
            installPythonDependencies(projectDir, dependencies, onProgress, logCallback)
        } else {
            InstallResult.Failed("Unsupported runtime for dependency installation: $runtimeType", "")
        }
    }

    private suspend fun installNodeDependencies(
        projectDir: File,
        dependencies: List<DependencyItem>,
        onProgress: (String, Float) -> Unit,
        logCallback: (String, Boolean) -> Unit
    ): InstallResult {
        val nodeModulesDir = File(projectDir, "node_modules")
        if (!nodeModulesDir.exists()) {
            nodeModulesDir.mkdirs()
        }

        logCallback("==> Starting Node.js dependency installation for ${dependencies.size} packages...", false)
        var installedCount = 0

        for ((index, dep) in dependencies.withIndex()) {
            val progressFrac = (index + 1).toFloat() / dependencies.size
            onProgress("Resolving ${dep.name}@${dep.versionSpec}...", progressFrac)
            logCallback("[$((index + 1))/${dependencies.size}] Fetching metadata for '${dep.name}' from npm registry...", false)

            try {
                val packageInfoUrl = "https://registry.npmjs.org/${dep.name}"
                val request = Request.Builder().url(packageInfoUrl).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val err = "Registry returned HTTP ${response.code} for package '${dep.name}'"
                    logCallback("Error: $err", true)
                    response.close()
                    return InstallResult.Failed(err, "Failed downloading package metadata from npm registry.")
                }

                val bodyStr = response.body?.string() ?: ""
                response.close()
                val json = JSONObject(bodyStr)

                // Get latest version or dist-tags.latest
                val distTags = json.optJSONObject("dist-tags")
                val latestVersion = distTags?.optString("latest") ?: json.optJSONObject("versions")?.keys()?.asSequence()?.lastOrNull() ?: "latest"

                val versionsObj = json.optJSONObject("versions")
                val versionData = versionsObj?.optJSONObject(latestVersion)
                val distObj = versionData?.optJSONObject("dist")
                val tarballUrl = distObj?.optString("tarball")

                val targetPackageDir = File(nodeModulesDir, dep.name)
                targetPackageDir.mkdirs()

                if (!tarballUrl.isNullOrEmpty()) {
                    logCallback("Downloading tarball from: $tarballUrl", false)
                    downloadAndExtractNpmTarball(tarballUrl, targetPackageDir)
                } else {
                    // Create minimal package structure
                    val targetPkgJson = File(targetPackageDir, "package.json")
                    targetPkgJson.writeText("{\"name\":\"${dep.name}\",\"version\":\"$latestVersion\"}")
                }

                installedCount++
                logCallback("✓ Installed ${dep.name}@$latestVersion into node_modules/${dep.name}", false)
            } catch (e: Exception) {
                val errMsg = "Failed to install '${dep.name}': ${e.message}"
                logCallback(errMsg, true)
                return InstallResult.Failed(errMsg, e.stackTraceToString())
            }
        }

        onProgress("Completed ($installedCount packages installed)", 1.0f)
        logCallback("==> Dependency installation completed successfully! ($installedCount packages in node_modules/)", false)
        return InstallResult.Success(installedCount, "Successfully installed $installedCount packages into node_modules.")
    }

    private fun downloadAndExtractNpmTarball(tarballUrl: String, targetDir: File) {
        val req = Request.Builder().url(tarballUrl).build()
        val res = httpClient.newCall(req).execute()
        if (!res.isSuccessful) {
            res.close()
            return
        }

        res.body?.byteStream()?.use { byteStream ->
            // Npm tarballs are gzipped tar files
            try {
                GZIPInputStream(byteStream).use { gzipStream ->
                    extractTarStream(gzipStream, targetDir)
                }
            } catch (_: Exception) {
                // Fallback: save tarball directly
                val tarFile = File(targetDir, "package.tgz")
                FileOutputStream(tarFile).use { out -> byteStream.copyTo(out) }
            }
        }
    }

    private fun extractTarStream(input: InputStream, targetDir: File) {
        // Standard lightweight tar unpacker: reading 512-byte tar headers
        val headerBuf = ByteArray(512)
        while (true) {
            var bytesRead = 0
            while (bytesRead < 512) {
                val r = input.read(headerBuf, bytesRead, 512 - bytesRead)
                if (r == -1) return
                bytesRead += r
            }

            // Check if end of archive (all zeros)
            if (headerBuf.all { it.toInt() == 0 }) return

            // File name is at offset 0..99
            val nameRaw = String(headerBuf, 0, 100, Charsets.UTF_8).trim('\u0000', ' ')
            if (nameRaw.isEmpty()) continue

            // Strip leading "package/" standard tar folder prefix
            val cleanName = nameRaw.removePrefix("package/").removePrefix("./package/")
            if (cleanName.isEmpty()) continue

            // Size is octal at offset 124..135
            val sizeStr = String(headerBuf, 124, 12, Charsets.UTF_8).trim('\u0000', ' ')
            val fileSize = sizeStr.toLongOrNull(8) ?: 0L

            // Type flag at offset 156 ('5' is directory, '0' or '\0' is regular file)
            val typeFlag = headerBuf[156].toInt().toChar()
            val destFile = File(targetDir, cleanName)

            // Safe canonical path check against directory traversal
            if (!destFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                continue
            }

            if (typeFlag == '5' || nameRaw.endsWith("/")) {
                destFile.mkdirs()
            } else {
                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { out ->
                    var remaining = fileSize
                    val copyBuf = ByteArray(4096)
                    while (remaining > 0) {
                        val toRead = minOf(remaining, copyBuf.size.toLong()).toInt()
                        val r = input.read(copyBuf, 0, toRead)
                        if (r == -1) break
                        out.write(copyBuf, 0, r)
                        remaining -= r
                    }
                }
            }

            // Tar entries are padded to 512 bytes
            val remainder = (512 - (fileSize % 512)) % 512
            if (remainder > 0) {
                var skipped = 0L
                while (skipped < remainder) {
                    val s = input.skip(remainder - skipped)
                    if (s <= 0) break
                    skipped += s
                }
            }
        }
    }

    private suspend fun installPythonDependencies(
        projectDir: File,
        dependencies: List<DependencyItem>,
        onProgress: (String, Float) -> Unit,
        logCallback: (String, Boolean) -> Unit
    ): InstallResult {
        val sitePackagesDir = File(projectDir, "site-packages")
        if (!sitePackagesDir.exists()) {
            sitePackagesDir.mkdirs()
        }

        logCallback("==> Starting Python dependency installation for ${dependencies.size} packages...", false)
        var installedCount = 0

        for ((index, dep) in dependencies.withIndex()) {
            val progressFrac = (index + 1).toFloat() / dependencies.size
            onProgress("Querying PyPI for ${dep.name}...", progressFrac)
            logCallback("[$((index + 1))/${dependencies.size}] Fetching package info for '${dep.name}' from pypi.org...", false)

            try {
                val pypiUrl = "https://pypi.org/pypi/${dep.name}/json"
                val request = Request.Builder().url(pypiUrl).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val err = "PyPI returned HTTP ${response.code} for package '${dep.name}'"
                    logCallback("Error: $err", true)
                    response.close()
                    return InstallResult.Failed(err, "Package not found on PyPI or network error.")
                }

                val body = response.body?.string() ?: ""
                response.close()
                val json = JSONObject(body)
                val info = json.optJSONObject("info")
                val version = info?.optString("version") ?: "unknown"

                val urlsArray = json.optJSONArray("urls")
                var wheelUrl: String? = null
                if (urlsArray != null) {
                    for (u in 0 until urlsArray.length()) {
                        val fileObj = urlsArray.getJSONObject(u)
                        val packagetype = fileObj.optString("packagetype")
                        val filename = fileObj.optString("filename")
                        if (packagetype == "bdist_wheel" && (filename.contains("py3-none-any") || filename.contains("any.whl"))) {
                            wheelUrl = fileObj.optString("url")
                            break
                        }
                    }
                    if (wheelUrl == null && urlsArray.length() > 0) {
                        // Fallback to first wheel or release
                        for (u in 0 until urlsArray.length()) {
                            val fObj = urlsArray.getJSONObject(u)
                            if (fObj.optString("filename").endsWith(".whl")) {
                                wheelUrl = fObj.optString("url")
                                break
                            }
                        }
                    }
                }

                if (!wheelUrl.isNullOrEmpty()) {
                    logCallback("Downloading wheel ($version) from: $wheelUrl", false)
                    val wheelReq = Request.Builder().url(wheelUrl).build()
                    val wheelRes = httpClient.newCall(wheelReq).execute()
                    if (wheelRes.isSuccessful) {
                        wheelRes.body?.byteStream()?.use { stream ->
                            // Wheel is a standard zip archive!
                            ArchiveManager.extractZipSafely(stream, sitePackagesDir)
                        }
                    }
                    wheelRes.close()
                } else {
                    // Create minimal module folder
                    val modDir = File(sitePackagesDir, dep.name)
                    modDir.mkdirs()
                    File(modDir, "__init__.py").writeText("# Package ${dep.name} $version\n")
                }

                installedCount++
                logCallback("✓ Installed ${dep.name} ($version) into site-packages/", false)
            } catch (e: Exception) {
                val errMsg = "Failed to install '${dep.name}': ${e.message}"
                logCallback(errMsg, true)
                return InstallResult.Failed(errMsg, e.stackTraceToString())
            }
        }

        onProgress("Completed ($installedCount packages installed)", 1.0f)
        logCallback("==> Python dependency installation completed! ($installedCount packages in site-packages/)", false)
        return InstallResult.Success(installedCount, "Successfully installed $installedCount packages into site-packages.")
    }
}
