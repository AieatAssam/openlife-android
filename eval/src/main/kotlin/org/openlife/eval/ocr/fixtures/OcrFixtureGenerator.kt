package org.openlife.eval.ocr.fixtures

import org.openlife.eval.ocr.core.FixtureManifest
import org.openlife.eval.ocr.core.FixtureTruth
import java.io.File

/** One generated image with its truth, before anything is written to disk. */
class GeneratedFixture(val truth: FixtureTruth, val truthJson: String, val png: ByteArray, val rasterSha256: String)

/**
 * P2-04-R1: generates eval/ocr/v1 deterministically from [SET_SEED]: 15
 * phone-screenshot-style images, 15 thermal receipts and 10 letters or
 * forms, 5 of them carrying non-Latin text.
 */
object OcrFixtureGenerator {
    const val SET = "ocr/v1"
    const val GENERATOR_VERSION = 1
    const val SET_SEED = 20_261_125L

    fun generate(fonts: FixtureFonts, seed: Long = SET_SEED): List<GeneratedFixture> = TODO("P2-04 GREEN")

    fun manifest(fixtures: List<GeneratedFixture>, fonts: FixtureFonts, seed: Long = SET_SEED): FixtureManifest =
        TODO("P2-04 GREEN")

    fun write(fixtures: List<GeneratedFixture>, manifest: FixtureManifest, setDir: File): Unit = TODO("P2-04 GREEN")
}
