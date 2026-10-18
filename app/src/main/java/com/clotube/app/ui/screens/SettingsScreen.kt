package com.clotube.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clotube.app.ui.components.DriveFolderPickerDialog
import com.clotube.app.ui.theme.*
import com.clotube.app.viewmodel.VideoViewModel
import com.google.api.services.drive.model.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: VideoViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var showFolderPickerType by remember { mutableStateOf<String?>(null) }
    var availableDriveFolders by remember { mutableStateOf<List<File>>(emptyList()) }
    var isFetchingFolders by remember { mutableStateOf(false) }

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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BackgroundDeep,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDefault,
                    titleContentColor = TextHighEmphasis
                ),
                title = {
                    Text(
                        text = "Settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextHighEmphasis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextHighEmphasis
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Account Information Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceElevated)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!uiState.userAvatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = uiState.userAvatarUrl,
                                contentDescription = "Profile",
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Surface(
                                modifier = Modifier.size(52.dp),
                                shape = CircleShape,
                                color = SurfaceDefault
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = uiState.userName?.take(1)?.uppercase() ?: "U",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SecondaryAccent
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
                                color = TextHighEmphasis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = uiState.userEmail ?: "Signed in with Google Drive",
                                fontSize = 13.sp,
                                color = TextMuted
                            )
                        }
                    }
                }
            }

            // Google Drive Folders Section
            item {
                Text(
                    text = "GOOGLE DRIVE LIBRARY FOLDERS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = SecondaryAccent,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                )
            }

            // Movies Folder Selector
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceElevated)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Movie, contentDescription = null, tint = PrimaryAccent)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Movies Folder",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextHighEmphasis
                                )
                                Text(
                                    text = "Folder where full-length films and MKVs are located",
                                    fontSize = 12.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    isFetchingFolders = true
                                    showFolderPickerType = "Movies"
                                    availableDriveFolders = viewModel.fetchAllDriveFolders()
                                    isFetchingFolders = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = PillShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF))
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Change Movies Folder")
                        }
                    }
                }
            }

            // Series Folder Selector
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceElevated)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Tv, contentDescription = null, tint = PrimaryAccent)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Series Folder",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextHighEmphasis
                                )
                                Text(
                                    text = "Folder containing TV show collections and seasons",
                                    fontSize = 12.sp,
                                    color = TextMuted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    isFetchingFolders = true
                                    showFolderPickerType = "Series"
                                    availableDriveFolders = viewModel.fetchAllDriveFolders()
                                    isFetchingFolders = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = PillShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF))
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Change Series Folder")
                        }
                    }
                }
            }

            // Auto-Discovery Reset
            item {
                Button(
                    onClick = {
                        viewModel.clearCachedFoldersAndRescan()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = SecondaryAccent, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset Folder Cache & Auto-Detect", color = SecondaryAccent)
                }
            }

            // Playback Section
            item {
                Text(
                    text = "PLAYBACK & RENDERING",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = SecondaryAccent,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceElevated)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Hardware Acceleration",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextHighEmphasis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Use hardware decoders for seamless 4K / 60fps playback",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }

                        Switch(
                            checked = uiState.isHardwareAccelerationEnabled,
                            onCheckedChange = { viewModel.setHardwareAcceleration(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PrimaryAccent,
                                checkedTrackColor = PrimaryAccent.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }

            // Sign Out
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.signOut() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B1E22))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        tint = Color(0xFFF28B82),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign Out", color = Color(0xFFF28B82), fontWeight = FontWeight.SemiBold)
                }
            }

            item { Spacer(modifier = Modifier.height(40.dp)) }
        }
    }
}
