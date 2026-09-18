package com.example.runtime.node

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class NodeRuntimeEngine {

    class ExitException(val exitCode: Int) : RuntimeException("process.exit($exitCode)")

    suspend fun execute(
        entryFile: File,
        workingDir: File,
        scope: CoroutineScope,
        logCallback: (String, Boolean) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val cx = Context.enter()
        // Optimization level -1 is interpreted mode (works cleanly on Android without bytecode generation restrictions)
        cx.optimizationLevel = -1
        try {
            val globalScope = cx.initSafeStandardObjects()

            // Setup process object
            setupProcessObject(cx, globalScope, workingDir, entryFile, logCallback)

            // Setup console object
            setupConsoleObject(cx, globalScope, logCallback)

            // Setup timers (setTimeout, setInterval)
            setupTimers(cx, globalScope, scope)

            // Setup require system
            setupRequire(cx, globalScope, workingDir, logCallback)

            logCallback("$ node ${entryFile.name}", false)

            val scriptText = entryFile.readText(Charsets.UTF_8)
            val result = cx.evaluateString(globalScope, scriptText, entryFile.name, 1, null)
            if (result != null && result != Context.getUndefinedValue()) {
                val resStr = Context.toString(result)
                if (resStr != "undefined") {
                    logCallback(resStr, false)
                }
            }

            0 // Success exit code
        } catch (exitEx: ExitException) {
            logCallback("[Process exited with code ${exitEx.exitCode}]", exitEx.exitCode != 0)
            exitEx.exitCode
        } catch (cancellation: CancellationException) {
            logCallback("[Process terminated by user]", false)
            130 // Standard SIGINT exit code
        } catch (t: Throwable) {
            val msg = t.message ?: t.toString()
            logCallback("Runtime Error: $msg\n${t.stackTraceToString()}", true)
            1
        } finally {
            Context.exit()
        }
    }

    private fun setupConsoleObject(
        cx: Context,
        scope: Scriptable,
        logCallback: (String, Boolean) -> Unit
    ) {
        val console = cx.newObject(scope)

        fun createLogFn(isError: Boolean): Function {
            return object : org.mozilla.javascript.BaseFunction() {
                override fun call(
                    cx: Context?,
                    scope: Scriptable?,
                    thisObj: Scriptable?,
                    args: Array<out Any?>?
                ): Any? {
                    val message = args?.joinToString(" ") { arg ->
                        when (arg) {
                            null -> "null"
                            is Scriptable -> Context.toString(arg)
                            else -> arg.toString()
                        }
                    } ?: ""
                    logCallback(message, isError)
                    return Context.getUndefinedValue()
                }
            }
        }

        ScriptableObject.putProperty(console, "log", createLogFn(false))
        ScriptableObject.putProperty(console, "info", createLogFn(false))
        ScriptableObject.putProperty(console, "warn", createLogFn(true))
        ScriptableObject.putProperty(console, "error", createLogFn(true))

        ScriptableObject.putProperty(scope, "console", console)
    }

    private fun setupProcessObject(
        cx: Context,
        scope: Scriptable,
        workingDir: File,
        entryFile: File,
        logCallback: (String, Boolean) -> Unit
    ) {
        val process = cx.newObject(scope)

        ScriptableObject.putProperty(process, "cwd", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                return workingDir.absolutePath
            }
        })

        ScriptableObject.putProperty(process, "platform", "android")
        ScriptableObject.putProperty(process, "arch", System.getProperty("os.arch") ?: "arm64")
        ScriptableObject.putProperty(process, "version", "v18.16.0")

        // process.exit(code)
        ScriptableObject.putProperty(process, "exit", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                val code = if (args != null && args.isNotEmpty()) {
                    Context.toNumber(args[0]).toInt()
                } else 0
                throw ExitException(code)
            }
        })

        // process.argv
        val argvArray = cx.newArray(scope, 2)
        argvArray.put(0, argvArray, "node")
        argvArray.put(1, argvArray, entryFile.absolutePath)
        ScriptableObject.putProperty(process, "argv", argvArray)

        // process.env
        val envObj = cx.newObject(scope)
        System.getenv().forEach { (k, v) ->
            ScriptableObject.putProperty(envObj, k, v)
        }
        ScriptableObject.putProperty(process, "env", envObj)

        ScriptableObject.putProperty(scope, "process", process)
    }

    private fun setupTimers(
        cx: Context,
        scope: Scriptable,
        coroutineScope: CoroutineScope
    ) {
        val activeJobs = ConcurrentHashMap<Int, Job>()
        var timerIdCounter = 1

        ScriptableObject.putProperty(scope, "setTimeout", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                if (args == null || args.isEmpty()) return 0
                val callback = args[0] as? Function ?: return 0
                val delayMs = if (args.size > 1) Context.toNumber(args[1]).toLong() else 0L
                val id = timerIdCounter++

                val job = coroutineScope.launch(Dispatchers.IO) {
                    delay(delayMs)
                    if (isActive) {
                        val timerCx = Context.enter()
                        timerCx.optimizationLevel = -1
                        try {
                            callback.call(timerCx, scope, scope, emptyArray())
                        } catch (_: Exception) {
                        } finally {
                            Context.exit()
                            activeJobs.remove(id)
                        }
                    }
                }
                activeJobs[id] = job
                return id
            }
        })

        ScriptableObject.putProperty(scope, "clearTimeout", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                val id = if (args != null && args.isNotEmpty()) Context.toNumber(args[0]).toInt() else 0
                activeJobs.remove(id)?.cancel()
                return Context.getUndefinedValue()
            }
        })
    }

    private fun setupRequire(
        cx: Context,
        scope: Scriptable,
        workingDir: File,
        logCallback: (String, Boolean) -> Unit
    ) {
        val moduleCache = mutableMapOf<String, Any>()

        ScriptableObject.putProperty(scope, "require", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                val moduleName = args?.firstOrNull()?.let { Context.toString(it) } ?: return Context.getUndefinedValue()

                if (moduleCache.containsKey(moduleName)) {
                    return moduleCache[moduleName]!!
                }

                val targetCx = cx ?: Context.getCurrentContext() ?: Context.enter().apply { optimizationLevel = -1 }
                val targetScope = scope ?: targetCx.initSafeStandardObjects()

                // Builtin modules
                val moduleExport: Any = when (moduleName) {
                    "fs" -> createFsModule(targetCx, targetScope, workingDir)
                    "path" -> createPathModule(targetCx, targetScope)
                    "os" -> createOsModule(targetCx, targetScope)
                    else -> {
                        // Resolve file or node_modules
                        val resolvedFile = resolveModuleFile(moduleName, workingDir)
                        if (resolvedFile != null && resolvedFile.exists()) {
                            loadJsFileModule(targetCx, targetScope, resolvedFile, workingDir, logCallback)
                        } else {
                            throw RuntimeException("Cannot find module '$moduleName'")
                        }
                    }
                }

                moduleCache[moduleName] = moduleExport
                return moduleExport
            }
        })
    }

    private fun createFsModule(cx: Context, scope: Scriptable, workingDir: File): Scriptable {
        val fs = cx.newObject(scope)

        // readFileSync
        ScriptableObject.putProperty(fs, "readFileSync", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                val p = args?.firstOrNull()?.let { Context.toString(it) } ?: ""
                val file = resolveFilePath(p, workingDir)
                return if (file.exists() && file.isFile) {
                    file.readText(Charsets.UTF_8)
                } else {
                    throw RuntimeException("ENOENT: no such file or directory, open '$p'")
                }
            }
        })

        // writeFileSync
        ScriptableObject.putProperty(fs, "writeFileSync", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                val p = args?.firstOrNull()?.let { Context.toString(it) } ?: ""
                val content = if (args != null && args.size > 1) Context.toString(args[1]) else ""
                val file = resolveFilePath(p, workingDir)
                file.parentFile?.mkdirs()
                file.writeText(content, Charsets.UTF_8)
                return Context.getUndefinedValue()
            }
        })

        // existsSync
        ScriptableObject.putProperty(fs, "existsSync", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                val p = args?.firstOrNull()?.let { Context.toString(it) } ?: ""
                val file = resolveFilePath(p, workingDir)
                return file.exists()
            }
        })

        // readdirSync
        ScriptableObject.putProperty(fs, "readdirSync", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                val p = args?.firstOrNull()?.let { Context.toString(it) } ?: ""
                val file = resolveFilePath(p, workingDir)
                val list = file.list() ?: emptyArray()
                val targetCx = cx ?: Context.enter()
                val targetScope = scope ?: targetCx.initSafeStandardObjects()
                val arr = targetCx.newArray(targetScope, list.size)
                list.forEachIndexed { idx, item ->
                    arr.put(idx, arr, item)
                }
                return arr
            }
        })

        return fs
    }

    private fun createPathModule(cx: Context, scope: Scriptable): Scriptable {
        val path = cx.newObject(scope)

        ScriptableObject.putProperty(path, "join", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                val parts = args?.map { Context.toString(it) } ?: emptyList()
                return File(parts.joinToString(File.separator)).normalize().path
            }
        })

        ScriptableObject.putProperty(path, "resolve", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                val parts = args?.map { Context.toString(it) } ?: emptyList()
                return File(parts.joinToString(File.separator)).canonicalPath
            }
        })

        ScriptableObject.putProperty(path, "basename", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                val p = args?.firstOrNull()?.let { Context.toString(it) } ?: ""
                return File(p).name
            }
        })

        ScriptableObject.putProperty(path, "dirname", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                val p = args?.firstOrNull()?.let { Context.toString(it) } ?: ""
                return File(p).parent ?: "."
            }
        })

        ScriptableObject.putProperty(path, "extname", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                val p = args?.firstOrNull()?.let { Context.toString(it) } ?: ""
                val ext = File(p).extension
                return if (ext.isNotEmpty()) ".$ext" else ""
            }
        })

        return path
    }

    private fun createOsModule(cx: Context, scope: Scriptable): Scriptable {
        val os = cx.newObject(scope)
        ScriptableObject.putProperty(os, "platform", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any = "android"
        })
        ScriptableObject.putProperty(os, "arch", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any =
                System.getProperty("os.arch") ?: "arm64"
        })
        return os
    }

    private fun resolveFilePath(path: String, workingDir: File): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(workingDir, path)
    }

    private fun resolveModuleFile(moduleName: String, workingDir: File): File? {
        if (moduleName.startsWith("./") || moduleName.startsWith("../") || moduleName.startsWith("/")) {
            val direct = resolveFilePath(moduleName, workingDir)
            if (direct.exists() && direct.isFile) return direct
            val withJs = File("${direct.path}.js")
            if (withJs.exists()) return withJs
            val indexInDir = File(direct, "index.js")
            if (indexInDir.exists()) return indexInDir
            return null
        }

        // Check node_modules
        val nodeModulesDir = File(workingDir, "node_modules")
        val packageDir = File(nodeModulesDir, moduleName)
        if (packageDir.exists() && packageDir.isDirectory) {
            val pkgJson = File(packageDir, "package.json")
            if (pkgJson.exists()) {
                try {
                    val content = pkgJson.readText(Charsets.UTF_8)
                    val json = org.json.JSONObject(content)
                    val mainFile = json.optString("main", "index.js")
                    val mainTarget = File(packageDir, mainFile)
                    if (mainTarget.exists()) return mainTarget
                    val withJs = File(packageDir, "$mainFile.js")
                    if (withJs.exists()) return withJs
                } catch (_: Exception) {}
            }
            val index = File(packageDir, "index.js")
            if (index.exists()) return index
        }
        return null
    }

    private fun loadJsFileModule(
        cx: Context,
        parentScope: Scriptable,
        file: File,
        workingDir: File,
        logCallback: (String, Boolean) -> Unit
    ): Any {
        val moduleScope = cx.newObject(parentScope)
        val exports = cx.newObject(moduleScope)
        val module = cx.newObject(moduleScope)
        ScriptableObject.putProperty(module, "exports", exports)
        ScriptableObject.putProperty(moduleScope, "module", module)
        ScriptableObject.putProperty(moduleScope, "exports", exports)

        val code = file.readText(Charsets.UTF_8)
        cx.evaluateString(moduleScope, code, file.name, 1, null)

        return ScriptableObject.getProperty(module, "exports")
    }
}
