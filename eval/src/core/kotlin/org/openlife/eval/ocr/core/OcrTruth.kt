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
        private const val LEFT = 0
        private const val TOP = 1
        private const val RIGHT = 2
        private const val BOTTOM = 3

        fun fromJson(value: JsonValue): Box {
            val items = (value as? JsonArray)?.items ?: jsonFail("box is not an array")
            if (items.size != COORDINATES) jsonFail("box needs four coordinates")
            val numbers = items.map { (it as? JsonNumber)?.toLong() ?: jsonFail("box coordinate") }
            if (numbers.any { it !in 0..Int.MAX_VALUE }) jsonFail("box coordinate out of range")
            val coordinates = numbers.map { it.toInt() }.toIntArray()
            return try {
                Box(coordinates[LEFT], coordinates[TOP], coordinates[RIGHT], coordinates[BOTTOM])
            } catch (invalid: IllegalArgumentException) {
                throw JsonFormatException("invalid box: ${invalid.message}", invalid)
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
            entries.firstOrNull { it.key == key } ?: jsonFail("unknown category $key")
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
            entries.firstOrNull { it.key == key } ?: jsonFail("unknown script class $key")
    }
}

/**
 * One column of a line: text drawn without a column gap (3+ spaces), with its
 * own ink box and script. A line without a column gap has one segment equal
 * to the line.
 */
data class TruthSegment(val text: String, val box: Box, val script: ScriptClass) {
    fun toJson(): JsonObject = jsonObject("text" to text.json(), "box" to box.toJson(), "script" to script.key.json())

    companion object {
        fun fromJson(value: JsonValue): TruthSegment {
            val fields = value as? JsonObject ?: jsonFail("segment is not an object")
            return TruthSegment(
                fields.string("text"),
                Box.fromJson(fields.value("box")),
                ScriptClass.of(fields.string("script")),
            )
        }
    }
}

/**
 * One truth line. [box] is the ink of the whole line; [segments] are its
 * columns (P2-04 review X-01). Line matching accepts either one span over
 * [box] or one span per segment; CER always uses the whole [text].
 */
data class TruthLine(
    val text: String,
    val box: Box,
    val script: ScriptClass,
    val segments: List<TruthSegment> = listOf(TruthSegment(text, box, script)),
) {
    init {
        require(segments.isNotEmpty()) { "a line has at least one segment" }
    }
}

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
    const val SCHEMA = "openlife-ocr-truth/2"

    fun encode(truth: FixtureTruth): String = Json.write(
        jsonObject(
            "schema" to SCHEMA.json(),
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
                    jsonObject(
                        "text" to it.text.json(),
                        "box" to it.box.toJson(),
                        "script" to it.script.key.json(),
                        "segments" to jsonArray(it.segments.map(TruthSegment::toJson)),
                    )
                },
            ),
        ),
    )

    fun decode(text: String): FixtureTruth {
        val root = Json.parseObject(text)
        if (root.string("schema") != SCHEMA) jsonFail("unknown truth schema")
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
                val fields = line as? JsonObject ?: jsonFail("line is not an object")
                val segments = fields.array("segments").map(TruthSegment::fromJson)
                if (segments.isEmpty()) jsonFail("line has no segments")
                TruthLine(
                    fields.string("text"),
                    Box.fromJson(fields.value("box")),
                    ScriptClass.of(fields.string("script")),
                    segments,
                )
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

/**
 * The JDK and platform that rasterised the set. Font rasterisation differs
 * between JDK builds and platforms, so pixels are regenerated and compared
 * only on an identical runtime; the committed PNGs are the source of truth.
 */
data class RenderedWith(val javaRuntime: String, val javaVmVendor: String, val os: String) {
    companion object {
        fun current(): RenderedWith = RenderedWith(
            javaRuntime = System.getProperty("java.runtime.version").orEmpty(),
            javaVmVendor = System.getProperty("java.vm.vendor").orEmpty(),
            os = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
        )
    }
}

/** The versioned set: what was generated, from which fonts, and the digests that bind results to it. */
data class FixtureManifest(
    val set: String,
    val generator: String,
    val generatorVersion: Int,
    val seed: Long,
    val renderedWith: RenderedWith,
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
                "java_runtime_version" to manifest.renderedWith.javaRuntime.json(),
                "java_vm_vendor" to manifest.renderedWith.javaVmVendor.json(),
                "os" to manifest.renderedWith.os.json(),
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
            renderedWith = RenderedWith(
                javaRuntime = renderedWith.string("java_runtime_version"),
                javaVmVendor = renderedWith.string("java_vm_vendor"),
                os = renderedWith.string("os"),
            ),
            fonts = root.array("fonts").map {
                val font = it as? JsonObject ?: jsonFail("font is not an object")
                FontEntry(font.string("file"), font.string("sha256"))
            },
            fixtures = root.array("fixtures").map {
                val entry = it as? JsonObject ?: jsonFail("fixture is not an object")
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
