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
    private val generatedRaw = projectDir.resolve("app/build/generated/openlife/bundledDocuments/raw")

    @Test
    fun packagedCopiesEqualTheirRepositorySources() {
        mapOf(
            "third_party_licenses.md" to "docs/generated/THIRD_PARTY_LICENSES.md",
            "privacy_policy.md" to "docs/PRIVACY_POLICY.md",
        ).forEach { (packaged, source) ->
            val copy = generatedRaw.resolve(packaged)
            assertTrue("$packaged is not generated", Files.isRegularFile(copy))
            assertArrayEquals(packaged, Files.readAllBytes(projectDir.resolve(source)), Files.readAllBytes(copy))
        }
        val committedCopies = listOf("third_party_licenses.md", "privacy_policy.md")
            .filter { Files.exists(projectDir.resolve("app/src/main/res/raw").resolve(it)) }
        assertTrue("hand-kept copies drift: $committedCopies", committedCopies.isEmpty())
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
