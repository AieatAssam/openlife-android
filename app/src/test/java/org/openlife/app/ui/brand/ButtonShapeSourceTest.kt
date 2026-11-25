package org.openlife.app.ui.brand

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * A-04 (P1-19-R9, review Q-06): every Material button in shipped code goes
 * through the OpenLife wrappers in Buttons.kt, which apply the brief's 8 dp
 * shape. Bare, fully qualified and referenced calls and aliased imports are
 * all caught. The instrumented ButtonShapeTest checks the drawn corner on a
 * device.
 */
class ButtonShapeSourceTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))
    private val wrapperFile = "ui/brand/Buttons.kt"

    @Test
    fun everyButtonCallUsesTheEightDpWrapper() {
        val root = projectDir.resolve("app/src/main/java/org/openlife/app")
        val violations = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { !it.toString().endsWith(wrapperFile) }
                .flatMap { file ->
                    Files.readAllLines(file).withIndex()
                        .filter { (_, line) -> isBareButtonUse(line) }
                        .map { (index, line) -> "$file:${index + 1}: ${line.trim()}" }
                        .stream()
                }
                .collect(Collectors.toList())
        }
        assertTrue("bare Material buttons (use OpenLife*Button): $violations", violations.isEmpty())
        val wrappers = Files.readString(root.resolve(wrapperFile))
        assertTrue("the wrappers use the 8 dp shape", wrappers.contains("RoundedCornerShape(8.dp)"))
    }

    /** Self-check: each way around the wrapper is reported, and the wrappers themselves are not. */
    @Test
    fun theCheckCatchesEveryBypass() {
        listOf(
            "    Button(onClick = {}) { Text(label) }",
            "    androidx.compose.material3.Button(onClick = {}) { Text(label) }",
            "    androidx.compose.material3.TextButton(onClick = {}) { Text(label) }",
            "import androidx.compose.material3.OutlinedButton as PlainOutlinedButton",
            "    val button = ::FilledTonalButton",
        ).forEach { line -> assertTrue("\"$line\" must be reported", isBareButtonUse(line)) }
        listOf(
            "import androidx.compose.material3.Button",
            "    OpenLifeButton(onClick = {}) { Text(label) }",
            "    OpenLifeTextButton(onClick = {}) { Text(label) }",
            "    colors = ButtonDefaults.textButtonColors()",
        ).forEach { line -> assertFalse("\"$line\" must not be reported", isBareButtonUse(line)) }
    }

    private fun isBareButtonUse(line: String): Boolean {
        val isImport = line.trimStart().startsWith("import ")
        val call = BARE_BUTTON.containsMatchIn(line) || QUALIFIED_BUTTON.containsMatchIn(line) ||
            BUTTON_REFERENCE.containsMatchIn(line)
        return (call && !isImport) || ALIASED_BUTTON_IMPORT.containsMatchIn(line)
    }

    private companion object {
        const val BUTTON_NAMES = "Button|OutlinedButton|TextButton|FilledTonalButton|ElevatedButton"
        val BARE_BUTTON = Regex("""(?<![A-Za-z0-9_.])($BUTTON_NAMES)\s*\(""")
        val QUALIFIED_BUTTON = Regex("""\bandroidx\.compose\.material3?\.($BUTTON_NAMES)\s*\(""")
        val BUTTON_REFERENCE = Regex("""::($BUTTON_NAMES)\b""")
        val ALIASED_BUTTON_IMPORT = Regex("""^\s*import\s+[\w.]+\.($BUTTON_NAMES)\s+as\s+\w+""")
    }
}
