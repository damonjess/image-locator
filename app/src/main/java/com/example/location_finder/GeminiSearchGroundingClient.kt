package com.example.location_finder

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object GeminiSearchGroundingClient {

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    private val MODELS_TO_TRY = listOf(
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite"
    )

    /** Grounded verification call — only invoke this on demand, not on every photo. */
    fun generateContentWithSearch(
        apiKey: String,
        bitmap: Bitmap,
        promptText: String
    ): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
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
            // Low temperature for deterministic, factual location identification
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("topP", 0.95)
                put("maxOutputTokens", 4096)
            })
            put("tools", JSONArray().apply {
                put(JSONObject().apply {
                    put("googleSearch", JSONObject())
                })
            })
        }

        var lastException: Exception? = null
        for (model in MODELS_TO_TRY) {
            try {
                val requestUrl = "$BASE_URL/$model:generateContent?key=$apiKey"
                return executePostRequest(requestUrl, payload)
            } catch (e: Exception) {
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
        connection.outputStream.use { it.write(requestBytes, 0, requestBytes.size) }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseString = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""

        if (responseCode !in 200..299) {
            Log.e("GeminiSearchGroundingClient", "API Error Response ($responseCode): $responseString")
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
        if (candidates.length() == 0) throw RuntimeException("Empty candidates array from Gemini")

        val content = candidates.getJSONObject(0).optJSONObject("content")
            ?: throw RuntimeException("No content in candidate")
        val parts = content.optJSONArray("parts")
            ?: throw RuntimeException("No parts in candidate content")

        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("text")) sb.append(part.getString("text"))
        }
        return sb.toString()
    }
}
