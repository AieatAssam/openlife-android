package org.openlife.eval.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The evaluation set never ships. Only test configurations of :vault may
 * reference :eval or eval/, and :app references neither. The release APK
 * itself is checked by scripts/check-no-eval-fixtures.sh.
 */
class EvalBoundaryTest {
    private val root = File(System.getProperty("openlife.rootDir") ?: "..")

    @Test
    fun onlyTestConfigurationsReferenceTheEvalModule() {
        val app = File(root, "app/build.gradle.kts").readLines()
        assertEquals("app must not reference eval", emptyList<String>(), app.filter { it.mentionsEval() })

        val vault = File(root, "vault/build.gradle.kts").readLines()
            .map(String::trim)
            .filterNot { it.startsWith("//") }
            .filter { it.mentionsEval() }
        assertTrue("vault references eval: $vault", vault.isNotEmpty())
        val allowed = listOf(
            "testImplementation(project(\":eval\"))",
            "java.srcDir(rootProject.file(\"eval/src/core/kotlin\"))",
            "assets.srcDir(rootProject.file(\"eval/ocr\"))",
            "inputs.files(rootProject.fileTree(\"eval/ocr/v1\")).withPropertyName(\"ocrSet\")",
            "inputs.files(rootProject.fileTree(\"eval/facts/v1\")).withPropertyName(\"factsSet\")",
        )
        assertEquals(allowed.sorted(), vault.sorted())
        val buildFile = File(root, "vault/build.gradle.kts").readText()
        val androidTestBlock = buildFile.substringAfter("getByName(\"androidTest\") {").substringBefore("}")
        assertTrue("the source and asset directories belong to androidTest", androidTestBlock.contains("eval/ocr"))
        assertTrue(androidTestBlock.contains("eval/src/core/kotlin"))
        val testInputs = buildFile.substringAfter("tasks.withType<Test>().configureEach {").substringBefore("}")
        assertTrue("the set is an input of vault's unit tests only", testInputs.contains("eval/ocr/v1"))
        assertTrue("the facts set is an input of vault's unit tests only", testInputs.contains("eval/facts/v1"))
    }

    private fun String.mentionsEval(): Boolean = contains(":eval") || contains("eval/")
}
