package com.clotube.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.clotube.app.navigation.ClotubeNavHost
import com.clotube.app.ui.theme.ClotubeTheme
import com.clotube.app.viewmodel.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val videoViewModel: VideoViewModel by viewModels()

    private val authRecoveryLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            android.util.Log.i("MainActivity", "User granted permissions! Refreshing library and syncing history...")
            videoViewModel.refreshCatalog(forceSync = true)
            lifecycleScope.launch(Dispatchers.IO) {
                videoViewModel.playbackHistoryManager.syncFromCloud()
            }
        } else {
            android.util.Log.w("MainActivity", "User declined or dismissed permissions dialog")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start background sync guardian service
        try {
            startService(android.content.Intent(this, com.clotube.app.data.ClotubeSyncService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start ClotubeSyncService: ${e.message}", e)
        }

        // Bind recovery launcher so UserRecoverableAuthException displays the consent checkboxes
        videoViewModel.tokenRepository.recoveryIntentLauncher = { recoveryIntent ->
            authRecoveryLauncher.launch(recoveryIntent)
        }

        setContent {
            ClotubeTheme {
                // State controlling whether splash boot animation or main host is displayed
                var isBooting by remember { mutableStateOf(true) }

                Crossfade(
                    targetState = isBooting,
                    animationSpec = tween(durationMillis = 400),
                    label = "SplashToMainTransition"
                ) { booting ->
                    if (booting) {
                        ClotubeBootScreen(
                            iconResId = R.drawable.ic_clotube,
                            onBootComplete = {
                                isBooting = false
                            }
                        )
                    } else {
                        ClotubeNavHost(videoViewModel = videoViewModel)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        videoViewModel.vlcPlayerManager.onAppResume()
    }

    override fun onPause() {
        super.onPause()
        videoViewModel.vlcPlayerManager.onAppPause()
        videoViewModel.pauseActivePlayer()
    }

    override fun onStop() {
        super.onStop()
        // 1. Enforce global pause
        videoViewModel.vlcPlayerManager.onAppPause()
        videoViewModel.pauseActivePlayer()

        // 2. When user presses Home or enters Recents, start immediate sync
        try {
            videoViewModel.playbackHistoryManager.backupImmediately()
        } catch (ignored: Exception) {}

        // 3. Schedule WorkManager persistent sync to push watch history to Drive
        videoViewModel.scheduleBackgroundDriveSync(applicationContext)

        // Reset window brightness back to system default
        val lp = window.attributes
        lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    fun handleGoogleSignInResult(data: android.content.Intent?) {
        try {
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(data)
            val account: com.google.android.gms.auth.api.signin.GoogleSignInAccount? =
                task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            if (account != null) {
                val hasRead = com.google.android.gms.auth.api.signin.GoogleSignIn.hasPermissions(
                    account,
                    com.clotube.app.auth.GoogleAuthManager.DRIVE_READONLY_SCOPE
                )
                val hasFile = com.google.android.gms.auth.api.signin.GoogleSignIn.hasPermissions(
                    account,
                    com.clotube.app.auth.GoogleAuthManager.DRIVE_FILE_SCOPE
                )

                if (!hasRead || !hasFile) {
                    // Force Android to present the checkboxes!
                    com.google.android.gms.auth.api.signin.GoogleSignIn.requestPermissions(
                        this,
                        com.clotube.app.auth.GoogleAuthManager.REQUEST_CODE_DRIVE_PERMISSIONS,
                        account,
                        com.clotube.app.auth.GoogleAuthManager.DRIVE_READONLY_SCOPE,
                        com.clotube.app.auth.GoogleAuthManager.DRIVE_FILE_SCOPE
                    )
                } else {
                    // All permissions granted, proceed to Home
                    videoViewModel.onSignInSuccess(account)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Google Sign-In failed", e)
            videoViewModel.handleSignInResult(data)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == com.clotube.app.auth.GoogleAuthManager.REQUEST_CODE_DRIVE_PERMISSIONS) {
            val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(this)
            if (account != null && com.clotube.app.auth.GoogleAuthManager.hasAllDrivePermissions(this, account)) {
                videoViewModel.onSignInSuccess(account)
            } else {
                // User unchecked or cancelled permissions
                android.widget.Toast.makeText(
                    this,
                    "Drive permissions are required to use Clotube",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                videoViewModel.onPermissionsDenied()
            }
        }
    }
}

@Composable
fun ClotubeBootScreen(
    iconResId: Int = R.drawable.ic_clotube,
    onBootComplete: () -> Unit = {}
) {
    val iconScale = remember { Animatable(0.2f) }
    val iconAlpha = remember { Animatable(0f) }

    val letters = remember { listOf('l', 'o', 't', 'u', 'b', 'e') }
    val letterAlphas = remember { letters.map { Animatable(0f) } }
    val letterTranslations = remember { letters.map { Animatable(-16f) } }

    val glyphSize = 100.dp
    val textSpacing = (-6).dp

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val textStyle = TextStyle(
        color = Color.White,
        fontSize = 58.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.SansSerif,
        letterSpacing = (-1).sp
    )

    // Calculate shift required so that in Phase 1, the "C" glyph is positioned precisely at the center of the screen
    val centerShiftPx = remember(density) {
        val textWidth = textMeasurer.measure(
            text = "lotube",
            style = textStyle
        ).size.width.toFloat()
        val spacerPx = with(density) { textSpacing.toPx() }
        (textWidth + spacerPx) / 2f
    }

    val rowShiftX = remember { Animatable(centerShiftPx) }

    LaunchedEffect(Unit) {
        // 0.0s – 0.5s: The isolated "C" zooms in from the center of the screen with a punchy spring curve (scale: 0.2f -> 1.0f, alpha: 0f -> 1f)
        launch {
            iconAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
            )
        }
        iconScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )

        // Settle at 0.5s mark
        delay(60)

        // 0.5s – 1.0s: As the "C" settles, reveal l, o, t, u, b, e sequentially letter-by-letter with a 70ms stagger delay.
        // Each letter fades in with a quick horizontal slide (translationX: -16f -> 0f).
        launch {
            rowShiftX.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 440,
                    easing = FastOutSlowInEasing
                )
            )
        }

        letters.indices.forEach { index ->
            launch {
                launch {
                    letterAlphas[index].animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
                    )
                }
                launch {
                    letterTranslations[index].animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
                    )
                }
            }
            delay(70)
        }

        // Allow final letter animation to settle
        delay(80)

        // 1.0s – 1.6s: Hold the unified "Clotube" wordmark before flipping isBooting = false
        delay(600)
        onBootComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07090E)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer {
                translationX = rowShiftX.value
            }
        ) {
            // Isolated Floating "C" with Soft Radial Fade
            Box(
                modifier = Modifier
                    .size(glyphSize) // Proportional visual height to match "lotube"
                    .graphicsLayer {
                        scaleX = iconScale.value
                        scaleY = iconScale.value
                        alpha = iconAlpha.value
                        // Required so DstIn blends against this offscreen layer rather than the window background
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        // 1. Draw the actual logo asset
                        drawContent()
                        // 2. Erase outer perimeter by applying a radial alpha mask
                        drawRect(
                            brush = Brush.radialGradient(
                                0.0f to Color.White,        // 100% visible at the center "C"
                                0.72f to Color.White,       // Retain full vibrant gradient colors
                                0.98f to Color.Transparent, // Softly feather into zero alpha before reaching the edges
                                center = center,
                                radius = size.minDimension / 2f
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = iconResId),
                    contentDescription = "Clotube C",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(textSpacing))

            // Trailing "lotube" characters revealed one by one
            Row(verticalAlignment = Alignment.CenterVertically) {
                letters.forEachIndexed { index, char ->
                    Text(
                        text = char.toString(),
                        color = Color.White,
                        fontSize = 58.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = (-1).sp,
                        modifier = Modifier.graphicsLayer {
                            alpha = letterAlphas[index].value
                            translationX = letterTranslations[index].value
                        }
                    )
                }
            }
        }
    }
}