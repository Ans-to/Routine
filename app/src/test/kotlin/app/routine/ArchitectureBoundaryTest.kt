package app.routine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces Routine's non-negotiable architectural and language constraints across the repository.
 */
class ArchitectureBoundaryTest {
    private val repoRoot: File by lazy {
        val current = File(System.getProperty("user.dir") ?: ".").canonicalFile
        generateSequence(current) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
    }

    @Test
    fun `repository contains zero Java or JavaScript source files`() {
        val forbiddenFiles = repoRoot.walkTopDown()
            .onEnter { dir ->
                dir.name !in setOf(".git", ".gradle", "build")
            }
            .filter { file ->
                file.isFile && (file.extension == "java" || file.extension == "js")
            }
            .toList()

        assertTrue(
            "Found forbidden Java/JS files: $forbiddenFiles",
            forbiddenFiles.isEmpty(),
        )
    }

    @Test
    fun `no module outside privilege implementations contains ADB or Settings Secure write calls`() {
        val allowedRoots = listOf(
            File(repoRoot, "privilege/adb-bridge").canonicalPath,
            File(repoRoot, "privilege/one-time-grant").canonicalPath,
        )
        val forbiddenTokens = listOf(
            "Settings.Secure.put",
            "Settings.Global.put",
            "NsdManager",
            "_adb-tls-pairing",
            "_adb-tls-connect",
        )

        val violations = mutableListOf<String>()
        repoRoot.walkTopDown()
            .onEnter { dir ->
                dir.name !in setOf(".git", ".gradle", "build")
            }
            .filter { file ->
                file.isFile &&
                    file.extension == "kt" &&
                    file.name != "ArchitectureBoundaryTest.kt" &&
                    allowedRoots.none { file.canonicalPath.startsWith(it) }
            }
            .forEach { file ->
                val text = file.readText()
                for (token in forbiddenTokens) {
                    if (text.contains(token)) {
                        violations += "${file.relativeTo(repoRoot)}: contains '$token'"
                    }
                }
            }

        assertTrue(
            "Architectural boundary violation(s):\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `public functions in engine, permissions, and privilege api document contract, failure modes, and safety`() {
        val documentedDirs = listOf(
            File(repoRoot, "core/engine/src/main"),
            File(repoRoot, "core/permissions/src/main"),
            File(repoRoot, "privilege/api/src/main"),
        )
        val missingDocs = documentedDirs
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file -> findUndocumentedPublicFunctions(file) }

        assertTrue(
            "Public functions missing Contract/Failure modes/Thread safety KDoc:\n${missingDocs.joinToString("\n")}",
            missingDocs.isEmpty(),
        )
    }

    private fun findUndocumentedPublicFunctions(file: File): List<String> {
        val publicFunRegex = Regex("""^\s*(?:override\s+|suspend\s+)*fun\s+([A-Za-z0-9_]+)\s*\(""")
        val lines = file.readLines()
        val missing = mutableListOf<String>()
        for ((idx, line) in lines.withIndex()) {
            if (line.contains("private ") || line.contains("internal ")) continue
            val match = publicFunRegex.find(line) ?: continue
            val nearHeader = lines.subList((idx - 5).coerceAtLeast(0), idx)
            if (line.trimStart().startsWith("override ") && nearHeader.any { it.contains("object :") }) continue
            val window = lines.subList((idx - 22).coerceAtLeast(0), idx).joinToString("\n")
            val hasKdoc = window.contains("Contract:") &&
                window.contains("Failure modes:") &&
                window.contains("Thread safety:")
            if (!hasKdoc) {
                missing += "${file.relativeTo(repoRoot)}:${idx + 1} fun ${match.groupValues[1]}"
            }
        }
        return missing
    }
}
