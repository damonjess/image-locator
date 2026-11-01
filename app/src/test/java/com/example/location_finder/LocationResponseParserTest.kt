package com.example.location_finder

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationResponseParserTest {

    @Test
    fun extractCandidateText_skipsThoughtParts() {
        val parts = JSONArray().apply {
            put(JSONObject().apply {
                put("thought", true)
                put("text", "At first glance this looks like UNKNOWN_LOCATION...")
            })
            put(JSONObject().apply {
                put("text", """{"title": "Texas Taff BBQ, Ystalyfera", "city": "Ystalyfera"}""")
            })
        }
        val text = LocationResponseParser.extractCandidateText(parts)
        assertFalse(text.contains("UNKNOWN_LOCATION"))
        assertTrue(text.contains("Texas Taff BBQ"))
    }

    @Test
    fun isUnknownLocation_doesNotFalseTriggerOnDeductionMentioningUnknownLocation() {
        val raw = """
            {
              "step_1_visual_clues": "FRC logo on black top, BBQ platter, Welsh valley houses",
              "step_2_logical_deduction": "Without the FRC logo this would be UNKNOWN_LOCATION, but it matches Food Review Club's visit to Texas Taff BBQ in Ystalyfera.",
              "title": "Texas Taff BBQ, Ystalyfera",
              "description": "Back-garden Texas barbecue in Ystalyfera featured by Food Review Club.",
              "confidence": "medium",
              "latitude": 51.7689,
              "longitude": -3.7806,
              "street": "UNKNOWN_LOCATION",
              "city": "Ystalyfera",
              "region": "Swansea Valley, South Wales",
              "country": "United Kingdom",
              "country_code": "gb",
              "postcode": "SA9",
              "search_query": "Texas Taff BBQ, Ystalyfera, Swansea Valley, South Wales, United Kingdom"
            }
        """.trimIndent()
        val json = LocationResponseParser.extractJsonObject(raw)
        assertNotNull(json)
        val parsed = LocationResponseParser.parseGeminiResponse(json!!)
        assertFalse(LocationResponseParser.isUnknownLocation(raw, parsed))
        assertNull(parsed.street)
        assertEquals("Ystalyfera", parsed.city)
    }

    @Test
    fun parseGeminiResponse_filtersNullIslandZeroCoordinates() {
        val raw = """
            {
              "title": "Texas Taff BBQ, Ystalyfera",
              "city": "Ystalyfera",
              "country": "United Kingdom",
              "latitude": 0,
              "longitude": 0
            }
        """.trimIndent()
        val json = LocationResponseParser.extractJsonObject(raw)!!
        val parsed = LocationResponseParser.parseGeminiResponse(json)
        assertNull(parsed.latitude)
        assertNull(parsed.longitude)
        assertFalse(LocationResponseParser.isUnknownLocation(raw, parsed))
    }

    @Test
    fun buildTownFallbackQueries_ordersSpecificToBroad() {
        val result = GeminiLocationResult(
            title = "Texas Taff BBQ, Ystalyfera",
            description = "Back-garden BBQ in Ystalyfera",
            confidence = "medium",
            latitude = 51.7689,
            longitude = -3.7806,
            street = null,
            city = "Ystalyfera",
            region = "Swansea Valley, South Wales",
            country = "United Kingdom",
            countryCode = "gb",
            postcode = null,
            searchQuery = "Texas Taff BBQ, Ystalyfera, Swansea Valley, South Wales, United Kingdom"
        )
        val queries = LocationResponseParser.buildTownFallbackQueries(result)
        assertEquals(
            listOf(
                "Ystalyfera, Swansea Valley, South Wales, United Kingdom",
                "Ystalyfera, United Kingdom",
                "Ystalyfera, Swansea Valley, South Wales",
                "Ystalyfera",
                "Swansea Valley, South Wales, United Kingdom",
                "Swansea Valley, South Wales"
            ),
            queries
        )
    }

    @Test
    fun isUnknownLocation_genericBuildingTitleWithoutLocality_isTrue() {
        val raw = """
            {
              "title": "Red brick building complex with pantile roof in a UK market town",
              "description": "Generic building description without town or coordinates",
              "confidence": "low",
              "latitude": 0,
              "longitude": 0,
              "country": "United Kingdom",
              "country_code": "gb"
            }
        """.trimIndent()
        val json = LocationResponseParser.extractJsonObject(raw)!!
        val parsed = LocationResponseParser.parseGeminiResponse(json)
        assertTrue(LocationResponseParser.isUnknownLocation(raw, parsed))
    }
}
