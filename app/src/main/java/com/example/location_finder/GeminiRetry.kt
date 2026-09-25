package com.example.location_finder

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * Shared retry-with-backoff for the Gemini API calls.
 *
 * Google's Gemini API intermittently returns transient errors — most commonly
 * "This model is currently experiencing high demand" (HTTP 503 UNAVAILABLE) or
 * 429 rate limits. A single attempt per model fails instantly during a spike,
 * so callers wrap their model loop with [withRetries] to re-attempt with
 * exponential backoff before surfacing an error to the user.
 */
object GeminiRetry {

    /** Total attempts per model: 1 initial + 2 retries. */
    private const val MAX_ATTEMPTS = 3

    /** Base delay for exponential backoff: 1s, 2s (+/- jitter). */
    private const val BASE_DELAY_MS = 1_000L

    /**
     * Run [block], re-running it when it throws a transient error.
     * Non-retryable errors (bad API key, malformed request, cancelled) are
     * rethrown immediately.
     */
    suspend fun <T> withRetries(block: suspend () -> T): T {
        var lastException: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!isRetryable(e)) throw e
                lastException = e
                if (attempt < MAX_ATTEMPTS) {
                    delay(backoffDelayMs(attempt))
                }
            }
        }
        throw lastException ?: IOException("Request failed after $MAX_ATTEMPTS attempts")
    }

    /**
     * Errors worth retrying: server-side overload/capacity issues, rate
     * limits, and network timeouts. Google surfaces these with different
     * phrasings depending on whether the error came from HTTP status parsing
     * or the JSON error body, so match generously on message text.
     */
    internal fun isRetryable(e: Exception): Boolean {
        if (e is SocketTimeoutException || e is IOException) return true

        val message = (e.message ?: "").lowercase()
        return message.contains("overload") ||
            message.contains("overloaded") ||
            message.contains("high demand") ||
            message.contains("unavailable") ||
            message.contains("503") ||
            message.contains("internal error") ||
            message.contains("internal server error") ||
            message.contains("500") ||
            message.contains("rate limit") ||
            message.contains("resource_exhausted") ||
            message.contains("429") ||
            message.contains("deadline") ||
            message.contains("timed out") ||
            message.contains("timeout") ||
            message.contains("connection")
    }

    /** Exponential backoff with jitter: attempt 1 -> ~1s, attempt 2 -> ~2s. */
    internal fun backoffDelayMs(attempt: Int): Long {
        val exponential = BASE_DELAY_MS shl (attempt - 1)
        val jitter = Random.nextLong(0, exponential / 2 + 1)
        return exponential + jitter
    }
}
