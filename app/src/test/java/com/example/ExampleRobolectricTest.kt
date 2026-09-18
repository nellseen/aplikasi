package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.analyzer.ProjectAnalyzer
import com.example.runtime.node.NodeRuntimeEngine
import com.example.runtime.python.PythonRuntimeEngine
import com.example.storage.StorageManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Runtime Manager", appName)
    }

    @Test
    fun `storage manager creates and analyzes sample node project`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = StorageManager(context)
        val projectDir = storageManager.createSampleNodeProject()

        val analysis = ProjectAnalyzer.analyze(projectDir)
        assertEquals("NODE", analysis.runtimeType)
        assertEquals("index.js", analysis.entryPoint)
        assertEquals("npm", analysis.packageManager)
        assertTrue(analysis.dependencies.isNotEmpty())
    }

    @Test
    fun `storage manager creates and analyzes sample python project`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = StorageManager(context)
        val projectDir = storageManager.createSamplePythonProject()

        val analysis = ProjectAnalyzer.analyze(projectDir)
        assertEquals("PYTHON", analysis.runtimeType)
        assertEquals("main.py", analysis.entryPoint)
        assertEquals("pip", analysis.packageManager)
        assertTrue(analysis.dependencies.isNotEmpty())
    }

    @Test
    fun `node runtime executes javascript successfully`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = StorageManager(context)
        val projectDir = storageManager.createSampleNodeProject()
        val entryFile = File(projectDir, "index.js")

        val engine = NodeRuntimeEngine()
        val logs = mutableListOf<String>()

        val exitCode = engine.execute(
            entryFile = entryFile,
            workingDir = projectDir,
            scope = this,
            logCallback = { msg, _ -> logs.add(msg) }
        )

        println("NODE_TEST_LOGS: ${logs.joinToString(" || ")}")
        assertEquals(0, exitCode)
        assertTrue(logs.any { it.contains("Hello from Node.js") })
    }

    @Test
    fun `python runtime executes python script successfully`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = StorageManager(context)
        val projectDir = storageManager.createSamplePythonProject()
        val entryFile = File(projectDir, "main.py")

        val engine = PythonRuntimeEngine()
        val logs = mutableListOf<String>()

        val exitCode = engine.execute(
            entryFile = entryFile,
            workingDir = projectDir,
            customPythonBin = null,
            logCallback = { msg, _ -> logs.add(msg) }
        )

        println("PYTHON_TEST_LOGS: ${logs.joinToString(" || ")}")
        assertEquals(0, exitCode)
        assertTrue(logs.any { it.contains("Hello from Python") })
    }
}

