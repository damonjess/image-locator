package com.example.location_finder

import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun testGeminiLocationResultDataClass() {
        val result = GeminiLocationResult(
            title = "The Buttercross, Brigg",
            description = "Historic Buttercross in Market Place, Brigg",
            confidence = "high",
            latitude = 53.5526,
            longitude = -0.4896,
            street = "Market Place",
            city = "Brigg",
            region = "North Lincolnshire",
            country = "United Kingdom",
            countryCode = "gb",
            postcode = "DN20 8ER",
            searchQuery = "The Buttercross, Market Place, Brigg, DN20 8ER, United Kingdom"
        )
        assertEquals("The Buttercross, Brigg", result.title)
        assertEquals("Brigg", result.city)
        assertEquals(53.5526, result.latitude!!, 0.0001)
        assertEquals(-0.4896, result.longitude!!, 0.0001)
    }
}