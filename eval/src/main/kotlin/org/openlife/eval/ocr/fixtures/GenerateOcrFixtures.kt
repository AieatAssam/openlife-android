package org.openlife.eval.ocr.fixtures

import java.io.File

/** `./gradlew :eval:generateOcrFixtures`: args are the repository root and the set directory. */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: GenerateOcrFixtures <repo-root> <set-dir>" }
    val fonts = FixtureFonts.load(File(args[0]))
    val fixtures = OcrFixtureGenerator.generate(fonts)
    val manifest = OcrFixtureGenerator.manifest(fixtures, fonts)
    OcrFixtureGenerator.write(fixtures, manifest, File(args[1]))
    println("wrote ${fixtures.size} fixtures to ${args[1]}")
}
