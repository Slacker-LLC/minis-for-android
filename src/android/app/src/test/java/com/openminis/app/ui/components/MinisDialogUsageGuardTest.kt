package com.openminis.app.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guard for docs/design/UI-DESIGN-LANGUAGE.md §7: overlays share one
 * shell. The Material `AlertDialog` may only be referenced inside
 * MinisAlertDialog.kt and `ModalBottomSheet` only inside
 * MinisModalBottomSheet.kt; everything else uses the Minis* wrappers.
 */
class MinisDialogUsageGuardTest {

    private val rawCall = Regex("""(?<![A-Za-z])AlertDialog\(""")
    private val rawImport = Regex("""^import androidx\.compose\.material3\.AlertDialog$""")
    private val rawSheetCall = Regex("""(?<![A-Za-z])ModalBottomSheet\(""")
    private val rawSheetImport = Regex("""^import androidx\.compose\.material3\.ModalBottomSheet$""")

    internal fun findRawDialogUsages(source: String): List<String> =
        source.lines().mapIndexedNotNull { i, line ->
            val code = line.substringBefore("//").trim()
            if (rawImport.containsMatchIn(code) || rawCall.containsMatchIn(code)) "${i + 1}: ${line.trim()}" else null
        }

    internal fun findRawSheetUsages(source: String): List<String> =
        source.lines().mapIndexedNotNull { i, line ->
            val code = line.substringBefore("//").trim()
            if (rawSheetImport.containsMatchIn(code) || rawSheetCall.containsMatchIn(code)) "${i + 1}: ${line.trim()}" else null
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

    @Test
    fun `flags raw ModalBottomSheet but not the wrapper`() {
        assertEquals(1, findRawSheetUsages("    ModalBottomSheet(onDismissRequest = {}) { }").size)
        assertEquals(1, findRawSheetUsages("import androidx.compose.material3.ModalBottomSheet").size)
        assertTrue(findRawSheetUsages("    MinisModalBottomSheet(onDismissRequest = {}) { }").isEmpty())
        assertTrue(findRawSheetUsages("    // ModalBottomSheet(onDismissRequest = {})").isEmpty())
    }

    @Test
    fun `only MinisModalBottomSheet dot kt may use the material ModalBottomSheet`() {
        val root = File("src/main/java/com/openminis/app")
        assertTrue("source root not found (cwd=${File(".").absolutePath})", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "MinisModalBottomSheet.kt" }
            .flatMap { f -> findRawSheetUsages(f.readText()).map { "${f.path}:$it" } }
            .toList()
        assertTrue(
            "Use MinisModalBottomSheet (shared design-language shell).\n" +
                offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty(),
        )
    }

    // Dark scheme `surface` is the page black; sheets must use minisSheetColor()
    // (#1C1C1E in dark) so they read as a raised layer.
    private val pageSurfaceAsSheet = Regex(
        """Color\.Transparent\s+else\s+(MaterialTheme\.colorScheme\.surface|ChatColors\.background)\b""",
    )

    internal fun findPageSurfaceSheets(source: String): List<String> =
        source.lines().mapIndexedNotNull { i, line ->
            val code = line.substringBefore("//").trim()
            if (pageSurfaceAsSheet.containsMatchIn(code)) "${i + 1}: ${line.trim()}" else null
        }

    @Test
    fun `flags page surface used as sheet container but not minisSheetColor`() {
        assertEquals(
            1,
            findPageSurfaceSheets("containerColor = if (g) Color.Transparent else MaterialTheme.colorScheme.surface,").size,
        )
        assertEquals(1, findPageSurfaceSheets("containerColor = if (g) Color.Transparent else ChatColors.background,").size)
        assertTrue(findPageSurfaceSheets("containerColor = if (g) Color.Transparent else minisSheetColor(),").isEmpty())
    }

    @Test
    fun `no sheet uses the page surface as its container`() {
        val root = File("src/main/java/com/openminis/app")
        assertTrue("source root not found (cwd=${File(".").absolutePath})", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> findPageSurfaceSheets(f.readText()).map { "${f.path}:$it" } }
            .toList()
        assertTrue(
            "Use minisSheetColor() for sheet containers.\n" + offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty(),
        )
    }
}
