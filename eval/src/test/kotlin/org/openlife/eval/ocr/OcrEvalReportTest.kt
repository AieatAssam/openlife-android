package org.openlife.eval.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openlife.eval.ocr.core.EngineResults
import org.openlife.eval.ocr.core.ResultsCodec
import org.openlife.eval.ocr.report.OcrEvalReport
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** The gate CLI behind `./gradlew :eval:checkOcrGate`, on the committed set. */
class OcrEvalReportTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun exitStatusFollowsTheWorstRequiredOutcome() {
        val tesseract = RealSet.perfectResults(TESSERACT)
        val mlkit = RealSet.perfectResults(MLKIT)
        val pass = listOf(write("a", tesseract), write("a", mlkit))
        assertEquals(OcrEvalReport.EXIT_PASS, run(pass).first)

        val slow = tesseract.copy(images = tesseract.images.map { it.copy(durationMs = 7_000) })
        assertEquals(OcrEvalReport.EXIT_FAIL, run(listOf(write("b", slow), pass[1])).first)

        val (status, output) = run(emptyList())
        assertEquals(OcrEvalReport.EXIT_NO_MEASUREMENT, status)
        assertTrue(output, output.contains("GATE $TESSERACT: NO_MEASUREMENT (no results file)"))
        assertEquals(
            "only Tesseract required",
            OcrEvalReport.EXIT_PASS,
            run(listOf(pass[0]), require = TESSERACT).first,
        )

        val flattered = tesseract.copy(images = tesseract.images.map { it.copy(cer = 0.0, spans = emptyList()) })
        assertEquals(OcrEvalReport.EXIT_INVALID, run(listOf(write("c", flattered), pass[1])).first)
        assertEquals(OcrEvalReport.EXIT_INVALID, run(listOf(File(temp.root, "results-$MLKIT-missing.json"))).first)
        assertEquals(OcrEvalReport.EXIT_INVALID, run(pass, require = "no-such-engine").first)
        assertEquals(OcrEvalReport.EXIT_USAGE, OcrEvalReport.run(emptyList(), sink(), sink()))
    }

    @Test
    fun twoResultsFilesForOneEngineAreDuplicatesNotAbsence() {
        val tesseract = RealSet.perfectResults(TESSERACT)
        val files = listOf(write("d", tesseract), write("e", tesseract), write("d", RealSet.perfectResults(MLKIT)))
        val (status, output) = run(files)
        assertEquals(OcrEvalReport.EXIT_INVALID, status)
        assertTrue(output, output.contains("GATE $TESSERACT: INVALID (duplicate results:"))
    }

    private fun write(dir: String, results: EngineResults): File {
        val folder = File(temp.root, dir).apply { mkdirs() }
        return File(folder, "results-${results.engineId}-${results.runDate}.json").apply {
            writeText(ResultsCodec.encode(results))
        }
    }

    private fun run(files: List<File>, require: String? = null): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val args = listOfNotNull(
            RealSet.dir.path,
            "--results=${files.joinToString(",")}",
            require?.let { "--require=$it" },
        )
        val status = OcrEvalReport.run(args, PrintStream(out, true, "UTF-8"), sink())
        return status to out.toString("UTF-8")
    }

    private fun sink() = PrintStream(ByteArrayOutputStream())

    private companion object {
        const val TESSERACT = "tesseract-eng-fast"
        const val MLKIT = "mlkit-latin"
    }
}
