package com.example.process

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class LogManager {

    private val projectLogs = ConcurrentHashMap<String, MutableStateFlow<List<LogEntry>>>()
    private val maxBufferLines = 1000

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun getLogFlow(projectId: String): StateFlow<List<LogEntry>> {
        return projectLogs.computeIfAbsent(projectId) {
            MutableStateFlow(emptyList())
        }.asStateFlow()
    }

    fun appendLog(projectId: String, message: String, isError: Boolean = false, logFile: File? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            message = message,
            isError = isError
        )

        val flow = projectLogs.computeIfAbsent(projectId) {
            MutableStateFlow(emptyList())
        }

        synchronized(flow) {
            val current = flow.value
            val next = if (current.size >= maxBufferLines) {
                current.drop(current.size - maxBufferLines + 1) + entry
            } else {
                current + entry
            }
            flow.value = next
        }

        // Persist to disk log file if provided
        logFile?.let { file ->
            try {
                file.parentFile?.mkdirs()
                PrintWriter(FileOutputStream(file, true)).use { writer ->
                    val timeStr = dateFormat.format(Date(entry.timestamp))
                    val prefix = if (isError) "[STDERR]" else "[STDOUT]"
                    writer.println("[$timeStr] $prefix $message")
                }
            } catch (_: Exception) {
                // Ignore background logging errors
            }
        }
    }

    fun clearLogs(projectId: String, logFile: File? = null) {
        projectLogs[projectId]?.value = emptyList()
        logFile?.let { file ->
            try {
                if (file.exists()) {
                    file.writeText("")
                }
            } catch (_: Exception) {}
        }
    }

    fun getFormattedLogs(projectId: String): String {
        val entries = projectLogs[projectId]?.value ?: emptyList()
        return entries.joinToString("\n") { entry ->
            val timeStr = dateFormat.format(Date(entry.timestamp))
            val prefix = if (entry.isError) "[ERR]" else "[OUT]"
            "[$timeStr] $prefix ${entry.message}"
        }
    }
}
