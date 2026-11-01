package com.example.location_finder

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object GeminiClient {

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    /** Models tried in order; the first that answers wins. Shared with [GeminiSearchGroundingClient] via [GeminiModels]. */
    private val MODELS_TO_TRY = GeminiModels.FALLBACK_CHAIN

    /**
     * Calls Gemini API to analyze the image and generate content.
     * Each model is retried with backoff for transient errors ("model
     * overloaded", rate limits, network timeouts) before moving to the next.
     */
    suspend fun generateContent(
        apiKey: String,
        bitmap: Bitmap,
        promptText: String
    ): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                        put(JSONObject().apply {
                            put("text", promptText)
                        })
                    })
                })
            })
            // Low temperature for deterministic, factual location identification.
            // Use a generous maxOutputTokens budget because Gemini 3.x thinking models
            // count internal thoughtsTokenCount (~3,500+ tokens) against maxOutputTokens;
            // a 4,096 cap truncates the visible JSON mid-sentence before the closing brace.
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("topP", 0.95)
                put("maxOutputTokens", 16384)
            })
        }

        return executeAcrossModels(apiKey, payload)
    }

    /**
     * Text-only Gemini call used to structure grounded prose responses into
     * the app's strict JSON location schema when Google Search grounding
     * replies in natural language instead of JSON.
     */
    suspend fun generateTextContent(
        apiKey: String,
        promptText: String
    ): String {
        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", promptText)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("topP", 0.95)
                put("maxOutputTokens", 8192)
            })
        }

        return executeAcrossModels(apiKey, payload)
    }

    private suspend fun executeAcrossModels(apiKey: String, payload: JSONObject): String {
        var lastException: Exception? = null
        for (model in MODELS_TO_TRY) {
            try {
                val requestUrl = "$BASE_URL/$model:generateContent?key=$apiKey"
                // Retry transient failures per model before falling back
                return withContext(Dispatchers.IO) {
                    GeminiRetry.withRetries {
                        executePostRequest(requestUrl, payload)
                    }
                }
            } catch (e: Exception) {
                Log.w("GeminiClient", "Model $model failed: ${e.message}")
                lastException = e
            }
        }

        throw lastException ?: RuntimeException("All Gemini models failed to process request.")
    }

    private fun executePostRequest(urlString: String, payload: JSONObject): String {
        val url = URL(urlString)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        val requestBytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
        connection.outputStream.use { os ->
            os.write(requestBytes, 0, requestBytes.size)
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseString = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""

        if (responseCode !in 200..299) {
            Log.e("GeminiClient", "API Error Response ($responseCode): $responseString")
            val errorMsg = try {
                JSONObject(responseString).getJSONObject("error").getString("message")
            } catch (_: Exception) {
                "HTTP $responseCode: $responseString"
            }
            throw RuntimeException(errorMsg)
        }

        val responseJson = JSONObject(responseString)
        val candidates = responseJson.optJSONArray("candidates")
            ?: throw RuntimeException("No candidates in Gemini response")

        if (candidates.length() == 0) {
            throw RuntimeException("Empty candidates array from Gemini")
        }

        val content = candidates.getJSONObject(0).optJSONObject("content")
            ?: throw RuntimeException("No content in candidate")

        val parts = content.optJSONArray("parts")
            ?: throw RuntimeException("No parts in candidate content")

        return LocationResponseParser.extractCandidateText(parts)
    }
}
