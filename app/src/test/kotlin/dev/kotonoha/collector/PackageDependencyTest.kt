package dev.kotonoha.collector

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readLines
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageDependencyTest {
    @Test
    fun productionPackagesFollowTheDeclaredDependencyDirection() {
        val sourceRoot = sourceRoot()
        val violations = mutableListOf<String>()
        Files.walk(sourceRoot).use { paths ->
            paths.filter { it.extension == "kt" }.forEach { file ->
                val lines = file.readLines()
                val sourcePackage = lines.firstNotNullOfOrNull(::packageName) ?: return@forEach
                lines.mapNotNull(::importName).forEach { imported ->
                    if (!imported.startsWith(BASE_PACKAGE) || isGeneratedRootType(imported)) return@forEach
                    val targetPackage = imported.substringBeforeLast('.', missingDelimiterValue = "")
                    if (!isAllowed(sourcePackage, targetPackage)) {
                        violations += "${sourceRoot.relativize(file)}: $sourcePackage -> $imported"
                    }
                }
            }
        }

        assertTrue(
            "Forbidden package dependencies:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    private fun isAllowed(sourcePackage: String, targetPackage: String): Boolean {
        if (sourcePackage == targetPackage) return true
        val source = area(sourcePackage)
        val target = area(targetPackage)
        if (source == target) return true
        return target in ALLOWED_DEPENDENCIES.getValue(source)
    }

    private fun area(packageName: String): String = when {
        packageName == BASE_PACKAGE -> "entrypoint"
        packageName.startsWith("$BASE_PACKAGE.ui.contract") -> "ui.contract"
        packageName.startsWith("$BASE_PACKAGE.editor.android") -> "editor.android"
        else -> packageName.removePrefix("$BASE_PACKAGE.").substringBefore('.')
    }

    private fun packageName(line: String): String? =
        PACKAGE.matchEntire(line.trim())?.groupValues?.get(1)

    private fun importName(line: String): String? =
        IMPORT.matchEntire(line.trim())?.groupValues?.get(1)

    private fun isGeneratedRootType(imported: String): Boolean =
        imported == "$BASE_PACKAGE.R" || imported == "$BASE_PACKAGE.BuildConfig"

    private fun sourceRoot(): Path = listOf(
        Paths.get("src/main/kotlin"),
        Paths.get("app/src/main/kotlin"),
    ).first(Files::exists)

    private companion object {
        const val BASE_PACKAGE = "dev.kotonoha.collector"
        val PACKAGE = Regex("package\\s+([A-Za-z0-9_.]+)")
        val IMPORT = Regex("import\\s+([A-Za-z0-9_.]+)(?:\\s+as\\s+[A-Za-z0-9_]+)?")
        val ALLOWED_DEPENDENCIES = mapOf(
            "entrypoint" to setOf(
                "clipboard", "conversion", "editor", "editor.android", "ime", "input", "telemetry",
                "ui", "ui.contract",
            ),
            "input" to emptySet(),
            "editor" to emptySet(),
            "editor.android" to setOf("editor"),
            "conversion" to setOf("input"),
            "telemetry" to setOf("input"),
            "clipboard" to emptySet(),
            "ime" to setOf("editor", "input", "telemetry", "ui.contract"),
            "ui.contract" to setOf("editor", "input"),
            "ui" to setOf("clipboard", "editor", "input", "ui.contract"),
        )
    }
}
