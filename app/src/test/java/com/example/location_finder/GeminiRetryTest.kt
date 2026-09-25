package com.example.location_finder

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiRetryTest {

    // -----------------------------------------------------------------------
    // isRetryable
    // -----------------------------------------------------------------------

    @Test
    fun retryable_overloadMessage_isRetryable() {
        assertTrue(
            GeminiRetry.isRetryable(
                RuntimeException("The model is overloaded. Please try again later.")
            )
        )
    }

    @Test
    fun retryable_highDemandMessage_isRetryable() {
        assertTrue(
            GeminiRetry.isRetryable(
                RuntimeException("This model is currently experiencing high demand. Spikes in demand can cause errors.")
            )
        )
    }

    @Test
    fun retryable_rateLimit_isRetryable() {
        assertTrue(GeminiRetry.isRetryable(RuntimeException("429 RESOURCE_EXHAUSTED rate limit exceeded")))
    }

    @Test
    fun retryable_serverErrors_areRetryable() {
        assertTrue(GeminiRetry.isRetryable(RuntimeException("HTTP 503: Service Unavailable")))
        assertTrue(GeminiRetry.isRetryable(RuntimeException("500 Internal Server Error")))
    }

    @Test
    fun notRetryable_badApiKey_isNotRetryable() {
        assertFalse(
            GeminiRetry.isRetryable(
                RuntimeException("API key not valid. Please pass a valid API key.")
            )
        )
    }

    @Test
    fun notRetryable_malformedRequest_isNotRetryable() {
        assertFalse(
            GeminiRetry.isRetryable(
                RuntimeException("Invalid JSON payload received. Unknown name \"foo\"")
            )
        )
    }

    @Test
    fun notRetryable_nullMessage_isNotRetryable() {
        assertFalse(GeminiRetry.isRetryable(RuntimeException()))
    }

    // -----------------------------------------------------------------------
    // withRetries
    // -----------------------------------------------------------------------

    @Test
    fun withRetries_succeedsImmediately_noDelay() = runTest {
        var calls = 0
        val result = GeminiRetry.withRetries {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun withRetries_retriesTransientFailure_thenSucceeds() = runTest {
        var calls = 0
        val result = GeminiRetry.withRetries {
            calls++
            if (calls < 3) throw RuntimeException("The model is overloaded") else "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun withRetries_permanentFailure_thrownImmediately() = runTest {
        var calls = 0
        try {
            GeminiRetry.withRetries<String> {
                calls++
                throw RuntimeException("API key not valid")
            }
            throw AssertionError("Expected exception")
        } catch (e: RuntimeException) {
            assertEquals("API key not valid", e.message)
        }
        assertEquals(1, calls)
    }

    @Test
    fun withRetries_allAttemptsFail_throwsLastException() = runTest {
        var calls = 0
        try {
            GeminiRetry.withRetries<String> {
                calls++
                throw RuntimeException("The model is overloaded. Please try again later.")
            }
            throw AssertionError("Expected exception")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!.contains("overloaded"))
        }
        assertEquals(3, calls)
    }
}
