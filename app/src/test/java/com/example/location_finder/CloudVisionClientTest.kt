package com.example.location_finder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudVisionClientTest {

    @Test
    fun parseResponse_extractsLandmarksLabelsEntitiesAndPages() {
        val json = JSONObject("""
            {
              "responses": [{
                "landmarkAnnotations": [{
                  "description": "The Buttercross",
                  "score": 0.88,
                  "locations": [{"latLng": {"latitude": 53.5526, "longitude": -0.4896}}]
                }],
                "webDetection": {
                  "bestGuessLabels": [{"label": "brigg market place"}],
                  "webEntities": [
                    {"description": "Brigg", "score": 0.95},
                    {"description": "Buttercross", "score": 0.89},
                    {"description": "brigg", "score": 0.80}
                  ],
                  "pagesWithMatchingImages": [
                    {"pageTitle": "Market Place, Brigg &bull; Visit Lincolnshire", "url": "https://www.visitlincolnshire.com/places/brigg/"},
                    {"pageTitle": "The Buttercross - Brigg Town Council", "url": "https://brigg.gov.uk/buttercross"}
                  ]
                }
              }]
            }
        """.trimIndent())

        val clues = CloudVisionClient.parseResponse(json)

        assertFalse(clues.isEmpty)
        assertEquals(1, clues.landmarks.size)
        assertEquals("The Buttercross", clues.landmarks[0].name)
        assertEquals(53.5526, clues.landmarks[0].latitude!!, 1e-4)
        assertEquals(-0.4896, clues.landmarks[0].longitude!!, 1e-4)

        assertEquals(listOf("brigg market place"), clues.bestGuessLabels)
        // Deduplicated case-insensitively
        assertEquals(listOf("Brigg", "Buttercross"), clues.webEntities)

        assertEquals(2, clues.matchingPages.size)
        assertEquals("Market Place, Brigg • Visit Lincolnshire", clues.matchingPages[0].title)
        assertEquals("visitlincolnshire.com", clues.matchingPages[0].host)
    }

    @Test
    fun parseResponse_emptyResponses_returnsEmptyClues() {
        val clues = CloudVisionClient.parseResponse(JSONObject("{}"))
        assertTrue(clues.isEmpty)
    }

    @Test(expected = RuntimeException::class)
    fun parseResponse_withError_throws() {
        val json = JSONObject("""
            {"responses": [{"error": {"code": 3, "message": "API key not valid"}}]}
        """.trimIndent())
        CloudVisionClient.parseResponse(json)
    }

    @Test
    fun formatForPrompt_emptyClues_returnsEmptyString() {
        val empty = CloudVisionClient.WebClues(emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals("", CloudVisionClient.formatForPrompt(empty))
        assertEquals("", CloudVisionClient.formatForPrompt(null))
    }

    @Test
    fun formatForPrompt_formatsReadableSection() {
        val clues = CloudVisionClient.WebClues(
            landmarks = listOf(CloudVisionClient.Landmark("The Buttercross", 0.92, 53.5526, -0.4896)),
            bestGuessLabels = listOf("brigg market place"),
            webEntities = listOf("Brigg", "Market Place"),
            matchingPages = listOf(CloudVisionClient.MatchingPage("Brigg Heritage", "example.com"))
        )

        val formatted = CloudVisionClient.formatForPrompt(clues)

        assertTrue(formatted.contains("REVERSE IMAGE SEARCH RESULTS"))
        assertTrue(formatted.contains("The Buttercross"))
        assertTrue(formatted.contains("53.55260"))
        assertTrue(formatted.contains("-0.48960"))
        assertTrue(formatted.contains("brigg market place"))
        assertTrue(formatted.contains("Brigg, Market Place"))
        assertTrue(formatted.contains("Brigg Heritage"))
        assertTrue(formatted.contains("example.com"))
    }

    @Test
    fun stripHtml_decodesEntitiesAndTags() {
        val raw = "<b>Market</b> &amp; &quot;Cross&quot; &#39;Town&#39; &lt;Place&gt;"
        assertEquals("Market & \"Cross\" 'Town' <Place>", CloudVisionClient.stripHtml(raw))
    }
}
