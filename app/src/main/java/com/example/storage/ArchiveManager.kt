package com.example.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ArchiveManager {

    sealed class ExtractionResult {
        data class Success(val extractedFilesCount: Int, val rootDir: File) : ExtractionResult()
        data class SecurityError(val reason: String) : ExtractionResult()
        data class Failure(val error: String, val cause: Throwable? = null) : ExtractionResult()
    }

    /**
     * Safely extracts a ZIP archive from an InputStream into the target directory,
     * strictly verifying that no Zip-Slip / Path Traversal attacks (e.g. "../../malicious") can escape.
     */
    fun extractZipSafely(inputStream: InputStream, destinationDir: File): ExtractionResult {
        if (!destinationDir.exists()) {
            destinationDir.mkdirs()
        }

        val canonicalDestDir = destinationDir.canonicalFile
        var extractedCount = 0

        return try {
            ZipInputStream(inputStream).use { zipStream ->
                var entry: ZipEntry? = zipStream.nextEntry
                while (entry != null) {
                    val entryName = entry.name

                    // Security check 1: Reject explicit path traversal sequences
                    if (entryName.contains("..") || entryName.startsWith("/") || entryName.startsWith("\\")) {
                        val testFile = File(destinationDir, entryName)
                        val canonicalTarget = testFile.canonicalFile
                        if (!canonicalTarget.path.startsWith(canonicalDestDir.path + File.separator) &&
                            canonicalTarget.path != canonicalDestDir.path
                        ) {
                            return ExtractionResult.SecurityError(
                                "Malicious archive entry rejected: '$entryName' attempts directory traversal outside of workspace."
                            )
                        }
                    }

                    val targetFile = File(destinationDir, entryName)
                    val canonicalTarget = targetFile.canonicalFile

                    // Security check 2: Canonical path validation
                    if (!canonicalTarget.path.startsWith(canonicalDestDir.path + File.separator) &&
                        canonicalTarget.path != canonicalDestDir.path
                    ) {
                        return ExtractionResult.SecurityError(
                            "Zip Slip vulnerability detected: '$entryName' resolves to '${canonicalTarget.path}', which is outside destination."
                        )
                    }

                    if (entry.isDirectory) {
                        canonicalTarget.mkdirs()
                    } else {
                        canonicalTarget.parentFile?.mkdirs()
                        FileOutputStream(canonicalTarget).use { output ->
                            zipStream.copyTo(output)
                        }
                        extractedCount++
                    }

                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
            }
            ExtractionResult.Success(extractedCount, destinationDir)
        } catch (securityEx: SecurityException) {
            ExtractionResult.SecurityError("Security violation while extracting ZIP: ${securityEx.message}")
        } catch (e: Exception) {
            ExtractionResult.Failure("Failed to extract archive: ${e.message ?: "Unknown I/O error"}", e)
        }
    }

    fun listProjectFiles(dir: File): List<FileSummary> {
        val list = mutableListOf<FileSummary>()
        if (!dir.exists() || !dir.isDirectory) return list

        dir.walkTopDown().maxDepth(6).forEach { file ->
            if (file != dir) {
                val relative = file.relativeTo(dir).path
                // Skip cluttering with huge node_modules internals in top listing
                if (!relative.contains("node_modules/") && !relative.contains("__pycache__/") && !relative.contains(".git/")) {
                    list.add(
                        FileSummary(
                            name = file.name,
                            relativePath = relative,
                            isDirectory = file.isDirectory,
                            sizeBytes = if (file.isFile) file.length() else 0L
                        )
                    )
                }
            }
        }
        return list.sortedWith(compareBy({ !it.isDirectory }, { it.relativePath }))
    }

    data class FileSummary(
        val name: String,
        val relativePath: String,
        val isDirectory: Boolean,
        val sizeBytes: Long
    )
}
