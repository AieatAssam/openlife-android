package org.openlife.app.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openlife.app.BuildConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * P1-08-R2: About shows the repository's own files, packaged by the build
 * (never a hand-kept copy), and the source revision injected at build time.
 */
class BundledDocumentsTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))
    private val generated = projectDir.resolve("app/build/generated")

    @Test
    fun packagedCopiesEqualTheirRepositorySources() {
        mapOf(
            "third_party_licenses.md" to "docs/generated/THIRD_PARTY_LICENSES.md",
            "privacy_policy.md" to "docs/PRIVACY_POLICY.md",
        ).forEach { (packaged, source) ->
            // One generated raw resource per variant (the variant API chooses the directory).
            val copies = generatedRawFiles(packaged)
            assertTrue("$packaged is not generated", copies.isNotEmpty())
            copies.forEach { copy ->
                assertArrayEquals("$copy", Files.readAllBytes(projectDir.resolve(source)), Files.readAllBytes(copy))
            }
        }
        val committedCopies = listOf("third_party_licenses.md", "privacy_policy.md")
            .filter { Files.exists(projectDir.resolve("app/src/main/res/raw").resolve(it)) }
        assertTrue("hand-kept copies drift: $committedCopies", committedCopies.isEmpty())
    }

    private fun generatedRawFiles(name: String): List<Path> = if (!Files.isDirectory(generated)) {
        emptyList()
    } else {
        Files.walk(generated).use { paths ->
            paths.filter { it.fileName.toString() == name && it.parent.fileName.toString() == "raw" }.toList()
        }
    }

    @Test
    fun gitCommitIsInjectedAtBuildTime() {
        val value = BuildConfig::class.java.getField("GIT_COMMIT").get(null) as String
        // `git describe --tags --always --dirty`: a tag, tag-N-gHASH or a bare hash, maybe -dirty; else "unknown".
        assertTrue("GIT_COMMIT is '$value'", GIT_DESCRIBE.matches(value))
    }

    private companion object {
        val GIT_DESCRIBE = Regex("""[\w.+\-]+""")
    }
}
