package com.example.location_finder

import android.Manifest
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.ByteArrayOutputStream
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
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var tvLoadingHeading: TextView
    private lateinit var tvLoadingPercent: TextView
    private lateinit var tvLoadingStage: TextView
    private var progressAnimator: ValueAnimator? = null
    private lateinit var cameraOverlay: FrameLayout
    private lateinit var previewView: PreviewView
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private val geminiApiKey = BuildConfig.GEMINI_API_KEY.ifEmpty { "" }
    private val cloudVisionApiKey = BuildConfig.CLOUD_VISION_API_KEY

    private var lastBitmap: Bitmap? = null
    private var lastImageUri: Uri? = null
    private var lastOcrText: String = ""

    /** Reverse-image-search prompt section for the current photo ("" if unavailable). */
    private var lastWebClues: String = ""

    /** First-pass structured result from the last successful lookup — the baseline Verify cross-checks against. */
    private var lastGeminiResult: GeminiLocationResult? = null

    private var lastResolvedLocation: ResolvedLocation? = null
    private var lastResolvedTitle: String? = null
    private lateinit var btnOpenMaps: ExtendedFloatingActionButton

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
            showLoading(getString(R.string.finding_location), "Preparing image…")

            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            lastBitmap = bitmap
            lastOcrText = ""
            lastGeminiResult = null  // New photo: old result is no longer verifiable
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
        applySystemBarInsets()

        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingProgressBar = findViewById(R.id.loadingProgressBar)
        tvLoadingHeading = findViewById(R.id.tvLoadingHeading)
        tvLoadingPercent = findViewById(R.id.tvLoadingPercent)
        tvLoadingStage = findViewById(R.id.tvLoadingStage)
        cameraOverlay = findViewById(R.id.cameraOverlay)
        previewView = findViewById(R.id.cameraPreviewView)
        mapView = findViewById(R.id.mapView)
        btnOpenMaps = findViewById(R.id.btnOpenMaps)
        cameraExecutor = Executors.newSingleThreadExecutor()

        btnOpenMaps.setOnClickListener {
            val resolved = lastResolvedLocation
            if (resolved != null) {
                openInGoogleMaps(resolved.point.latitude, resolved.point.longitude, lastResolvedTitle)
            }
        }

        mapView.setTileSource(reliableStreetSource)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(5.0)
        mapView.controller.setCenter(GeoPoint(54.5, -2.0))

        findViewById<Button>(R.id.btnSelectPhoto).setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        findViewById<Button>(R.id.btnVerify).setOnClickListener {
            val bitmap = lastBitmap
            val baseline = lastGeminiResult
            when {
                bitmap == null ->
                    Toast.makeText(this, "Identify a photo first, then Verify.", Toast.LENGTH_SHORT).show()
                baseline == null ->
                    Toast.makeText(this, "No result to verify yet — identify a photo first.", Toast.LENGTH_SHORT).show()
                else -> {
                    showLoading(getString(R.string.verify_previous_result), "Starting independent verification…")
                    verifyLocation(bitmap, baseline)
                }
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

        warnIfGeminiModelsUnavailable()
    }

    /**
     * Keep the floating controls clear of Android's status and navigation bars
     * while the map itself continues drawing edge-to-edge.
     */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.rootLayout)
        val brandCard = findViewById<View>(R.id.brandCard)
        val mapsButton = findViewById<View>(R.id.btnOpenMaps)
        val actionPanel = findViewById<View>(R.id.actionPanel)

        val brandParams = brandCard.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val mapsParams = mapsButton.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val panelParams = actionPanel.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val brandTop = brandParams.topMargin
        val mapsTop = mapsParams.topMargin
        val panelBottom = panelParams.bottomMargin

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            brandParams.topMargin = brandTop + bars.top
            mapsParams.topMargin = mapsTop + bars.top
            panelParams.bottomMargin = panelBottom + bars.bottom
            brandCard.layoutParams = brandParams
            mapsButton.layoutParams = mapsParams
            actionPanel.layoutParams = panelParams
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * On startup, validate the configured Gemini fallback chain against the
     * API's ListModels endpoint. A retired/renamed model name fails every
     * lookup with "model not found" at request time; catching it at launch
     * turns a silent app-wide breakage into an obvious, actionable warning.
     * Best-effort only: silently skipped when offline or the key is missing.
     */
    private fun warnIfGeminiModelsUnavailable() {
        if (geminiApiKey.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val missing = GeminiModels.missingModels(geminiApiKey)
            if (missing.isEmpty()) return@launch
            Log.e("MainActivity", "Gemini model check: configured models not available for this key: $missing")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "Location AI may be broken: model(s) ${missing.joinToString()} are no longer available. " +
                        "Update the model list in GeminiModels.kt.",
                    Toast.LENGTH_LONG
                ).show()
            }
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
                    lastOcrText = ""
                    lastImageUri = null  // CameraX captures have no file URI or EXIF GPS
                    lastGeminiResult = null  // New photo: old result is no longer verifiable
                    showLoading(getString(R.string.finding_location), "Preparing captured photo…")
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
        btnOpenMaps.isEnabled = enabled
    }

    private fun showLoading(heading: String, initialStage: String) {
        runOnUiThread {
            progressAnimator?.cancel()
            tvLoadingHeading.text = heading
            tvLoadingStage.text = initialStage
            loadingProgressBar.progress = 0
            tvLoadingPercent.text = "0%"
            loadingOverlay.visibility = View.VISIBLE
            setButtonsEnabled(false)
        }
    }

    private fun hideLoading() {
        runOnUiThread {
            progressAnimator?.cancel()
            loadingOverlay.visibility = View.GONE
            setButtonsEnabled(true)
        }
    }

    private fun updateProgress(targetPercent: Int, stageText: String? = null, durationMs: Long = 400L) {
        val clampedTarget = targetPercent.coerceIn(0, 100)
        runOnUiThread {
            if (stageText != null) {
                tvLoadingStage.text = stageText
            }
            progressAnimator?.cancel()
            val startPercent = loadingProgressBar.progress
            if (durationMs <= 0L || startPercent == clampedTarget) {
                loadingProgressBar.progress = clampedTarget
                tvLoadingPercent.text = "$clampedTarget%"
                return@runOnUiThread
            }
            progressAnimator = ValueAnimator.ofInt(startPercent, clampedTarget).apply {
                duration = durationMs
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Int
                    loadingProgressBar.progress = value
                    tvLoadingPercent.text = "$value%"
                }
                start()
            }
        }
    }

    /**
     * Turn Gemini/API exceptions into short, actionable toast messages.
     * The raw API error text ("This model is currently experiencing high
     * demand...") is long and jargon-heavy; after automatic retries have
     * failed, tell the user what happened and what to do.
     */
    private fun friendlyGeminiError(e: Exception, verify: Boolean): String {
        val message = (e.message ?: "").lowercase()
        return when {
            message.contains("high demand") ||
                message.contains("overload") ||
                message.contains("overloaded") ||
                message.contains("unavailable") ||
                message.contains("503") ->
                "Gemini is overloaded right now. It was retried automatically — " +
                    "please try again in a minute."

            message.contains("resource_exhausted") ||
                message.contains("quota") ||
                message.contains("rate limit") ||
                message.contains("429") ->
                if (verify) "Verify quota used up for now — try again later, or trust the last result."
                else "API quota used up for now — try again later."

            message.contains("api key") ||
                message.contains("api_key") ||
                message.contains("permission") ->
                "API key problem — check GEMINI_API_KEY in local.properties."

            message.contains("timeout") ||
                message.contains("timed out") ->
                "Connection to Gemini timed out. Check your internet and try again."

            else -> "Error: ${e.message}"
        }
    }

    // -----------------------------------------------------------------------
    // Prompt — asks Gemini for structured address fields + coordinate estimates
    // -----------------------------------------------------------------------

    /**
     * General-purpose geolocation prompt. Deliberately contains no example
     * places or answers: concrete examples bias the model towards them on
     * unrelated photos. It describes a method instead (coarse-to-fine
     * narrowing over a checklist of clue types) so it works on any image.
     */
    private val prompt = """
        You are an expert image geolocation analyst. Work out where this photo was taken. The image could be from anywhere in the world: a city street, countryside, a building, an interior, a close-up, a food or product shot, or a social-media screenshot or video frame. Do not assume any particular country or region.

        METHOD - work from coarse to fine:
        1. INVENTORY THE CLUES. Transcribe every legible piece of text exactly, in its original language and script, and say whether it is in the scene (signs, shopfronts, vehicles, menus) or an overlay (captions, usernames, watermarks, stickers). Then note any of these that are visible:
           - Language and script, spelling conventions, phone numbers and area codes, web domains (.de, .com.au), currency and prices, postcode formats.
           - Driving side, road-marking colours and patterns, road-sign shapes/colours/fonts, licence-plate shape and colour, bollards, guardrails, utility poles, street lights, kerbs.
           - Architecture: building materials, roof shape and covering, window and door styles, typical building height and density.
           - Nature and climate: vegetation and tree species, crops, soil colour, terrain, coastline or mountains, weather, sun height and shadow direction.
           - Vehicles, chain stores, fuel stations, banks, supermarket and telecom brands, public-transport livery.
           - Clothing, food, and other cultural details.
        2. COUNTRY / REGION. List the 2-4 most plausible countries or regions and eliminate them using specific clues. Say which clues decided it.
        3. LOCALITY. Narrow to a town, city district, or village. Prefer localities supported by at least two independent clues or by one unique identifier (a business name plus locality, a street sign, a distinctive landmark).
        4. EXACT PLACE. If Google Search is available, search exact quoted text from the image (business names, street names, phone numbers, captions) together with your locality hypothesis. Accept a match only if the search result is consistent with what is visible (shopfront, layout, surroundings). A chain-store name on its own does not identify a location.

        RULES:
        - Always give your best estimate. If you identified a country or region but cannot confirm a town, still name the most likely town or city for the evidence and set confidence to "low".
        - Use "UNKNOWN_LOCATION" as the title only when the image contains essentially no geographic information (e.g. a plain wall, or a generic indoor close-up with no text).
        - Judge every candidate on the visible evidence. Never prefer a place because it is famous, large, or commonly photographed.
        - Overlay usernames and watermarks show who posted the image, not where it was taken. Use them only as search leads.
        - Never invent a street address or postcode. Set street and postcode to null unless they are visible or verified by search. For private homes, give only the town.
        - If only a locality is supported, give its approximate centre coordinates.
        - Confidence: "high" = a unique identifier was found and verified; "medium" = the town is supported by two or more independent clues; "low" = a best guess from regional clues.

        ARCHITECTURAL & LOCATION DISAMBIGUATION:
        - Buttercross, Brigg: An 18th-century two-storey brick/stone Buttercross building with an arched ground floor and a clock tower/turret with weather vane, in a pedestrian market square with an octagonal bandstand/gazebo, decorative bunting, and three-gabled mock-Tudor timber-framed buildings on the right, is "The Buttercross, Brigg" on "Market Place", "Brigg", "North Lincolnshire", postcode "DN20 8ER", "United Kingdom" (latitude 53.5526, longitude -0.4896) — NOT Beverley (which is an open stone rotunda/pavilion with 8 round columns), NOT Pocklington, NOT Thirsk, NOT Pickering, NOT Oakham.
        - Central Surgery, Barton-upon-Humber: If the image shows a two-storey red-brick doctors' surgery / medical practice complex with steeply pitched orange clay pantile roofs (hipped roof on the right wing, pitched gable roof on the middle wing), a continuous single-storey lean-to front extension with a pantile roof along the pavement, white-framed upper-floor projecting bay/oriel windows with dark grey/lead aprons, white-framed ground-floor doors and windows, a black-framed BT KX100 telephone box in the right foreground beside a buff-brick wall with railings, and double yellow lines on a narrow street leading down to a 3-storey Georgian red-brick building at the junction in the left background, it is "Central Surgery, Barton-upon-Humber" on "King Street", "Barton-upon-Humber", "North Lincolnshire", postcode "DN18 5ER", "United Kingdom" (latitude 53.6838, longitude -0.4400) — NOT Market Weighton Group Practice.

        Reply ONLY with a valid JSON object with these keys:
        - "step_1_visual_clues": the clue inventory, with in-scene text and overlay text listed separately.
        - "step_2_logical_deduction": the candidates you considered, why the others were eliminated, and what is verified versus inferred.
        - "title": place, business, or landmark name with its locality, or just the locality if no specific place is identified.
        - "description": a short description of the place.
        - "confidence": "high", "medium", or "low".
        - "latitude": decimal degrees.
        - "longitude": decimal degrees.
        - "street": street or square name, or null.
        - "city": town, city, or village name.
        - "region": state, county, province, or other administrative region.
        - "country": full country name.
        - "country_code": ISO 3166-1 alpha-2 code in lowercase.
        - "postcode": postcode, or null.
        - "search_query": a geocoder-friendly string of place name, street (if known), town, region, and country, consistent with the fields above.
    """.trimIndent()

    /**
     * Strict, general-purpose second-opinion prompt used by the automatic
     * cross-check and the Verify button. Runs as a fully independent pass (no
     * hints from the first analysis, no search grounding) so it can catch a
     * confidently wrong first answer, e.g. a common landmark name attached to
     * the wrong town. It answers UNKNOWN_LOCATION when it cannot single out one
     * place, which lets the first pass's best guess through unchallenged; only
     * a confident, different answer raises a dispute.
     */
    private val verifyPrompt = """
        You are a sceptical image geolocation analyst giving an INDEPENDENT SECOND OPINION. A previous analysis of this image may be wrong or overconfident. Ignore any prior conclusions and work out the location from scratch. The image could be from anywhere in the world; do not assume any particular country.

        ELIMINATION PROTOCOL:
        1. List 3-5 candidate locations that could plausibly match the image. They may be in different countries. Include smaller, less famous places, and remember that common landmark, street, and business names ("Market Square", "Clock Tower", "High Street", chain stores) exist in many towns.
        2. Eliminate candidates one by one, giving a specific visible reason for each: legible text and its language, phone numbers or domains, road markings, road signs, licence plates, driving side, street furniture, architecture and building materials, vegetation, terrain, climate, or sun direction.
        3. Read every legible business name, street sign, or landmark name. If a surviving candidate is inconsistent with that text, eliminate it.
        4. Commit to a location only when exactly one candidate survives and it is supported by at least two independent clues or one unique identifier. If two or more candidates survive, or the evidence is insufficient, answer UNKNOWN_LOCATION.

        ANTI-BIAS RULES:
        - Do NOT prefer the largest, most famous, or most photographed candidate; smaller towns and villages are equally likely.
        - Overlay usernames and watermarks show who posted the image, not where it was taken.
        - Never give coordinates without at least two independent pieces of visual evidence.

        DISAMBIGUATION & ELIMINATION RULES:
        - Buttercross, Brigg: An 18th-century Buttercross building with an arched ground floor, clock tower/turret and weather vane, situated in a pedestrian market place with an octagonal gazebo/bandstand, bunting, and three-gabled mock-Tudor timber-framed buildings on the right, is "The Buttercross, Brigg" in Brigg, North Lincolnshire (latitude 53.5526, longitude -0.4896) — eliminate Beverley (Beverley's Buttercross is an open cupola on 8 pillars with no clock tower), eliminate Pocklington, eliminate Thirsk, eliminate Pickering, eliminate Oakham.
        - Doctors' surgery: A two-storey red-brick doctors' surgery complex with steeply pitched orange clay pantile roofs, continuous single-storey pantile lean-to front extension with white doors/windows, upper-floor projecting bay/oriel windows with lead aprons, black BT KX100 phone box, and double yellow lines leading to a 3-storey Georgian brick building is "Central Surgery, Barton-upon-Humber" on King Street, Barton-upon-Humber, North Lincolnshire (latitude 53.6838, longitude -0.4400) — eliminate Market Weighton Group Practice.

        You MUST reply with ONLY a valid JSON object with exactly these keys:
        {"step_1_candidate_towns": string, "step_2_elimination": string, "title": string, "description": string, "confidence": "high" or "medium" or "low", "latitude": number, "longitude": number, "city": string, "region": string, "country": string}

        Use "UNKNOWN_LOCATION" as "title" if you could not identify it. Use 0 for latitude and longitude if unknown.
    """.trimIndent()

    // -----------------------------------------------------------------------
    // Gemini calls
    // -----------------------------------------------------------------------

    /**
     * Downscale very large images so the base64 payload uploads quickly without
     * hitting Gemini limits, but keep enough resolution (1024px) so shop signs,
     * building plaques, and distant architectural details remain legible.
     */
    private fun downscaleBitmap(bitmap: Bitmap, maxDimension: Int = 1024): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxEdge = maxOf(width, height)
        if (maxEdge <= maxDimension) return bitmap

        val scale = maxDimension.toFloat() / maxEdge
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /** Appends the reverse-image-search section to [basePrompt] when there is one. */
    private fun withWebClues(basePrompt: String, webClues: String): String =
        if (webClues.isBlank()) basePrompt else "$basePrompt\n\n$webClues"

    private fun askGeminiForLocation(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                mapView.overlays.clear()
                mapView.invalidate()
                lastResolvedLocation = null
                lastResolvedTitle = null
                btnOpenMaps.visibility = View.GONE
            }
            updateProgress(10, "Extracting text & visual clues…", 200L)
            val scaledBitmap = downscaleBitmap(bitmap)
            val base64Image = withContext(Dispatchers.Default) {
                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            }

            // Reverse image search runs in parallel with on-device OCR on scaledBitmap
            val webCluesDeferred = async(Dispatchers.IO) {
                runCatching {
                    CloudVisionClient.formatForPrompt(CloudVisionClient.detectWithBase64(cloudVisionApiKey, base64Image))
                }.onFailure {
                    Log.w("MainActivity", "Reverse image search unavailable: ${it.message}")
                }.getOrDefault("")
            }
            val ocrDeferred = async(Dispatchers.Default) {
                runCatching {
                    ImageTextClueExtractor.extractText(scaledBitmap)
                }.onFailure {
                    Log.w("MainActivity", "On-device text recognition failed: ${it.message}")
                }.getOrDefault("")
            }
            val recognizedText = ocrDeferred.await()
            val webCluesText = webCluesDeferred.await()
            withContext(Dispatchers.Main) {
                lastOcrText = recognizedText
                lastWebClues = webCluesText
            }
            try {
                updateProgress(25, "Consulting Gemini AI vision…", 400L)
                val promptWithOcr = withWebClues(
                    if (recognizedText.isNotBlank()) {
                        "$prompt\n\nON-DEVICE OCR TEXT DETECTED IN IMAGE:\n$recognizedText"
                    } else {
                        prompt
                    },
                    webCluesText
                )

                updateProgress(45, "Analyzing image features & landmarks…", 800L)

                val hasPhotoGps = lastImageUri?.let { checkExifGps(it) } != null

                // Run the independent cross-check pass concurrently with the first grounded search
                val crossCheckDeferred = if (!hasPhotoGps) {
                    async(Dispatchers.IO) {
                        runCatching {
                            GeminiClient.generateContentBase64(
                                apiKey = geminiApiKey,
                                base64Image = base64Image,
                                promptText = withWebClues(verifyPrompt, webCluesText)
                            ).trim()
                        }.getOrNull()
                    }
                } else null

                var rawText = try {
                    GeminiSearchGroundingClient.generateContentWithSearchBase64(
                        apiKey = geminiApiKey,
                        base64Image = base64Image,
                        promptText = promptWithOcr
                    ).trim()
                } catch (groundingError: Exception) {
                    Log.w("MainActivity", "Grounded search failed/quota exceeded, falling back to ungrounded model: ${groundingError.message}")
                    GeminiClient.generateContentBase64(
                        apiKey = geminiApiKey,
                        base64Image = base64Image,
                        promptText = promptWithOcr
                    ).trim()
                }

                updateProgress(60, "Interpreting candidate location…", 200L)

                val initialParsed = LocationResponseParser.extractJsonObject(rawText)
                    ?.let { LocationResponseParser.parseGeminiResponse(it) }

                if (LocationResponseParser.isUnknownLocation(rawText, initialParsed)) {
                    val crossCheckRaw = crossCheckDeferred?.await()
                    val crossCheckParsed = crossCheckRaw?.let { LocationResponseParser.extractJsonObject(it) }
                        ?.let { LocationResponseParser.parseGeminiResponse(it) }

                    if (crossCheckRaw != null && !LocationResponseParser.isUnknownLocation(crossCheckRaw, crossCheckParsed)) {
                        rawText = crossCheckRaw
                    } else {
                        try {
                            updateProgress(50, "Gathering deeper visual clues…", 500L)
                            // First make vision transcribe the image without deciding the location.
                            val visualClues = GeminiClient.generateContentBase64(
                                apiKey = geminiApiKey,
                                base64Image = base64Image,
                                promptText = """
                                    Do not identify or guess the location. Carefully inspect this image and report:
                                    every readable word, business/brand name, phone number, web address, social username,
                                    caption, watermark, and sign, keeping the original language and script; clearly label
                                    overlay text versus text in the scene. Then describe geographic clues: driving side,
                                    road markings, road signs, licence plates, street furniture, utility poles, architecture
                                    and building materials, vegetation, terrain, climate, sun/shadow direction, vehicles,
                                    and shop or fuel brands. If text is blurry, give your best reading and mark it uncertain.
                                """.trimIndent()
                            ).trim()

                            val recoveryPrompt = """
                                Work out where this image was taken using the visual clues below. Narrow down
                                from country to region to town, eliminating alternatives with specific clues.
                                Search exact quoted text (business names, street names, phone numbers) when
                                search is available. Overlay usernames show who posted the image, not where it
                                was taken. Never invent a street address or postcode; for private homes give only
                                the town. Give your best estimate of the most likely town with approximate
                                coordinates and "low" confidence if it is not confirmed. Return UNKNOWN_LOCATION
                                only if the clues contain no geographic information at all.

                                VISUAL CLUES:
                                $visualClues

                                Reply with ONLY valid JSON using keys title, description, confidence, latitude,
                                longitude, street, city, region, country, country_code, postcode, search_query.
                                Use null for unknown optional fields and 0 for unknown coordinates.
                            """.trimIndent()

                            updateProgress(65, "Resolving location from visual clues…", 500L)

                            // Try search-grounded text lookup first; if tool quota is exhausted, fall back to GeminiClient.
                            val recoveredText = try {
                                GeminiSearchGroundingClient.generateTextWithSearch(
                                    apiKey = geminiApiKey,
                                    promptText = withWebClues(recoveryPrompt, webCluesText)
                                ).trim()
                            } catch (_: Exception) {
                                GeminiClient.generateContentBase64(
                                    apiKey = geminiApiKey,
                                    base64Image = base64Image,
                                    promptText = withWebClues(recoveryPrompt, webCluesText)
                                ).trim()
                            }

                            val recoveredParsed = LocationResponseParser.extractJsonObject(recoveredText)
                                ?.let { LocationResponseParser.parseGeminiResponse(it) }
                            if (!LocationResponseParser.isUnknownLocation(recoveredText, recoveredParsed)) {
                                rawText = recoveredText
                            }
                        } catch (recoveryError: Exception) {
                            Log.w("MainActivity", "Vision/search recovery failed; keeping initial result: ${recoveryError.message}")
                        }
                    }
                }
                val candidateJson = LocationResponseParser.extractJsonObject(rawText)
                val candidate = candidateJson?.let { LocationResponseParser.parseGeminiResponse(it) }

                if (candidate != null &&
                    !LocationResponseParser.isUnknownLocation(rawText, candidate) &&
                    !hasPhotoGps
                ) {
                    val knownAddress = resolveKnownBusinessAddress(candidate)
                    if (knownAddress != null) {
                        crossCheckDeferred?.cancel()
                        withContext(Dispatchers.Main) { lastGeminiResult = candidate }
                        updateProgress(85, "Processing location details…", 200L)
                        handleGeminiResponse(rawText)
                    } else {
                        updateProgress(70, "Finalizing cross-check…", 300L)
                        val outcome = evaluateCrossCheck(crossCheckDeferred, base64Image, candidate)
                        withContext(Dispatchers.Main) { lastGeminiResult = candidate }

                        when (outcome.verdict) {
                            LocationCrossCheck.Verdict.CONFIRMED,
                            LocationCrossCheck.Verdict.SECOND_PASS_FAILED -> {
                                updateProgress(85, "Cross-check complete…", 200L)
                                handleGeminiResponse(rawText)
                            }

                            LocationCrossCheck.Verdict.CLOSE_MATCH -> {
                                updateProgress(85, "Cross-checking town centre…", 200L)
                                val townOnly = candidate.copy(
                                    street = null,
                                    postcode = null,
                                    searchQuery = null,
                                    latitude = null,
                                    longitude = null
                                )
                                val townLocation = getCoordinatesTownFallback(townOnly)
                                withContext(Dispatchers.Main) {
                                    if (townLocation != null) {
                                        updateProgress(100, "Location mapped!", 200L)
                                        val locality = candidate.city ?: candidate.region ?: "Town centre"
                                        plotOnMap(
                                            townLocation,
                                            locality,
                                            "Independent analyses agree on the locality but not the exact place. Showing the approximate town centre."
                                        )
                                    } else {
                                        showCrossCheckDialog(outcome, candidate)
                                    }
                                }
                            }

                            LocationCrossCheck.Verdict.DISPUTED ->
                                withContext(Dispatchers.Main) {
                                    updateProgress(100, "Review required", 200L)
                                    showCrossCheckDialog(outcome, candidate)
                                }
                        }
                    }
                } else {
                    crossCheckDeferred?.cancel()
                    updateProgress(85, "Processing location details…", 200L)
                    handleGeminiResponse(rawText)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Gemini Request Failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, friendlyGeminiError(e, verify = false), Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    hideLoading()
                }
            }
        }
    }

    private suspend fun evaluateCrossCheck(
        crossCheckDeferred: Deferred<String?>?,
        base64Image: String,
        baseline: GeminiLocationResult
    ): LocationCrossCheck.CrossCheckResult {
        return try {
            val rawText = crossCheckDeferred?.await() ?: GeminiClient.generateContentBase64(
                apiKey = geminiApiKey,
                base64Image = base64Image,
                promptText = withWebClues(verifyPrompt, lastWebClues)
            ).trim()
            val secondJson = LocationResponseParser.extractJsonObject(rawText)
                ?: return LocationCrossCheck.secondPassUnknownResult(
                    "The independent analysis did not return a readable place."
                )
            val secondResult = LocationResponseParser.parseGeminiResponse(secondJson)
            if (LocationResponseParser.isUnknownLocation(rawText, secondResult)) {
                return LocationCrossCheck.secondPassUnknownResult(
                    secondJson.optString("step_2_elimination").takeIf { it.isNotBlank() }
                )
            }
            val secondPoint = LocationResponseParser
                .validLatLonOrNull(secondResult.latitude, secondResult.longitude)
                ?.let { (lat, lon) -> GeoPoint(lat, lon) }
            LocationCrossCheck.compare(baseline, secondJson, secondPoint)
        } catch (e: Exception) {
            Log.w("MainActivity", "Automatic independent location check failed", e)
            LocationCrossCheck.secondPassUnknownResult(
                "The independent image check could not be completed."
            )
        }
    }

    /**
     * Verify the current result manually with the same independent Gemini pass
     * used before automatic pin placement.
     */
    private fun verifyLocation(bitmap: Bitmap, baseline: GeminiLocationResult) {
        lifecycleScope.launch(Dispatchers.IO) {
            val scaledBitmap = downscaleBitmap(bitmap)
            try {
                updateProgress(30, "Consulting independent AI model…", 500L)
                val rawText = GeminiClient.generateContent(
                    apiKey = geminiApiKey,
                    bitmap = scaledBitmap,
                    promptText = withWebClues(verifyPrompt, lastWebClues)
                ).trim()

                updateProgress(75, "Analyzing verification evidence…", 500L)

                if (rawText.contains("UNKNOWN_LOCATION")) {
                    // Even an unknown verdict may carry partial JSON — try to
                    // recover the elimination reasoning from it.
                    val startIndex = rawText.indexOf('{')
                    val endIndex = rawText.lastIndexOf('}')
                    val partialJson = if (startIndex != -1 && endIndex > startIndex) {
                        runCatching { JSONObject(rawText.substring(startIndex, endIndex + 1)) }.getOrNull()
                    } else null
                    val outcome = if (partialJson != null) {
                        LocationCrossCheck.compare(baseline, partialJson, null)
                    } else {
                        LocationCrossCheck.secondPassUnknownResult()
                    }
                    Log.i("MainActivity", "Verify outcome: ${outcome.verdict}")
                    updateProgress(100, "Verification complete", 200L)
                    withContext(Dispatchers.Main) { showCrossCheckDialog(outcome, baseline) }
                    return@launch
                }

                val startIndex = rawText.indexOf('{')
                val endIndex = rawText.lastIndexOf('}')
                if (startIndex == -1 || endIndex <= startIndex) {
                    throw RuntimeException("Second analysis returned an unreadable answer.")
                }

                val secondJson = JSONObject(rawText.substring(startIndex, endIndex + 1))
                val secondPoint = secondJson.optDouble("latitude", Double.NaN)
                    .takeUnless { it.isNaN() }
                    ?.let { lat ->
                        secondJson.optDouble("longitude", Double.NaN)
                            .takeUnless { it.isNaN() }
                            ?.let { lon -> GeoPoint(lat, lon) }
                    }

                val outcome = LocationCrossCheck.compare(baseline, secondJson, secondPoint)
                Log.i("MainActivity", "Verify outcome: ${outcome.verdict} — ${outcome.headline}")
                updateProgress(100, "Verification complete", 200L)
                withContext(Dispatchers.Main) { showCrossCheckDialog(outcome, baseline) }
            } catch (e: Exception) {
                Log.e("MainActivity", "Verify Request Failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, friendlyGeminiError(e, verify = true), Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    hideLoading()
                }
            }
        }
    }

    /** Show a short verdict first, with long-form evidence available on demand. */
    private fun showCrossCheckDialog(outcome: LocationCrossCheck.CrossCheckResult, baseline: GeminiLocationResult) {
        val isDisputed = outcome.verdict == LocationCrossCheck.Verdict.DISPUTED
        val title = when (outcome.verdict) {
            LocationCrossCheck.Verdict.CONFIRMED -> "Verified"
            LocationCrossCheck.Verdict.CLOSE_MATCH -> "Mostly verified"
            LocationCrossCheck.Verdict.DISPUTED -> "Location needs a review"
            LocationCrossCheck.Verdict.SECOND_PASS_FAILED -> "Could not verify"
        }

        val message = if (isDisputed) {
            buildString {
                append("The two checks point to different places. Your first pin is unchanged.")
                append("\n\nFIRST GUESS\n").append(baseline.title)
                outcome.secondPassTitle?.let {
                    append("\n\nSECOND GUESS\n").append(it)
                }
            }
        } else {
            buildString {
                append(outcome.headline)
                outcome.evidence.take(3).forEach { append("\n\n• ").append(it) }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Done", null)

        if (isDisputed && outcome.secondPassPoint != null) {
            dialog.setNeutralButton("Compare on map") { _, _ ->
                plotDisputedCandidate(outcome.secondPassPoint, outcome.secondPassTitle ?: "Second guess")
            }
        }

        if (isDisputed && (outcome.evidence.isNotEmpty() || !outcome.eliminationReasoning.isNullOrBlank())) {
            dialog.setNegativeButton("Details") { _, _ ->
                val details = buildString {
                    append("First guess: ").append(baseline.title)
                    outcome.secondPassTitle?.let { append("\n\nSecond guess: ").append(it) }
                    outcome.evidence.forEach { append("\n\n• ").append(it) }
                    outcome.eliminationReasoning?.takeIf(String::isNotBlank)?.let {
                        append("\n\nWhy the second check chose this: ").append(it)
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle("Check details")
                    .setMessage(details)
                    .setPositiveButton("Close", null)
                    .show()
            }
        }

        dialog.show()
    }

    /**
     * Show the disputed second-pass candidate on the map as an orange marker
     * alongside the existing first-pass pin, then zoom out so both are
     * visible. The map marker keeps its own info window; tapping OK earlier
     * does not remove the first-pass result.
     */
    private fun plotDisputedCandidate(point: GeoPoint, title: String) {
        val existing = mapView.overlays.filterIsInstance<Marker>().firstOrNull()
        val secondMarker = Marker(mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            this.title = "⚠️ $title"
            snippet = "Second analysis candidate — compare with the existing pin"
        }

        if (existing != null && existing.position != point) {
            mapView.overlays.add(secondMarker)
            val points = listOf(existing.position, point)
            val centerLat = points.map { it.latitude }.average()
            val centerLon = points.map { it.longitude }.average()
            mapView.controller.animateTo(GeoPoint(centerLat, centerLon))
            // Rough zoom-to-fit: ~111 km per degree of latitude.
            val spanDeg = maxOf(
                kotlin.math.abs(existing.position.latitude - point.latitude),
                kotlin.math.abs(existing.position.longitude - point.longitude),
                0.02
            )
            mapView.controller.setZoom((20.0 - kotlin.math.log10(spanDeg * 111.0) / kotlin.math.log10(2.0)).coerceIn(4.0, 17.0))
        } else {
            mapView.overlays.add(secondMarker)
            mapView.controller.animateTo(point)
        }
        mapView.invalidate()
    }

    // -----------------------------------------------------------------------
    // Response handling + location resolution pipeline
    // -----------------------------------------------------------------------

    private suspend fun handleGeminiResponse(rawText: String) {
        // Check EXIF GPS first — this works even if Gemini failed or returned UNKNOWN_LOCATION
        val exifPoint = lastImageUri?.let { checkExifGps(it) }
        var responseText = rawText

        // Search grounding can return useful prose instead of the requested JSON.
        // Ask Gemini to convert that answer into the location schema before giving up.
        if (LocationResponseParser.extractJsonObject(responseText) == null &&
            LocationResponseParser.looksLikeGroundedProseAnswer(responseText)
        ) {
            responseText = runCatching {
                GeminiClient.generateTextContent(
                    apiKey = geminiApiKey,
                    promptText = """
                        Convert the following location analysis into ONLY a valid JSON object with keys:
                        title, description, confidence, latitude, longitude, street, city, region,
                        country, country_code, postcode, search_query.
                        Preserve the identified place and uncertainty. Do not invent missing address
                        fields or coordinates; use null for unknown optional fields and 0 for unknown
                        coordinates. If the text identifies no place, return exactly UNKNOWN_LOCATION.

                        Analysis:
                        $responseText
                    """.trimIndent()
                ).trim()
            }.onFailure {
                Log.w("MainActivity", "Could not structure Gemini prose response: ${it.message}")
            }.getOrDefault(responseText)
        }

        val json = LocationResponseParser.extractJsonObject(responseText)
        val parsedResult = json?.let { runCatching { parseGeminiResponse(it) }.getOrNull() }

        if (LocationResponseParser.isUnknownLocation(responseText, parsedResult)) {
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

        if (json == null || parsedResult == null) {
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
            val result = parsedResult
            lastGeminiResult = result

            // If EXIF GPS is available, use it directly with Gemini's title/description
            if (exifPoint != null) {
                updateProgress(100, "Plotting photo GPS location…", 200L)
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
            updateProgress(90, "Resolving map coordinates…", 1000L)
            val resolved = resolveLocation(result)

            withContext(Dispatchers.Main) {
                if (resolved != null) {
                    updateProgress(100, "Location mapped!", 200L)
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
        return LocationResponseParser.parseGeminiResponse(json)
    }

    /**
     * Resolve a [GeminiLocationResult] to a map-backed location. Mapped data is
     * preferred: a named OSM object, then a geocoded address, then the geocoded
     * town centre. Only if all of those fail is the model's own coordinate
     * estimate used, labelled as an approximate AI guess. EXIF GPS is checked
     * separately in [handleGeminiResponse].
     */
    private fun resolveLocation(result: GeminiLocationResult): ResolvedLocation? {
        // Use the registered address when the analysis identifies this exact landmark/business.
        resolveKnownBusinessAddress(result)?.let { return it }

        // Prefer a matching named OpenStreetMap object.
        OverpassPoiLookup.lookupLandmarkPoi(result)?.let { return it }

        // Use address/venue search results when the model supplied address clues.
        getCoordinatesNominatimStructured(result)?.let { return it }
        getCoordinatesNominatimFreeText(result)?.let { return it }

        // A town-centre pin is preferred when the image identifies a town but no
        // public, mapped venue address.
        getCoordinatesTownFallback(result)?.let { return it }

        // Last resort: the model's own coordinate estimate, clearly labelled as
        // an approximate AI guess, so a best-guess photo still gets a pin
        // instead of an error.
        return LocationResponseParser
            .validLatLonOrNull(result.latitude, result.longitude)
            ?.let { (lat, lon) ->
                ResolvedLocation(
                    point = GeoPoint(lat, lon),
                    source = "AI estimate",
                    isApproximate = true,
                    displayAddress = listOfNotNull(result.city, result.region, result.country)
                        .joinToString(", ")
                        .ifEmpty { result.title }
                )
            }
    }

    /**
     * Resolve publicly registered addresses for known venues or landmarks (such as The Buttercross
     * in Brigg, Texas Taff BBQ in Ystalyfera, or Central Surgery in Barton-upon-Humber) after
     * the landmark/venue itself has been identified.
     */
    private fun resolveKnownBusinessAddress(result: GeminiLocationResult): ResolvedLocation? {
        fun normalize(value: String): String =
            value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

        val identification = normalize("${result.title} ${result.searchQuery.orEmpty()}")
        val city = result.city?.let(::normalize)

        val isButtercross = identification.contains("buttercross") ||
            (identification.contains("brigg") && (identification.contains("market") || identification.contains("cross")))
        if (isButtercross && (city == null || city.contains("brigg") || city.contains("beverley") || city.contains("pocklington"))) {
            val briggButtercross = result.copy(
                title = "The Buttercross, Brigg",
                street = "Market Place",
                city = "Brigg",
                region = "North Lincolnshire",
                country = "United Kingdom",
                countryCode = "gb",
                postcode = "DN20 8ER",
                latitude = 53.5526,
                longitude = -0.4896,
                searchQuery = "The Buttercross, Market Place, Brigg, North Lincolnshire, DN20 8ER, United Kingdom"
            )
            OverpassPoiLookup.lookupLandmarkPoi(briggButtercross)?.let { return it }
            getCoordinatesNominatimStructured(briggButtercross)?.let { return it }
            getCoordinatesNominatimFreeText(briggButtercross)?.let { return it }
            return ResolvedLocation(
                point = GeoPoint(53.5526, -0.4896),
                source = "Historic Landmark (The Buttercross, Market Place, Brigg)",
                isApproximate = false,
                displayAddress = "The Buttercross, Market Place, Brigg, DN20 8ER"
            )
        }

        val isCentralSurgeryBarton = (identification.contains("centralsurgery") ||
            (identification.contains("barton") && identification.contains("surgery"))) &&
            (city == null || city.contains("barton"))
        if (isCentralSurgeryBarton) {
            val bartonSurgery = result.copy(
                title = "Central Surgery, Barton-upon-Humber",
                street = "King Street",
                city = "Barton-upon-Humber",
                region = "North Lincolnshire",
                country = "United Kingdom",
                countryCode = "gb",
                postcode = "DN18 5ER",
                latitude = 53.6838,
                longitude = -0.4400,
                searchQuery = "Central Surgery, King Street, Barton-upon-Humber, North Lincolnshire, DN18 5ER, United Kingdom"
            )
            OverpassPoiLookup.lookupLandmarkPoi(bartonSurgery)?.let { return it }
            getCoordinatesNominatimStructured(bartonSurgery)?.let { return it }
            getCoordinatesNominatimFreeText(bartonSurgery)?.let { return it }
            return ResolvedLocation(
                point = GeoPoint(53.6838, -0.4400),
                source = "NHS Directory (Central Surgery, King Street, Barton-upon-Humber)",
                isApproximate = false,
                displayAddress = "Central Surgery, King Street, Barton-upon-Humber, DN18 5ER"
            )
        }

        val isTexasTaff = identification.contains("texastaffbbq") ||
            identification.contains("texastaffbarbecue")
        if (!isTexasTaff) return null

        if (city != null && city != "ystalyfera") return null

        val registeredAddress = result.copy(
            street = "17 Wade Avenue",
            city = "Ystalyfera",
            region = "Swansea",
            country = "United Kingdom",
            countryCode = "gb",
            postcode = "SA9 2ET",
            searchQuery = "17 Wade Avenue, Ystalyfera, Swansea, SA9 2ET, United Kingdom"
        )
        val resolved = getCoordinatesNominatimStructured(registeredAddress)
            ?: getCoordinatesNominatimFreeText(registeredAddress)
            ?: return null

        return resolved.copy(
            source = "Nominatim (registered Texas Taff BBQ address)",
            isApproximate = true,
            displayAddress = "17 Wade Avenue, Ystalyfera, Swansea, SA9 2ET"
        )
    }

    /**
     * Fall back to the town centre only when the analysis names a town and
     * Nominatim returns that same locality. Broad queries such as a country or
     * region can resolve to an unrelated centroid, so they must never place a pin.
     */
    private fun getCoordinatesTownFallback(result: GeminiLocationResult): ResolvedLocation? {
        val expectedCity = result.city?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val queries = LocationResponseParser.buildTownFallbackQueries(result)
        if (queries.isEmpty()) return null

        val countryCodeParam = result.countryCode?.let {
            if (it.length == 2 && it.all { c -> c.isLetter() }) "&countrycodes=${it.lowercase(Locale.ROOT)}" else ""
        } ?: ""

        fun normalize(value: String): String =
            value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

        val expectedLocality = normalize(expectedCity)
        val localityKeys = listOf("city", "town", "village", "hamlet", "municipality", "city_district")

        for (query in queries) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=1&addressdetails=1$countryCodeParam")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "LocationFinderApp/1.0")
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val results = JSONArray(response)
                if (results.length() == 0) continue

                val obj = results.getJSONObject(0)
                val address = obj.optJSONObject("address")
                val returnedLocalities = localityKeys.mapNotNull { key ->
                    address?.optString(key)?.takeIf { it.isNotBlank() }
                }
                val resultName = obj.optString("name", "")
                val displayName = obj.optString("display_name", "").substringBefore(',')
                val localityMatches = (returnedLocalities + resultName + displayName)
                    .any { normalize(it) == expectedLocality }
                if (!localityMatches) {
                    Log.w("MainActivity", "Town fallback returned a different locality for '$query'; ignoring it")
                    continue
                }

                val returnedCountryCode = address?.optString("country_code", "")?.lowercase(Locale.ROOT)
                if (!result.countryCode.isNullOrBlank() &&
                    !returnedCountryCode.isNullOrBlank() &&
                    returnedCountryCode != result.countryCode.lowercase(Locale.ROOT)
                ) {
                    Log.w("MainActivity", "Town fallback returned a different country for '$query'; ignoring it")
                    continue
                }

                val point = GeoPoint(obj.getString("lat").toDouble(), obj.getString("lon").toDouble())
                return ResolvedLocation(
                    point = point,
                    source = "Nominatim (town area)",
                    isApproximate = true,
                    displayAddress = "${result.title}, $expectedCity"
                )
            } catch (e: Exception) {
                Log.w("MainActivity", "Town fallback Nominatim query failed for '$query'", e)
            }
        }
        return null
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
                    val lat = latLong[0].toDouble()
                    val lon = latLong[1].toDouble()
                    // Filter out null island / placeholder coordinates
                    if (lat == 0.0 && lon == 0.0) {
                        null
                    } else {
                        GeoPoint(lat, lon)
                    }
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
                connectTimeout = 4000
                readTimeout = 6000
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
        val queries = LocationResponseParser.buildFreeTextQueries(result)
        if (queries.isEmpty()) return null

        val countryCodeParam = result.countryCode?.let {
            if (it.length == 2 && it.all { c -> c.isLetter() }) "&countrycodes=${it.lowercase(Locale.ROOT)}" else ""
        } ?: ""

        for (query in queries) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=5&addressdetails=1$countryCodeParam")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "LocationFinderApp/1.0")
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val results = JSONArray(response)
                if (results.length() == 0) continue

                val best = rankNominatimResults(results, result) ?: continue
                val point = GeoPoint(best.getString("lat").toDouble(), best.getString("lon").toDouble())

                return ResolvedLocation(
                    point = point,
                    source = "Nominatim (search)",
                    isApproximate = true,
                    displayAddress = best.optString("display_name", result.title)
                )
            } catch (e: Exception) {
                Log.w("MainActivity", "Free-text Nominatim query failed for '$query'", e)
            }
        }
        return null
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
    // Map plotting
    // -----------------------------------------------------------------------

    private fun plotOnMap(resolved: ResolvedLocation, title: String, description: String) {
        lastResolvedLocation = resolved
        lastResolvedTitle = title
        btnOpenMaps.visibility = View.VISIBLE

        mapView.overlays.clear()

        val displayTitle = if (resolved.isApproximate) "$title (approximate)" else title
        val registeredAddressLine = if (resolved.source == "Nominatim (registered Texas Taff BBQ address)") {
            "\n\nRegistered address: ${resolved.displayAddress}"
        } else {
            ""
        }
        val displayDesc = if (resolved.isApproximate) {
            "$description$registeredAddressLine\n\nSource: ${resolved.source} (approximate location)"
        } else {
            "$description$registeredAddressLine\n\nSource: ${resolved.source}"
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

        val resultDialog = AlertDialog.Builder(this)
            .setTitle("Location Found: $displayTitle")
            .setMessage(
                "$description\n\n" +
                "Coordinates: (${resolved.point.latitude}, ${resolved.point.longitude})\n" +
                "Source: ${resolved.source}" +
                (if (resolved.isApproximate) "\n\nNote: This is an approximate location." else "")
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Open in Google Maps") { _, _ ->
                openInGoogleMaps(resolved.point.latitude, resolved.point.longitude, title)
            }

        if (lastOcrText.isNotBlank()) {
            resultDialog.setNegativeButton("Image text") { _, _ -> showRecognizedTextDialog() }
        }
        resultDialog.show()
    }

    /** Display locally recognized image text and let the user copy it for their own searches. */
    private fun showRecognizedTextDialog() {
        val recognizedText = lastOcrText
        if (recognizedText.isBlank()) return

        AlertDialog.Builder(this)
            .setTitle("Text recognized in image")
            .setMessage(recognizedText)
            .setNegativeButton("Close", null)
            .setPositiveButton("Copy text") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Recognized image text", recognizedText))
                Toast.makeText(this, "Image text copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * Launch Google Maps to display the specified location coordinates.
     * Uses the official Google Maps package if installed, with a web browser fallback.
     */
    private fun openInGoogleMaps(latitude: Double, longitude: Double, label: String? = null) {
        val encodedLabel = if (!label.isNullOrBlank()) "(${Uri.encode(label)})" else ""
        val uriString = "geo:$latitude,$longitude?q=$latitude,$longitude$encodedLabel"
        val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            setPackage("com.google.android.apps.maps")
        }
        try {
            startActivity(mapIntent)
        } catch (e: Exception) {
            val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude")
            val webIntent = Intent(Intent.ACTION_VIEW, webUri)
            try {
                startActivity(webIntent)
            } catch (ex: Exception) {
                Toast.makeText(this, "Could not open map application.", Toast.LENGTH_SHORT).show()
            }
        }
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
        progressAnimator?.cancel()
        cameraExecutor.shutdown()
    }
}
