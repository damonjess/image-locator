package com.example.location_finder

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Single source of truth for the Gemini models used by [GeminiClient] and
 * [GeminiSearchGroundingClient].
 *
 * The fallback chain used to be duplicated in both clients; when one copy was
 * edited to retired model names ("gemini-1.5-flash-8b" etc.), every lookup
 * failed with "model not found for API version v1beta" while the other copy
 * still looked correct. Keeping one list here makes that drift impossible.
 */
object GeminiModels {

    private const val TAG = "GeminiModels"

    private const val LIST_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    /**
     * Models tried in order for every request; the first that answers wins.
     * Only edit this list here — both clients read it.
     */
    val FALLBACK_CHAIN = listOf(
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite"
    )

    /**
     * Best-effort check: query the API for the models the current key can
     * actually use, then return the configured chain entries that are NOT
     * available (retired, renamed, or typo'd). Empty list = the whole chain
     * is healthy.
     *
     * Blocking network call — must be run off the main thread. Never throws:
     * on any failure (offline, bad key) it returns an empty list so startup
     * checks don't nag about infrastructure problems; the per-request
     * fallback chain still handles real availability issues at request time.
     */
    suspend fun missingModels(apiKey: String): List<String> {
        val available = try {
            fetchAvailableModelNames(apiKey)
        } catch (e: Exception) {
            Log.w(TAG, "Could not list Gemini models (offline or API error): ${e.message}")
            return emptyList()
        }
        return unavailableModels(FALLBACK_CHAIN, available)
    }

    /**
     * Pure helper: which of [configured] are missing from [available]?
     * ListModels returns names like "models/gemini-3.6-flash"; comparisons
     * ignore that prefix and letter case.
     */
    internal fun unavailableModels(configured: List<String>, available: Set<String>): List<String> {
        val normalized = available.map { it.removePrefix("models/").lowercase() }.toSet()
        return configured.filter { it.lowercase() !in normalized }
    }

    /** Fetch every model name the key can call generateContent on (all pages). */
    private fun fetchAvailableModelNames(apiKey: String): Set<String> {
        val names = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val pageUrl = buildString {
                append(LIST_MODELS_URL).append("?pageSize=1000")
                pageToken?.let { append("&pageToken=").append(URLEncoder.encode(it, "UTF-8")) }
                append("&key=").append(URLEncoder.encode(apiKey, "UTF-8"))
            }
            val connection = (URL(pageUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            if (responseCode !in 200..299) {
                throw RuntimeException("ListModels failed (HTTP $responseCode): $body")
            }

            val json = JSONObject(body)
            val models = json.getJSONArray("models")
            for (i in 0 until models.length()) {
                val model = models.getJSONObject(i)
                val supported = model.optJSONArray("supportedGenerationMethods") ?: continue
                var canGenerate = false
                for (j in 0 until supported.length()) {
                    if (supported.getString(j) == "generateContent") canGenerate = true
                }
                if (canGenerate) names.add(model.getString("name"))
            }
            pageToken = json.optString("nextPageToken", "").ifEmpty { null }
        } while (pageToken != null)
        return names
    }
}
