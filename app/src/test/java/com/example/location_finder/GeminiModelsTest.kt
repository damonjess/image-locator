package com.example.location_finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiModelsTest {

    // -----------------------------------------------------------------------
    // unavailableModels — pure helper, no Android dependencies
    // -----------------------------------------------------------------------

    @Test
    fun unavailableModels_allConfiguredAvailable_returnsEmpty() {
        val available = setOf(
            "models/gemini-3.6-flash",
            "models/gemini-3.5-flash",
            "models/gemini-3.1-flash-lite"
        )
        val missing = GeminiModels.unavailableModels(GeminiModels.FALLBACK_CHAIN, available)
        assertTrue("Expected no missing models, got $missing", missing.isEmpty())
    }

    @Test
    fun unavailableModels_retiredModel_isReported() {
        // Simulates the gemini-1.5 retirement breakage: chain contains a
        // retired name that ListModels no longer returns.
        val configured = listOf("gemini-1.5-flash", "gemini-3.6-flash")
        val available = setOf("models/gemini-3.6-flash", "models/gemini-2.0-flash")
        val missing = GeminiModels.unavailableModels(configured, available)
        assertEquals(listOf("gemini-1.5-flash"), missing)
    }

    @Test
    fun unavailableModels_namesAreCaseInsensitive_andPrefixTolerant() {
        val configured = listOf("GEMINI-3.6-FLASH")
        val available = setOf("models/gemini-3.6-flash")
        val missing = GeminiModels.unavailableModels(configured, available)
        assertTrue("Expected case/prefix-insensitive match, got $missing", missing.isEmpty())
    }

    @Test
    fun unavailableModels_emptyAvailable_reportsEverything() {
        val missing = GeminiModels.unavailableModels(GeminiModels.FALLBACK_CHAIN, emptySet())
        assertEquals(GeminiModels.FALLBACK_CHAIN, missing)
    }

    @Test
    fun unavailableModels_preservesConfiguredOrder_inReport() {
        val configured = listOf("a-model", "b-model", "c-model")
        val available = setOf("models/b-model")
        val missing = GeminiModels.unavailableModels(configured, available)
        assertEquals(listOf("a-model", "c-model"), missing)
    }

    // -----------------------------------------------------------------------
    // Shared fallback chain sanity
    // -----------------------------------------------------------------------

    @Test
    fun fallbackChain_isNonEmpty() {
        assertTrue(GeminiModels.FALLBACK_CHAIN.isNotEmpty())
    }

    @Test
    fun fallbackChain_containsNoRetiredGemini15Models() {
        // Guards against the exact regression that broke every lookup:
        // the 1.5 family is retired and rejects with "model not found".
        val retired = GeminiModels.FALLBACK_CHAIN.filter {
            it.startsWith("gemini-1.5") || it.startsWith("gemini-1.0")
        }
        assertTrue("Retired 1.x models in fallback chain: $retired", retired.isEmpty())
    }
}
