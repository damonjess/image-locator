package com.example.location_finder

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.location.Geocoder
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

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var cameraOverlay: FrameLayout
    private lateinit var previewView: PreviewView
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private val geminiApiKey = BuildConfig.GEMINI_API_KEY.ifEmpty { "" }

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

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}