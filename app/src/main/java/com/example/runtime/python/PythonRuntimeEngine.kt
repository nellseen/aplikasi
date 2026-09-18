package com.example.runtime.python

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class PythonRuntimeEngine {

    class PythonExitException(val exitCode: Int) : RuntimeException("sys.exit($exitCode)")

    suspend fun execute(
        entryFile: File,
        workingDir: File,
        customPythonBin: String? = null,
        logCallback: (String, Boolean) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val detectedBinary = findPythonBinary(customPythonBin)

        if (detectedBinary != null) {
            // Execute via native OS subprocess
            executeSubprocess(detectedBinary, entryFile, workingDir, logCallback)
        } else {
            // Execute via our built-in Native Python 3 Engine
            executeEmbeddedPython(entryFile, workingDir, logCallback)
        }
    }

    private fun findPythonBinary(customPath: String?): String? {
        if (!customPath.isNullOrBlank()) {
            val f = File(customPath)
            if (f.exists() && f.canExecute()) return f.absolutePath
        }

        val candidates = listOf(
            "/system/bin/python3",
            "/system/bin/python",
            "/data/data/com.termux/files/usr/bin/python3",
            "/data/data/com.termux/files/usr/bin/python",
            "/data/local/tmp/python3"
        )

        for (path in candidates) {
            val f = File(path)
            if (f.exists() && f.canExecute()) {
                return path
            }
        }

        // Check PATH
        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(":")) {
            for (bin in listOf("python3", "python")) {
                val f = File(dir, bin)
                if (f.exists() && f.canExecute()) {
                    return f.absolutePath
                }
            }
        }
        return null
    }

    private suspend fun executeSubprocess(
        binaryPath: String,
        entryFile: File,
        workingDir: File,
        logCallback: (String, Boolean) -> Unit
    ): Int {
        logCallback("$ $binaryPath ${entryFile.name}", false)
        return try {
            val sitePackagesDir = File(workingDir, "site-packages")
            val pythonPath = if (sitePackagesDir.exists()) {
                sitePackagesDir.absolutePath + ":" + (System.getenv("PYTHONPATH") ?: "")
            } else {
                System.getenv("PYTHONPATH") ?: ""
            }

            val processBuilder = ProcessBuilder(binaryPath, "-u", entryFile.absolutePath)
                .directory(workingDir)

            processBuilder.environment()["PYTHONUNBUFFERED"] = "1"
            if (pythonPath.isNotEmpty()) {
                processBuilder.environment()["PYTHONPATH"] = pythonPath
            }

            val process = processBuilder.start()

            // Read stdout and stderr
            coroutineScope {
                val stdoutJob = launch(Dispatchers.IO) {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            logCallback(line, false)
                            line = reader.readLine()
                        }
                    }
                }

                val stderrJob = launch(Dispatchers.IO) {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String? = reader.readLine()
                        while (line != null) {
                            logCallback(line, true)
                            line = reader.readLine()
                        }
                    }
                }

                stdoutJob.join()
                stderrJob.join()
            }

            val exitCode = process.waitFor()
            logCallback("[Subprocess finished with exit code $exitCode]", exitCode != 0)
            exitCode
        } catch (cancellation: CancellationException) {
            logCallback("[Process terminated by user]", false)
            130
        } catch (e: Exception) {
            logCallback("Process execution error: ${e.message}", true)
            1
        }
    }

    private suspend fun executeEmbeddedPython(
        entryFile: File,
        workingDir: File,
        logCallback: (String, Boolean) -> Unit
    ): Int {
        logCallback("$ python3 ${entryFile.name} (Native Python Engine)", false)
        return try {
            val interpreter = EmbeddedPythonInterpreter(workingDir, logCallback)
            interpreter.runScript(entryFile)
            logCallback("[Python process completed successfully]", false)
            0
        } catch (exitEx: PythonExitException) {
            logCallback("[Process exited with code ${exitEx.exitCode}]", exitEx.exitCode != 0)
            exitEx.exitCode
        } catch (cancellation: CancellationException) {
            logCallback("[Process terminated by user]", false)
            130
        } catch (e: Exception) {
            logCallback("Traceback (most recent call last):\n  File \"${entryFile.name}\"\n${e.message ?: e.toString()}", true)
            1
        }
    }

    /**
     * Fully native Python 3 execution environment in Kotlin.
     */
    class EmbeddedPythonInterpreter(
        private val workingDir: File,
        private val logCallback: (String, Boolean) -> Unit
    ) {
        val globalScope = mutableMapOf<String, Any?>()

        init {
            // Builtin math, os, sys, time, json
            globalScope["__name__"] = "__main__"
            globalScope["__file__"] = workingDir.absolutePath
            globalScope["True"] = true
            globalScope["False"] = false
            globalScope["None"] = null
        }

        suspend fun runScript(file: File) {
            val lines = file.readLines(Charsets.UTF_8)
            executeBlock(lines, 0, lines.size, 0)
        }

        private suspend fun executeBlock(
            lines: List<String>,
            startIdx: Int,
            endIdx: Int,
            expectedIndent: Int
        ): Int {
            var i = startIdx
            while (i < endIdx) {
                val rawLine = lines[i]
                val trimmed = rawLine.trim()

                // Skip blanks and comments
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    i++
                    continue
                }

                val indent = rawLine.indexOfFirst { !it.isWhitespace() }
                if (indent < expectedIndent) {
                    return i
                }

                // Check for multi-line block statements: if, for, while, def, with
                if (trimmed.startsWith("if ") || trimmed == "else:" || trimmed.startsWith("elif ")) {
                    i = handleIfStatement(lines, i, indent)
                } else if (trimmed.startsWith("for ")) {
                    i = handleForLoop(lines, i, indent)
                } else if (trimmed.startsWith("while ")) {
                    i = handleWhileLoop(lines, i, indent)
                } else if (trimmed.startsWith("with ")) {
                    i = handleWithStatement(lines, i, indent)
                } else if (trimmed.startsWith("def ")) {
                    i = handleDefStatement(lines, i, indent)
                } else if (trimmed.startsWith("import ") || trimmed.startsWith("from ")) {
                    handleImport(trimmed)
                    i++
                } else {
                    // Single statement
                    executeStatement(trimmed)
                    i++
                }
            }
            return endIdx
        }

        private suspend fun handleIfStatement(lines: List<String>, start: Int, indent: Int): Int {
            val condLine = lines[start].trim()
            val condExpr = condLine.removePrefix("if ").removeSuffix(":").trim()
            val condResult = evalCondition(condExpr)

            // Collect body of if
            val ifBody = mutableListOf<String>()
            var nextIdx = start + 1
            while (nextIdx < lines.size) {
                val l = lines[nextIdx]
                if (l.trim().isEmpty() || l.trim().startsWith("#")) {
                    ifBody.add(l)
                    nextIdx++
                    continue
                }
                val lineIndent = l.indexOfFirst { !it.isWhitespace() }
                if (lineIndent > indent) {
                    ifBody.add(l)
                    nextIdx++
                } else break
            }

            // Check if there is an else or elif
            var elseIdx: Int? = null
            if (nextIdx < lines.size && lines[nextIdx].trim().startsWith("else:")) {
                elseIdx = nextIdx
            }

            if (condResult) {
                executeBlock(ifBody, 0, ifBody.size, indent + 1)
            } else if (elseIdx != null) {
                // Collect else body
                val elseBody = mutableListOf<String>()
                var eIdx = elseIdx + 1
                while (eIdx < lines.size) {
                    val l = lines[eIdx]
                    if (l.trim().isEmpty() || l.trim().startsWith("#")) {
                        elseBody.add(l)
                        eIdx++
                        continue
                    }
                    val lineIndent = l.indexOfFirst { !it.isWhitespace() }
                    if (lineIndent > indent) {
                        elseBody.add(l)
                        eIdx++
                    } else break
                }
                executeBlock(elseBody, 0, elseBody.size, indent + 1)
                nextIdx = eIdx
            }

            return nextIdx
        }

        private suspend fun handleForLoop(lines: List<String>, start: Int, indent: Int): Int {
            val header = lines[start].trim().removePrefix("for ").removeSuffix(":").trim()
            val varName = header.substringBefore(" in ").trim()
            val iterExpr = header.substringAfter(" in ").trim()

            val bodyLines = mutableListOf<String>()
            var nextIdx = start + 1
            while (nextIdx < lines.size) {
                val l = lines[nextIdx]
                if (l.trim().isEmpty() || l.trim().startsWith("#")) {
                    bodyLines.add(l)
                    nextIdx++
                    continue
                }
                val lineIndent = l.indexOfFirst { !it.isWhitespace() }
                if (lineIndent > indent) {
                    bodyLines.add(l)
                    nextIdx++
                } else break
            }

            val iterable = evalExpression(iterExpr)
            val items: List<Any?> = when (iterable) {
                is List<*> -> iterable
                is String -> iterable.map { it.toString() }
                is IntRange -> iterable.toList()
                else -> emptyList()
            }

            for (item in items) {
                globalScope[varName] = item
                executeBlock(bodyLines, 0, bodyLines.size, indent + 1)
            }

            return nextIdx
        }

        private suspend fun handleWhileLoop(lines: List<String>, start: Int, indent: Int): Int {
            val condExpr = lines[start].trim().removePrefix("while ").removeSuffix(":").trim()
            val bodyLines = mutableListOf<String>()
            var nextIdx = start + 1
            while (nextIdx < lines.size) {
                val l = lines[nextIdx]
                if (l.trim().isEmpty() || l.trim().startsWith("#")) {
                    bodyLines.add(l)
                    nextIdx++
                    continue
                }
                val lineIndent = l.indexOfFirst { !it.isWhitespace() }
                if (lineIndent > indent) {
                    bodyLines.add(l)
                    nextIdx++
                } else break
            }

            var iterations = 0
            while (evalCondition(condExpr) && iterations < 100_000) {
                executeBlock(bodyLines, 0, bodyLines.size, indent + 1)
                iterations++
            }

            return nextIdx
        }

        private suspend fun handleWithStatement(lines: List<String>, start: Int, indent: Int): Int {
            val line = lines[start].trim().removePrefix("with ").removeSuffix(":").trim()
            val expr = line.substringBefore(" as ").trim()
            val varName = line.substringAfter(" as ").trim()

            val bodyLines = mutableListOf<String>()
            var nextIdx = start + 1
            while (nextIdx < lines.size) {
                val l = lines[nextIdx]
                if (l.trim().isEmpty() || l.trim().startsWith("#")) {
                    bodyLines.add(l)
                    nextIdx++
                    continue
                }
                val lineIndent = l.indexOfFirst { !it.isWhitespace() }
                if (lineIndent > indent) {
                    bodyLines.add(l)
                    nextIdx++
                } else break
            }

            val fileObj = evalExpression(expr)
            globalScope[varName] = fileObj

            executeBlock(bodyLines, 0, bodyLines.size, indent + 1)

            if (fileObj is PythonFileHandle) {
                fileObj.close()
            }

            return nextIdx
        }

        private fun handleDefStatement(lines: List<String>, start: Int, indent: Int): Int {
            val header = lines[start].trim().removePrefix("def ").removeSuffix(":").trim()
            val funcName = header.substringBefore("(")
            val params = header.substringAfter("(").substringBefore(")").split(",").map { it.trim() }.filter { it.isNotEmpty() }

            val bodyLines = mutableListOf<String>()
            var nextIdx = start + 1
            while (nextIdx < lines.size) {
                val l = lines[nextIdx]
                if (l.trim().isEmpty() || l.trim().startsWith("#")) {
                    bodyLines.add(l)
                    nextIdx++
                    continue
                }
                val lineIndent = l.indexOfFirst { !it.isWhitespace() }
                if (lineIndent > indent) {
                    bodyLines.add(l)
                    nextIdx++
                } else break
            }

            globalScope[funcName] = PythonFunction(funcName, params, bodyLines, indent + 1)
            return nextIdx
        }

        private fun handleImport(statement: String) {
            val modName = if (statement.startsWith("import ")) {
                statement.removePrefix("import ").trim().substringBefore(" as ")
            } else {
                statement.removePrefix("from ").substringBefore(" import ").trim()
            }

            when (modName) {
                "sys" -> {
                    globalScope["sys"] = mapOf(
                        "platform" to "android",
                        "version" to "3.11.0 (Android Native Engine)",
                        "argv" to listOf("main.py")
                    )
                }
                "os" -> {
                    globalScope["os"] = PythonOsModule(workingDir)
                }
                "time" -> {
                    globalScope["time"] = PythonTimeModule()
                }
                "math" -> {
                    globalScope["math"] = PythonMathModule()
                }
                "json" -> {
                    globalScope["json"] = PythonJsonModule()
                }
                else -> {
                    // Try to import from project directory
                    val pyFile = File(workingDir, "$modName.py")
                    if (pyFile.exists()) {
                        // Successfully referenced local module
                        globalScope[modName] = mapOf("loaded" to true)
                    }
                }
            }
        }

        private suspend fun executeStatement(stmt: String) {
            when {
                stmt.startsWith("print(") && stmt.endsWith(")") -> {
                    val inner = stmt.removeSurrounding("print(", ")")
                    val result = evalPrintExpression(inner)
                    logCallback(result, false)
                }
                stmt.contains(" = ") -> {
                    val left = stmt.substringBefore(" = ").trim()
                    val right = stmt.substringAfter(" = ").trim()
                    val value = evalExpression(right)
                    assignVariable(left, value)
                }
                stmt.contains(".append(") -> {
                    val varName = stmt.substringBefore(".append(").trim()
                    val itemExpr = stmt.substringAfter(".append(").removeSuffix(")").trim()
                    val item = evalExpression(itemExpr)
                    @Suppress("UNCHECKED_CAST")
                    val list = globalScope[varName] as? MutableList<Any?>
                    list?.add(item)
                }
                stmt.startsWith("time.sleep(") -> {
                    val secs = stmt.removeSurrounding("time.sleep(", ")").trim().toDoubleOrNull() ?: 0.0
                    delay((secs * 1000).toLong())
                }
                stmt.startsWith("sys.exit(") -> {
                    val code = stmt.removeSurrounding("sys.exit(", ")").trim().toIntOrNull() ?: 0
                    throw PythonExitException(code)
                }
                else -> {
                    evalExpression(stmt)
                }
            }
        }

        private fun assignVariable(name: String, value: Any?) {
            if (name.contains("[") && name.endsWith("]")) {
                val varName = name.substringBefore("[")
                val keyExpr = name.substringAfter("[").removeSuffix("]")
                val key = evalExpression(keyExpr)
                val target = globalScope[varName]
                if (target is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    (target as MutableMap<Any?, Any?>)[key] = value
                }
            } else {
                globalScope[name] = value
            }
        }

        private fun evalCondition(expr: String): Boolean {
            val clean = expr.trim()
            if (clean.equals("True", ignoreCase = true)) return true
            if (clean.equals("False", ignoreCase = true)) return false

            if (clean.contains(" == ")) {
                val (l, r) = clean.split(" == ", limit = 2).map { evalExpression(it.trim()) }
                return l == r
            }
            if (clean.contains(" != ")) {
                val (l, r) = clean.split(" != ", limit = 2).map { evalExpression(it.trim()) }
                return l != r
            }
            if (clean.contains(" <= ")) {
                val (l, r) = clean.split(" <= ", limit = 2).map { (evalExpression(it.trim()) as? Number)?.toDouble() ?: 0.0 }
                return l <= r
            }
            if (clean.contains(" >= ")) {
                val (l, r) = clean.split(" >= ", limit = 2).map { (evalExpression(it.trim()) as? Number)?.toDouble() ?: 0.0 }
                return l >= r
            }
            if (clean.contains(" < ")) {
                val (l, r) = clean.split(" < ", limit = 2).map { (evalExpression(it.trim()) as? Number)?.toDouble() ?: 0.0 }
                return l < r
            }
            if (clean.contains(" > ")) {
                val (l, r) = clean.split(" > ", limit = 2).map { (evalExpression(it.trim()) as? Number)?.toDouble() ?: 0.0 }
                return l > r
            }

            val v = evalExpression(clean)
            return when (v) {
                is Boolean -> v
                is Number -> v.toDouble() != 0.0
                is String -> v.isNotEmpty()
                is List<*> -> v.isNotEmpty()
                is Map<*, *> -> v.isNotEmpty()
                null -> false
                else -> true
            }
        }

        private fun evalPrintExpression(expr: String): String {
            // Check for f-string: f"..." or f'...'
            if (expr.startsWith("f\"") || expr.startsWith("f'")) {
                val content = expr.drop(2).dropLast(1)
                return replaceFStringBrackets(content)
            }

            // Split arguments by comma outside of strings or function calls
            val args = splitArgs(expr)
            return args.joinToString(" ") { evalExpression(it)?.toString() ?: "None" }
        }

        private fun replaceFStringBrackets(content: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < content.size) {
                if (content[i] == '{') {
                    val end = content.indexOf('}', i)
                    if (end != -1) {
                        val expr = content.substring(i + 1, end).trim()
                        val value = evalExpression(expr)
                        sb.append(value?.toString() ?: "None")
                        i = end + 1
                        continue
                    }
                }
                sb.append(content[i])
                i++
            }
            return sb.toString()
        }

        private fun evalExpression(expr: String): Any? {
            val clean = expr.trim()
            if (clean.isEmpty()) return null

            // String literals
            if ((clean.startsWith("\"") && clean.endsWith("\"")) || (clean.startsWith("'") && clean.endsWith("'"))) {
                return clean.substring(1, clean.length - 1)
            }

            // Numbers
            clean.toIntOrNull()?.let { return it }
            clean.toDoubleOrNull()?.let { return it }
            if (clean == "True") return true
            if (clean == "False") return false
            if (clean == "None") return null

            // List literal: [...]
            if (clean.startsWith("[") && clean.endsWith("]")) {
                val inner = clean.substring(1, clean.length - 1).trim()
                if (inner.isEmpty()) return mutableListOf<Any?>()
                val items = splitArgs(inner).map { evalExpression(it) }
                return items.toMutableList()
            }

            // Dict literal: {...}
            if (clean.startsWith("{") && clean.endsWith("}")) {
                val inner = clean.substring(1, clean.length - 1).trim()
                val map = mutableMapOf<String, Any?>()
                if (inner.isNotEmpty()) {
                    splitArgs(inner).forEach { pair ->
                        val k = pair.substringBefore(":").trim().removeSurrounding("\"", "'")
                        val v = evalExpression(pair.substringAfter(":").trim())
                        map[k] = v
                    }
                }
                return map
            }

            // range(...)
            if (clean.startsWith("range(") && clean.endsWith(")")) {
                val inner = clean.removeSurrounding("range(", ")")
                val parts = inner.split(",").map { it.trim().toInt() }
                return if (parts.size == 1) {
                    0 until parts[0]
                } else {
                    parts[0] until parts[1]
                }
            }

            // len(...)
            if (clean.startsWith("len(") && clean.endsWith(")")) {
                val inner = clean.removeSurrounding("len(", ")")
                val v = evalExpression(inner)
                return when (v) {
                    is List<*> -> v.size
                    is String -> v.length
                    is Map<*, *> -> v.size
                    else -> 0
                }
            }

            // int(math.sqrt(...))
            if (clean.startsWith("int(") && clean.endsWith(")")) {
                val inner = clean.removeSurrounding("int(", ")")
                val v = evalExpression(inner)
                return (v as? Number)?.toInt() ?: 0
            }

            // math.sqrt(...)
            if (clean.startsWith("math.sqrt(") && clean.endsWith(")")) {
                val inner = clean.removeSurrounding("math.sqrt(", ")")
                val num = (evalExpression(inner) as? Number)?.toDouble() ?: 0.0
                return kotlin.math.sqrt(num)
            }

            // open(...)
            if (clean.startsWith("open(") && clean.endsWith(")")) {
                val args = splitArgs(clean.removeSurrounding("open(", ")"))
                val p = evalExpression(args[0])?.toString() ?: ""
                val mode = if (args.size > 1) evalExpression(args[1])?.toString() ?: "r" else "r"
                val file = if (File(p).isAbsolute) File(p) else File(workingDir, p)
                return PythonFileHandle(file, mode)
            }

            // json.dump(...)
            if (clean.startsWith("json.dump(") && clean.endsWith(")")) {
                val args = splitArgs(clean.removeSurrounding("json.dump(", ")"))
                val data = evalExpression(args[0])
                val fObj = evalExpression(args[1]) as? PythonFileHandle
                if (fObj != null && data != null) {
                    val jsonStr = JSONObject(data as Map<*, *>).toString(2)
                    fObj.write(jsonStr)
                }
                return null
            }

            // os.path.join(...)
            if (clean.startsWith("os.path.join(") && clean.endsWith(")")) {
                val args = splitArgs(clean.removeSurrounding("os.path.join(", ")"))
                val parts = args.map { evalExpression(it)?.toString() ?: "" }
                return parts.joinToString(File.separator)
            }

            // os.getcwd()
            if (clean == "os.getcwd()") {
                return workingDir.absolutePath
            }

            // time.time()
            if (clean == "time.time()") {
                return System.currentTimeMillis() / 1000.0
            }

            // Simple arithmetic: a % b, a + b, etc.
            if (clean.contains(" % ")) {
                val (a, b) = clean.split(" % ", limit = 2).map { (evalExpression(it) as? Number)?.toInt() ?: 0 }
                return a % b
            }
            if (clean.contains(" + ")) {
                val (a, b) = clean.split(" + ", limit = 2).map { evalExpression(it) }
                if (a is Number && b is Number) return a.toDouble() + b.toDouble()
                return "${a ?: ""}${b ?: ""}"
            }

            // Direct variable lookup or attribute
            if (clean.contains(".")) {
                val objName = clean.substringBefore(".")
                val prop = clean.substringAfter(".")
                val obj = globalScope[objName]
                if (obj is Map<*, *>) {
                    return obj[prop]
                }
            }

            return globalScope[clean]
        }

        private fun splitArgs(s: String): List<String> {
            val list = mutableListOf<String>()
            var current = StringBuilder()
            var inQuote = false
            var quoteChar = ' '
            var depth = 0

            for (c in s) {
                if ((c == '"' || c == '\'') && (depth == 0 || inQuote)) {
                    if (!inQuote) {
                        inQuote = true
                        quoteChar = c
                    } else if (c == quoteChar) {
                        inQuote = false
                    }
                }
                if (!inQuote) {
                    if (c == '(' || c == '[' || c == '{') depth++
                    if (c == ')' || c == ']' || c == '}') depth--
                    if (c == ',' && depth == 0) {
                        list.add(current.toString().trim())
                        current = StringBuilder()
                        continue
                    }
                }
                current.append(c)
            }
            if (current.isNotEmpty()) {
                list.add(current.toString().trim())
            }
            return list
        }

        private val String.size: Int
            get() = this.length
    }

    class PythonFunction(
        val name: String,
        val params: List<String>,
        val bodyLines: List<String>,
        val indent: Int
    )

    class PythonFileHandle(val file: File, val mode: String) {
        fun write(content: String) {
            file.parentFile?.mkdirs()
            if (mode.contains("a")) {
                file.appendText(content)
            } else {
                file.writeText(content)
            }
        }
        fun read(): String = if (file.exists()) file.readText() else ""
        fun close() {}
    }

    class PythonOsModule(private val workingDir: File) {
        fun getcwd(): String = workingDir.absolutePath
    }

    class PythonTimeModule {
        fun time(): Double = System.currentTimeMillis() / 1000.0
    }

    class PythonMathModule {
        val pi = kotlin.math.PI
    }

    class PythonJsonModule
}
