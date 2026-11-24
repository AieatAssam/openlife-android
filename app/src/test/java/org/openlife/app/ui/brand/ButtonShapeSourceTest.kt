package org.openlife.app.ui.brand

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * A-04 (P1-19-R9): every Material button in shipped code goes through the
 * OpenLife wrappers in Buttons.kt, which apply the brief's 8 dp shape. The
 * instrumented ButtonShapeTest checks the drawn corner on a device.
 */
class ButtonShapeSourceTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))
    private val wrapperFile = "ui/brand/Buttons.kt"
    private val bareButton = Regex(
        """(?<![A-Za-z.])(Button|OutlinedButton|TextButton|FilledTonalButton|ElevatedButton)\s*\(""",
    )

    @Test
    fun everyButtonCallUsesTheEightDpWrapper() {
        val root = projectDir.resolve("app/src/main/java/org/openlife/app")
        val violations = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { !it.toString().endsWith(wrapperFile) }
                .flatMap { file ->
                    Files.readAllLines(file).withIndex()
                        .filter { (_, line) -> isBareButtonCall(line) }
                        .map { (index, line) -> "$file:${index + 1}: ${line.trim()}" }
                        .stream()
                }
                .collect(Collectors.toList())
        }
        assertTrue("bare Material buttons (use OpenLife*Button): $violations", violations.isEmpty())
        val wrappers = Files.readString(root.resolve(wrapperFile))
        assertTrue("the wrappers use the 8 dp shape", wrappers.contains("RoundedCornerShape(8.dp)"))
    }

    private fun isBareButtonCall(line: String): Boolean =
        bareButton.containsMatchIn(line) && !line.trimStart().startsWith("import ")
}
