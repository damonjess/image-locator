package com.example.location_finder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.location_finder.ui.LocationFinderApp
import com.example.location_finder.ui.theme.LocationFinderTheme
import com.example.location_finder.viewmodel.LocationSearchViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint

/** Structured location data parsed from Gemini's JSON response. */
data class GeminiLocationResult(
    val title: String,
    val description: String,
    val confidence: String,
    val latitude: Double?,
    val longitude: Double?,
    val street: String?,
    val city: String?,
    val region: String?,
    val country: String?,
    val countryCode: String?,
    val postcode: String?,
    val searchQuery: String?
)

/** A final resolved location with metadata about how it was obtained. */
data class ResolvedLocation(
    val point: GeoPoint,
    val source: String,
    val isApproximate: Boolean,
    val displayAddress: String
)

class MainActivity : ComponentActivity() {

    private val viewModel: LocationSearchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val config = Configuration.getInstance()
        config.load(this, getSharedPreferences("osmdroid_prefs", MODE_PRIVATE))
        config.userAgentValue = "LocationFinderApp/1.0"
        config.tileDownloadThreads = 4
        config.tileDownloadMaxQueueSize = 40

        setContent {
            LocationFinderTheme {
                LocationFinderApp(viewModel = viewModel)
            }
        }
    }
}
