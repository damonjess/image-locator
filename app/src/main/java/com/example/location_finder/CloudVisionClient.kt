package com.example.location_finder

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * Reverse image search via Google Cloud Vision (LANDMARK_DETECTION +
 * WEB_DETECTION).
 *
 * Gemini alone struggles with photos that have no readable text: a clock
 * tower, a market square, and timber-framed buildings exist in hundreds of
 * towns, and the model tends to pick the most famous one. Web detection finds
 * where this exact image (or near-duplicates of it) appears online; the titles
 * of those pages and the best-guess label very often name the actual place.
 * The results are passed to Gemini as leads to verify, not as the answer.
 */
object CloudVisionClient {

    private const val ENDPOINT = "https://vision.googleapis.com/v1/images:annotate"
    private const val MAX_ENTITIES = 10
    private const val MAX_PAGES = 8

    data class Landmark(
        val name: String,
        val score: Double,
        val latitude: Double?,
        val longitude: Double?
    )

    data class MatchingPage(val title: String, val host: String?)

    data class WebClues(
        val landmarks: List<Landmark>,
        val bestGuessLabels: List<String>,
        val webEntities: List<String>,
        val matchingPages: List<MatchingPage>
    ) {
        val isEmpty: Boolean
            get() = landmarks.isEmpty() && bestGuessLabels.isEmpty() &&
                webEntities.isEmpty() && matchingPages.isEmpty()
    }

    /**
     * Runs landmark + web detection on [bitmap]. Returns null when the key is
     * missing; throws on API/network errors so the caller can log and carry on
     * without these clues.
     */
    suspend fun detect(apiKey: String, bitmap: Bitmap): WebClues? {
        if (apiKey.isBlank()) return null
        return withContext(Dispatchers.IO) {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
            val base64Image = Base64.getEncoder().encodeToString(output.toByteArray())
            detectWithBase64(apiKey, base64Image)
        }
    }

    /**
     * Runs landmark + web detection directly with a pre-encoded [base64Image]
     * to avoid redundant re-compression of the bitmap.
     */
    suspend fun detectWithBase64(apiKey: String, base64Image: String): WebClues? {
        if (apiKey.isBlank()) return null
        return withContext(Dispatchers.IO) {
            val payload = JSONObject().put(
                "requests", JSONArray().put(
                    JSONObject()
                        .put("image", JSONObject().put("content", base64Image))
                        .put(
                            "features", JSONArray()
                                .put(JSONObject().put("type", "LANDMARK_DETECTION").put("maxResults", 5))
                                .put(JSONObject().put("type", "WEB_DETECTION").put("maxResults", 15))
                        )
                        .put(
                            "imageContext",
                            JSONObject().put("webDetectionParams", JSONObject().put("includeGeoResults", true))
                        )
                )
            )

            val connection = (URL("$ENDPOINT?key=$apiKey").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 6000
                readTimeout = 12000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val message = runCatching {
                    JSONObject(body).getJSONObject("error").getString("message")
                }.getOrDefault("HTTP $code")
                throw RuntimeException("Cloud Vision error: $message")
            }

            parseResponse(JSONObject(body)).also {
                Log.i(
                    "CloudVisionClient",
                    "Web clues: landmarks=${it.landmarks.map { l -> l.name }}, " +
                        "labels=${it.bestGuessLabels}, entities=${it.webEntities.take(5)}, " +
                        "pages=${it.matchingPages.take(3).map { p -> p.title }}"
                )
            }
        }
    }

    /** Parses an `images:annotate` response body. Pure; unit-tested. */
    internal fun parseResponse(json: JSONObject): WebClues {
        val response = json.optJSONArray("responses")?.optJSONObject(0)
            ?: return WebClues(emptyList(), emptyList(), emptyList(), emptyList())

        response.optJSONObject("error")?.let { error ->
            throw RuntimeException("Cloud Vision error: ${error.optString("message")}")
        }

        val landmarks = response.optJSONArray("landmarkAnnotations").objects().mapNotNull { obj ->
            val name = obj.optString("description").trim()
            if (name.isEmpty()) return@mapNotNull null
            val latLng = obj.optJSONArray("locations")?.optJSONObject(0)?.optJSONObject("latLng")
            Landmark(
                name = name,
                score = obj.optDouble("score", 0.0),
                latitude = latLng?.optDouble("latitude")?.takeUnless { it.isNaN() },
                longitude = latLng?.optDouble("longitude")?.takeUnless { it.isNaN() }
            )
        }

        val web = response.optJSONObject("webDetection")

        val labels = web?.optJSONArray("bestGuessLabels").objects()
            .map { it.optString("label").trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val entities = web?.optJSONArray("webEntities").objects()
            .filter { it.optString("description").isNotBlank() }
            .sortedByDescending { it.optDouble("score", 0.0) }
            .map { it.optString("description").trim() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_ENTITIES)

        val pages = web?.optJSONArray("pagesWithMatchingImages").objects()
            .mapNotNull { obj ->
                val title = stripHtml(obj.optString("pageTitle"))
                if (title.isEmpty()) return@mapNotNull null
                MatchingPage(title, hostOf(obj.optString("url")))
            }
            .distinctBy { it.title.lowercase(Locale.ROOT) }
            .take(MAX_PAGES)

        return WebClues(landmarks, labels, entities, pages)
    }

    /**
     * Formats [clues] as a prompt section. Returns "" when there is nothing
     * useful, so callers can append it unconditionally.
     */
    fun formatForPrompt(clues: WebClues?): String {
        if (clues == null || clues.isEmpty) return ""
        return buildString {
            appendLine("REVERSE IMAGE SEARCH RESULTS (Google Cloud Vision, for this exact image):")
            appendLine(
                "These come from copies of this image or visually similar images found on the web. " +
                    "Titles of pages showing the same image and the best-guess label often name the exact place. " +
                    "Treat them as strong leads: confirm them against what is visible, prefer a place they name " +
                    "over one you merely think looks similar, and ignore results that are generic or contradict the image."
            )
            if (clues.landmarks.isNotEmpty()) {
                appendLine("Landmark detection:")
                clues.landmarks.forEach { l ->
                    val coordsPart = if (l.latitude != null && l.longitude != null) {
                        ", at %.5f, %.5f".format(Locale.ROOT, l.latitude, l.longitude)
                    } else ""
                    val scorePart = "%.2f".format(Locale.ROOT, l.score)
                    appendLine("- ${l.name} (score $scorePart$coordsPart)")
                }
            }
            if (clues.bestGuessLabels.isNotEmpty()) {
                appendLine("Best-guess label: ${clues.bestGuessLabels.joinToString("; ")}")
            }
            if (clues.webEntities.isNotEmpty()) {
                appendLine("Web entities (most relevant first): ${clues.webEntities.joinToString(", ")}")
            }
            if (clues.matchingPages.isNotEmpty()) {
                appendLine("Pages containing this image:")
                clues.matchingPages.forEach { p ->
                    appendLine("- \"${p.title}\"" + (p.host?.let { " ($it)" } ?: ""))
                }
            }
        }.trimEnd()
    }

    internal fun stripHtml(raw: String): String =
        raw.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&bull;", "•")
            .replace(Regex("&#(\\d+);")) { match ->
                val code = match.groupValues[1].toIntOrNull()
                if (code != null && code in 32..65535) code.toChar().toString() else match.value
            }
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
