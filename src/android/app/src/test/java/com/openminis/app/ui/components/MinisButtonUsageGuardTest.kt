package com.openminis.app.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guard for docs/design/UI-DESIGN-LANGUAGE.md §6: every action is a text
 * button. The Material filled / outlined / tonal / elevated button composables
 * must only appear inside MinisButton.kt (which renders them as text buttons);
 * everything else goes through the Minis* wrappers.
 *
 * `android.widget.Button(context)` in the View-based pet overlay is not a
 * Compose button and is deliberately not matched.
 */
class MinisButtonUsageGuardTest {

    private val rawCall = Regex("""(?<![A-Za-z])(Button|OutlinedButton|FilledTonalButton|ElevatedButton)\((?!\s*context\b)""")
    private val rawImport = Regex("""^import androidx\.compose\.material3\.(Button|OutlinedButton|FilledTonalButton|ElevatedButton)$""")

    /** Returns "line: text" for each raw Material button call or import in [source]. */
    internal fun findRawButtonUsages(source: String): List<String> =
        source.lines().mapIndexedNotNull { i, line ->
            val code = line.substringBefore("//").trim()
            if (rawImport.containsMatchIn(code) || rawCall.containsMatchIn(code)) "${i + 1}: ${line.trim()}" else null
        }

    @Test
    fun `flags raw material buttons and imports`() {
        assertEquals(1, findRawButtonUsages("    Button(onClick = {}) { }").size)
        assertEquals(1, findRawButtonUsages("    OutlinedButton(onClick = {}) { }").size)
        assertEquals(1, findRawButtonUsages("    FilledTonalButton(onClick = {}) { }").size)
        assertEquals(1, findRawButtonUsages("import androidx.compose.material3.OutlinedButton").size)
    }

    @Test
    fun `does not flag wrappers, comments, or view buttons`() {
        assertTrue(findRawButtonUsages("    MinisButton(onClick = {}) { }").isEmpty())
        assertTrue(findRawButtonUsages("    MinisOutlinedButton(onClick = {}) { }").isEmpty())
        assertTrue(findRawButtonUsages("    // Button(onClick = {})").isEmpty())
        assertTrue(findRawButtonUsages("    private val mic = Button(context)").isEmpty())
        assertTrue(findRawButtonUsages("import androidx.compose.material3.TextButton").isEmpty())
    }

    @Test
    fun `only MinisButton dot kt may use material buttons`() {
        val root = File("src/main/java/com/openminis/app")
        assertTrue(
            "source root not found (cwd=${File(".").absolutePath})",
            root.isDirectory,
        )
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "MinisButton.kt" }
            .flatMap { f -> findRawButtonUsages(f.readText()).map { "${f.path}:$it" } }
            .toList()
        assertTrue(
            "Use MinisButton / MinisOutlinedButton / MinisTextButton (text buttons only).\n" +
                offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty(),
        )
    }
}
