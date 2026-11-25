package org.openlife.eval.ocr.core

import java.security.MessageDigest

/** An axis-aligned rectangle in source pixels. Right and bottom are exclusive, as in `OcrEvidenceRegion`. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left >= 0 && top >= 0) { "box coordinates cannot be negative" }
        require(right >= left && bottom >= top) { "box must be ordered" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height.toLong()

    fun toJson(): JsonArray = jsonArray(listOf(left.json(), top.json(), right.json(), bottom.json()))

    companion object {
        private const val COORDINATES = 4

        fun fromJson(value: JsonValue): Box {
            val items = (value as? JsonArray)?.items ?: throw JsonFormatException("box is not an array")
            if (items.size != COORDINATES) throw JsonFormatException("box needs four coordinates")
            val numbers = items.map { (it as? JsonNumber)?.toLong() ?: throw JsonFormatException("box coordinate") }
            if (numbers.any { it !in 0..Int.MAX_VALUE }) throw JsonFormatException("box coordinate out of range")
            return try {
                Box(numbers[0].toInt(), numbers[1].toInt(), numbers[2].toInt(), numbers[3].toInt())
            } catch (invalid: IllegalArgumentException) {
                throw JsonFormatException("invalid box: ${invalid.message}")
            }
        }
    }
}

enum class FixtureCategory(val key: String) {
    SCREENSHOT("screenshot"),
    RECEIPT("receipt"),
    LETTER("letter"),
    ;

    companion object {
        fun of(key: String): FixtureCategory =
            entries.firstOrNull { it.key == key } ?: throw JsonFormatException("unknown category $key")
    }
}

/** The script of the letters in a line or image. A line with no letters at all counts as Latin. */
enum class ScriptClass(val key: String) {
    LATIN("latin"),
    MIXED("mixed"),
    NON_LATIN("non_latin"),
    ;

    companion object {
        fun of(key: String): ScriptClass =
            entries.firstOrNull { it.key == key } ?: throw JsonFormatException("unknown script class $key")
    }
}

data class TruthLine(val text: String, val box: Box, val script: ScriptClass)

/** Ground truth for one fixture image (P2-04-R1). */
data class FixtureTruth(
    val id: String,
    val category: FixtureCategory,
    val image: String,
    val width: Int,
    val height: Int,
    val seed: Long,
    val style: String,
    val skewDegrees: Double,
    val noiseDensity: Double,
    val scriptClass: ScriptClass,
    val expectedAbstain: Boolean,
    val lines: List<TruthLine>,
)

object TruthCodec {
    fun encode(truth: FixtureTruth): String = Json.write(
        jsonObject(
            "id" to truth.id.json(),
            "category" to truth.category.key.json(),
            "image" to truth.image.json(),
            "width" to truth.width.json(),
            "height" to truth.height.json(),
            "seed" to truth.seed.json(),
            "style" to truth.style.json(),
            "skew_degrees" to truth.skewDegrees.json(),
            "noise_density" to truth.noiseDensity.json(),
            "script_class" to truth.scriptClass.key.json(),
            "expected_abstain" to truth.expectedAbstain.json(),
            "lines" to jsonArray(
                truth.lines.map {
                    jsonObject("text" to it.text.json(), "box" to it.box.toJson(), "script" to it.script.key.json())
                },
            ),
        ),
    )

    fun decode(text: String): FixtureTruth {
        val root = Json.parseObject(text)
        return FixtureTruth(
            id = root.string("id"),
            category = FixtureCategory.of(root.string("category")),
            image = root.string("image"),
            width = root.int("width"),
            height = root.int("height"),
            seed = root.long("seed"),
            style = root.string("style"),
            skewDegrees = root.double("skew_degrees"),
            noiseDensity = root.double("noise_density"),
            scriptClass = ScriptClass.of(root.string("script_class")),
            expectedAbstain = root.bool("expected_abstain"),
            lines = root.array("lines").map { line ->
                val fields = line as? JsonObject ?: throw JsonFormatException("line is not an object")
                TruthLine(fields.string("text"), Box.fromJson(fields.value("box")), ScriptClass.of(fields.string("script")))
            },
        )
    }
}

data class ManifestEntry(
    val id: String,
    val category: FixtureCategory,
    val expectedAbstain: Boolean,
    val image: String,
    val truth: String,
    val pngSha256: String,
    val rasterSha256: String,
    val truthSha256: String,
)

data class FontEntry(val file: String, val sha256: String)

/** The versioned set: what was generated, from which fonts, and the digests that bind results to it. */
data class FixtureManifest(
    val set: String,
    val generator: String,
    val generatorVersion: Int,
    val seed: Long,
    val javaVersion: String,
    val javaVendor: String,
    val fonts: List<FontEntry>,
    val fixtures: List<ManifestEntry>,
)

object ManifestCodec {
    fun encode(manifest: FixtureManifest): String = Json.write(
        jsonObject(
            "set" to manifest.set.json(),
            "generator" to manifest.generator.json(),
            "generator_version" to manifest.generatorVersion.json(),
            "seed" to manifest.seed.json(),
            "rendered_with" to jsonObject(
                "java_version" to manifest.javaVersion.json(),
                "java_vendor" to manifest.javaVendor.json(),
            ),
            "fonts" to jsonArray(
                manifest.fonts.map { jsonObject("file" to it.file.json(), "sha256" to it.sha256.json()) },
            ),
            "fixtures" to jsonArray(manifest.fixtures.map(::entryJson)),
        ),
    )

    fun decode(text: String): FixtureManifest {
        val root = Json.parseObject(text)
        val renderedWith = root.obj("rendered_with")
        return FixtureManifest(
            set = root.string("set"),
            generator = root.string("generator"),
            generatorVersion = root.int("generator_version"),
            seed = root.long("seed"),
            javaVersion = renderedWith.string("java_version"),
            javaVendor = renderedWith.string("java_vendor"),
            fonts = root.array("fonts").map {
                val font = it as? JsonObject ?: throw JsonFormatException("font is not an object")
                FontEntry(font.string("file"), font.string("sha256"))
            },
            fixtures = root.array("fixtures").map {
                val entry = it as? JsonObject ?: throw JsonFormatException("fixture is not an object")
                ManifestEntry(
                    id = entry.string("id"),
                    category = FixtureCategory.of(entry.string("category")),
                    expectedAbstain = entry.bool("expected_abstain"),
                    image = entry.string("image"),
                    truth = entry.string("truth"),
                    pngSha256 = entry.string("png_sha256"),
                    rasterSha256 = entry.string("raster_sha256"),
                    truthSha256 = entry.string("truth_sha256"),
                )
            },
        )
    }

    private fun entryJson(entry: ManifestEntry): JsonObject = jsonObject(
        "id" to entry.id.json(),
        "category" to entry.category.key.json(),
        "expected_abstain" to entry.expectedAbstain.json(),
        "image" to entry.image.json(),
        "truth" to entry.truth.json(),
        "png_sha256" to entry.pngSha256.json(),
        "raster_sha256" to entry.rasterSha256.json(),
        "truth_sha256" to entry.truthSha256.json(),
    )
}

object Digests {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
}
