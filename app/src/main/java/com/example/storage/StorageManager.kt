package com.example.storage

import android.content.Context
import java.io.File
import java.util.UUID

class StorageManager(private val context: Context) {

    private val rootProjectsDir: File
        get() = File(context.filesDir, "projects").apply { if (!exists()) mkdirs() }

    private val runtimesDir: File
        get() = File(context.filesDir, "runtimes").apply { if (!exists()) mkdirs() }

    fun createNewProjectDirectory(projectName: String): File {
        val safeName = projectName.trim().replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val uniqueFolder = "${safeName}_${UUID.randomUUID().toString().take(8)}"
        val projectDir = File(rootProjectsDir, uniqueFolder)
        if (!projectDir.exists()) {
            projectDir.mkdirs()
        }
        // Create logs subdirectory
        File(projectDir, "logs").mkdirs()
        return projectDir
    }

    fun getProjectDirectory(path: String): File {
        return File(path)
    }

    fun deleteProjectDirectory(path: String): Boolean {
        val dir = File(path)
        return if (dir.exists()) {
            dir.deleteRecursively()
        } else {
            true
        }
    }

    fun getLogFile(projectDir: File): File {
        val logsDir = File(projectDir, "logs").apply { if (!exists()) mkdirs() }
        return File(logsDir, "execution.log")
    }

    fun readFileContent(file: File, maxBytes: Int = 100_000): String {
        if (!file.exists() || !file.isFile) return ""
        return try {
            val bytes = file.readBytes()
            if (bytes.size > maxBytes) {
                String(bytes.copyOf(maxBytes), Charsets.UTF_8) + "\n\n...[Truncated: file exceeds 100KB]..."
            } else {
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }
    }

    fun createSampleNodeProject(): File {
        val dir = createNewProjectDirectory("sample-node")
        val pkgJson = File(dir, "package.json")
        pkgJson.writeText(
            """
            {
              "name": "sample-node-app",
              "version": "1.0.0",
              "description": "Native Node.js test project for Android",
              "main": "index.js",
              "scripts": {
                "start": "node index.js"
              },
              "dependencies": {
                "mime-types": "^2.1.35"
              }
            }
            """.trimIndent()
        )

        val indexJs = File(dir, "index.js")
        indexJs.writeText(
            """
            console.log("=========================================");
            console.log("🚀 Hello from Node.js on Android Native Runtime!");
            console.log("Current working directory:", process.cwd());
            console.log("Platform:", process.platform, "Architecture:", process.arch);
            console.log("Node version:", process.version);
            console.log("=========================================");

            const fs = require('fs');
            const path = require('path');

            const testFile = path.join(process.cwd(), 'runtime_status.txt');
            fs.writeFileSync(testFile, 'Node.js runtime write verified at ' + new Date().toISOString() + '\n');
            console.log("File written successfully:", testFile);

            const content = fs.readFileSync(testFile, 'utf8');
            console.log("File read verification:", content.trim());

            console.log("Project environment checks completed successfully!");
            """.trimIndent()
        )
        return dir
    }

    fun createSamplePythonProject(): File {
        val dir = createNewProjectDirectory("sample-python")
        val reqTxt = File(dir, "requirements.txt")
        reqTxt.writeText(
            """
            urllib3>=2.0.0
            certifi>=2024.2.2
            """.trimIndent()
        )

        val mainPy = File(dir, "main.py")
        mainPy.writeText(
            """
            import sys
            import os
            import time
            import json
            import math

            print("=========================================")
            print("🐍 Hello from Python on Android Native Runtime!")
            print(f"Python Platform: {sys.platform}")
            print(f"Current Working Dir: {os.getcwd()}")
            print("=========================================")

            # Test computational logic
            primes = []
            for num in range(2, 50):
                is_prime = True
                for i in range(2, int(math.sqrt(num)) + 1):
                    if num % i == 0:
                        is_prime = False
                        break
                if is_prime:
                    primes.append(num)

            print(f"Calculated primes up to 50: {primes}")

            # Test File I/O
            log_path = os.path.join(os.getcwd(), "python_output.json")
            data = {
                "runtime": "Python 3 Native Engine",
                "timestamp": time.time(),
                "primes_count": len(primes),
                "status": "SUCCESS"
            }
            with open(log_path, "w") as f:
                json.dump(data, f)

            print(f"JSON metrics written successfully to: {log_path}")
            print("Python execution completed with code 0.")
            """.trimIndent()
        )
        return dir
    }
}
