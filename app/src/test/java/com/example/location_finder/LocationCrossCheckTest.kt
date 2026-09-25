package com.example.location_finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCrossCheckTest {

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /** Baseline mimicking the classic disambiguation failure: right landmark, wrong town. */
    private fun firstPass(
        lat: Double? = 53.5526,
        lon: Double? = -0.4896,
        city: String? = "Brigg"
    ) = GeminiLocationResult(
        title = "The Buttercross, Brigg",
        description = "Market cross",
        confidence = "high",
        latitude = lat,
        longitude = lon,
        street = "Market Place",
        city = city,
        region = "North Lincolnshire",
        country = "United Kingdom",
        countryCode = "gb",
        postcode = "DN20 8ER",
        searchQuery = "The Buttercross, Market Place, Brigg"
    )

    private fun secondPassJson(
        city: String = "Oakham",
        lat: Double? = 52.6668,
        lon: Double? = -0.6427,
        confidence: String = "high",
        title: String = "The Buttercross, Oakham"
    ): String {
        val latStr = lat?.toString() ?: "0"
        val lonStr = lon?.toString() ?: "0"
        return """
            {"title": "$title", "description": "Market cross", "confidence": "$confidence",
             "latitude": $latStr, "longitude": $lonStr, "city": "$city"}
        """.trimIndent()
    }

    // -----------------------------------------------------------------------
    // Verdicts
    // -----------------------------------------------------------------------

    @Test
    fun sameLandmarkDifferentTown_isDisputed() {
        // First pass: Brigg (53.55, -0.49). Second pass: Oakham (52.67, -0.64) — ~100 km apart.
        val outcome = LocationCrossCheck.compare(
            firstPass(),
            org.json.JSONObject(secondPassJson(city = "Oakham")),
            secondPoint = org.osmdroid.util.GeoPoint(52.6668, -0.6427)
        )
        assertEquals(LocationCrossCheck.Verdict.DISPUTED, outcome.verdict)
        assertTrue(outcome.headline.contains("Disputed"))
    }

    @Test
    fun coordinatesWithin2km_isConfirmed() {
        // Second pass ~200 m from the first pass's Market Place coordinates.
        val outcome = LocationCrossCheck.compare(
            firstPass(),
            org.json.JSONObject(secondPassJson(city = "Brigg", lat = 53.5535, lon = -0.4905)),
            secondPoint = org.osmdroid.util.GeoPoint(53.5535, -0.4905)
        )
        assertEquals(LocationCrossCheck.Verdict.CONFIRMED, outcome.verdict)
        assertTrue(outcome.headline.contains("agree"))
    }

    @Test
    fun sameTownFarApart_isCloseMatch() {
        // Both say Brigg, but the second pass is on the wrong side of town (>2 km).
        val outcome = LocationCrossCheck.compare(
            firstPass(),
            org.json.JSONObject(secondPassJson(city = "Brigg", lat = 53.5700, lon = -0.5200)),
            secondPoint = org.osmdroid.util.GeoPoint(53.5700, -0.5200)
        )
        assertEquals(LocationCrossCheck.Verdict.CLOSE_MATCH, outcome.verdict)
    }

    @Test
    fun secondPassUnknown_isSecondPassFailed() {
        val outcome = LocationCrossCheck.compare(
            firstPass(),
            org.json.JSONObject(secondPassJson(title = "UNKNOWN_LOCATION")),
            secondPoint = null
        )
        assertEquals(LocationCrossCheck.Verdict.SECOND_PASS_FAILED, outcome.verdict)
        assertTrue(outcome.secondPassUnknown)
        assertNull(outcome.secondPassPoint)
    }

    @Test
    fun emptyTitle_isSecondPassFailed() {
        val outcome = LocationCrossCheck.compare(
            firstPass(),
            org.json.JSONObject(secondPassJson(title = "")),
            secondPoint = null
        )
        assertEquals(LocationCrossCheck.Verdict.SECOND_PASS_FAILED, outcome.verdict)
    }

    @Test
    fun noSecondPointButSameTown_isCloseMatch() {
        // Second pass identified the town but gave no usable coordinates.
        val outcome = LocationCrossCheck.compare(
            firstPass(),
            org.json.JSONObject(secondPassJson(city = "Brigg", lat = null, lon = null)),
            secondPoint = null
        )
        assertEquals(LocationCrossCheck.Verdict.CLOSE_MATCH, outcome.verdict)
    }

    @Test
    fun noSecondPointDifferentTown_isDisputed() {
        val outcome = LocationCrossCheck.compare(
            firstPass(),
            org.json.JSONObject(secondPassJson(city = "Oakham", lat = null, lon = null)),
            secondPoint = null
        )
        assertEquals(LocationCrossCheck.Verdict.DISPUTED, outcome.verdict)
    }

    // -----------------------------------------------------------------------
    // Town normalization
    // -----------------------------------------------------------------------

    @Test
    fun normalizeTown_stripsPunctuationAndCase() {
        assertEquals("brigg", LocationCrossCheck.normalizeTown("Brigg"))
        assertEquals("north lincolnshire", LocationCrossCheck.normalizeTown("North Lincolnshire!"))
    }

    @Test
    fun normalizeTown_stripsCityOfPrefix() {
        assertEquals("lincoln", LocationCrossCheck.normalizeTown("City of Lincoln"))
    }

    @Test
    fun normalizeTown_blankIsNull() {
        assertNull(LocationCrossCheck.normalizeTown(null))
        assertNull(LocationCrossCheck.normalizeTown("  "))
        assertNull(LocationCrossCheck.normalizeTown("!!!"))
    }

    // -----------------------------------------------------------------------
    // Distance math + formatting
    // -----------------------------------------------------------------------

    @Test
    fun haversine_samePoint_isZero() {
        assertEquals(0.0, LocationCrossCheck.haversineKm(53.5526, -0.4896, 53.5526, -0.4896), 1e-9)
    }

    @Test
    fun haversine_knownDistance_isSane() {
        // Brigg -> Oakham is roughly 100 km straight-line; accept 80–120 km.
        val d = LocationCrossCheck.haversineKm(53.5526, -0.4896, 52.6668, -0.6427)
        assertTrue("Expected 80-120 km, got $d", d in 80.0..120.0)
    }

    @Test
    fun formatDistance_switchesUnits() {
        assertEquals("150 m", LocationCrossCheck.formatDistance(0.15))
        assertEquals("2.5 km", LocationCrossCheck.formatDistance(2.5))
    }

    // -----------------------------------------------------------------------
    // Evidence quality
    // -----------------------------------------------------------------------

    @Test
    fun disputedOutcome_includesSecondPassEvidence() {
        val outcome = LocationCrossCheck.compare(
            firstPass(),
            org.json.JSONObject(secondPassJson(city = "Oakham", confidence = "medium")),
            secondPoint = org.osmdroid.util.GeoPoint(52.6668, -0.6427)
        )
        val evidence = outcome.evidence.joinToString("\n")
        assertTrue(evidence.contains("Oakham"))
        assertTrue(evidence.contains("medium"))
        assertTrue(evidence.contains("Brigg"))
    }

    // -----------------------------------------------------------------------
    // Elimination reasoning (shown in the dispute dialog)
    // -----------------------------------------------------------------------

    @Test
    fun disputedOutcome_carriesEliminationReasoning() {
        val json = """
            {"title": "Bungay Buttercross", "city": "Bungay", "confidence": "high",
             "latitude": 52.4536, "longitude": 1.4358,
             "step_1_candidate_towns": "Brigg, Oakham, Bungay",
             "step_2_elimination": "King's Head Hotel sign visible; Brigg has no King's Head adjacent"}
        """.trimIndent()
        val outcome = LocationCrossCheck.compare(
            firstPass(),
            org.json.JSONObject(json),
            secondPoint = org.osmdroid.util.GeoPoint(52.4536, 1.4358)
        )
        assertEquals(LocationCrossCheck.Verdict.DISPUTED, outcome.verdict)
        assertEquals(
            "King's Head Hotel sign visible; Brigg has no King's Head adjacent",
            outcome.eliminationReasoning
        )
    }

    @Test
    fun unknownVerdict_withReasoning_keepsReasoning() {
        // Second pass said UNKNOWN_LOCATION but still returned JSON with its
        // elimination trail — the reasoning must survive for the dialog.
        val json = """
            {"title": "UNKNOWN_LOCATION", "step_2_elimination": "Two candidate towns survived"}
        """.trimIndent()
        val outcome = LocationCrossCheck.compare(firstPass(), org.json.JSONObject(json), secondPoint = null)
        assertEquals(LocationCrossCheck.Verdict.SECOND_PASS_FAILED, outcome.verdict)
        assertEquals("Two candidate towns survived", outcome.eliminationReasoning)
    }

    @Test
    fun confirmedOutcome_nullReasoningWhenAbsent() {
        val outcome = LocationCrossCheck.compare(
            firstPass(),
            org.json.JSONObject(secondPassJson(city = "Brigg", lat = 53.5535, lon = -0.4905)),
            secondPoint = org.osmdroid.util.GeoPoint(53.5535, -0.4905)
        )
        assertEquals(LocationCrossCheck.Verdict.CONFIRMED, outcome.verdict)
        assertNull(outcome.eliminationReasoning)
    }
}
