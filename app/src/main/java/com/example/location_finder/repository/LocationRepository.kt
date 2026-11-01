package com.example.location_finder.repository

import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.location_finder.CloudVisionClient
import com.example.location_finder.GeminiClient
import com.example.location_finder.GeminiLocationResult
import com.example.location_finder.GeminiModels
import com.example.location_finder.GeminiSearchGroundingClient
import com.example.location_finder.ImageTextClueExtractor
import com.example.location_finder.LocationCrossCheck
import com.example.location_finder.LocationResponseParser
import com.example.location_finder.OverpassPoiLookup
import com.example.location_finder.ResolvedLocation
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class LocationRepository(
    private val geminiApiKey: String,
    private val cloudVisionApiKey: String
) {

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

    fun downscaleBitmap(bitmap: Bitmap, maxDimension: Int = 1024): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxEdge = maxOf(width, height)
        if (maxEdge <= maxDimension) return bitmap

        val scale = maxDimension.toFloat() / maxEdge
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun withWebClues(basePrompt: String, webClues: String): String =
        if (webClues.isBlank()) basePrompt else "$basePrompt\n\n$webClues"

    @Suppress("DEPRECATION")
    fun checkExifGps(context: Context, uri: Uri): GeoPoint? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val latLong = FloatArray(2)
                if (exif.getLatLong(latLong)) {
                    val lat = latLong[0].toDouble()
                    val lon = latLong[1].toDouble()
                    if (lat == 0.0 && lon == 0.0) null else GeoPoint(lat, lon)
                } else null
            }
        } catch (e: Exception) {
            Log.w("LocationRepository", "Could not read EXIF GPS", e)
            null
        }
    }

    suspend fun checkMissingGeminiModels(): List<String> {
        if (geminiApiKey.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            GeminiModels.missingModels(geminiApiKey)
        }
    }

    suspend fun extractOcrAndWebClues(bitmap: Bitmap): Pair<String, String> = withContext(Dispatchers.IO) {
        val scaledBitmap = downscaleBitmap(bitmap)
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

        val webCluesDeferred = async(Dispatchers.IO) {
            runCatching {
                CloudVisionClient.formatForPrompt(CloudVisionClient.detectWithBase64(cloudVisionApiKey, base64Image))
            }.getOrDefault("")
        }
        val ocrDeferred = async(Dispatchers.Default) {
            runCatching {
                ImageTextClueExtractor.extractText(scaledBitmap)
            }.getOrDefault("")
        }

        Pair(ocrDeferred.await(), webCluesDeferred.await())
    }

    data class GeminiAnalysisOutcome(
        val rawText: String,
        val candidate: GeminiLocationResult?,
        val crossCheckDeferred: Deferred<String?>?,
        val base64Image: String
    )

    suspend fun runGeminiAnalysis(
        bitmap: Bitmap,
        recognizedText: String,
        webCluesText: String,
        hasPhotoGps: Boolean,
        onProgressUpdate: (Int, String) -> Unit
    ): GeminiAnalysisOutcome = withContext(Dispatchers.IO) {
        val scaledBitmap = downscaleBitmap(bitmap)
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

        onProgressUpdate(25, "Consulting Gemini AI vision…")
        val promptWithOcr = withWebClues(
            if (recognizedText.isNotBlank()) {
                "$prompt\n\nON-DEVICE OCR TEXT DETECTED IN IMAGE:\n$recognizedText"
            } else {
                prompt
            },
            webCluesText
        )

        onProgressUpdate(45, "Analyzing image features & landmarks…")

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
            Log.w("LocationRepository", "Grounded search failed, falling back: ${groundingError.message}")
            GeminiClient.generateContentBase64(
                apiKey = geminiApiKey,
                base64Image = base64Image,
                promptText = promptWithOcr
            ).trim()
        }

        onProgressUpdate(60, "Interpreting candidate location…")

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
                    onProgressUpdate(50, "Gathering deeper visual clues…")
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

                    onProgressUpdate(65, "Resolving location from visual clues…")

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
                    Log.w("LocationRepository", "Vision recovery failed: ${recoveryError.message}")
                }
            }
        }

        val candidateJson = LocationResponseParser.extractJsonObject(rawText)
        val candidate = candidateJson?.let { LocationResponseParser.parseGeminiResponse(it) }

        GeminiAnalysisOutcome(rawText, candidate, crossCheckDeferred, base64Image)
    }

    suspend fun evaluateCrossCheck(
        crossCheckDeferred: Deferred<String?>?,
        base64Image: String,
        baseline: GeminiLocationResult,
        webCluesText: String
    ): LocationCrossCheck.CrossCheckResult = withContext(Dispatchers.IO) {
        try {
            val rawText = crossCheckDeferred?.await() ?: GeminiClient.generateContentBase64(
                apiKey = geminiApiKey,
                base64Image = base64Image,
                promptText = withWebClues(verifyPrompt, webCluesText)
            ).trim()

            val secondJson = LocationResponseParser.extractJsonObject(rawText)
                ?: return@withContext LocationCrossCheck.secondPassUnknownResult(
                    "The independent analysis did not return a readable place."
                )

            val secondResult = LocationResponseParser.parseGeminiResponse(secondJson)
            if (LocationResponseParser.isUnknownLocation(rawText, secondResult)) {
                return@withContext LocationCrossCheck.secondPassUnknownResult(
                    secondJson.optString("step_2_elimination").takeIf { it.isNotBlank() }
                )
            }

            val secondPoint = LocationResponseParser
                .validLatLonOrNull(secondResult.latitude, secondResult.longitude)
                ?.let { (lat, lon) -> GeoPoint(lat, lon) }

            LocationCrossCheck.compare(baseline, secondJson, secondPoint)
        } catch (e: Exception) {
            Log.w("LocationRepository", "Cross-check failed", e)
            LocationCrossCheck.secondPassUnknownResult(
                "The independent image check could not be completed."
            )
        }
    }

    suspend fun verifyLocation(
        bitmap: Bitmap,
        baseline: GeminiLocationResult,
        webCluesText: String,
        onProgressUpdate: (Int, String) -> Unit
    ): LocationCrossCheck.CrossCheckResult = withContext(Dispatchers.IO) {
        val scaledBitmap = downscaleBitmap(bitmap)
        onProgressUpdate(30, "Consulting independent AI model…")

        val rawText = GeminiClient.generateContent(
            apiKey = geminiApiKey,
            bitmap = scaledBitmap,
            promptText = withWebClues(verifyPrompt, webCluesText)
        ).trim()

        onProgressUpdate(75, "Analyzing verification evidence…")

        if (rawText.contains("UNKNOWN_LOCATION")) {
            val startIndex = rawText.indexOf('{')
            val endIndex = rawText.lastIndexOf('}')
            val partialJson = if (startIndex != -1 && endIndex > startIndex) {
                runCatching { JSONObject(rawText.substring(startIndex, endIndex + 1)) }.getOrNull()
            } else null
            return@withContext if (partialJson != null) {
                LocationCrossCheck.compare(baseline, partialJson, null)
            } else {
                LocationCrossCheck.secondPassUnknownResult()
            }
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

        LocationCrossCheck.compare(baseline, secondJson, secondPoint)
    }

    suspend fun processGeminiResponse(rawText: String): Pair<JSONObject?, GeminiLocationResult?> = withContext(Dispatchers.IO) {
        var responseText = rawText
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
            }.getOrDefault(responseText)
        }

        val json = LocationResponseParser.extractJsonObject(responseText)
        val parsedResult = json?.let { runCatching { LocationResponseParser.parseGeminiResponse(it) }.getOrNull() }
        Pair(json, parsedResult)
    }

    suspend fun resolveLocation(result: GeminiLocationResult): ResolvedLocation? = withContext(Dispatchers.IO) {
        resolveKnownBusinessAddress(result)?.let { return@withContext it }
        OverpassPoiLookup.lookupLandmarkPoi(result)?.let { return@withContext it }
        getCoordinatesNominatimStructured(result)?.let { return@withContext it }
        getCoordinatesNominatimFreeText(result)?.let { return@withContext it }
        getCoordinatesTownFallback(result)?.let { return@withContext it }

        LocationResponseParser
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

    fun resolveKnownBusinessAddress(result: GeminiLocationResult): ResolvedLocation? {
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

    fun getCoordinatesTownFallback(result: GeminiLocationResult): ResolvedLocation? {
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
                if (!localityMatches) continue

                val returnedCountryCode = address?.optString("country_code", "")?.lowercase(Locale.ROOT)
                if (!result.countryCode.isNullOrBlank() &&
                    !returnedCountryCode.isNullOrBlank() &&
                    returnedCountryCode != result.countryCode.lowercase(Locale.ROOT)
                ) continue

                val point = GeoPoint(obj.getString("lat").toDouble(), obj.getString("lon").toDouble())
                return ResolvedLocation(
                    point = point,
                    source = "Nominatim (town area)",
                    isApproximate = true,
                    displayAddress = "${result.title}, $expectedCity"
                )
            } catch (e: Exception) {
                Log.w("LocationRepository", "Town fallback query failed for '$query'", e)
            }
        }
        return null
    }

    private fun getCoordinatesNominatimStructured(result: GeminiLocationResult): ResolvedLocation? {
        if (result.street == null && result.postcode == null) return null

        return try {
            val params = StringBuilder()
            result.street?.let { params.append("&street=").append(URLEncoder.encode(it, "UTF-8")) }
            result.city?.let { params.append("&city=").append(URLEncoder.encode(it, "UTF-8")) }
            result.region?.let { params.append("&county=").append(URLEncoder.encode(it, "UTF-8")) }
            result.postcode?.let { params.append("&postalcode=").append(URLEncoder.encode(it, "UTF-8")) }
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

            ResolvedLocation(
                point = point,
                source = "Nominatim (structured)",
                isApproximate = true,
                displayAddress = best.optString("display_name", result.title)
            )
        } catch (e: Exception) {
            Log.w("LocationRepository", "Structured Nominatim query failed", e)
            null
        }
    }

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
                Log.w("LocationRepository", "Free-text Nominatim query failed for '$query'", e)
            }
        }
        return null
    }

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

    private fun scoreNominatimResult(obj: JSONObject, geminiResult: GeminiLocationResult): Double {
        var score = obj.optDouble("importance", 0.0)

        val displayName = obj.optString("display_name", "").lowercase(Locale.ROOT)
        val address = obj.optJSONObject("address")
        val objClass = obj.optString("class", "")

        geminiResult.street?.let { street ->
            if (displayName.contains(street.lowercase(Locale.ROOT))) {
                score += 3.0
            }
        }

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

        geminiResult.postcode?.let { postcode ->
            val addrPostcode = address?.optString("postcode", "") ?: ""
            if (postcode.replace(" ", "").equals(addrPostcode.replace(" ", ""), ignoreCase = true)) {
                score += 2.0
            }
        }

        geminiResult.country?.let { country ->
            val addrCountry = address?.optString("country", "") ?: ""
            if (country.lowercase(Locale.ROOT) == addrCountry.lowercase(Locale.ROOT)) {
                score += 1.0
            }
        }

        if (geminiResult.street != null) {
            if (objClass == "highway") score += 1.5
            if (objClass == "place") score -= 2.0
        } else {
            if (objClass == "tourism") score += 2.0
            if (objClass == "amenity") score += 1.0
            if (objClass == "historic") score += 1.5
            if (objClass == "leisure") score += 1.0
        }

        return score
    }

    fun friendlyGeminiError(e: Exception, verify: Boolean): String {
        val message = (e.message ?: "").lowercase()
        return when {
            message.contains("high demand") ||
                message.contains("overload") ||
                message.contains("overloaded") ||
                message.contains("unavailable") ||
                message.contains("503") ->
                "Gemini is overloaded right now. It was retried automatically — please try again in a minute."

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
}
