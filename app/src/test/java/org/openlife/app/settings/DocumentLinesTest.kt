package org.openlife.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** P1-08-R2/R5: bundled Markdown is shown as plain lines; link syntax becomes visible text, never a link. */
class DocumentLinesTest {
    private val projectDir = Path.of(System.getProperty("openlife.projectDir", "."))

    @Test
    fun headingsTextAndLinksBecomePlainLines() {
        val lines = DocumentLines.parse(
            """
            # Title
            ## 

            **1** **Group:** `net.example` **Name:** `lib`
            > - **Project URL**: [https://example.org/x](https://example.org/x)
            > - **License URL**: [https://example.org/l](BSD-3-Clause)
            Plain paragraph
            continues here.
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                DocumentLine(heading = true, text = "Title"),
                DocumentLine(heading = false, text = "1 Group: net.example Name: lib"),
                DocumentLine(heading = false, text = "Project URL: https://example.org/x"),
                DocumentLine(heading = false, text = "License URL: https://example.org/l (BSD-3-Clause)"),
                DocumentLine(heading = false, text = "Plain paragraph continues here."),
            ),
            lines,
        )
    }

    @Test
    fun theRealLicenceReportKeepsEveryComponentAndNoLinkSyntax() {
        val report = Files.readString(projectDir.resolve("docs/generated/THIRD_PARTY_LICENSES.md"))
        val lines = DocumentLines.parse(report)
        val components = Regex("""\*\*Name:\*\* `([^`]+)`""").findAll(report).map { it.groupValues[1] }.toList()
        assertTrue("the report lists components", components.size > 10)
        components.forEach { name -> assertTrue("missing $name", lines.any { "Name: $name" in it.text }) }
        assertTrue("the bundled font licence is kept", lines.any { it.heading && it.text == "Bundled font licences" })
        lines.forEach { line -> assertFalse("link syntax left in ${line.text}", "](" in line.text) }
    }
}
