package com.example.location_finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Unit tests for the pure parts of [OverpassPoiLookup] — landmark name
 * extraction, query building, Overpass XML parsing and candidate scoring.
 */
class OverpassPoiLookupTest {

    private val brigg = GeoPoint(53.5526, -0.4896)

    // -----------------------------------------------------------------------
    // landmarkNameFromTitle
    // -----------------------------------------------------------------------

    @Test
    fun landmarkName_extractsLandmarkFromTitle() {
        assertEquals("The Buttercross", OverpassPoiLookup.landmarkNameFromTitle("The Buttercross, Brigg", "Brigg"))
    }

    @Test
    fun landmarkName_titleWithoutComma_isWholeTitle() {
        assertEquals("Brigg Market Cross", OverpassPoiLookup.landmarkNameFromTitle("Brigg Market Cross", "Brigg"))
    }

    @Test
    fun landmarkName_titleEqualsCity_returnsNull() {
        assertNull(OverpassPoiLookup.landmarkNameFromTitle("Brigg", "Brigg"))
        assertNull(OverpassPoiLookup.landmarkNameFromTitle("brigg", "Brigg"))
    }

    @Test
    fun landmarkName_unknownLocation_returnsNull() {
        assertNull(OverpassPoiLookup.landmarkNameFromTitle("Unknown Location", null))
    }

    // -----------------------------------------------------------------------
    // buildOverpassQuery
    // -----------------------------------------------------------------------

    @Test
    fun buildQuery_containsRegexAroundAndOutput() {
        val query = OverpassPoiLookup.buildOverpassQuery("The Buttercross", brigg, 2000)
        assertTrue(query.startsWith("[out:xml][timeout:15];("))
        assertTrue(query.contains("node[\"name\"~\"The Buttercross\",i](around:2000,53.552600,-0.489600)"))
        assertTrue(query.contains("way[\"name\"~\"The Buttercross\",i](around:2000,53.552600,-0.489600)"))
        assertTrue(query.contains("relation[\"name\"~\"The Buttercross\",i](around:2000,53.552600,-0.489600)"))
        assertTrue(query.endsWith(");out center 20;"))
    }

    @Test
    fun buildQuery_escapesRegexSpecials() {
        val query = OverpassPoiLookup.buildOverpassQuery("St. Mary's (Old)", brigg, 2000)
        assertTrue(query.contains("St\\. Mary's \\(Old\\)"))
    }

    // -----------------------------------------------------------------------
    // parseOverpassResponse
    // -----------------------------------------------------------------------

    @Test
    fun parseResponse_extractsNodesAndWayCenters() {
        val xml = """
            <osm version="0.6">
              <node id="123" lat="53.5530" lon="-0.4900">
                <tag k="name" v="Buttercross"/>
                <tag k="historic" v="market_cross"/>
              </node>
              <way id="456">
                <center lat="53.5520" lon="-0.4890"/>
                <tag k="name" v="Market Place"/>
                <tag k="highway" v="pedestrian"/>
              </way>
              <node id="789" lat="53.5531" lon="-0.4901"/>
            </osm>
        """.trimIndent()

        val pois = OverpassPoiLookup.parseOverpassResponse(xml)

        assertEquals(2, pois.size)
        val node = pois.first { it.osmType == "node" }
        assertEquals("Buttercross", node.name)
        assertEquals(53.5530, node.point.latitude, 1e-9)
        assertEquals(-0.4900, node.point.longitude, 1e-9)
        assertEquals("market_cross", node.tags["historic"])

        val way = pois.first { it.osmType == "way" }
        assertEquals("Market Place", way.name)
        assertEquals(53.5520, way.point.latitude, 1e-9)
        assertEquals(-0.4890, way.point.longitude, 1e-9)
    }

    @Test
    fun parseResponse_emptyResult_returnsEmptyList() {
        val xml = "<osm version=\"0.6\"></osm>"
        assertTrue(OverpassPoiLookup.parseOverpassResponse(xml).isEmpty())
    }

    @Test
    fun parseResponse_malformedXml_returnsEmptyList() {
        assertTrue(OverpassPoiLookup.parseOverpassResponse("<osm><node").isEmpty())
    }

    // -----------------------------------------------------------------------
    // scoreOverpassPoi / pickBestPoi
    // -----------------------------------------------------------------------

    private fun poi(name: String, vararg tags: Pair<String, String>, lat: Double = 53.5526, lon: Double = -0.4896) =
        OverpassPoiLookup.OverpassPoi(GeoPoint(lat, lon), name, "node", 1L, mapOf(*tags))

    @Test
    fun score_exactNameMatch_beatsSubstringMatch() {
        val exact = poi("Buttercross", "historic" to "market_cross")
        val partial = poi("Buttercross Bakehouse", "shop" to "bakery")
        val landmark = "Buttercross"

        val exactScore = OverpassPoiLookup.scoreOverpassPoi(exact, landmark, brigg)
        val partialScore = OverpassPoiLookup.scoreOverpassPoi(partial, landmark, brigg)

        assertTrue(exactScore > partialScore)
    }

    @Test
    fun score_historicTag_beatsPlainNamedObject() {
        val historic = poi("Buttercross", "historic" to "market_cross")
        val plain = poi("Buttercross")
        val landmark = "The Buttercross"

        assertTrue(
            OverpassPoiLookup.scoreOverpassPoi(historic, landmark, brigg) >
                OverpassPoiLookup.scoreOverpassPoi(plain, landmark, brigg)
        )
    }

    @Test
    fun score_unrelatedName_isBelowRejectionThreshold() {
        val unrelated = poi("Co-op Food", "shop" to "supermarket")
        assertTrue(OverpassPoiLookup.scoreOverpassPoi(unrelated, "The Buttercross", brigg) < OverpassPoiLookup.MIN_SCORE)
    }

    @Test
    fun score_farAwayCandidate_isPenalised() {
        val near = poi("Buttercross", lat = 53.5526, lon = -0.4896)
        val far = poi("Buttercross", lat = 53.5960, lon = -0.4400) // ~5 km away

        assertTrue(
            OverpassPoiLookup.scoreOverpassPoi(near, "Buttercross", brigg) >
                OverpassPoiLookup.scoreOverpassPoi(far, "Buttercross", brigg)
        )
    }

    @Test
    fun pickBest_prefersHighestScoringCandidate() {
        val candidates = listOf(
            poi("Market Place", "place" to "square", lat = 53.5520, lon = -0.4890),
            poi("Buttercross", "historic" to "market_cross"),
            poi("Buttercross Bakehouse", "shop" to "bakery", lat = 53.5530, lon = -0.4900)
        )

        val best = OverpassPoiLookup.pickBestPoi(candidates, "The Buttercross", brigg)

        assertNotNull(best)
        assertEquals("Buttercross", best!!.first.name)
        assertTrue(best.second >= 0.0)
    }

    @Test
    fun pickBest_allRejected_returnsNull() {
        val candidates = listOf(poi("Co-op Food", "shop" to "supermarket"))
        assertNull(OverpassPoiLookup.pickBestPoi(candidates, "The Buttercross", brigg))
    }

    // -----------------------------------------------------------------------
    // significantTokens
    // -----------------------------------------------------------------------

    @Test
    fun tokens_dropStopWordsAndCase() {
        assertEquals(
            listOf("buttercross", "brigg"),
            OverpassPoiLookup.significantTokens("The Buttercross of Brigg")
        )
        assertFalse(OverpassPoiLookup.significantTokens("The Market Cross").contains("the"))
    }
}
