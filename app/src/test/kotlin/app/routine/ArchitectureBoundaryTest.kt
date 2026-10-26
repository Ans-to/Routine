package app.routine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
