package com.example.location_finder

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var loadingOverlay: FrameLayout

    // Uses API key from local.properties / BuildConfig if present
    private val geminiApiKey = BuildConfig.GEMINI_API_KEY.ifEmpty { "" }

    // Keeps the last picked photo around so "Verify" can re-run it without a new picker trip
    private var lastBitmap: Bitmap? = null

    private val reliableStreetSource = object : XYTileSource(
        "OSMStandard",
        0, 19, 256, ".png",
        arrayOf(
            "https://a.tile.openstreetmap.org/",
            "https://b.tile.openstreetmap.org/",
            "https://c.tile.openstreetmap.org/"
        ),
        "© OpenStreetMap contributors"
    ) {}

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            loadingOverlay.visibility = View.VISIBLE
            setButtonsEnabled(false)

            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            lastBitmap = bitmap
            askGeminiForLocation(bitmap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = Configuration.getInstance()
        config.load(this, getSharedPreferences("osmdroid_prefs", MODE_PRIVATE))
        config.userAgentValue = "LocationFinderApp/1.0"
        config.tileDownloadThreads = 4
        config.tileDownloadMaxQueueSize = 40

        setContentView(R.layout.activity_main)

        loadingOverlay = findViewById(R.id.loadingOverlay)
        mapView = findViewById(R.id.mapView)

        mapView.setTileSource(reliableStreetSource)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(5.0)
        mapView.controller.setCenter(GeoPoint(54.5, -2.0))

        findViewById<Button>(R.id.btnSelectPhoto).setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        findViewById<Button>(R.id.btnVerify).setOnClickListener {
            val bitmap = lastBitmap
            if (bitmap == null) {
                Toast.makeText(this, "Identify a photo first, then Verify.", Toast.LENGTH_SHORT).show()
            } else {
                loadingOverlay.visibility = View.VISIBLE
                setButtonsEnabled(false)
                askGeminiForLocationGrounded(bitmap)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        findViewById<Button>(R.id.btnSelectPhoto).isEnabled = enabled
        findViewById<Button>(R.id.btnVerify).isEnabled = enabled
    }

    private val prompt = """
        You are an elite Geoguessr detective. Analyze this image to find its exact location.
        Return a valid JSON object with the following keys in exact order:
        - "step_1_visual_clues": List every readable shop name, street sign, architectural style, and specific background detail visible.
        - "step_2_logical_deduction": Explain step-by-step what specific town has this exact combination of clues. 
        - "title": Name of the landmark or location.
        - "description": A short summary of what is visible.
        - "search_query": Specific location name and city (e.g. 'High Street, Scunthorpe'). Do NOT include country.
        
        CRITICAL RULES:
        1. Do NOT include coordinates in the JSON.
        2. If you cannot logically deduce the specific town based on unique, cross-referenced evidence, you MUST reply EXACTLY with 'UNKNOWN_LOCATION'. Do not guess blindly.
    """.trimIndent()

    private fun askGeminiForLocation(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rawText = GeminiClient.generateContent(
                    apiKey = geminiApiKey,
                    bitmap = bitmap,
                    promptText = prompt
                ).trim()
                handleGeminiResponse(rawText)
            } catch (e: Exception) {
                Log.e("MainActivity", "Gemini Request Failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    loadingOverlay.visibility = View.GONE
                    setButtonsEnabled(true)
                }
            }
        }
    }

    /** Grounded re-check — only called when the user taps "Verify", not on every photo. */
    private fun askGeminiForLocationGrounded(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rawText = GeminiSearchGroundingClient.generateContentWithSearch(
                    apiKey = geminiApiKey,
                    bitmap = bitmap,
                    promptText = prompt
                ).trim()
                handleGeminiResponse(rawText)
            } catch (e: Exception) {
                Log.e("MainActivity", "Grounded Verify Request Failed", e)
                withContext(Dispatchers.Main) {
                    val msg = if (e.message?.contains("RESOURCE_EXHAUSTED") == true || e.message?.contains("quota") == true) {
                        "Verify quota used up for now — try again later, or trust the last result."
                    } else {
                        "Error: ${e.message}"
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    loadingOverlay.visibility = View.GONE
                    setButtonsEnabled(true)
                }
            }
        }
    }

    /** Shared parsing + mapping logic used by both the normal and grounded (Verify) calls. */
    private suspend fun handleGeminiResponse(rawText: String) {
        if (!rawText.contains("UNKNOWN_LOCATION")) {
            val startIndex = rawText.indexOf('{')
            val endIndex = rawText.lastIndexOf('}')

            if (startIndex != -1 && endIndex != -1) {
                val cleanJson = rawText.substring(startIndex, endIndex + 1)
                val json = JSONObject(cleanJson)

                val title = json.getString("title")
                val description = json.getString("description")
                val searchQuery = json.getString("search_query")

                val coords = getCoordinatesNominatim(searchQuery) ?: getCoordinatesNative(searchQuery)

                withContext(Dispatchers.Main) {
                    if (coords != null) {
                        plotOnMap(coords, title, description)
                    } else {
                        Toast.makeText(this@MainActivity, "Identified '$searchQuery', but could not map coordinates.", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: AI returned invalid format.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Could not identify location.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getCoordinatesNominatim(locationName: String): GeoPoint? {
        return try {
            val query = URLEncoder.encode(locationName, "UTF-8")
            val url = URL("https://nominatim.openstreetmap.org/search?q=$query&format=json&limit=5&countrycodes=gb")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("User-Agent", "LocationFinderApp/1.0")
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONArray(response)
            if (results.length() == 0) return null

            var best: JSONObject? = null
            var bestImportance = -1.0
            for (i in 0 until results.length()) {
                val obj = results.getJSONObject(i)
                val importance = obj.optDouble("importance", 0.0)
                if (importance > bestImportance) {
                    bestImportance = importance
                    best = obj
                }
            }
            best?.let { GeoPoint(it.getString("lat").toDouble(), it.getString("lon").toDouble()) }
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun getCoordinatesNative(locationName: String): GeoPoint? {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val addresses = geocoder.getFromLocationName(locationName, 1)
            if (!addresses.isNullOrEmpty()) GeoPoint(addresses[0].latitude, addresses[0].longitude) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun plotOnMap(point: GeoPoint, title: String, description: String) {
        mapView.overlays.clear()

        val marker = Marker(mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            this.title = title
            snippet = description
        }

        mapView.overlays.add(marker)
        mapView.controller.animateTo(point)
        mapView.controller.setZoom(17.0)
        marker.showInfoWindow()

        AlertDialog.Builder(this)
            .setTitle("Location Found: $title")
            .setMessage("$description\n\nCoordinates: (${point.latitude}, ${point.longitude})")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
}
