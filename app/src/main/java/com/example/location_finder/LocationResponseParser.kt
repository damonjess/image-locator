package com.example.location_finder

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Pure parsing, validation, and query-building helpers for Gemini location
 * responses. Kept free of Android framework dependencies so every edge case
 * (social-media/OSINT results, thought parts, prose answers, Null Island
 * coordinates, informal region names) can be unit-tested on the JVM.
 */
object LocationResponseParser {

    private val PLACEHOLDER_STRINGS = setOf(
        "",
        "null",
        "none",
        "n/a",
        "na",
        "unknown",
        "unknown_location",
        "unknown location",
        "not applicable",
        "not visible",
        "unidentifiable"
    )

    /**
     * Extract non-thought text from a Gemini `candidates[0].content.parts` array.
     * Gemini 2.5/3.x models may include `{"thought": true, "text": "..."}` parts
     * containing internal chain-of-thought reasoning (which often mentions
     * "UNKNOWN_LOCATION" or draft JSON). Skipping thought parts prevents those
     * internal notes from corrupting the final JSON answer.
     */
    fun extractCandidateText(parts: JSONArray): String {
        val nonThought = StringBuilder()
        val allText = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (!part.has("text")) continue
            val text = part.optString("text", "")
            allText.append(text)
            if (!part.optBoolean("thought", false)) {
                nonThought.append(text)
            }
        }
        return if (nonThought.isNotEmpty()) nonThought.toString() else allText.toString()
    }

    /**
     * Extract a valid location [JSONObject] from [rawText].
     *
     * Handles:
     * - Raw JSON objects
     * - Markdown-fenced ```json ... ``` blocks
     * - Preamble/postamble prose around a JSON object (common when Google
     *   Search grounding is enabled)
     * - Multiple `{ ... }` blocks (picks the last balanced block that parses
     *   as JSON and carries location fields such as `title` or `city`)
     */
    fun extractJsonObject(rawText: String): JSONObject? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null

        // 1. Try fenced ```json ... ``` blocks first (from last to first)
        val fenceRegex = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE)
        val fencedMatches = fenceRegex.findAll(trimmed).toList()
        for (match in fencedMatches.asReversed()) {
            val candidate = runCatching { JSONObject(match.groupValues[1]) }.getOrNull()
            if (candidate != null && looksLikeLocationJson(candidate)) {
                return candidate
            }
        }

        // 2. Scan balanced top-level { ... } blocks from the end of the text
        val blocks = extractBalancedJsonBlocks(trimmed)
        for (block in blocks.asReversed()) {
            val candidate = runCatching { JSONObject(block) }.getOrNull()
            if (candidate != null && looksLikeLocationJson(candidate)) {
                return candidate
            }
        }

        // 3. Fallback: outermost { ... }
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            return runCatching { JSONObject(trimmed.substring(firstBrace, lastBrace + 1)) }.getOrNull()
        }

        return null
    }

    private fun looksLikeLocationJson(json: JSONObject): Boolean {
        return json.has("title") ||
            json.has("city") ||
            json.has("latitude") ||
            json.has("search_query") ||
            json.has("step_2_elimination")
    }

    /** Find balanced `{ ... }` substrings while respecting JSON string quotes and escapes. */
    internal fun extractBalancedJsonBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false

        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start != -1) {
                            blocks.add(text.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
        }
        return blocks
    }

    /**
     * Parse Gemini's JSON into a structured [GeminiLocationResult].
     *
     * Sanitizes placeholder values ("unknown", "N/A", "null") and filters out
     * Null Island `(0.0, 0.0)` or out-of-range coordinates so downstream
     * geocoding never plots `(0, 0)` or queries Nominatim for `&street=unknown`.
     */
    fun parseGeminiResponse(json: JSONObject): GeminiLocationResult {
        fun cleanOptionalString(key: String): String? {
            val raw = json.optString(key, "").orEmpty().trim()
            if (raw.lowercase(Locale.ROOT) in PLACEHOLDER_STRINGS) return null
            return raw
        }

        val rawLat = json.optDouble("latitude", Double.NaN)
        val rawLon = json.optDouble("longitude", Double.NaN)
        val validCoords = validLatLonOrNull(rawLat, rawLon)

        val city = cleanOptionalString("city")
        val region = cleanOptionalString("region")
        val country = cleanOptionalString("country")
        val rawTitle = cleanOptionalString("title")

        // If Gemini identified the town/region (e.g. "Ystalyfera") but left title
        // blank or unknown, synthesize a meaningful title instead of failing.
        val resolvedTitle = rawTitle
            ?: listOfNotNull(city, region, country).take(2).joinToString(", ").ifEmpty { "Unknown Location" }

        val deduction = cleanOptionalString("step_2_logical_deduction")
        val rawDescription = cleanOptionalString("description")
        val description = when {
            rawDescription != null -> rawDescription
            deduction != null -> deduction
            else -> ""
        }

        return GeminiLocationResult(
            title = resolvedTitle,
            description = description,
            confidence = cleanOptionalString("confidence")?.lowercase(Locale.ROOT) ?: "medium",
            latitude = validCoords?.first,
            longitude = validCoords?.second,
            street = cleanOptionalString("street"),
            city = city,
            region = region,
            country = country,
            countryCode = cleanOptionalString("country_code")?.lowercase(Locale.ROOT),
            postcode = cleanOptionalString("postcode"),
            searchQuery = cleanOptionalString("search_query")
        )
    }

    /**
     * Returns `(lat, lon)` only when both numbers are finite, within valid
     * geographic bounds, and not the `(0.0, 0.0)` Null Island placeholder.
     */
    fun validLatLonOrNull(lat: Double?, lon: Double?): Pair<Double, Double>? {
        if (lat == null || lon == null) return null
        if (lat.isNaN() || lon.isNaN() || lat.isInfinite() || lon.isInfinite()) return null
        if (lat == 0.0 && lon == 0.0) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    /**
     * Determine whether Gemini's response represents an unidentifiable location.
     *
     * Importantly, this does NOT reject a valid JSON result just because the
     * substring `"UNKNOWN_LOCATION"` appeared inside `step_1_visual_clues` or
     * `step_2_logical_deduction` (e.g. "Without the FRC logo this would be
     * UNKNOWN_LOCATION, but it is Texas Taff Barbecue in Ystalyfera").
     */
    fun isUnknownLocation(rawText: String, parsed: GeminiLocationResult?): Boolean {
        if (parsed != null) {
            val titleUnknown = parsed.title.equals("UNKNOWN_LOCATION", ignoreCase = true) ||
                parsed.title.equals("Unknown Location", ignoreCase = true) ||
                parsed.title.isBlank()
            val hasAnyLocationData = parsed.city != null ||
                parsed.region != null ||
                parsed.street != null ||
                parsed.postcode != null ||
                (parsed.latitude != null && parsed.longitude != null)
            return titleUnknown || !hasAnyLocationData
        }

        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.equals("UNKNOWN_LOCATION", ignoreCase = true)) return true

        // If there's no JSON and the short response explicitly states UNKNOWN_LOCATION
        if (trimmed.contains("UNKNOWN_LOCATION", ignoreCase = true) && trimmed.length < 120) {
            return true
        }
        return false
    }

    /**
     * Check whether a non-JSON grounded response looks like natural-language
     * prose that identified a place (like a Google Search grounded summary)
     * and can be structured into JSON via a follow-up call.
     */
    fun looksLikeGroundedProseAnswer(rawText: String): Boolean {
        val trimmed = rawText.trim()
        if (trimmed.length < 25) return false
        if (trimmed.equals("UNKNOWN_LOCATION", ignoreCase = true)) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("i cannot") ||
            lower.startsWith("i'm sorry") ||
            lower.startsWith("unable to identify") ||
            lower.contains("cannot determine the location")
        ) {
            return false
        }
        return true
    }

    /**
     * Build ordered free-text search queries for Nominatim.
     *
     * When Gemini identifies a business/venue that isn't mapped as an OSM POI
     * (e.g. `"Texas Taff Barbecue, Ystalyfera, Swansea Valley, South Wales, United Kingdom"`),
     * the full `search_query` returns zero results on Nominatim because
     * Nominatim requires every token to match. Falling back to the street/town
     * components lets Nominatim still geocode the street or village.
     */
    fun buildFreeTextQueries(result: GeminiLocationResult): List<String> {
        val queries = linkedSetOf<String>()

        result.searchQuery?.trim()?.trimEnd(',')?.trim()?.takeIf { it.isNotEmpty() }?.let {
            queries.add(it)
        }

        val primaryTitle = result.title.substringBefore(',').trim()
            .takeIf { it.isNotEmpty() && !it.equals("Unknown Location", ignoreCase = true) }

        if (primaryTitle != null && result.city != null && !primaryTitle.equals(result.city, ignoreCase = true)) {
            if (result.street != null) {
                queries.add(
                    listOfNotNull(primaryTitle, result.street, result.city, result.country)
                        .joinToString(", ")
                )
            }
            queries.add(
                listOfNotNull(primaryTitle, result.city, result.country)
                    .joinToString(", ")
            )
        }

        if (result.street != null && result.city != null) {
            queries.add(
                listOfNotNull(result.street, result.city, result.postcode, result.country)
                    .joinToString(", ")
            )
            queries.add(
                listOfNotNull(result.street, result.city, result.country)
                    .joinToString(", ")
            )
        }

        return queries.filter { it.isNotBlank() }
    }

    /**
     * Build ordered town/area fallback queries for Nominatim, from most
     * specific to broadest.
     *
     * Crucial for places like `"Ystalyfera"` where Gemini sets `region` to a
     * geographical valley or colloquial region (`"Swansea Valley, South Wales"`)
     * that is not part of OpenStreetMap's administrative boundary hierarchy
     * (`Neath Port Talbot, Cymru / Wales`). Trying `"$city, $region, $country"`
     * first and then falling back to `"$city, $country"` and `"$city"` ensures
     * Nominatim finds the village even when the region name is non-administrative.
     */
    fun buildTownFallbackQueries(result: GeminiLocationResult): List<String> {
        val queries = linkedSetOf<String>()
        val city = result.city?.trim()?.takeIf { it.isNotEmpty() }
        val region = result.region?.trim()?.takeIf { it.isNotEmpty() }
        val country = result.country?.trim()?.takeIf { it.isNotEmpty() }

        if (city != null) {
            if (region != null && country != null) {
                queries.add("$city, $region, $country")
            }
            if (country != null) {
                queries.add("$city, $country")
            }
            if (region != null) {
                queries.add("$city, $region")
            }
            queries.add(city)
        }

        if (region != null) {
            if (country != null) {
                queries.add("$region, $country")
            }
            queries.add(region)
        }

        return queries.filter { it.isNotBlank() }
    }
}
