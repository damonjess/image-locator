package com.example.location_finder

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.location.Geocoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton

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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// ---------------------------------------------------------------------------
// Data classes
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var cameraOverlay: FrameLayout
    private lateinit var previewView: PreviewView
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private val geminiApiKey = BuildConfig.GEMINI_API_KEY.ifEmpty { "" }

    private var lastBitmap: Bitmap? = null
    private var lastImageUri: Uri? = null

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
            lastImageUri = uri
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

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            openCamera()
        } else {
            Toast.makeText(this, "Camera permission is needed for live capture.", Toast.LENGTH_LONG).show()
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
        cameraOverlay = findViewById(R.id.cameraOverlay)
        previewView = findViewById(R.id.cameraPreviewView)
        mapView = findViewById(R.id.mapView)
        cameraExecutor = Executors.newSingleThreadExecutor()

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

        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                openCamera()
            } else {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        findViewById<ImageButton>(R.id.btnCloseCamera).setOnClickListener {
            closeCamera()
        }

        findViewById<FloatingActionButton>(R.id.btnShutter).setOnClickListener {
            capturePhoto()
        }
    }

    private fun openCamera() {
        cameraOverlay.visibility = View.VISIBLE
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder().build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
            } catch (e: Exception) {
                Log.e("MainActivity", "Camera bind failed", e)
                Toast.makeText(this, "Could not start camera: ${e.message}", Toast.LENGTH_LONG).show()
                closeCamera()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun closeCamera() {
        cameraOverlay.visibility = View.GONE
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (_: Exception) {
        }
        imageCapture = null
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()
                runOnUiThread {
                    closeCamera()
                    lastBitmap = bitmap
                    lastImageUri = null  // CameraX captures have no file URI or EXIF GPS
                    loadingOverlay.visibility = View.VISIBLE
                    setButtonsEnabled(false)
                    askGeminiForLocation(bitmap)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("MainActivity", "Photo capture failed", exception)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val rotation = image.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        findViewById<Button>(R.id.btnSelectPhoto).isEnabled = enabled
        findViewById<Button>(R.id.btnVerify).isEnabled = enabled
        findViewById<Button>(R.id.btnCamera).isEnabled = enabled
    }

    // -----------------------------------------------------------------------
    // Prompt — asks Gemini for structured address fields + coordinate estimates
    // -----------------------------------------------------------------------

    private val prompt = """
        You are an elite Geoguessr detective. Analyze this image to find its exact location.
        Return a valid JSON object with the following keys:
        - "step_1_visual_clues": List every readable shop name, street sign, road marking, vehicle registration plate, architectural style, and specific background detail visible.
        - "step_2_logical_deduction": Explain step-by-step what specific town or city has this exact combination of clues.
        - "title": Name of the landmark or location.
        - "description": A short summary of what is visible.
        - "confidence": Your confidence level: "high", "medium", or "low".
        - "latitude": Your best estimate of the latitude in decimal degrees. Always include your best estimate when you can identify the location.
        - "longitude": Your best estimate of the longitude in decimal degrees. Always include your best estimate when you can identify the location.
        - "street": The street name if identifiable (e.g. "High Street"). Omit if unknown.
        - "city": The city or town name (e.g. "Scunthorpe"). Omit if unknown.
        - "region": The county or region if identifiable. Omit if unknown.
        - "country": The full country name (e.g. "United Kingdom"). Omit if unknown.
        - "country_code": The ISO 3166-1 alpha-2 country code in lowercase (e.g. "gb"). Omit if unknown.
        - "postcode": The postcode if visible or deducible. Omit if unknown.
        - "search_query": A precise search string for geocoding including all known address parts and the country (e.g. "High Street, Scunthorpe, DN15 6SU, England, United Kingdom").

        CRITICAL RULES:
        1. If you cannot logically deduce the specific location based on unique, cross-referenced evidence, you MUST reply EXACTLY with 'UNKNOWN_LOCATION'. Do not guess blindly.
        2. Latitude and longitude are estimates. The app will verify them against other sources.
        3. Include as many structured address fields as possible for accurate geocoding.
        4. The search_query MUST include the country name.
    """.trimIndent()

    // -----------------------------------------------------------------------
    // Gemini calls
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Response handling + location resolution pipeline
    // -----------------------------------------------------------------------

    private suspend fun handleGeminiResponse(rawText: String) {
        // Check EXIF GPS first — this works even if Gemini failed or returned UNKNOWN_LOCATION
        val exifPoint = lastImageUri?.let { checkExifGps(it) }

        if (rawText.contains("UNKNOWN_LOCATION")) {
            // Gemini couldn't identify the location, but EXIF GPS might still be available
            if (exifPoint != null) {
                withContext(Dispatchers.Main) {
                    plotOnMap(
                        ResolvedLocation(exifPoint, "EXIF GPS", false, "Photo GPS location"),
                        "Photo Location",
                        "Location from photo GPS metadata"
                    )
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Could not identify location.", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        val startIndex = rawText.indexOf('{')
        val endIndex = rawText.lastIndexOf('}')

        if (startIndex == -1 || endIndex == -1) {
            // Invalid Gemini response, but EXIF GPS might still save us
            if (exifPoint != null) {
                withContext(Dispatchers.Main) {
                    plotOnMap(
                        ResolvedLocation(exifPoint, "EXIF GPS", false, "Photo GPS location"),
                        "Photo Location",
                        "Location from photo GPS metadata"
                    )
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: AI returned invalid format.", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        try {
            val cleanJson = rawText.substring(startIndex, endIndex + 1)
            val json = JSONObject(cleanJson)

            val result = parseGeminiResponse(json)

            // If EXIF GPS is available, use it directly with Gemini's title/description
            if (exifPoint != null) {
                withContext(Dispatchers.Main) {
                    plotOnMap(
                        ResolvedLocation(exifPoint, "EXIF GPS", false, result.title),
                        result.title,
                        result.description
                    )
                }
                return
            }

            // Otherwise, resolve through the geocoding pipeline
            val resolved = resolveLocation(result)

            withContext(Dispatchers.Main) {
                if (resolved != null) {
                    plotOnMap(resolved, result.title, result.description)
                } else {
                    val query = result.searchQuery ?: result.title
                    Toast.makeText(this@MainActivity, "Identified '$query', but could not map coordinates.", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to parse Gemini response", e)
            // Last chance: EXIF GPS
            if (exifPoint != null) {
                withContext(Dispatchers.Main) {
                    plotOnMap(
                        ResolvedLocation(exifPoint, "EXIF GPS", false, "Photo GPS location"),
                        "Photo Location",
                        "Location from photo GPS metadata"
                    )
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: Could not parse location data.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Parse Gemini's JSON into a structured [GeminiLocationResult]. */
    private fun parseGeminiResponse(json: JSONObject): GeminiLocationResult {
        fun optStringOrNull(key: String): String? {
            val value = json.optString(key, "")
            return if (value.isEmpty() || value == "null") null else value
        }

        fun optDoubleOrNull(key: String): Double? {
            val value = json.optDouble(key, Double.NaN)
            return if (value.isNaN()) null else value
        }

        return GeminiLocationResult(
            title = optStringOrNull("title") ?: "Unknown Location",
            description = optStringOrNull("description") ?: "",
            confidence = optStringOrNull("confidence") ?: "medium",
            latitude = optDoubleOrNull("latitude"),
            longitude = optDoubleOrNull("longitude"),
            street = optStringOrNull("street"),
            city = optStringOrNull("city"),
            region = optStringOrNull("region"),
            country = optStringOrNull("country"),
            countryCode = optStringOrNull("country_code"),
            postcode = optStringOrNull("postcode"),
            searchQuery = optStringOrNull("search_query")
        )
    }

    /**
     * Resolve a [GeminiLocationResult] to a [ResolvedLocation] through a ranked
     * fallback pipeline. EXIF GPS is checked separately in [handleGeminiResponse]
     * before this is called, so this pipeline handles geocoding only:
     *
     * 1. Structured Nominatim query (street / city / postcode / country)
     * 2. Free-text Nominatim query (full search_query + country code)
     * 3. Gemini coordinates validated against the town's bounding box
     *    (used when Nominatim can't find the specific landmark, but Gemini's
     *    coordinate estimates fall within the correct town)
     * 4. Android native Geocoder
     * 5. Gemini coordinates without validation (last resort)
     */
    private fun resolveLocation(result: GeminiLocationResult): ResolvedLocation? {
        // 1. Structured Nominatim query
        getCoordinatesNominatimStructured(result)?.let { return it }

        // 2. Free-text Nominatim query
        getCoordinatesNominatimFreeText(result)?.let { return it }

        // 3. Town-validated Gemini coordinates
        // When Nominatim can't find the specific landmark/street (e.g. "The Buttercross,
        // Market Place, Brigg" returns zero results), Gemini's coordinate estimates
        // validated against the town's bounding box are more reliable than the native
        // Geocoder, which may resolve to the wrong street entirely.
        getCoordinatesGeminiValidated(result)?.let { return it }

        // 4. Native Android Geocoder
        val nativeQuery = result.searchQuery ?: result.title
        val nativePoint = getCoordinatesNative(nativeQuery)
        if (nativePoint != null) {
            return ResolvedLocation(
                point = nativePoint,
                source = "Android Geocoder",
                isApproximate = true,
                displayAddress = result.title
            )
        }

        // 5. Last resort: Gemini coordinates without validation
        if (result.latitude != null && result.longitude != null) {
            return ResolvedLocation(
                point = GeoPoint(result.latitude, result.longitude),
                source = "Gemini coordinates",
                isApproximate = true,
                displayAddress = result.title
            )
        }

        return null
    }

    // -----------------------------------------------------------------------
    // Gemini coordinate validation against town bounding box
    // -----------------------------------------------------------------------

    /**
     * When Nominatim can't find the specific landmark/street, validate Gemini's
     * coordinate estimates against the town's bounding box. If Gemini's
     * coordinates fall within the correct town, they're far more reliable than
     * the native Geocoder (which may resolve to the wrong street entirely).
     *
     * Example: "The Buttercross, Market Place, Brigg" returns zero Nominatim
     * results. But Gemini correctly identifies the location and provides
     * coordinates near the Market Place. We validate those coordinates fall
     * within Brigg's bounding box, then use them.
     */
    private fun getCoordinatesGeminiValidated(result: GeminiLocationResult): ResolvedLocation? {
        // Need both Gemini coordinates and a city name to validate against
        if (result.latitude == null || result.longitude == null) return null
        val city = result.city ?: return null

        return try {
            val encodedQuery = URLEncoder.encode(city, "UTF-8")
            val countryCodeParam = result.countryCode?.let {
                if (it.length == 2 && it.all { c -> c.isLetter() }) "&countrycodes=${it.lowercase(Locale.ROOT)}" else ""
            } ?: ""

            val url = URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=1$countryCodeParam")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("User-Agent", "LocationFinderApp/1.0")
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONArray(response)
            if (results.length() == 0) return null

            val town = results.getJSONObject(0)
            val bbox = town.optJSONArray("boundingbox") ?: return null

            val south = bbox.getString(0).toDouble()
            val north = bbox.getString(1).toDouble()
            val west = bbox.getString(2).toDouble()
            val east = bbox.getString(3).toDouble()

            val lat = result.latitude
            val lon = result.longitude

            // Check if Gemini's coordinates fall within the town's bounding box
            if (lat in south..north && lon in west..east) {
                ResolvedLocation(
                    point = GeoPoint(lat, lon),
                    source = "Gemini coordinates (town-validated)",
                    isApproximate = true,
                    displayAddress = result.title
                )
            } else {
                // Gemini's coordinates are outside the town — don't trust them
                null
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Gemini coordinate town validation failed", e)
            null
        }
    }

    // -----------------------------------------------------------------------
    // EXIF GPS extraction
    // -----------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun checkExifGps(uri: Uri): GeoPoint? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val latLong = FloatArray(2)
                if (exif.getLatLong(latLong)) {
                    GeoPoint(latLong[0].toDouble(), latLong[1].toDouble())
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Could not read EXIF GPS", e)
            null
        }
    }

    // -----------------------------------------------------------------------
    // Nominatim geocoding — structured query
    // -----------------------------------------------------------------------

    /**
     * Query Nominatim using structured address parameters (street, city,
     * postcode, country) instead of a single free-text string. This produces
     * much more precise results when Gemini provides individual address
     * components.
     */
    private fun getCoordinatesNominatimStructured(result: GeminiLocationResult): ResolvedLocation? {
        // Only attempt structured query if we have at least a street or postcode
        // — a city-only structured query would just return the city centre
        if (result.street == null && result.postcode == null) return null

        return try {
            val params = StringBuilder()
            result.street?.let { params.append("&street=").append(URLEncoder.encode(it, "UTF-8")) }
            result.city?.let { params.append("&city=").append(URLEncoder.encode(it, "UTF-8")) }
            result.region?.let { params.append("&county=").append(URLEncoder.encode(it, "UTF-8")) }
            result.postcode?.let { params.append("&postalcode=").append(URLEncoder.encode(it, "UTF-8")) }
            // Prefer country code (more precise for Nominatim), fall back to country name
            result.countryCode?.let {
                if (it.length == 2 && it.all { c -> c.isLetter() }) {
                    params.append("&countrycodes=").append(it.lowercase(Locale.ROOT))
                } else {
                    result.country?.let { c -> params.append("&country=").append(URLEncoder.encode(c, "UTF-8")) }
                }
            } ?: result.country?.let { c -> params.append("&country=").append(URLEncoder.encode(c, "UTF-8")) }

            if (params.isEmpty()) return null

            val url = URL("https://nominatim.openstreetmap.org/search?format=json&limit=5&addressdetails=1$params")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("User-Agent", "LocationFinderApp/1.0")
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONArray(response)
            if (results.length() == 0) return null

            val best = rankNominatimResults(results, result) ?: return null
            val point = GeoPoint(best.getString("lat").toDouble(), best.getString("lon").toDouble())

            // All Nominatim results are approximate — only EXIF GPS is exact
            ResolvedLocation(
                point = point,
                source = "Nominatim (structured)",
                isApproximate = true,
                displayAddress = best.optString("display_name", result.title)
            )
        } catch (e: Exception) {
            Log.w("MainActivity", "Structured Nominatim query failed", e)
            null
        }
    }

    // -----------------------------------------------------------------------
    // Nominatim geocoding — free-text query
    // -----------------------------------------------------------------------

    /**
     * Query Nominatim using the full search_query string from Gemini.
     * Unlike the old implementation, this does NOT hardcode countrycodes=gb
     * — it uses Gemini's country_code if available, or does a global search.
     */
    private fun getCoordinatesNominatimFreeText(result: GeminiLocationResult): ResolvedLocation? {
        return try {
            val query = (result.searchQuery ?: buildString {
                result.street?.let { append(it).append(", ") }
                result.city?.let { append(it).append(", ") }
                result.postcode?.let { append(it).append(", ") }
                result.country?.let { append(it) }
            }).trim().trimEnd(',').trim()

            if (query.isEmpty()) return null

            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val countryCodeParam = result.countryCode?.let {
                if (it.length == 2 && it.all { c -> c.isLetter() }) "&countrycodes=${it.lowercase(Locale.ROOT)}" else ""
            } ?: ""

            val url = URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=5&addressdetails=1$countryCodeParam")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("User-Agent", "LocationFinderApp/1.0")
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONArray(response)
            if (results.length() == 0) return null

            val best = rankNominatimResults(results, result) ?: return null
            val point = GeoPoint(best.getString("lat").toDouble(), best.getString("lon").toDouble())

            ResolvedLocation(
                point = point,
                source = "Nominatim (search)",
                isApproximate = true,
                displayAddress = best.optString("display_name", result.title)
            )
        } catch (e: Exception) {
            Log.w("MainActivity", "Free-text Nominatim query failed", e)
            null
        }
    }

    // -----------------------------------------------------------------------
    // Nominatim result ranking
    // -----------------------------------------------------------------------

    /**
     * Pick the best Nominatim result by scoring each candidate against
     * Gemini's structured address fields, instead of just taking the one
     * with the highest "importance" value.
     */
    private fun rankNominatimResults(results: JSONArray, geminiResult: GeminiLocationResult): JSONObject? {
        var best: JSONObject? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (i in 0 until results.length()) {
            val obj = results.getJSONObject(i)
            val score = scoreNominatimResult(obj, geminiResult)
            if (score > bestScore) {
                bestScore = score
                best = obj
            }
        }

        return best
    }

    /**
     * Score a single Nominatim result. Higher is better.
     *
     * Factors:
     * - Base importance score from Nominatim
     * - Street name match in display_name (+3)
     * - City / town / village match in address (+2) or display_name (+1)
     * - Postcode match (+2)
     * - Country match (+1)
     * - Class-based bonuses: prefer highway results for street queries,
     *   tourism / amenity / historic for landmark queries
     * - Penalise broad "place" results when a street is expected
     */
    private fun scoreNominatimResult(obj: JSONObject, geminiResult: GeminiLocationResult): Double {
        var score = obj.optDouble("importance", 0.0)

        val displayName = obj.optString("display_name", "").lowercase(Locale.ROOT)
        val address = obj.optJSONObject("address")
        val objClass = obj.optString("class", "")

        // --- Street name match ---
        geminiResult.street?.let { street ->
            if (displayName.contains(street.lowercase(Locale.ROOT))) {
                score += 3.0
            }
        }

        // --- City match ---
        geminiResult.city?.let { city ->
            val cityLower = city.lowercase(Locale.ROOT)
            val addrCity = address?.optString("city", "")?.lowercase(Locale.ROOT) ?: ""
            val addrTown = address?.optString("town", "")?.lowercase(Locale.ROOT) ?: ""
            val addrVillage = address?.optString("village", "")?.lowercase(Locale.ROOT) ?: ""
            val addrHamlet = address?.optString("hamlet", "")?.lowercase(Locale.ROOT) ?: ""

            if (cityLower == addrCity || cityLower == addrTown ||
                cityLower == addrVillage || cityLower == addrHamlet
            ) {
                score += 2.0
            } else if (displayName.contains(cityLower)) {
                score += 1.0
            }
        }

        // --- Postcode match ---
        geminiResult.postcode?.let { postcode ->
            val addrPostcode = address?.optString("postcode", "") ?: ""
            if (postcode.replace(" ", "").equals(addrPostcode.replace(" ", ""), ignoreCase = true)) {
                score += 2.0
            }
        }

        // --- Country match ---
        geminiResult.country?.let { country ->
            val addrCountry = address?.optString("country", "") ?: ""
            if (country.lowercase(Locale.ROOT) == addrCountry.lowercase(Locale.ROOT)) {
                score += 1.0
            }
        }

        // --- Class-based preference ---
        if (geminiResult.street != null) {
            // Street-level query: prefer highway results, penalise broad place results
            if (objClass == "highway") score += 1.5
            if (objClass == "place") score -= 2.0
        } else {
            // Landmark query: prefer tourism / amenity / historic / leisure results
            if (objClass == "tourism") score += 2.0
            if (objClass == "amenity") score += 1.0
            if (objClass == "historic") score += 1.5
            if (objClass == "leisure") score += 1.0
        }

        return score
    }

    // -----------------------------------------------------------------------
    // Native Android Geocoder (last geocoding fallback)
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Map plotting
    // -----------------------------------------------------------------------

    private fun plotOnMap(resolved: ResolvedLocation, title: String, description: String) {
        mapView.overlays.clear()

        val displayTitle = if (resolved.isApproximate) "$title (approximate)" else title
        val displayDesc = if (resolved.isApproximate) {
            "$description\n\nSource: ${resolved.source} (approximate location)"
        } else {
            "$description\n\nSource: ${resolved.source}"
        }

        val marker = Marker(mapView).apply {
            position = resolved.point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            this.title = displayTitle
            snippet = displayDesc
        }

        mapView.overlays.add(marker)
        mapView.controller.animateTo(resolved.point)
        // Zoom out for approximate results so the user sees the general area
        mapView.controller.setZoom(if (resolved.isApproximate) 15.0 else 17.0)
        marker.showInfoWindow()

        AlertDialog.Builder(this)
            .setTitle("Location Found: $displayTitle")
            .setMessage(
                "$description\n\n" +
                "Coordinates: (${resolved.point.latitude}, ${resolved.point.longitude})\n" +
                "Source: ${resolved.source}" +
                (if (resolved.isApproximate) "\n\nNote: This is an approximate location." else "")
            )
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

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
