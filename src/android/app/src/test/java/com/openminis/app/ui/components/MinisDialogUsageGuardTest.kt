package com.openminis.app.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guard for docs/design/UI-DESIGN-LANGUAGE.md §7: dialogs share one
 * shell. The Material `AlertDialog` composable may only be referenced inside
 * MinisAlertDialog.kt; everything else uses `MinisAlertDialog`.
 */
class MinisDialogUsageGuardTest {

    private val rawCall = Regex("""(?<![A-Za-z])AlertDialog\(""")
    private val rawImport = Regex("""^import androidx\.compose\.material3\.AlertDialog$""")

    internal fun findRawDialogUsages(source: String): List<String> =
        source.lines().mapIndexedNotNull { i, line ->
            val code = line.substringBefore("//").trim()
            if (rawImport.containsMatchIn(code) || rawCall.containsMatchIn(code)) "${i + 1}: ${line.trim()}" else null
        }

    @Test
    fun `flags raw AlertDialog calls and imports but not the wrapper`() {
        assertEquals(1, findRawDialogUsages("    AlertDialog(onDismissRequest = {}, confirmButton = {})").size)
        assertEquals(1, findRawDialogUsages("import androidx.compose.material3.AlertDialog").size)
        assertTrue(findRawDialogUsages("    MinisAlertDialog(onDismissRequest = {}, confirmButton = {})").isEmpty())
        assertTrue(findRawDialogUsages("    // AlertDialog(onDismissRequest = {})").isEmpty())
    }

    @Test
    fun `only MinisAlertDialog dot kt may use the material AlertDialog`() {
        val root = File("src/main/java/com/openminis/app")
        assertTrue("source root not found (cwd=${File(".").absolutePath})", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "MinisAlertDialog.kt" }
            .flatMap { f -> findRawDialogUsages(f.readText()).map { "${f.path}:$it" } }
            .toList()
        assertTrue(
            "Use MinisAlertDialog (shared design-language shell).\n" +
                offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty(),
        )
    }
}
