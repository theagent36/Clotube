package com.clotube.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.clotube.app.BuildConfig
import com.clotube.app.data.ByocConfigManager
import com.clotube.app.data.MetadataDiskCache
import com.clotube.app.data.PlaybackTrackPreferences
import com.clotube.app.data.TrackPreferenceState
import com.clotube.app.network.ClotubeNetworkMonitor
import com.clotube.app.ui.components.AppUpdateDialog
import com.clotube.app.ui.components.ByocSetupBottomSheet
import com.clotube.app.ui.components.DriveFolderPickerDialog
import com.clotube.app.ui.components.ReleaseNotesDialog
import com.clotube.app.ui.theme.*
import com.clotube.app.util.triggerNavigationHaptic
import com.clotube.app.viewmodel.VideoViewModel
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Storage utilization metrics snapshot in Megabytes.
 */
data class StorageMetrics(
    val metadataCacheMb: Double = 0.0,
    val imageCacheMb: Double = 0.0,
    val totalCacheMb: Double = 0.0,
    val isCalculating: Boolean = false
)

/**
 * Categorized Obsidian Cinema Settings Hub with live asynchronous MB disk usage calculation,
 * granular cache-clearing confirmation dialogs, sticky playback track defaults, and
 * dual-socket connection diagnostics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: VideoViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val isOnline by ClotubeNetworkMonitor.isOnline.collectAsState()
    val isNetworkChecking by ClotubeNetworkMonitor.isChecking.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var showFolderPickerType by remember { mutableStateOf<String?>(null) }
    var availableDriveFolders by remember { mutableStateOf<List<File>>(emptyList()) }
    var isFetchingFolders by remember { mutableStateOf(false) }

    var showSignOutDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showFullResetDialog by remember { mutableStateOf(false) }
    var showResetTracksDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showReleaseNotesDialog by remember { mutableStateOf(false) }
    var showByocSheet by remember { mutableStateOf(false) }

    val byocConfig by ByocConfigManager.configFlow.collectAsState()

    LaunchedEffect(Unit) {
        ByocConfigManager.ensureLoaded(context)
    }

    // Reactive sticky track preferences
    val trackPrefs by PlaybackTrackPreferences.getTrackPreferencesFlow(context)
        .collectAsState(initial = TrackPreferenceState())

    // Asynchronous Storage Metrics Calculation
    var storageMetrics by remember { mutableStateOf(StorageMetrics(isCalculating = true)) }

    fun refreshStorageMetrics() {
        coroutineScope.launch {
            storageMetrics = storageMetrics.copy(isCalculating = true)
            val updated = calculateStorageMetrics(context)
            storageMetrics = updated
        }
    }

    LaunchedEffect(Unit) {
        refreshStorageMetrics()
    }

    // Folder Picker Dialog
    if (showFolderPickerType != null) {
        DriveFolderPickerDialog(
            folderType = showFolderPickerType!!,
            folders = availableDriveFolders,
            isLoading = isFetchingFolders,
            onFolderSelected = { selectedFolder ->
                viewModel.selectCustomFolder(showFolderPickerType!!, selectedFolder)
                showFolderPickerType = null
            },
            onDismiss = {
                showFolderPickerType = null
            }
        )
    }

    // Sign Out Confirmation Dialog
    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = {
                Text(
                    text = "Sign Out?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to sign out? Your playback progress and library configuration will remain saved.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        viewModel.signOut()
                        onNavigateBack()
                    }
                ) {
                    Text("Sign Out", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Clear Metadata & Poster Cache Confirmation Dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = {
                Text(
                    text = "Clear Metadata & Posters?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "This will delete all locally cached TMDB/Cinemeta movie and series artwork and offline metadata (${String.format(Locale.US, "%.1f MB", storageMetrics.totalCacheMb)}). Images and descriptions will be re-fetched on demand.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheDialog = false
                        coroutineScope.launch {
                            clearArtworkAndMetadataCache(context)
                            refreshStorageMetrics()
                            Toast.makeText(context, "Metadata & Poster cache cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Clear Cache", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Full Reset & Re-index Confirmation Dialog
    if (showFullResetDialog) {
        AlertDialog(
            onDismissRequest = { showFullResetDialog = false },
            title = {
                Text(
                    text = "Full Reset & Re-index?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "This purges all folder discovery caches, deletes local poster metadata, and restarts library indexing from your Google Drive roots.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFullResetDialog = false
                        coroutineScope.launch {
                            clearArtworkAndMetadataCache(context)
                            viewModel.clearCachedFoldersAndRescan()
                            refreshStorageMetrics()
                            Toast.makeText(context, "Library index reset triggered", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Reset & Re-index", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullResetDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Reset Sticky Track Defaults Confirmation Dialog
    if (showResetTracksDialog) {
        AlertDialog(
            onDismissRequest = { showResetTracksDialog = false },
            title = {
                Text(
                    text = "Reset Track Preferences?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Reset your sticky audio and subtitle language selections back to default. Next time you play video files, tracks will default to stream 0.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetTracksDialog = false
                        coroutineScope.launch {
                            PlaybackTrackPreferences.resetTrackPreferences(context)
                            Toast.makeText(context, "Track preferences reset", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Reset", color = AccentBlue, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetTracksDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CanvasDeep,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CanvasDeep,
                    titleContentColor = Color.White
                ),
                title = {
                    Text(
                        text = "Settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    val view = LocalView.current
                    IconButton(onClick = {
                        view.triggerNavigationHaptic()
                        onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp)
        ) {
            // ========================================================
            // 1. ACCOUNT & GOOGLE DRIVE SECTION
            // ========================================================
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDefault),
                    border = BorderStroke(1.dp, SurfaceBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!uiState.userAvatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(uiState.userAvatarUrl)
                                    .crossfade(150)
                                    .bitmapConfig(Bitmap.Config.HARDWARE)
                                    .build(),
                                contentDescription = "Profile",
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, SurfaceBorder, CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Surface(
                                modifier = Modifier.size(56.dp),
                                shape = CircleShape,
                                color = SurfaceElevated,
                                border = BorderStroke(1.dp, SurfaceBorder)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = uiState.userName?.take(1)?.uppercase() ?: "U",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.userName ?: "Clotube User",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = uiState.userEmail ?: "Signed in with Google Drive",
                                fontSize = 13.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            // Inline status chip: "Google Drive Connected" with a soft emerald green dot
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(Color(0x1A10B981), RoundedCornerShape(12.dp))
                                    .border(0.5.dp, Color(0x3310B981), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981))
                                    )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Google Drive Connected",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF10B981)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDefault),
                    border = BorderStroke(1.dp, SurfaceBorder)
                ) {
                    SettingsRow(
                        icon = Icons.Rounded.Key,
                        title = "Custom OAuth (BYOC)",
                        subtitle = if (byocConfig.isEnabled) "Using project: ${byocConfig.projectId}" else "Configure personal Google Cloud client_secret credentials",
                        trailingText = if (byocConfig.isEnabled) "Active" else "Default",
                        onClick = { showByocSheet = true }
                    )
                }
            }

            item {
                SettingsCategoryHeader("Google Drive Directories")
            }

            item {
                val movieCount = uiState.moviesList.size
                val moviesSubtitle = if (movieCount > 0) "/Google Drive/Movies • $movieCount titles" else "Google Drive > Movies"

                val seriesCount = uiState.tvSeriesList.size
                val seriesSubtitle = if (seriesCount > 0) "/Google Drive/Series • $seriesCount shows" else "Google Drive > Series"

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDefault),
                    border = BorderStroke(1.dp, SurfaceBorder)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Movies Directory
                        SettingsRow(
                            icon = Icons.Rounded.Movie,
                            title = "Movies Directory",
                            subtitle = moviesSubtitle,
                            onClick = {
                                coroutineScope.launch {
                                    isFetchingFolders = true
                                    showFolderPickerType = "Movies"
                                    availableDriveFolders = viewModel.fetchAllDriveFolders()
                                    isFetchingFolders = false
                                }
                            }
                        )

                        SettingsRowDivider()

                        // TV Series Directory
                        SettingsRow(
                            icon = Icons.Rounded.Tv,
                            title = "TV Series Directory",
                            subtitle = seriesSubtitle,
                            onClick = {
                                coroutineScope.launch {
                                    isFetchingFolders = true
                                    showFolderPickerType = "Series"
                                    availableDriveFolders = viewModel.fetchAllDriveFolders()
                                    isFetchingFolders = false
                                }
                            }
                        )

                        SettingsRowDivider()

                        // Library Rescan / Sync
                        SettingsRow(
                            icon = Icons.Rounded.Sync,
                            title = "Sync Library & Cloud History",
                            subtitle = "Force background reconciliation with Google Drive",
                            onClick = {
                                viewModel.refreshAppFreshBoot()
                                Toast.makeText(context, "Synchronizing with Google Drive...", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            // ========================================================
            // 2. PLAYBACK & TRACK DEFAULTS SECTION
            // ========================================================
            item {
                SettingsCategoryHeader("Playback & Track Defaults")
            }

            item {
                val audioLangDisplay = if (trackPrefs.preferredAudioLang.isNotBlank()) {
                    trackPrefs.preferredAudioLang.uppercase()
                } else {
                    "Default / Stream 0"
                }

                val subtitleLangDisplay = if (!trackPrefs.subtitlesEnabled) {
                    "Disabled / Off"
                } else if (trackPrefs.preferredSubtitleLang.isNotBlank()) {
                    "${trackPrefs.preferredSubtitleLang.uppercase()} (Enabled)"
                } else {
                    "Auto / Stream 0"
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDefault),
                    border = BorderStroke(1.dp, SurfaceBorder)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Hardware Acceleration Toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceElevated),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayCircle,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Hardware Acceleration",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "MediaCodec 4K/60fps direct pipeline",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }

                            Switch(
                                checked = uiState.isHardwareAccelerationEnabled,
                                onCheckedChange = { viewModel.setHardwareAcceleration(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = AccentBlue,
                                    uncheckedThumbColor = Color(0xFF94A3B8),
                                    uncheckedTrackColor = SurfaceElevated
                                )
                            )
                        }

                        SettingsRowDivider()

                        // Sticky Audio Track Preference Display
                        SettingsRow(
                            icon = Icons.Rounded.Audiotrack,
                            title = "Preferred Audio Language",
                            subtitle = "Sticky: $audioLangDisplay",
                            trailingText = audioLangDisplay,
                            onClick = {
                                Toast.makeText(context, "Select track in player to change sticky preference", Toast.LENGTH_SHORT).show()
                            }
                        )

                        SettingsRowDivider()

                        // Sticky Subtitle Track Preference Display
                        SettingsRow(
                            icon = Icons.Rounded.Subtitles,
                            title = "Preferred Subtitle Language",
                            subtitle = "Sticky: $subtitleLangDisplay",
                            trailingText = if (trackPrefs.subtitlesEnabled) trackPrefs.preferredSubtitleLang.uppercase().ifBlank { "ON" } else "OFF",
                            onClick = {
                                Toast.makeText(context, "Select subtitle in player to change sticky preference", Toast.LENGTH_SHORT).show()
                            }
                        )

                        SettingsRowDivider()

                        // Reset Track Defaults
                        SettingsRow(
                            icon = Icons.Rounded.RestartAlt,
                            title = "Reset Sticky Track Defaults",
                            subtitle = "Clear persisted language tokens and re-enable auto-selection",
                            onClick = { showResetTracksDialog = true }
                        )
                    }
                }
            }

            // ========================================================
            // 3. STORAGE & CACHE MANAGEMENT SECTION
            // ========================================================
            item {
                SettingsCategoryHeader("Storage & Cache Management")
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDefault),
                    border = BorderStroke(1.dp, SurfaceBorder)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Storage Metrics Breakdown
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(SurfaceElevated),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Storage,
                                            contentDescription = null,
                                            tint = AccentBlue,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column {
                                        Text(
                                            text = "Disk Cache Utilization",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Local offline storage",
                                            fontSize = 12.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }

                                if (storageMetrics.isCalculating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = AccentBlue,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        text = String.format(Locale.US, "%.1f MB", storageMetrics.totalCacheMb),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Cache detail breakdown rows
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Metadata & Synopses (7-day TTL)",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                                Text(
                                    text = String.format(Locale.US, "%.2f MB", storageMetrics.metadataCacheMb),
                                    fontSize = 12.sp,
                                    color = TextPrimary
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Posters & Backdrops (Coil Disk)",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1f MB", storageMetrics.imageCacheMb),
                                    fontSize = 12.sp,
                                    color = TextPrimary
                                )
                            }
                        }

                        SettingsRowDivider()

                        // Action: Clear Metadata & Poster Cache
                        SettingsRow(
                            icon = Icons.Rounded.CleaningServices,
                            title = "Clear Metadata & Poster Cache",
                            subtitle = "Free ${String.format(Locale.US, "%.1f MB", storageMetrics.totalCacheMb)} without affecting your Drive",
                            onClick = { showClearCacheDialog = true }
                        )

                        SettingsRowDivider()

                        // Action: Full Reset & Re-index
                        SettingsRow(
                            icon = Icons.Rounded.Refresh,
                            title = "Reset Discovery & Media Index",
                            subtitle = "Clear folder discovery and re-index media files",
                            onClick = { showFullResetDialog = true }
                        )
                    }
                }
            }

            // ========================================================
            // 4. DIAGNOSTICS & SYSTEM SECTION
            // ========================================================
            item {
                SettingsCategoryHeader("Diagnostics & System")
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDefault),
                    border = BorderStroke(1.dp, SurfaceBorder)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Connection Probe Status
                        SettingsRow(
                            icon = if (isOnline) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                            title = "Dual-Socket DNS Reachability",
                            subtitle = if (isOnline) "8.8.8.8:53 & 1.1.1.1:53 active" else "Probes timed out (Offline)",
                            trailingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .background(
                                            if (isOnline) Color(0x1A10B981) else Color(0x26F59E0B),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .border(
                                            0.5.dp,
                                            if (isOnline) Color(0x3310B981) else Color(0x4DF59E0B),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(if (isOnline) Color(0xFF10B981) else Color(0xFFF59E0B))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isOnline) "Online" else "Offline",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isOnline) Color(0xFF10B981) else Color(0xFFF59E0B)
                                    )
                                }
                            },
                            onClick = {
                                coroutineScope.launch {
                                    ClotubeNetworkMonitor.recheckNow()
                                    Toast.makeText(
                                        context,
                                        if (ClotubeNetworkMonitor.isOnline.value) "Online: Connection verified" else "Offline: Connection unreachable",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )

                        SettingsRowDivider()

                        // Media Engine Spec
                        SettingsRow(
                            icon = Icons.Rounded.Memory,
                            title = "Playback Engine",
                            subtitle = "LibVLC 3.6.0 • MediaCodec 4K Direct",
                            trailingText = "LibVLC",
                            onClick = {}
                        )

                        SettingsRowDivider()

                        // App Version
                        SettingsRow(
                            icon = Icons.Rounded.Info,
                            title = "Clotube Android Version",
                            subtitle = "Obsidian Cinema Edition",
                            trailingText = "v${BuildConfig.VERSION_NAME}",
                            onClick = {}
                        )

                        SettingsRowDivider()

                        // Check for Updates
                        SettingsRow(
                            icon = Icons.Rounded.SystemUpdate,
                            title = "Check for Updates",
                            subtitle = "Query latest GitHub releases & install APK",
                            trailingText = "GitHub",
                            onClick = {
                                showUpdateDialog = true
                            }
                        )

                        SettingsRowDivider()

                        // Release Notes / What's New
                        SettingsRow(
                            icon = Icons.Rounded.AutoAwesome,
                            title = "What's New",
                            subtitle = "View feature highlights & release notes",
                            onClick = {
                                showReleaseNotesDialog = true
                            }
                        )
                    }
                }
            }

            // ========================================================
            // 5. ACCOUNT SIGN OUT
            // ========================================================
            item {
                SettingsCategoryHeader("Session")
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { showSignOutDialog = true },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDefault),
                    border = BorderStroke(1.dp, SurfaceBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Logout,
                            contentDescription = "Sign Out",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sign Out",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFEF4444)
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // In-App Update Dialog
    AppUpdateDialog(
        isOpen = showUpdateDialog,
        onDismissRequest = { showUpdateDialog = false }
    )

    // Release Notes Modal
    ReleaseNotesDialog(
        isOpen = showReleaseNotesDialog,
        onDismissRequest = { showReleaseNotesDialog = false }
    )

    // Custom OAuth (BYOC) Wizard
    ByocSetupBottomSheet(
        isOpen = showByocSheet,
        onDismissRequest = { showByocSheet = false }
    )
}

/**
 * Standard settings row with Obsidian Cinema styling.
 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailingText: String? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (trailingContent != null) {
            trailingContent()
        } else if (trailingText != null) {
            Text(
                text = trailingText,
                fontSize = 13.sp,
                color = TextTertiary,
                fontWeight = FontWeight.Medium
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFF64748B),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Inset divider line between settings rows.
 */
@Composable
private fun SettingsRowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 66.dp, end = 16.dp)
            .height(0.5.dp)
            .background(Color(0x0FFFFFFF))
    )
}

/**
 * Subdued uppercase category header matching Obsidian Cinema layout.
 */
@Composable
private fun SettingsCategoryHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = Color(0xFF64748B),
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

/**
 * Calculates current disk usage for metadata and Coil image caches.
 */
private suspend fun calculateStorageMetrics(context: Context): StorageMetrics = withContext(Dispatchers.IO) {
    val metadataBytes = MetadataDiskCache.getCacheSizeBytes(context)
    val imageCacheDir = context.cacheDir.resolve("clotube_image_cache")
    val imageBytes = try {
        if (imageCacheDir.exists()) {
            imageCacheDir.walkTopDown()
                .filter { it.isFile }
                .map { it.length() }
                .sum()
        } else 0L
    } catch (_: Exception) {
        0L
    }

    val metaMb = metadataBytes / (1024.0 * 1024.0)
    val imgMb = imageBytes / (1024.0 * 1024.0)
    StorageMetrics(
        metadataCacheMb = metaMb,
        imageCacheMb = imgMb,
        totalCacheMb = metaMb + imgMb,
        isCalculating = false
    )
}

/**
 * Clears persistent metadata cache and Coil disk cache directory.
 */
private suspend fun clearArtworkAndMetadataCache(context: Context) = withContext(Dispatchers.IO) {
    MetadataDiskCache.clearAll(context)
    try {
        val imageCacheDir = context.cacheDir.resolve("clotube_image_cache")
        if (imageCacheDir.exists()) {
            imageCacheDir.deleteRecursively()
            imageCacheDir.mkdirs()
        }
    } catch (_: Exception) {
    }
}
