package org.openlife.eval.ocr.report

import org.openlife.eval.ocr.core.Digests
import org.openlife.eval.ocr.core.GateBinding
import org.openlife.eval.ocr.core.GateCodec
import org.openlife.eval.ocr.core.GateOutcome
import org.openlife.eval.ocr.core.GateVerdict
import org.openlife.eval.ocr.core.ManifestCodec
import org.openlife.eval.ocr.core.OcrGate
import org.openlife.eval.ocr.core.TruthCodec
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * `./gradlew :eval:checkOcrGate [-PocrResults=a.json,b.json] [-PocrRequire=engine,...]`
 *
 * Prints a markdown gate table for every engine in gate.json. Results files
 * are recognised by name, `results-<engine>-<date>.json`; an engine with no
 * file is NO_MEASUREMENT, one with two or more files is INVALID. Every score
 * is recomputed from the recorded spans against the committed truth.
 *
 * Exit status over the required engines (default: all of them): 0 when all
 * PASS, otherwise the worst outcome: 3 INVALID (also for a named results
 * file that does not exist or an unknown required engine), 1 FAIL,
 * 2 NO_MEASUREMENT. 64 for a usage error.
 */
object OcrEvalReport {
    const val EXIT_PASS = 0
    const val EXIT_FAIL = 1
    const val EXIT_NO_MEASUREMENT = 2
    const val EXIT_INVALID = 3
    const val EXIT_USAGE = 64

    fun verdicts(setDir: File, resultFiles: List<File>): List<GateVerdict> {
        val manifestText = File(setDir, "manifest.json").readText()
        val gateText = File(setDir, "gate.json").readText()
        val manifest = ManifestCodec.decode(manifestText)
        val gate = GateCodec.decode(gateText)
        val binding = GateBinding(
            manifestSha256 = Digests.sha256(manifestText),
            gateSha256 = Digests.sha256(gateText),
            fixtures = manifest.fixtures,
            truths = manifest.fixtures.associate { it.id to TruthCodec.decode(File(setDir, it.truth).readText()) },
        )
        return gate.engines.map { engine ->
            val files = resultFiles.filter { it.name.startsWith("results-${engine.id}-") }
            if (files.size > 1) {
                OcrGate.notEvaluated(
                    engine.id,
                    gate,
                    GateOutcome.INVALID,
                    "duplicate results: ${files.joinToString { it.name }}",
                )
            } else {
                OcrGate.evaluate(engine.id, gate, binding, files.singleOrNull()?.readText())
            }
        }
    }

    fun markdown(verdicts: List<GateVerdict>): String = buildString {
        appendLine("| Engine | Check | Status | Observed | Limit |")
        appendLine("| --- | --- | --- | --- | --- |")
        verdicts.forEach { verdict ->
            verdict.checks.forEach {
                appendLine("| ${verdict.engineId} | ${it.name} | ${it.status} | ${it.observed ?: "-"} | ${it.limit} |")
            }
        }
        appendLine()
        verdicts.forEach { verdict ->
            val detail = if (verdict.problems.isEmpty()) "" else " (${verdict.problems.joinToString("; ")})"
            appendLine(
                "GATE ${verdict.engineId}: ${verdict.outcome}$detail; permits default: ${verdict.permitsDefault}",
            )
        }
    }

    /** `args`: the set directory, then optional `--results=a,b` and `--require=engine,...`. */
    fun run(args: List<String>, out: PrintStream, err: PrintStream): Int {
        val setDir = args.firstOrNull()?.let(::File)
        if (setDir == null || !File(setDir, "gate.json").isFile) {
            err.println("usage: OcrEvalReport <set dir> [--results=a.json,b.json] [--require=engine,...]")
            return EXIT_USAGE
        }
        val options = args.drop(1).associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val results = options["--results"].orEmpty().split(',').filter { it.isNotBlank() }.map(::File)
        val absent = results.filterNot { it.isFile }
        absent.forEach { err.println("no such results file: $it") }
        val verdicts = verdicts(setDir, results.filter { it.isFile })
        out.print(markdown(verdicts))
        val required = options["--require"].orEmpty().split(',').filter { it.isNotBlank() }
            .ifEmpty { verdicts.map { it.engineId } }
        val unknown = required - verdicts.map { it.engineId }.toSet()
        val outcomes = verdicts.filter { it.engineId in required }.map { it.outcome }
        val status = when {
            absent.isNotEmpty() || unknown.isNotEmpty() || GateOutcome.INVALID in outcomes -> EXIT_INVALID
            GateOutcome.FAIL in outcomes -> EXIT_FAIL
            GateOutcome.NO_MEASUREMENT in outcomes -> EXIT_NO_MEASUREMENT
            else -> EXIT_PASS
        }
        if (status != EXIT_PASS) {
            err.println(
                "OCR gate not passed: ${verdicts.filter {
                    it.engineId in required
                }.map { "${it.engineId}=${it.outcome}" } + unknown}",
            )
        }
        return status
    }
}

fun main(args: Array<String>) {
    exitProcess(OcrEvalReport.run(args.toList(), System.out, System.err))
}
