package com.example.location_finder

import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure cross-check logic for the Verify flow: compares a first-pass
 * [GeminiLocationResult] with the verdict from an independent second
 * analysis and produces an overall verdict + evidence for the dialog.
 *
 * No Android framework calls (except osmdroid's GeoPoint math), so the
 * whole object is unit-testable.
 */
object LocationCrossCheck {

    /** Verdicts shown to the user after a Verify click. */
    enum class Verdict { CONFIRMED, CLOSE_MATCH, DISPUTED, SECOND_PASS_FAILED }

    /** Complete outcome of a verification pass. */
    data class CrossCheckResult(
        val verdict: Verdict,
        /** Human-readable explanation lines for the result dialog. */
        val evidence: List<String>,
        /** Coordinates from the second pass, when it identified a location. */
        val secondPassPoint: GeoPoint?,
        /** True when the second pass replied UNKNOWN_LOCATION. */
        val secondPassUnknown: Boolean,
        /** The second pass's own confidence, e.g. "high". */
        val secondPassConfidence: String?,
        /** What the second pass titled the location, e.g. "The Buttercross, Brigg". */
        val secondPassTitle: String?,
        /** The second pass's candidate-elimination reasoning (step_2_elimination), shown in the dispute dialog. */
        val eliminationReasoning: String?,
        /** Short one-line headline, e.g. "Verified — both analyses agree". */
        val headline: String
    )

    /**
     * Compare the first pass with the second pass's JSON verdict.
     *
     * Agreement rules (in order):
     * - normalized town and place names match, and coordinates are within 2 km -> CONFIRMED
     * - the same town and place are named but coordinates are further apart or missing -> CLOSE_MATCH
     * - towns or place names differ, or the result cannot be corroborated -> DISPUTED
     */
    fun compare(first: GeminiLocationResult, second: JSONObject, secondPoint: GeoPoint?): CrossCheckResult {
        val secondResult = parseSecondPass(second)

        // Second pass explicitly could not identify the location.
        if (secondResult.unknown) return secondPassUnknownResult(secondResult.elimination)

        val secondTownRaw = secondResult.city ?: secondResult.region
        val secondTown = normalizeTown(secondTownRaw)
        val firstTownRaw = first.city ?: first.region
        val firstTown = normalizeTown(firstTownRaw)

        val evidence = mutableListOf<String>()
        secondPoint?.let { p ->
            evidence.add("Second analysis places it at ${formatCoord(p.latitude)}, ${formatCoord(p.longitude)}")
        }
        secondResult.title?.let { evidence.add("Second analysis titled it: \"$it\"") }
        secondTownRaw?.let { evidence.add("Second analysis says town/area: $it") }
        secondResult.confidence?.let { evidence.add("Second analysis confidence: $it") }
        firstTownRaw?.let { evidence.add("First pass town/area: $it") }

        val firstPlace = normalizePlaceName(first.title, firstTown)
        val secondPlace = normalizePlaceName(secondResult.title, secondTown)
        val townsDisagree = firstTown != null && secondTown != null && firstTown != secondTown
        val placesMatch = placesAgree(firstPlace, secondPlace)
        val placesDisagree = firstPlace != null && secondPlace != null && !placesMatch
        if (townsDisagree || placesDisagree) {
            return CrossCheckResult(
                Verdict.DISPUTED,
                evidence,
                secondPoint,
                false,
                secondResult.confidence,
                secondResult.title,
                secondResult.elimination,
                "Disputed — the independent analyses identify different places."
            )
        }

        // Distance comparison only follows agreement on both town and place name.
        if (secondPoint != null && first.latitude != null && first.longitude != null) {
            val distanceKm = haversineKm(first.latitude, first.longitude, secondPoint.latitude, secondPoint.longitude)
            evidence.add("Distance between the two analyses: ${formatDistance(distanceKm)}")

            val townAndPlaceMatch = firstTown != null && secondTown != null && placesMatch
            if (distanceKm <= COORDINATE_AGREEMENT_KM && townAndPlaceMatch) {
                return CrossCheckResult(
                    Verdict.CONFIRMED,
                    evidence,
                    secondPoint,
                    false,
                    secondResult.confidence,
                    secondResult.title,
                    secondResult.elimination,
                    "Verified — both analyses agree (within ${formatDistance(distanceKm)})"
                )
            }
        }

        // Same named place and town but coordinates differ: retain only town-level certainty.
        if (secondTown != null && firstTown != null) {
            return CrossCheckResult(
                Verdict.CLOSE_MATCH,
                evidence,
                secondPoint,
                false,
                secondResult.confidence,
                secondResult.title,
                secondResult.elimination,
                "Partly verified — same town, different exact spot. Showing the town centre only."
            )
        }

        return CrossCheckResult(
            Verdict.DISPUTED,
            evidence,
            secondPoint,
            false,
            secondResult.confidence,
            secondResult.title,
            secondResult.elimination,
            "Disputed — the two analyses disagree. Trust the first result with caution."
        )
    }

    /** Outcome when the second pass replies UNKNOWN_LOCATION (with or without JSON). */
    fun secondPassUnknownResult(eliminationReasoning: String? = null): CrossCheckResult = CrossCheckResult(
        verdict = Verdict.SECOND_PASS_FAILED,
        evidence = listOf(
            "The second analysis could not identify this location from the image, " +
                "so there is nothing to cross-check against."
        ),
        secondPassPoint = null,
        secondPassUnknown = true,
        secondPassConfidence = null,
        secondPassTitle = null,
        eliminationReasoning = eliminationReasoning,
        headline = "Unverified — second analysis could not identify the location"
    )

    /** Normalize the main venue/landmark name, excluding any locality after a comma or town tokens. */
    internal fun normalizePlaceName(raw: String?, normalizedTown: String? = null): String? {
        if (raw.isNullOrBlank()) return null
        var cleaned = raw.substringBefore(',')
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("the ")
        if (!normalizedTown.isNullOrBlank()) {
            val townTokens = normalizedTown.split(" ").filter { it.isNotBlank() }.toSet()
            val stripped = cleaned.split(" ")
                .filter { it !in townTokens }
                .joinToString(" ")
                .trim()
            if (stripped.isNotEmpty()) {
                cleaned = stripped
            }
        }
        return cleaned.ifEmpty { null }
    }

    private fun placesAgree(firstPlace: String?, secondPlace: String?): Boolean {
        if (firstPlace == null || secondPlace == null) return false
        if (firstPlace == secondPlace) return true
        if (firstPlace.contains(secondPlace) || secondPlace.contains(firstPlace)) return true
        return false
    }

    /** Loose town comparison: lowercase, strip "city of", punctuation, whitespace. */
    internal fun normalizeTown(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .replace(Regex("\\bcity of\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifEmpty { null }
    }

    internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    internal fun formatDistance(km: Double): String =
        if (km < 1.0) "${(km * 1000).toInt()} m" else "%.1f km".format(km)

    internal fun formatCoord(value: Double): String = "%.5f".format(value)

    /** Minimal parser for the second pass's JSON verdict. */
    private fun parseSecondPass(json: JSONObject): ParsedSecondPass {
        // orEmpty(): with returnDefaultValues unit tests, org.json stubs return
        // null instead of the fallback — keep this null-safe for both worlds.
        val title = json.optString("title", "").orEmpty()
        val unknown = title.isEmpty() || title.contains("UNKNOWN_LOCATION", ignoreCase = true)
        return ParsedSecondPass(
            unknown = unknown,
            title = title.ifEmpty { null },
            city = json.optString("city", "").orEmpty().ifEmpty { null },
            region = json.optString("region", "").orEmpty().ifEmpty { null },
            confidence = json.optString("confidence", "").orEmpty().ifEmpty { null },
            candidateTowns = json.optString("step_1_candidate_towns", "").orEmpty().ifEmpty { null },
            elimination = json.optString("step_2_elimination", "").orEmpty().ifEmpty { null }
        )
    }

    private data class ParsedSecondPass(
        val unknown: Boolean,
        val title: String?,
        val city: String?,
        val region: String?,
        val confidence: String?,
        val candidateTowns: String?,
        val elimination: String?
    )

    private const val EARTH_RADIUS_KM = 6371.0088

    /** First and second pass coordinates within this distance count as agreement. */
    const val COORDINATE_AGREEMENT_KM = 2.0
}
