package com.clotube.app.ui.components

import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.app.data.ByocConfigManager
import com.clotube.app.ui.theme.AccentBlue
import com.clotube.app.ui.theme.CanvasDeep
import com.clotube.app.ui.theme.SurfaceBorder
import com.clotube.app.ui.theme.SurfaceDefault
import com.clotube.app.ui.theme.SurfaceElevated
import com.clotube.app.ui.theme.TextPrimary
import com.clotube.app.ui.theme.TextSecondary
import com.clotube.app.ui.theme.TextTertiary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Obsidian Cinema styled Bring Your Own Cloud (BYOC) Interactive OAuth Setup Wizard.
 * Guides beginners step-by-step through Google Cloud Console OAuth setup with direct
 * deep links, one-tap copy chips, Error 403 prevention, and JSON import validation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ByocSetupBottomSheet(
    isOpen: Boolean,
    onDismissRequest: () -> Unit
) {
    if (!isOpen) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val byocConfig by ByocConfigManager.configFlow.collectAsState()

    var currentStep by remember { mutableIntStateOf(1) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ByocConfigManager.ensureLoaded(context)
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isImporting = true
            errorMessage = null
            coroutineScope.launch {
                val fileName = ByocConfigManager.queryFileName(context, uri) ?: "client_secret.json"
                val result = ByocConfigManager.importOAuthJson(context, uri, fileName)
                isImporting = false
                result.onSuccess {
                    Toast.makeText(context, "BYOC OAuth credentials activated!", Toast.LENGTH_SHORT).show()
                    onDismissRequest()
                }.onFailure { e ->
                    errorMessage = e.message ?: "Failed to import JSON file"
                    currentStep = 5 // Ensure error is visible on the import step
                }
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = SurfaceDefault,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 10.dp),
                color = TextTertiary.copy(alpha = 0.4f),
                shape = CircleShape
            ) {
                Box(modifier = Modifier.size(width = 36.dp, height = 4.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = AccentBlue.copy(alpha = 0.15f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "Custom OAuth Setup (BYOC)",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Beginner-Friendly Step-by-Step Guide",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Active Config Banner (if enabled)
            if (byocConfig.isEnabled) {
                ActiveCredentialsCard(
                    config = byocConfig,
                    onReset = {
                        coroutineScope.launch {
                            ByocConfigManager.clearByocConfig(context)
                            Toast.makeText(context, "Reverted to Clotube default OAuth", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Step Indicator Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "STEP $currentStep OF 5",
                    color = AccentBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = when (currentStep) {
                        1 -> "Create Cloud Project"
                        2 -> "Enable Drive API"
                        3 -> "Audience & Test Users"
                        4 -> "Create OAuth Client"
                        5 -> "Import JSON File"
                        else -> ""
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Interactive 5-Step Progress Pills Bar
            StepProgressIndicator(
                currentStep = currentStep,
                totalSteps = 5,
                onStepSelected = { selectedStep ->
                    currentStep = selectedStep
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Animated Step Content Card
            Surface(
                color = CanvasDeep,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally { it } + fadeIn()).togetherWith(
                                slideOutHorizontally { -it } + fadeOut()
                            )
                        } else {
                            (slideInHorizontally { -it } + fadeIn()).togetherWith(
                                slideOutHorizontally { it } + fadeOut()
                            )
                        }
                    },
                    label = "ByocStepTransition"
                ) { step ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        when (step) {
                            1 -> StepOneContent(onOpenProjectCreator = {
                                openSafeUrl(uriHandler, "https://console.cloud.google.com/projectcreate", context)
                            })
                            2 -> StepTwoContent(onOpenDriveApi = {
                                openSafeUrl(uriHandler, "https://console.cloud.google.com/apis/library/drive.googleapis.com", context)
                            })
                            3 -> StepThreeContent(onOpenAudience = {
                                openSafeUrl(uriHandler, "https://console.cloud.google.com/auth/audience", context)
                            })
                            4 -> StepFourContent(onOpenClients = {
                                openSafeUrl(uriHandler, "https://console.cloud.google.com/auth/clients", context)
                            })
                            5 -> StepFiveContent(
                                errorMessage = errorMessage,
                                isImporting = isImporting,
                                onImportClick = {
                                    openDocumentLauncher.launch(arrayOf("application/json", "*/*"))
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Bottom Navigation Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { currentStep = (currentStep - 1).coerceAtLeast(1) },
                    enabled = currentStep > 1,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (currentStep > 1) SurfaceBorder else SurfaceBorder.copy(alpha = 0.3f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextPrimary,
                        disabledContentColor = TextTertiary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Previous", fontSize = 13.sp)
                }

                if (currentStep < 5) {
                    Button(
                        onClick = { currentStep = (currentStep + 1).coerceAtMost(5) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("Next Step", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Next",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Button(
                        onClick = { openDocumentLauncher.launch(arrayOf("application/json", "*/*")) },
                        enabled = !isImporting,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.FileUpload,
                                contentDescription = "Import",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import JSON", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Quick Import Shortcut for experienced users (visible on steps 1-4)
            if (currentStep < 5) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { openDocumentLauncher.launch(arrayOf("application/json", "*/*")) }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FileOpen,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Already downloaded client_secret_*.json? Tap here to import directly",
                        color = TextSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ==============================================================================
// STEP 1 CONTENT: Create Cloud Project
// ==============================================================================
@Composable
private fun StepOneContent(onOpenProjectCreator: () -> Unit) {
    Column {
        Text(
            text = "Create a Free Google Cloud Project",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Google requires a free Cloud Project to generate your personal Drive access key. No credit card or billing is needed.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        SubInstructionRow(
            number = "1",
            text = "Tap Open Project Creator below and sign in with your Google account."
        )
        Spacer(modifier = Modifier.height(8.dp))
        SubInstructionRow(
            number = "2",
            text = "Enter Project Name: Clotube Personal and tap Create."
        )
        Spacer(modifier = Modifier.height(8.dp))
        SubInstructionRow(
            number = "3",
            text = "Wait ~10 seconds for the notification bell, then tap Select Project."
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onOpenProjectCreator,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Google Cloud Project Creator ↗", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ==============================================================================
// STEP 2 CONTENT: Enable Google Drive API
// ==============================================================================
@Composable
private fun StepTwoContent(onOpenDriveApi: () -> Unit) {
    Column {
        Text(
            text = "Enable the Google Drive API",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Turn on Google Drive access for your new project so Clotube can list and stream your Movies & Series folders.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        SubInstructionRow(
            number = "1",
            text = "Tap Open Drive API Library below (make sure your new project is selected in the top bar)."
        )
        Spacer(modifier = Modifier.height(8.dp))
        SubInstructionRow(
            number = "2",
            text = "Tap the blue Enable button and wait for the API dashboard to load."
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onOpenDriveApi,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Google Drive API Library ↗", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ==============================================================================
// STEP 3 CONTENT: Configure Branding & Add Test User (Crucial!)
// ==============================================================================
@Composable
private fun StepThreeContent(onOpenAudience: () -> Unit) {
    Column {
        Text(
            text = "Configure Branding & Test Users",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Set up the consent screen and whitelist your Gmail address so Google doesn't block sign-in with Error 403.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        SubInstructionRow(
            number = "1",
            text = "Tap Open Google Auth Platform below. If prompted, click Get Started, set App Name to Clotube, choose External, enter your email, and click Create."
        )
        Spacer(modifier = Modifier.height(8.dp))
        SubInstructionRow(
            number = "2",
            text = "In the left sidebar, open Audience (under Google Auth Platform)."
        )
        Spacer(modifier = Modifier.height(8.dp))
        SubInstructionRow(
            number = "3",
            text = "Scroll to Test users, click + Add users, type your exact Gmail address, and click Save."
        )
        Spacer(modifier = Modifier.height(8.dp))
        SubInstructionRow(
            number = "4",
            text = "(Optional) Under Data Access → Add or remove scopes, add the Drive scope below:"
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Drive Scope Copy Chip
        OneTapCopyChip(
            label = "Google Drive Scope",
            valueToCopy = "https://www.googleapis.com/auth/drive"
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Error 403 Prevention Callout Box
        Surface(
            color = Color(0x1AF59E0B),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color(0x66F59E0B)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFBBF24),
                    modifier = Modifier
                        .size(18.dp)
                        .padding(top = 1.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Avoid Error 403 (access_denied)",
                        color = Color(0xFFFBBF24),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "You MUST add your own Gmail address under Audience → Test users, otherwise Google will block login while your project is in Testing mode.",
                        color = Color(0xFFFDE68A),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onOpenAudience,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Auth Audience & Test Users ↗", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ==============================================================================
// STEP 4 CONTENT: Create OAuth Client & Download JSON
// ==============================================================================
@Composable
private fun StepFourContent(onOpenClients: () -> Unit) {
    val context = LocalContext.current
    var showAndroidDetails by remember { mutableStateOf(false) }

    Column {
        Text(
            text = "Create OAuth Client & Download JSON",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Generate your downloadable JSON credential file.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        SubInstructionRow(
            number = "1",
            text = "Tap Open OAuth Clients below, then tap + Create Client."
        )
        Spacer(modifier = Modifier.height(8.dp))
        SubInstructionRow(
            number = "2",
            text = "Open the Application type dropdown and select Desktop app (Recommended: loopback http://127.0.0.1 authentication works automatically with PKCE without needing to manually add redirect URIs)."
        )
        Spacer(modifier = Modifier.height(8.dp))
        SubInstructionRow(
            number = "3",
            text = "Name it Clotube Android and tap Create."
        )
        Spacer(modifier = Modifier.height(8.dp))
        SubInstructionRow(
            number = "4",
            text = "In the confirmation popup (or by clicking the ⬇ download icon on the client row), tap Download JSON to save client_secret_...json to your phone's Downloads folder."
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Important Warning Against Firebase google-services.json
        Surface(
            color = Color(0x18EF4444),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Color(0x33EF4444)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFFF87171),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Do not import Firebase google-services.json. You need client_secret_*.json from Google Auth Platform → Clients.",
                    color = Color(0xFFFCA5A5),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Collapsible Android Package & SHA-1 Helper
        Surface(
            color = SurfaceElevated,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, SurfaceBorder),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAndroidDetails = !showAndroidDetails }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Need Android Package / SHA-1 details?",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Icon(
                        imageVector = if (showAndroidDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (showAndroidDetails) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Desktop app is the primary recommended client type because loopback authentication works automatically out-of-the-box and provides the direct Download JSON button. If you choose Android client instead, copy these details:",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OneTapCopyChip(
                        label = "Package Name",
                        valueToCopy = context.packageName,
                        isMonospace = true
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    val sha1 = remember(context) { ByocConfigManager.getAppSha1Fingerprint(context) }
                    OneTapCopyChip(
                        label = "SHA-1 Certificate Fingerprint",
                        valueToCopy = sha1,
                        isMonospace = true
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onOpenClients,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open OAuth Clients Page ↗", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ==============================================================================
// STEP 5 CONTENT: Import Downloaded JSON File
// ==============================================================================
@Composable
private fun StepFiveContent(
    errorMessage: String?,
    isImporting: Boolean,
    onImportClick: () -> Unit
) {
    Column {
        Text(
            text = "Import Your Downloaded JSON File",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Select the client_secret_*.json file you just downloaded from your phone's Downloads folder.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Checklist Summary
        Surface(
            color = SurfaceElevated,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, SurfaceBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "PRE-FLIGHT CHECKLIST",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                ChecklistItem("Google Cloud Project created (e.g. Clotube Personal)")
                Spacer(modifier = Modifier.height(6.dp))
                ChecklistItem("Google Drive API enabled")
                Spacer(modifier = Modifier.height(6.dp))
                ChecklistItem("Your Gmail added under Audience → Test Users")
                Spacer(modifier = Modifier.height(6.dp))
                ChecklistItem("Desktop or Web OAuth Client JSON downloaded")
            }
        }

        // Inline Error Display (if previous import failed)
        if (!errorMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                color = Color(0xFF451A1A),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = Color(0xFFF87171),
                        modifier = Modifier
                            .size(18.dp)
                            .padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Validation Error",
                            color = Color(0xFFFCA5A5),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = errorMessage,
                            color = Color(0xFFFECACA),
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onImportClick,
            enabled = !isImporting,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (isImporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Validating JSON...", color = Color.White, fontSize = 13.sp)
            } else {
                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Import client_secret_*.json", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ==============================================================================
// REUSABLE HELPER COMPONENTS
// ==============================================================================

@Composable
private fun ActiveCredentialsCard(
    config: com.clotube.app.data.ByocOAuthConfig,
    onReset: () -> Unit
) {
    Surface(
        color = Color(0xFF064E3B),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0x3310B981)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF34D399),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Custom OAuth Active",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Project: ${config.projectId}",
                color = Color(0xFFE2E8F0),
                fontSize = 12.sp
            )

            val maskedClient = if (config.clientId.length > 25) {
                "${config.clientId.take(14)}...${config.clientId.takeLast(22)}"
            } else {
                config.clientId
            }

            Text(
                text = "Client ID: $maskedClient",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )

            if (config.importedFileName.isNotBlank()) {
                Text(
                    text = "File: ${config.importedFileName}",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onReset,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset to Default OAuth", fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
private fun StepProgressIndicator(
    currentStep: Int,
    totalSteps: Int = 5,
    onStepSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        for (step in 1..totalSteps) {
            val isCurrent = step == currentStep
            val isCompleted = step < currentStep

            val pillBg = when {
                isCurrent -> AccentBlue
                isCompleted -> Color(0xFF065F46)
                else -> SurfaceElevated
            }
            val pillBorder = when {
                isCurrent -> AccentBlue
                isCompleted -> Color(0xFF10B981)
                else -> SurfaceBorder
            }
            val textColor = when {
                isCurrent || isCompleted -> Color.White
                else -> TextTertiary
            }

            Surface(
                shape = CircleShape,
                color = pillBg,
                border = BorderStroke(1.dp, pillBorder),
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable { onStepSelected(step) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isCompleted) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Completed",
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Text(
                            text = "$step",
                            color = textColor,
                            fontSize = 13.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            if (step < totalSteps) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .padding(horizontal = 4.dp)
                        .background(
                            if (step < currentStep) Color(0xFF10B981) else SurfaceBorder
                        )
                )
            }
        }
    }
}

@Composable
private fun SubInstructionRow(
    number: String,
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = CircleShape,
            color = SurfaceElevated,
            border = BorderStroke(1.dp, SurfaceBorder),
            modifier = Modifier.size(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number,
                    color = AccentBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ChecklistItem(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF34D399),
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 12.sp
        )
    }
}

@Composable
fun OneTapCopyChip(
    label: String,
    valueToCopy: String,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = true
) {
    val clipboardManager = LocalClipboardManager.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    var isCopied by remember { mutableStateOf(false) }

    Surface(
        color = SurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (isCopied) Color(0xFF10B981) else SurfaceBorder),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                clipboardManager.setText(AnnotatedString(valueToCopy))
                try {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                } catch (_: Exception) {}
                isCopied = true
                coroutineScope.launch {
                    delay(2000)
                    isCopied = false
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (label.isNotBlank()) {
                    Text(
                        text = label,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = valueToCopy,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                    maxLines = 2,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isCopied) Color(0xFF064E3B) else Color(0x22FFFFFF),
                border = BorderStroke(1.dp, if (isCopied) Color(0xFF10B981) else Color(0x1AFFFFFF))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (isCopied) "Copied" else "Copy",
                        tint = if (isCopied) Color(0xFF34D399) else Color(0xFFCBD5E1),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isCopied) "Copied!" else "Copy",
                        color = if (isCopied) Color(0xFF34D399) else Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun openSafeUrl(uriHandler: androidx.compose.ui.platform.UriHandler, url: String, context: android.content.Context) {
    try {
        uriHandler.openUri(url)
    } catch (_: Exception) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
