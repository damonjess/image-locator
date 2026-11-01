package com.example.location_finder.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.location_finder.BuildConfig
import com.example.location_finder.GeminiLocationResult
import com.example.location_finder.LocationCrossCheck
import com.example.location_finder.ResolvedLocation
import com.example.location_finder.model.CrossCheckDialogState
import com.example.location_finder.model.DisputedCandidateMarker
import com.example.location_finder.model.LoadingProgressState
import com.example.location_finder.model.LocationUiState
import com.example.location_finder.repository.LocationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint

class LocationSearchViewModel(
    private val repository: LocationRepository = LocationRepository(
        geminiApiKey = BuildConfig.GEMINI_API_KEY.ifEmpty { "" },
        cloudVisionApiKey = BuildConfig.CLOUD_VISION_API_KEY
    )
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    private var lastOcrText: String = ""
    private var lastWebClues: String = ""
    private var lastImageUri: Uri? = null

    init {
        checkGeminiModels()
    }

    private fun checkGeminiModels() {
        viewModelScope.launch {
            val missing = repository.checkMissingGeminiModels()
            if (missing.isNotEmpty()) {
                Log.e("LocationSearchViewModel", "Missing Gemini models: $missing")
                showToast("Location AI may be broken: model(s) ${missing.joinToString()} are no longer available.")
            }
        }
    }

    fun openCamera() {
        _uiState.update { it.copy(isCameraOverlayVisible = true) }
    }

    fun closeCamera() {
        _uiState.update { it.copy(isCameraOverlayVisible = false) }
    }

    fun clearToastMessage() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun showToast(msg: String) {
        _uiState.update { it.copy(toastMessage = msg) }
    }

    private fun showLoading(heading: String, stage: String) {
        _uiState.update {
            it.copy(
                loadingProgress = LoadingProgressState(
                    isVisible = true,
                    heading = heading,
                    stage = stage,
                    percent = 0
                )
            )
        }
    }

    private fun hideLoading() {
        _uiState.update {
            it.copy(loadingProgress = it.loadingProgress.copy(isVisible = false))
        }
    }

    private fun updateProgress(targetPercent: Int, stageText: String) {
        _uiState.update {
            it.copy(
                loadingProgress = it.loadingProgress.copy(
                    percent = targetPercent.coerceIn(0, 100),
                    stage = stageText
                )
            )
        }
    }

    fun onPhotoSelected(context: Context, uri: Uri) {
        lastImageUri = uri
        showLoading("Finding Location", "Preparing image…")

        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
            } catch (e: Exception) {
                Log.e("LocationSearchViewModel", "Failed to decode image URI", e)
                withContext(Dispatchers.Main) {
                    hideLoading()
                    showToast("Could not load selected photo.")
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    lastBitmap = bitmap,
                    lastGeminiResult = null,
                    resolvedLocation = null,
                    resolvedTitle = null,
                    resolvedDescription = null,
                    disputedCandidate = null
                )
            }
            analyzeImage(context, bitmap, uri)
        }
    }

    fun onPhotoCaptured(context: Context, bitmap: Bitmap) {
        closeCamera()
        lastImageUri = null
        showLoading("Finding Location", "Preparing captured photo…")

        _uiState.update {
            it.copy(
                lastBitmap = bitmap,
                lastGeminiResult = null,
                resolvedLocation = null,
                resolvedTitle = null,
                resolvedDescription = null,
                disputedCandidate = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            analyzeImage(context, bitmap, null)
        }
    }

    private suspend fun analyzeImage(context: Context, bitmap: Bitmap, uri: Uri?) {
        updateProgress(10, "Extracting text & visual clues…")
        val (ocr, webClues) = repository.extractOcrAndWebClues(bitmap)
        lastOcrText = ocr
        lastWebClues = webClues

        val exifPoint = uri?.let { repository.checkExifGps(context, it) }
        val hasPhotoGps = exifPoint != null

        try {
            val outcome = repository.runGeminiAnalysis(
                bitmap = bitmap,
                recognizedText = ocr,
                webCluesText = webClues,
                hasPhotoGps = hasPhotoGps,
                onProgressUpdate = { pct, stage -> updateProgress(pct, stage) }
            )

            val (json, parsedResult) = repository.processGeminiResponse(outcome.rawText)

            if (json == null || parsedResult == null) {
                if (exifPoint != null) {
                    plotOnMap(
                        ResolvedLocation(exifPoint, "EXIF GPS", false, "Photo GPS location"),
                        "Photo Location",
                        "Location from photo GPS metadata"
                    )
                } else {
                    showToast("Could not identify location.")
                }
                return
            }

            val candidate = parsedResult
            _uiState.update { it.copy(lastGeminiResult = candidate) }

            if (exifPoint != null) {
                updateProgress(100, "Plotting photo GPS location…")
                plotOnMap(
                    ResolvedLocation(exifPoint, "EXIF GPS", false, candidate.title),
                    candidate.title,
                    candidate.description
                )
                return
            }

            val knownAddress = repository.resolveKnownBusinessAddress(candidate)
            if (knownAddress != null) {
                outcome.crossCheckDeferred?.cancel()
                updateProgress(85, "Processing location details…")
                plotOnMap(knownAddress, candidate.title, candidate.description)
                return
            }

            updateProgress(70, "Finalizing cross-check…")
            val crossCheckOutcome = repository.evaluateCrossCheck(
                outcome.crossCheckDeferred,
                outcome.base64Image,
                candidate,
                webClues
            )

            when (crossCheckOutcome.verdict) {
                LocationCrossCheck.Verdict.CONFIRMED,
                LocationCrossCheck.Verdict.SECOND_PASS_FAILED -> {
                    updateProgress(85, "Resolving map coordinates…")
                    val resolved = repository.resolveLocation(candidate)
                    if (resolved != null) {
                        updateProgress(100, "Location mapped!")
                        plotOnMap(resolved, candidate.title, candidate.description)
                    } else {
                        val query = candidate.searchQuery ?: candidate.title
                        showToast("Identified '$query', but could not map coordinates.")
                    }
                }

                LocationCrossCheck.Verdict.CLOSE_MATCH -> {
                    updateProgress(85, "Cross-checking town centre…")
                    val townOnly = candidate.copy(
                        street = null,
                        postcode = null,
                        searchQuery = null,
                        latitude = null,
                        longitude = null
                    )
                    val townLocation = repository.getCoordinatesTownFallback(townOnly)
                    if (townLocation != null) {
                        updateProgress(100, "Location mapped!")
                        val locality = candidate.city ?: candidate.region ?: "Town centre"
                        plotOnMap(
                            townLocation,
                            locality,
                            "Independent analyses agree on the locality but not the exact place. Showing the approximate town centre."
                        )
                    } else {
                        showCrossCheckDialog(crossCheckOutcome, candidate)
                    }
                }

                LocationCrossCheck.Verdict.DISPUTED -> {
                    updateProgress(100, "Review required")
                    showCrossCheckDialog(crossCheckOutcome, candidate)
                }
            }
        } catch (e: Exception) {
            Log.e("LocationSearchViewModel", "Analysis failed", e)
            if (exifPoint != null) {
                plotOnMap(
                    ResolvedLocation(exifPoint, "EXIF GPS", false, "Photo GPS location"),
                    "Photo Location",
                    "Location from photo GPS metadata"
                )
            } else {
                showToast(repository.friendlyGeminiError(e, verify = false))
            }
        } finally {
            hideLoading()
        }
    }

    fun onVerifyClicked() {
        val bitmap = uiState.value.lastBitmap
        val baseline = uiState.value.lastGeminiResult

        if (bitmap == null) {
            showToast("Identify a photo first, then Verify.")
            return
        }
        if (baseline == null) {
            showToast("No result to verify yet — identify a photo first.")
            return
        }

        showLoading("Verifying Result", "Starting independent verification…")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outcome = repository.verifyLocation(
                    bitmap = bitmap,
                    baseline = baseline,
                    webCluesText = lastWebClues,
                    onProgressUpdate = { pct, stage -> updateProgress(pct, stage) }
                )
                showCrossCheckDialog(outcome, baseline)
            } catch (e: Exception) {
                Log.e("LocationSearchViewModel", "Verify failed", e)
                showToast(repository.friendlyGeminiError(e, verify = true))
            } finally {
                hideLoading()
            }
        }
    }

    private fun showCrossCheckDialog(
        outcome: LocationCrossCheck.CrossCheckResult,
        baseline: GeminiLocationResult
    ) {
        _uiState.update {
            it.copy(
                crossCheckDialog = CrossCheckDialogState(
                    isVisible = true,
                    outcome = outcome,
                    baseline = baseline
                )
            )
        }
    }

    fun dismissCrossCheckDialog() {
        _uiState.update {
            it.copy(crossCheckDialog = CrossCheckDialogState(isVisible = false))
        }
    }

    fun compareDisputedCandidateOnMap(point: GeoPoint, title: String) {
        _uiState.update {
            it.copy(
                disputedCandidate = DisputedCandidateMarker(
                    point = point,
                    title = "⚠️ $title",
                    snippet = "Second analysis candidate — compare with the existing pin"
                )
            )
        }
        dismissCrossCheckDialog()
    }

    private fun plotOnMap(resolved: ResolvedLocation, title: String, description: String) {
        _uiState.update {
            it.copy(
                resolvedLocation = resolved,
                resolvedTitle = title,
                resolvedDescription = description
            )
        }
    }

    class Factory(
        private val repository: LocationRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LocationSearchViewModel(repository) as T
        }
    }
}
