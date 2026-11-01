package com.example.location_finder.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.location_finder.LocationCrossCheck
import com.example.location_finder.R
import com.example.location_finder.ResolvedLocation
import com.example.location_finder.viewmodel.LocationSearchViewModel
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.concurrent.Executors

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

@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun LocationFinderApp(
    viewModel: LocationSearchViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onPhotoSelected(context, uri)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.openCamera()
        } else {
            Toast.makeText(context, "Camera permission is needed for live capture.", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.clearToastMessage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Main Map Viewport
            OSMMapViewport(
                resolvedLocation = uiState.resolvedLocation,
                resolvedTitle = uiState.resolvedTitle,
                resolvedDescription = uiState.resolvedDescription,
                disputedCandidate = uiState.disputedCandidate,
                onMapViewReady = { map -> mapViewInstance = map },
                onOpenMapsClick = { lat, lon, label ->
                    openInGoogleMaps(context, lat, lon, label)
                }
            )

            // Status Bar Protection Scrim
            StatusBarProtection()

            // Modern Top Header Overlay
            ModernTopHeader(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp)
            )

            // Floating Map Controls (Zoom in, Zoom out, Recenter)
            FloatingMapControls(
                mapView = mapViewInstance,
                resolvedLocation = uiState.resolvedLocation,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 80.dp, end = 16.dp)
            )

            // Bottom Column containing Resolved Location Card & Action Bottom Panel
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                // Resolved Location Card (shown when location is found)
                uiState.resolvedLocation?.let { resolved ->
                    ResolvedLocationCard(
                        resolvedLocation = resolved,
                        title = uiState.resolvedTitle,
                        description = uiState.resolvedDescription,
                        confidence = uiState.lastGeminiResult?.confidence,
                        onOpenMapsClick = {
                            openInGoogleMaps(
                                context,
                                resolved.point.latitude,
                                resolved.point.longitude,
                                uiState.resolvedTitle
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                // Modern Bottom Action Sheet
                ModernActionBottomPanel(
                    onSelectPhotoClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onCameraClick = {
                        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.openCamera()
                        } else {
                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                    },
                    onVerifyClick = {
                        viewModel.onVerifyClicked()
                    },
                    isVerifyEnabled = uiState.lastBitmap != null,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Live Camera Overlay
            AnimatedVisibility(
                visible = uiState.isCameraOverlayVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                CameraPreviewOverlay(
                    onClose = { viewModel.closeCamera() },
                    onPhotoCaptured = { bitmap ->
                        viewModel.onPhotoCaptured(context, bitmap)
                    }
                )
            }

            // Animated Progress Loading Overlay
            AnimatedVisibility(
                visible = uiState.loadingProgress.isVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ModernLoadingOverlayScreen(
                    heading = uiState.loadingProgress.heading,
                    stage = uiState.loadingProgress.stage,
                    percent = uiState.loadingProgress.percent
                )
            }

            // Cross Check Verdict Dialog
            if (uiState.crossCheckDialog.isVisible) {
                CrossCheckDialogScreen(
                    dialogState = uiState.crossCheckDialog,
                    onDismiss = { viewModel.dismissCrossCheckDialog() },
                    onCompareOnMap = { point, title ->
                        viewModel.compareDisputedCandidateOnMap(point, title)
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusBarProtection(
    color: Color = Color(0xFF05090E)
) {
    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(topPadding + 20.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        color.copy(alpha = 0.85f),
                        color.copy(alpha = 0.4f),
                        Color.Transparent
                    )
                )
            )
    )
}

@Composable
fun OSMMapViewport(
    resolvedLocation: ResolvedLocation?,
    resolvedTitle: String?,
    resolvedDescription: String?,
    disputedCandidate: com.example.location_finder.model.DisputedCandidateMarker?,
    onMapViewReady: (MapView) -> Unit,
    onOpenMapsClick: (Double, Double, String?) -> Unit
) {
    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(reliableStreetSource)
                setMultiTouchControls(true)
                controller.setZoom(5.0)
                controller.setCenter(GeoPoint(54.5, -2.0))
                onMapViewReady(this)
            }
        },
        update = { mapView ->
            mapView.overlays.clear()

            resolvedLocation?.let { resolved ->
                val displayTitle = if (resolved.isApproximate) "${resolvedTitle.orEmpty()} (approximate)" else resolvedTitle.orEmpty()
                val registeredAddressLine = if (resolved.source == "Nominatim (registered Texas Taff BBQ address)") {
                    "\n\nRegistered address: ${resolved.displayAddress}"
                } else ""
                val displayDesc = if (resolved.isApproximate) {
                    "${resolvedDescription.orEmpty()}$registeredAddressLine\n\nSource: ${resolved.source} (approximate location)"
                } else {
                    "${resolvedDescription.orEmpty()}$registeredAddressLine\n\nSource: ${resolved.source}"
                }

                val marker = Marker(mapView).apply {
                    position = resolved.point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    this.title = displayTitle
                    snippet = displayDesc
                    setOnMarkerClickListener { _, _ ->
                        onOpenMapsClick(resolved.point.latitude, resolved.point.longitude, resolvedTitle)
                        true
                    }
                }
                mapView.overlays.add(marker)
                mapView.controller.animateTo(resolved.point)
                mapView.controller.setZoom(if (resolved.isApproximate) 15.0 else 17.0)
            }

            disputedCandidate?.let { disputed ->
                val existing = mapView.overlays.filterIsInstance<Marker>().firstOrNull()
                val secondMarker = Marker(mapView).apply {
                    position = disputed.point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    this.title = disputed.title
                    snippet = disputed.snippet
                }

                if (existing != null && existing.position != disputed.point) {
                    mapView.overlays.add(secondMarker)
                    val points = listOf(existing.position, disputed.point)
                    val centerLat = points.map { it.latitude }.average()
                    val centerLon = points.map { it.longitude }.average()
                    mapView.controller.animateTo(GeoPoint(centerLat, centerLon))
                    val spanDeg = maxOf(
                        kotlin.math.abs(existing.position.latitude - disputed.point.latitude),
                        kotlin.math.abs(existing.position.longitude - disputed.point.longitude),
                        0.02
                    )
                    mapView.controller.setZoom((20.0 - kotlin.math.log10(spanDeg * 111.0) / kotlin.math.log10(2.0)).coerceIn(4.0, 17.0))
                } else {
                    mapView.overlays.add(secondMarker)
                    mapView.controller.animateTo(disputed.point)
                }
            }

            mapView.invalidate()
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun ModernTopHeader(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .border(
                width = 1.5.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        Color(0xFF00FF66),
                        Color(0xFF00E5FF)
                    )
                ),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF00FF66),
                                Color(0xFF00E5FF)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Explore,
                    contentDescription = null,
                    tint = Color(0xFF05090E),
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "> CYBER_LOCATOR",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    ),
                    color = Color(0xFF00FF66)
                )
                Text(
                    text = "SYSTEM // VISUAL RECON",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF00E5FF)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Surface(
                color = Color(0xFF003B19),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00FF66).copy(alpha = 0.5f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFF00FF66),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "[ ONLINE ]",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00FF66),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
fun FloatingMapControls(
    mapView: MapView?,
    resolvedLocation: ResolvedLocation?,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        SmallFloatingActionButton(
            onClick = { mapView?.controller?.zoomIn() },
            containerColor = Color(0xFF0A1017),
            contentColor = Color(0xFF00FF66),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.border(1.dp, Color(0xFF00FF66), RoundedCornerShape(10.dp))
        ) {
            Icon(Icons.Default.Add, contentDescription = "Zoom in")
        }
        SmallFloatingActionButton(
            onClick = { mapView?.controller?.zoomOut() },
            containerColor = Color(0xFF0A1017),
            contentColor = Color(0xFF00FF66),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.border(1.dp, Color(0xFF00FF66), RoundedCornerShape(10.dp))
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Zoom out")
        }
        resolvedLocation?.let { resolved ->
            SmallFloatingActionButton(
                onClick = { mapView?.controller?.animateTo(resolved.point) },
                containerColor = Color(0xFF00FF66),
                contentColor = Color(0xFF00220B),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = "Center on location")
            }
        }
    }
}

@Composable
fun ResolvedLocationCard(
    resolvedLocation: ResolvedLocation,
    title: String?,
    description: String?,
    confidence: String?,
    onOpenMapsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1017)),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.border(
            width = 1.5.dp,
            color = Color(0xFF00FF66).copy(alpha = 0.8f),
            shape = RoundedCornerShape(16.dp)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    color = Color(0xFF003B19),
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF00FF66),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "[ TARGET ACQUIRED ]",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF)
                        )
                        confidence?.let { conf ->
                            val (bgColor, txtColor, label) = when (conf.lowercase()) {
                                "high" -> Triple(Color(0xFF003B19), Color(0xFF00FF66), "[ CONF: HIGH ]")
                                "medium" -> Triple(Color(0xFF4A3B00), Color(0xFFFFD700), "[ CONF: MED ]")
                                else -> Triple(Color(0xFF4A0000), Color(0xFFFF3333), "[ CONF: LOW ]")
                            }
                            Surface(
                                color = bgColor,
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, txtColor.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = txtColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = title ?: "Location Found",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFD0F8E0)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "[ NET_NODE: ${resolvedLocation.source} ]",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF00FF66)
                    )
                }
            }

            description?.takeIf { it.isNotBlank() }?.let { desc ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFD0F8E0).copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenMapsClick,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00FF66),
                    contentColor = Color(0xFF00220B)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.open_in_google_maps),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


@Composable
fun ModernActionBottomPanel(
    onSelectPhotoClick: () -> Unit,
    onCameraClick: () -> Unit,
    onVerifyClick: () -> Unit,
    isVerifyEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF0A1017),
        tonalElevation = 8.dp,
        shadowElevation = 16.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier.border(
            width = 1.5.dp,
            brush = Brush.horizontalGradient(
                listOf(
                    Color(0xFF00FF66),
                    Color(0xFF00E5FF)
                )
            ),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 16.dp)
        ) {
            // Drag handle indicator
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF00FF66))
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_headline),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color(0xFF00FF66)
                    )
                    Text(
                        text = stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD0F8E0).copy(alpha = 0.7f)
                    )
                }

                Surface(
                    color = Color(0xFF003B19),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00FF66).copy(alpha = 0.5f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "[ GEMINI_2.5 ]",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ElevatedButton(
                    onClick = onSelectPhotoClick,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    colors = ButtonDefaults.elevatedButtonColors(
                        containerColor = Color(0xFF00FF66),
                        contentColor = Color(0xFF00220B)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.choose_photo),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                ElevatedButton(
                    onClick = onCameraClick,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    colors = ButtonDefaults.elevatedButtonColors(
                        containerColor = Color(0xFF003344),
                        contentColor = Color(0xFF00E5FF)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.camera),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onVerifyClick,
                enabled = isVerifyEnabled,
                shape = RoundedCornerShape(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (isVerifyEnabled) Color(0xFF00FF66) else Color(0xFF1E3A2B)
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isVerifyEnabled) Color(0xFF00FF66) else Color(0xFF648A73)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(
                    Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = if (isVerifyEnabled) Color(0xFF00FF66) else Color(0xFF648A73),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.verify_previous_result),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
fun CameraPreviewOverlay(
    onClose: () -> Unit,
    onPhotoCaptured: (android.graphics.Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var imageCaptureState by remember { mutableStateOf<ImageCapture?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(surfaceProvider)
                        }
                        val capture = ImageCapture.Builder().build()
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture
                            )
                            imageCaptureState = capture
                        } catch (_: Exception) {
                            onClose()
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .size(48.dp)
                .background(Color(0xFF0A1017).copy(alpha = 0.85f), shape = CircleShape)
                .border(1.dp, Color(0xFF00FF66), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.close_camera),
                tint = Color(0xFF00FF66)
            )
        }

        FloatingActionButton(
            onClick = {
                val capture = imageCaptureState ?: return@FloatingActionButton
                capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bitmap = imageProxyToBitmap(image)
                        image.close()
                        (context as? android.app.Activity)?.runOnUiThread {
                            onPhotoCaptured(bitmap)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        (context as? android.app.Activity)?.runOnUiThread {
                            Toast.makeText(context, "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                })
            },
            containerColor = Color(0xFF00FF66),
            contentColor = Color(0xFF00220B),
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .size(72.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = stringResource(R.string.take_photo),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

private fun imageProxyToBitmap(image: ImageProxy): android.graphics.Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    var bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    val rotation = image.imageInfo.rotationDegrees
    if (rotation != 0) {
        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        bitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    return bitmap
}

@Composable
fun ModernLoadingOverlayScreen(
    heading: String,
    stage: String,
    percent: Int
) {
    val animatedProgress by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = tween(durationMillis = 400),
        label = "loadingProgress"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF205090E))
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1017)),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
            modifier = Modifier.border(
                width = 1.5.dp,
                color = Color(0xFF00FF66),
                shape = RoundedCornerShape(16.dp)
            )
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 36.dp, vertical = 28.dp)
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF00FF66),
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "> ${heading.uppercase()}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF00FF66)
                )
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    color = Color(0xFF00FF66),
                    trackColor = Color(0xFF003B19),
                    modifier = Modifier
                        .width(240.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "[ MATRIX_DECRYPT: $percent% ]",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF39FF14)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "> $stage",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF00E5FF)
                )
            }
        }
    }
}


@Composable
fun CrossCheckDialogScreen(
    dialogState: com.example.location_finder.model.CrossCheckDialogState,
    onDismiss: () -> Unit,
    onCompareOnMap: (GeoPoint, String) -> Unit
) {
    val outcome = dialogState.outcome ?: return
    val baseline = dialogState.baseline
    var showDetailsDialog by remember { mutableStateOf(false) }

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
            baseline?.title?.let { append("\n\nFIRST GUESS\n").append(it) }
            outcome.secondPassTitle?.let { append("\n\nSECOND GUESS\n").append(it) }
        }
    } else {
        buildString {
            append(outcome.headline)
            outcome.evidence.take(3).forEach { append("\n\n• ").append(it) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A1017),
        titleContentColor = Color(0xFF00FF66),
        textContentColor = Color(0xFFD0F8E0),
        modifier = Modifier.border(1.5.dp, Color(0xFF00FF66), RoundedCornerShape(28.dp)),
        title = { Text(text = "> ${title.uppercase()}", fontWeight = FontWeight.Bold) },
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("[ DONE ]", color = Color(0xFF00FF66), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                if (isDisputed && outcome.secondPassPoint != null) {
                    TextButton(
                        onClick = {
                            onCompareOnMap(outcome.secondPassPoint, outcome.secondPassTitle ?: "Second guess")
                        }
                    ) {
                        Text("[ COMPARE ]", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                    }
                }
                if (isDisputed && (outcome.evidence.isNotEmpty() || !outcome.eliminationReasoning.isNullOrBlank())) {
                    TextButton(onClick = { showDetailsDialog = true }) {
                        Text("[ DETAILS ]", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    )

    if (showDetailsDialog) {
        val details = buildString {
            baseline?.title?.let { append("First guess: ").append(it) }
            outcome.secondPassTitle?.let { append("\n\nSecond guess: ").append(it) }
            outcome.evidence.forEach { append("\n\n• ").append(it) }
            outcome.eliminationReasoning?.takeIf(String::isNotBlank)?.let {
                append("\n\nWhy the second check chose this: ").append(it)
            }
        }
        AlertDialog(
            onDismissRequest = { showDetailsDialog = false },
            containerColor = Color(0xFF0A1017),
            titleContentColor = Color(0xFF00FF66),
            textContentColor = Color(0xFFD0F8E0),
            modifier = Modifier.border(1.5.dp, Color(0xFF00FF66), RoundedCornerShape(28.dp)),
            title = { Text(text = "> CHECK DETAILS", fontWeight = FontWeight.Bold) },
            text = { Text(text = details, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { showDetailsDialog = false }) {
                    Text("[ CLOSE ]", color = Color(0xFF00FF66), fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

private fun openInGoogleMaps(context: Context, latitude: Double, longitude: Double, label: String?) {
    val encodedLabel = if (!label.isNullOrBlank()) "(${Uri.encode(label)})" else ""
    val uriString = "geo:$latitude,$longitude?q=$latitude,$longitude$encodedLabel"
    val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
        setPackage("com.google.android.apps.maps")
    }
    try {
        context.startActivity(mapIntent)
    } catch (_: Exception) {
        val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude")
        val webIntent = Intent(Intent.ACTION_VIEW, webUri)
        try {
            context.startActivity(webIntent)
        } catch (_: Exception) {
            Toast.makeText(context, "Could not open map application.", Toast.LENGTH_SHORT).show()
        }
    }
}
