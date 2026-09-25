package com.example.location_finder

import android.util.Log
import org.osmdroid.util.GeoPoint
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Looks up the exact OpenStreetMap object for a landmark identified by Gemini.
 *
 * Nominatim often can't geocode a specific landmark ("The Buttercross, Market
 * Place, Brigg" returns zero results or just the street line midpoint). The OSM
 * data usually *does* contain the landmark as a node/way (e.g.
 * historic=market_cross named "Buttercross"). This queries Overpass for any OSM
 * object whose name matches the landmark, within a radius of Gemini's own
 * coordinate estimate, and returns the best name-matching candidate.
 *
 * The network + parsing split is deliberate: [parseOverpassResponse],
 * [pickBestPoi], [scoreOverpassPoi] etc. are pure functions so they can be unit
 * tested on the JVM without Robolectric.
 */
object OverpassPoiLookup {

    private const val TAG = "OverpassPoiLookup"

    /** A landmark POI extracted from an Overpass (OpenStreetMap) response. */
    data class OverpassPoi(
        val point: GeoPoint,
        val name: String,
        val osmType: String,
        val osmId: Long,
        val tags: Map<String, String>
    )

    /** Candidates below this score are considered unrelated — rejected. */
    internal const val MIN_SCORE = 3.0

    /** At or above this score the pin is treated as the exact landmark, not approximate. */
    private const val EXACT_MATCH_SCORE = 6.0

    private val ENDPOINTS = arrayOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    private val STOP_WORDS = setOf("the", "a", "an", "of", "and", "at", "in", "on")

    /**
     * Query Overpass for an OSM object matching the landmark in [result] and
     * return it as a [ResolvedLocation], or null when there is no usable
     * landmark name, no coordinate hint, or no convincing match.
     *
     * [centerHint] lets callers supply a better search centre than Gemini's raw
     * coordinates (e.g. a previously geocoded town centre).
     */
    fun lookupLandmarkPoi(result: GeminiLocationResult, centerHint: GeoPoint? = null): ResolvedLocation? {
        val center = centerHint
            ?: result.latitude?.let { lat -> result.longitude?.let { lon -> GeoPoint(lat, lon) } }
            ?: return null
        val landmarkName = landmarkNameFromTitle(result.title, result.city) ?: return null

        // Try a tight radius around the estimated position first, then widen.
        for (radiusMeters in intArrayOf(2_000, 5_000)) {
            val xml = try {
                executeQuery(buildOverpassQuery(landmarkName, center, radiusMeters))
            } catch (e: Exception) {
                Log.w(TAG, "Overpass landmark query failed", e)
                return null
            }
            val pois = parseOverpassResponse(xml)
            val best = pickBestPoi(pois, landmarkName, center) ?: continue

            return ResolvedLocation(
                point = best.first.point,
                source = "OpenStreetMap landmark (Overpass)",
                isApproximate = best.second < EXACT_MATCH_SCORE,
                displayAddress = best.first.name
            )
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Pure helpers (unit tested)
    // -----------------------------------------------------------------------

    /**
     * Extract the landmark name from Gemini's title: "The Buttercross, Brigg"
     * -> "The Buttercross". Returns null when the title carries no landmark
     * (it is just the town name or a generic fallback).
     */
    internal fun landmarkNameFromTitle(title: String, city: String?): String? {
        val primary = title.trim().substringBefore(',').trim()
        if (primary.isEmpty()) return null
        if (primary.equals("Unknown Location", ignoreCase = true)) return null
        if (city != null && primary.equals(city.trim(), ignoreCase = true)) return null
        return primary
    }

    /** Lower-cased significant words of a name, dropping stop words. */
    internal fun significantTokens(name: String): List<String> {
        return name.split(Regex("[^A-Za-z0-9']+"))
            .filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in STOP_WORDS }
            .map { it.lowercase(Locale.ROOT) }
    }

    /** Escape a plain name for use inside an Overpass regex value. */
    internal fun escapeOsmRegex(name: String): String {
        val specials = "\\^$.|?*+()[]{}"
        return buildString {
            for (c in name) {
                if (c in specials) append('\\')
                append(c)
            }
        }
    }

    /**
     * Build an Overpass QL query: any named node/way whose name exactly equals
     * the landmark name (or a common article variant), within [radiusMeters]
     * of [center]. Ways are returned with their geometric centre.
     *
     * Exact-name matches can use Overpass's name index. A case-insensitive
     * substring regex (~"...",i) scans unindexed data and regularly times out
     * on busy servers — observed at 29+ seconds against overpass-api.de.
     */
    internal fun buildOverpassQuery(landmarkName: String, center: GeoPoint, radiusMeters: Int): String {
        val lat = String.format(Locale.ROOT, "%.6f", center.latitude)
        val lon = String.format(Locale.ROOT, "%.6f", center.longitude)
        val clauses = StringBuilder()
        for (name in nameVariantsFor(landmarkName)) {
            val escaped = escapeOsmRegex(name)
            clauses.append("node[\"name\"=\"$escaped\"](around:$radiusMeters,$lat,$lon);")
            clauses.append("way[\"name\"=\"$escaped\"](around:$radiusMeters,$lat,$lon);")
        }
        return "[out:xml][timeout:15];($clauses);out center 20;"
    }

    /**
     * Common name variants for the landmark: OSM mappers often omit or keep
     * the leading article ("The Buttercross" vs "Buttercross").
     */
    internal fun nameVariantsFor(landmarkName: String): List<String> {
        val trimmed = landmarkName.trim()
        val variants = linkedSetOf(trimmed)
        if (trimmed.startsWith("The ", ignoreCase = true)) {
            variants.add(trimmed.substring(4).trim())
        } else {
            variants.add("The $trimmed")
        }
        return variants.toList()
    }

    /**
     * Detect Overpass failure responses that arrive with HTTP 200: an HTML
     * error page ("server too busy") or an XML body carrying a <remark>
     * runtime error. Both must be treated as failures so the caller falls
     * through to the mirror endpoint instead of parsing zero results.
     */
    internal fun isOverpassErrorBody(body: String): Boolean {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("<!DOCTYPE html", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
        ) {
            return true
        }
        return body.contains("runtime error", ignoreCase = true)
    }

    /**
     * Parse an Overpass XML response into named POIs. Nodes use their lat/lon;
     * ways and relations use the <center> element produced by "out center".
     * Unnamed or coordinate-less elements are skipped.
     */
    internal fun parseOverpassResponse(xml: String): List<OverpassPoi> {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            try {
                // The response comes from Overpass, but harden the parser anyway.
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {
                // Feature unsupported on this parser — proceed without it.
            }
            val doc = factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

            val pois = mutableListOf<OverpassPoi>()
            val all = doc.getElementsByTagName("*")
            for (i in 0 until all.length) {
                val element = all.item(i) as? Element ?: continue
                if (element.tagName !in setOf("node", "way", "relation")) continue

                val point = elementCenter(element) ?: continue
                val tags = elementTags(element)
                val name = tags["name"] ?: continue

                pois += OverpassPoi(
                    point = point,
                    name = name,
                    osmType = element.tagName,
                    osmId = element.getAttribute("id").toLongOrNull() ?: -1L,
                    tags = tags
                )
            }
            pois
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse Overpass response", e)
            emptyList()
        }
    }

    /** Highest-scoring candidate for the landmark, or null when none is convincing. */
    internal fun pickBestPoi(
        pois: List<OverpassPoi>,
        landmarkName: String,
        center: GeoPoint
    ): Pair<OverpassPoi, Double>? {
        var best: Pair<OverpassPoi, Double>? = null
        for (poi in pois) {
            val score = scoreOverpassPoi(poi, landmarkName, center)
            if (score < MIN_SCORE) continue
            if (best == null || score > best.second) best = poi to score
        }
        return best
    }

    /**
     * Score how well an OSM object matches the landmark Gemini identified.
     *
     * - Name: exact match +6, substring match +4, +1.5 per matched word
     * - Class hints: historic +2, tourism +1.5, amenity/leisure +1
     * - Distance from the search centre: small penalty so closer objects win ties
     */
    internal fun scoreOverpassPoi(poi: OverpassPoi, landmarkName: String, center: GeoPoint): Double {
        val poiName = poi.name.lowercase(Locale.ROOT)
        val landmarkLower = landmarkName.lowercase(Locale.ROOT)
        val landmarkTokens = significantTokens(landmarkName)
        val poiTokens = significantTokens(poi.name)

        var score = 0.0
        when {
            poiName == landmarkLower -> score += 6.0
            poiName.contains(landmarkLower) || landmarkLower.contains(poiName) -> score += 4.0
        }

        val matchedTokens = landmarkTokens.count { it in poiTokens }
        score += matchedTokens * 1.5
        if (landmarkTokens.size > 1 && matchedTokens == landmarkTokens.size) score += 1.0

        for (key in poi.tags.keys) {
            when (key) {
                "historic" -> score += 2.0
                "tourism" -> score += 1.5
                "amenity", "leisure" -> score += 1.0
            }
        }

        val distanceKm = center.distanceToAsDouble(poi.point) / 1000.0
        score -= minOf(distanceKm, 5.0) * 0.3

        return score
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun elementTags(element: Element): Map<String, String> {
        val tags = mutableMapOf<String, String>()
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (child.tagName == "tag") {
                val key = child.getAttribute("k")
                if (key.isNotEmpty()) tags[key] = child.getAttribute("v")
            }
        }
        return tags
    }

    private fun elementCenter(element: Element): GeoPoint? {
        if (element.tagName == "node" && element.hasAttribute("lat") && element.hasAttribute("lon")) {
            return geoPoint(element.getAttribute("lat"), element.getAttribute("lon"))
        }
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (child.tagName == "center") {
                return geoPoint(child.getAttribute("lat"), child.getAttribute("lon"))
            }
        }
        return null
    }

    private fun geoPoint(lat: String, lon: String): GeoPoint? {
        return try {
            GeoPoint(lat.toDouble(), lon.toDouble())
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Total time budget for the whole Overpass step across all endpoints. */
    private const val TOTAL_BUDGET_MS = 25_000L

    /** Per-request read timeout — kept short so a dead mirror doesn't eat the budget. */
    private const val READ_TIMEOUT_MS = 12_000

    /**
     * POST the query to Overpass, trying the primary endpoint then mirrors,
     * within a total time budget so an OSM outage doesn't stall the UI for
     * minutes — the geocoding pipeline has other fallbacks to get on with.
     */
    private fun executeQuery(query: String): String {
        var lastError: Exception? = null
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS
        for (endpoint in ENDPOINTS) {
            if (System.currentTimeMillis() >= deadline) break
            try {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("User-Agent", "LocationFinderApp/1.0")
                }
                val body = "data=" + URLEncoder.encode(query, "UTF-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = connection.responseCode
                if (code != 200) {
                    lastError = IOException("$endpoint returned HTTP $code")
                    continue
                }
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                // Busy Overpass servers return HTTP 200 with an HTML error page
                if (isOverpassErrorBody(responseBody)) {
                    lastError = IOException("$endpoint returned an error page")
                    continue
                }
                return responseBody
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Overpass query failed")
    }
}
