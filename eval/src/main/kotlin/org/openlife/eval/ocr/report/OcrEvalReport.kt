package org.openlife.eval.ocr.report

import org.openlife.eval.ocr.core.Digests
import org.openlife.eval.ocr.core.GateBinding
import org.openlife.eval.ocr.core.GateCodec
import org.openlife.eval.ocr.core.GateVerdict
import org.openlife.eval.ocr.core.ManifestCodec
import org.openlife.eval.ocr.core.OcrGate
import org.openlife.eval.ocr.core.TruthCodec
import java.io.File
import kotlin.system.exitProcess

/**
 * `./gradlew :eval:checkOcrGate [-PocrResults=a.json,b.json] [-PocrRequire=engine,...]`
 *
 * Prints a markdown gate table for every engine in gate.json. An engine with
 * no results file given is NO_MEASUREMENT. Exits 0 only when every required
 * engine (default: all of them) is PASS.
 */
object OcrEvalReport {
    fun verdicts(setDir: File, resultFiles: List<File>): List<GateVerdict> {
        val manifestText = File(setDir, "manifest.json").readText()
        val gateText = File(setDir, "gate.json").readText()
        val manifest = ManifestCodec.decode(manifestText)
        val gate = GateCodec.decode(gateText)
        val binding = GateBinding(
            manifestSha256 = Digests.sha256(manifestText),
            gateSha256 = Digests.sha256(gateText),
            fixtures = manifest.fixtures,
            lineCounts = manifest.fixtures.associate {
                it.id to TruthCodec.decode(
                    File(setDir, it.truth).readText(),
                ).lines.size
            },
        )
        return gate.engines.map { engine ->
            val file = resultFiles.singleOrNull { it.name.startsWith("results-$engine-") }
            OcrGate.evaluate(engine, gate, binding, file?.readText())
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
}

fun main(args: Array<String>) {
    val setDir = File(args.first())
    val options = args.drop(1).associate { it.substringBefore('=') to it.substringAfter('=', "") }
    val results = options["--results"].orEmpty().split(',').filter { it.isNotBlank() }.map(::File)
    results.filterNot { it.isFile }.forEach { System.err.println("no such results file: $it") }
    val verdicts = OcrEvalReport.verdicts(setDir, results.filter { it.isFile })
    print(OcrEvalReport.markdown(verdicts))
    val required = options["--require"].orEmpty().split(',').filter { it.isNotBlank() }
        .ifEmpty { verdicts.map { it.engineId } }
    val failing = verdicts.filter { it.engineId in required && !it.permitsDefault }
    val unknown = required - verdicts.map { it.engineId }.toSet()
    if (failing.isNotEmpty() || unknown.isNotEmpty() || results.any { !it.isFile }) {
        System.err.println("OCR gate not passed: ${failing.map { "${it.engineId}=${it.outcome}" } + unknown}")
        exitProcess(1)
    }
}
