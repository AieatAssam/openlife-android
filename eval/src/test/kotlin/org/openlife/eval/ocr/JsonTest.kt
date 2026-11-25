package org.openlife.eval.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.openlife.eval.ocr.core.Box
import org.openlife.eval.ocr.core.Json
import org.openlife.eval.ocr.core.JsonFormatException
import org.openlife.eval.ocr.core.JsonNull
import org.openlife.eval.ocr.core.JsonNumber
import org.openlife.eval.ocr.core.JsonString
import org.openlife.eval.ocr.core.ResultsCodec
import org.openlife.eval.ocr.core.SpanObservation
import org.openlife.eval.ocr.core.jsonArray
import org.openlife.eval.ocr.core.jsonObject

class JsonTest {
    @Test
    fun roundTripsStringsNumbersAndNesting() {
        val value = jsonObject(
            "text" to JsonString("Привет \"€\"\n\t\u0001"),
            "n" to JsonNumber("-12.5e3"),
            "list" to jsonArray(listOf(JsonNumber("1"), JsonNull)),
            "nested" to jsonObject("empty" to jsonArray(emptyList())),
        )
        assertEquals(value, Json.parse(Json.write(value)))
        assertEquals(JsonString("é\uD835\uDC00"), Json.parse("\"\\u00e9\\ud835\\udc00\""))
    }

    @Test
    fun rejectsMalformedInput() {
        val malformed = listOf(
            "", "{", "{\"a\":1,}", "{\"a\":1}{}", "{\"a\":1,\"a\":2}", "[01]", "NaN", "[1e999]",
            "\"\u0001\"", "{a:1}", "[1 2]", "\"\\x\"", "tru", "[" .repeat(100) + "]".repeat(100),
        )
        malformed.forEach { text ->
            try {
                Json.parse(text)
                fail("accepted: $text")
            } catch (expected: JsonFormatException) {
                // strict
            }
        }
    }

    @Test
    fun resultsRoundTrip() {
        val results = GateFixtures.passingResults().let { passing ->
            passing.copy(
                images = passing.images.map {
                    it.copy(spans = listOf(SpanObservation("a", Box(1, 2, 3, 4)), SpanObservation("b", null)))
                },
            )
        }
        assertEquals(results, ResultsCodec.decode(ResultsCodec.encode(results)))
    }
}
