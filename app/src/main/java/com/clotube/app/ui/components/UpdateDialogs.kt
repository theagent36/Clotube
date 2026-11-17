package com.clotube.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clotube.app.BuildConfig
import com.clotube.app.ui.theme.AccentBlue
import com.clotube.app.ui.theme.CanvasDeep
import com.clotube.app.ui.theme.SurfaceBorder
import com.clotube.app.ui.theme.SurfaceDefault
import com.clotube.app.ui.theme.SurfaceElevated
import com.clotube.app.ui.theme.TextPrimary
import com.clotube.app.ui.theme.TextSecondary
import com.clotube.app.ui.theme.TextTertiary
import com.clotube.app.update.AppUpdateInfo
import com.clotube.app.update.AppUpdateManager
import com.clotube.app.update.AppUpdateState
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Obsidian Cinema styled In-App Update Dialog.
 * Handles update discovery, progress tracking, and package installer execution.
 */
@Composable
fun AppUpdateDialog(
    isOpen: Boolean,
    onDismissRequest: () -> Unit
) {
    if (!isOpen) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val updateState by AppUpdateManager.updateState.collectAsState()

    Dialog(
        onDismissRequest = {
            if (updateState !is AppUpdateState.Downloading) {
                onDismissRequest()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = updateState !is AppUpdateState.Downloading,
            dismissOnClickOutside = updateState !is AppUpdateState.Downloading
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(24.dp),
            color = SurfaceDefault,
            border = BorderStroke(1.dp, SurfaceBorder),
            shadowElevation = 20.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = AccentBlue.copy(alpha = 0.15f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = "Software Update",
                                color = TextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Clotube for Android",
                                color = TextTertiary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (updateState !is AppUpdateState.Downloading) {
                        IconButton(
                            onClick = onDismissRequest,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                when (val state = updateState) {
                    is AppUpdateState.Checking -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = AccentBlue,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Checking for new releases...",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    is AppUpdateState.UpdateAvailable -> {
                        UpdateAvailableContent(
                            info = state.info,
                            onUpdateClick = {
                                coroutineScope.launch {
                                    AppUpdateManager.downloadUpdate(context, state.info)
                                }
                            },
                            onDismissClick = onDismissRequest
                        )
                    }

                    is AppUpdateState.Downloading -> {
                        DownloadingContent(
                            progress = state.progress,
                            bytesDownloaded = state.bytesDownloaded,
                            totalBytes = state.totalBytes
                        )
                    }

                    is AppUpdateState.Downloaded -> {
                        DownloadedContent(
                            info = state.info,
                            apkFile = state.apkFile,
                            onInstallClick = {
                                AppUpdateManager.installApk(context, state.apkFile)
                            },
                            onDismissClick = onDismissRequest
                        )
                    }

                    is AppUpdateState.UpToDate -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "You're up to date!",
                                color = Color(0xFF34D399),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Clotube version ${state.currentVersion} is the latest release.",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = onDismissRequest,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                            ) {
                                Text("Done", color = TextPrimary, fontSize = 14.sp)
                            }
                        }
                    }

                    is AppUpdateState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Update Check Failed",
                                color = Color(0xFFEF4444),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = state.message,
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = onDismissRequest) {
                                    Text("Dismiss", color = TextSecondary)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            AppUpdateManager.checkForUpdates(context, force = true)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Retry", color = Color.White)
                                }
                            }
                        }
                    }

                    AppUpdateState.Idle -> {
                        LaunchedEffect(Unit) {
                            AppUpdateManager.checkForUpdates(context)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateAvailableContent(
    info: AppUpdateInfo,
    onUpdateClick: () -> Unit,
    onDismissClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Version badges
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = AccentBlue.copy(alpha = 0.15f),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, AccentBlue.copy(alpha = 0.3f))
            ) {
                Text(
                    text = "New: ${info.tagName}",
                    color = AccentBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                color = SurfaceElevated,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, SurfaceBorder)
            ) {
                Text(
                    text = "Current: v${info.currentVersion}",
                    color = TextTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            if (info.apkSize > 0) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = formatApkSize(info.apkSize),
                    color = TextTertiary,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Release notes box
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp),
            shape = RoundedCornerShape(12.dp),
            color = CanvasDeep,
            border = BorderStroke(1.dp, SurfaceBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "What's New in this release:",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = info.releaseNotes,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onDismissClick,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Later", color = TextSecondary, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.width(10.dp))

            Button(
                onClick = onUpdateClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Download & Install", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DownloadingContent(
    progress: Float,
    bytesDownloaded: Long,
    totalBytes: Long
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val percent = (progress * 100).toInt()

        Text(
            text = "Downloading Update... $percent%",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(14.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = AccentBlue,
            trackColor = SurfaceElevated
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatApkSize(bytesDownloaded),
                color = TextTertiary,
                fontSize = 12.sp
            )
            Text(
                text = if (totalBytes > 0) formatApkSize(totalBytes) else "--",
                color = TextTertiary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun DownloadedContent(
    info: AppUpdateInfo,
    apkFile: File,
    onInstallClick: () -> Unit,
    onDismissClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = Color(0xFF064E3B),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = Color(0xFF34D399),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Download Complete!",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Ready to install ${info.tagName}",
                        color = Color(0xFF34D399),
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onDismissClick,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Dismiss", color = TextSecondary, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.width(10.dp))

            Button(
                onClick = onInstallClick,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
            ) {
                Text("Install Now", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Obsidian Cinema styled "What's New in Clotube" Release Notes Modal.
 */
@Composable
fun ReleaseNotesDialog(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    releaseNotes: String? = null
) {
    if (!isOpen) return

    val context = LocalContext.current
    var notesText by remember { mutableStateOf(releaseNotes) }

    LaunchedEffect(isOpen) {
        if (notesText == null) {
            notesText = AppUpdateManager.getOrFetchReleaseNotes(context)
        }
    }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(24.dp),
            color = SurfaceDefault,
            border = BorderStroke(1.dp, SurfaceBorder),
            shadowElevation = 20.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF8B5CF6).copy(alpha = 0.15f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color(0xFFA78BFA),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = "What's New",
                                color = TextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Version ${BuildConfig.VERSION_NAME}",
                                color = TextTertiary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Scrollable notes
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = CanvasDeep,
                    border = BorderStroke(1.dp, SurfaceBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = notesText ?: "Loading release notes...",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = onDismissRequest,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                ) {
                    Text("Got it", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun formatApkSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes.toDouble() / (1024 * 1024)
    return String.format(Locale.ROOT, "%.1f MB", mb)
}
