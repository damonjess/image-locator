package com.example.location_finder.model

import android.graphics.Bitmap
import com.example.location_finder.GeminiLocationResult
import com.example.location_finder.LocationCrossCheck
import com.example.location_finder.ResolvedLocation
import org.osmdroid.util.GeoPoint

/** State for the loading progress overlay. */
data class LoadingProgressState(
    val isVisible: Boolean = false,
    val heading: String = "",
    val stage: String = "",
    val percent: Int = 0
)

/** State for the verification outcome dialog. */
data class CrossCheckDialogState(
    val isVisible: Boolean = false,
    val outcome: LocationCrossCheck.CrossCheckResult? = null,
    val baseline: GeminiLocationResult? = null
)

/** Additional map marker for disputed locations. */
data class DisputedCandidateMarker(
    val point: GeoPoint,
    val title: String,
    val snippet: String
)

/** Complete UI State for the Location Finder screen. */
data class LocationUiState(
    val lastBitmap: Bitmap? = null,
    val lastGeminiResult: GeminiLocationResult? = null,
    val resolvedLocation: ResolvedLocation? = null,
    val resolvedTitle: String? = null,
    val resolvedDescription: String? = null,
    val disputedCandidate: DisputedCandidateMarker? = null,
    val loadingProgress: LoadingProgressState = LoadingProgressState(),
    val crossCheckDialog: CrossCheckDialogState = CrossCheckDialogState(),
    val isCameraOverlayVisible: Boolean = false,
    val toastMessage: String? = null
)
