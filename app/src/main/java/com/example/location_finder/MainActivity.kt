package com.example.location_finder

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView

    private val geminiApiKey = BuildConfig.GEMINI_API_KEY

    private val generativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = geminiApiKey,
        )
    }

    // Esri World Street Map (Free, reliable, no 403 blocks)
    private val esriStreetSource = object : XYTileSource(
        "EsriWorldStreetMap",
        0, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"),
        "© Esri, HERE, Garmin, USGS, NGA, EPA, USDA, NPS"
    ) {}

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            Toast.makeText(this, "Analyzing image...", Toast.LENGTH_SHORT).show()
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            askGeminiForLocation(bitmap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = Configuration.getInstance()
        config.userAgentValue = "LocationFinderApp/1.0 (${packageName}; contact@example.com)"
        config.load(this, getSharedPreferences("osmdroid_prefs", MODE_PRIVATE))

        try {
            config.osmdroidTileCache?.deleteRecursively()
            config.osmdroidBasePath?.deleteRecursively()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        config.tileDownloadThreads = 8
        config.tileDownloadMaxQueueSize = 80

        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)

        // Initialize with Esri World Street Map
        mapView.setTileSource(esriStreetSource)
        mapView.setMultiTouchControls(true)
        mapView.isTilesScaledToDpi = true

        // Remove tile grid lines and set seamless background color
        mapView.overlayManager.tilesOverlay.loadingLineColor = Color.TRANSPARENT
        mapView.overlayManager.tilesOverlay.loadingBackgroundColor = Color.parseColor("#F2EFE9")

        mapView.controller.setZoom(5.0)
        mapView.controller.setCenter(GeoPoint(54.5, -2.0))

        findViewById<Button>(R.id.btnSelectPhoto).setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun askGeminiForLocation(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prompt = """
                    Analyze this image. If you can confidently identify the location, return a valid JSON object with:
                    - "title": Name of the landmark or location.
                    - "description": A short summary of what is visible.
                    - "search_query": Specific location name and city (e.g. 'Market Place, Brigg'). Do NOT include country.
                    Do NOT include coordinates. If unknown, reply 'UNKNOWN_LOCATION'.
                """.trimIndent()

                val response = generativeModel.generateContent(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                )

                val rawText = response.text?.trim() ?: ""

                if (!rawText.contains("UNKNOWN_LOCATION")) {
                    val cleanJson = rawText.removePrefix("```json").removeSuffix("```").trim()
                    val json = JSONObject(cleanJson)
                    val title = json.getString("title")
                    val description = json.getString("description")
                    val searchQuery = json.getString("search_query")

                    val coords = getCoordinatesNative(searchQuery)

                    withContext(Dispatchers.Main) {
                        if (coords != null) {
                            plotOnMap(coords, title, description)
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Identified '$searchQuery', but could not map coordinates.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Could not identify location.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getCoordinatesNative(locationName: String): GeoPoint? {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val addresses = geocoder.getFromLocationName(locationName, 1)
            if (!addresses.isNullOrEmpty()) {
                GeoPoint(addresses[0].latitude, addresses[0].longitude)
            } else {
                null
            }
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
