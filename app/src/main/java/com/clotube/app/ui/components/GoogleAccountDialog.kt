package com.clotube.app.ui.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun GoogleAccountDialog(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    userDisplayName: String,
    userEmail: String,
    userPhotoUrl: String?,
    onRefreshClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn(animationSpec = tween(durationMillis = 200)),
        exit = fadeOut(animationSpec = tween(durationMillis = 180))
    ) {
        BackHandler(enabled = isOpen) {
            onDismissRequest()
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismissRequest() },
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                modifier = Modifier
                    .animateEnterExit(
                        enter = scaleIn(
                            initialScale = 0.82f,
                            transformOrigin = TransformOrigin(0.88f, 0.05f),
                            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(durationMillis = 200)),
                        exit = scaleOut(
                            targetScale = 0.85f,
                            transformOrigin = TransformOrigin(0.88f, 0.05f),
                            animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing)
                        ) + fadeOut(animationSpec = tween(durationMillis = 160))
                    )
                    .statusBarsPadding()
                    .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
                    .widthIn(max = 390.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Absorb clicks inside dialog */ },
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF1E1F24),
                border = BorderStroke(1.dp, Color(0xFF2E3138)),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Bar: Close Button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                    ) {
                        IconButton(
                            onClick = onDismissRequest,
                            modifier = Modifier
                                .size(32.dp)
                                .align(Alignment.CenterEnd)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color(0xFFC4C7C5),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // User Profile Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!userPhotoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = userPhotoUrl,
                                contentDescription = "Profile Photo",
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.AccountCircle,
                                contentDescription = null,
                                tint = Color(0xFFA8C7FA),
                                modifier = Modifier.size(48.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = userDisplayName.ifBlank { "Clotube User" },
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = Color(0xFF263238),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "Pro",
                                        color = Color(0xFF80D8FF),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = userEmail.ifBlank { "account@gmail.com" },
                                color = Color(0xFF9AA0A6),
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = Color(0xFF2F3238), thickness = 0.8.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Menu Action 1: Refresh Library
                    AccountDialogActionItem(
                        icon = Icons.Default.Refresh,
                        label = "Refresh Library",
                        iconTint = Color(0xFF8AB4F8),
                        onClick = {
                            onDismissRequest()
                            onRefreshClick()
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Menu Action 2: Settings
                    AccountDialogActionItem(
                        icon = Icons.Default.Settings,
                        label = "Settings",
                        iconTint = Color(0xFFC4C7C5),
                        onClick = {
                            onDismissRequest()
                            onSettingsClick()
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Menu Action 3: Sign out
                    AccountDialogActionItem(
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        label = "Sign out",
                        iconTint = Color(0xFFF28B82),
                        textColor = Color(0xFFF28B82),
                        onClick = {
                            onDismissRequest()
                            onSignOutClick()
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color(0xFF2F3238), thickness = 0.8.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Legal Footer
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Privacy Policy",
                            color = Color(0xFF9AA0A6),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "  •  ",
                            color = Color(0xFF5F6368),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Terms of Service",
                            color = Color(0xFF9AA0A6),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Convenience overload accepting [Uri] for photoUrl.
 */
@Composable
fun GoogleAccountDialog(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    userDisplayName: String,
    userEmail: String,
    userPhotoUrl: Uri?,
    onRefreshClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier
) = GoogleAccountDialog(
    isOpen = isOpen,
    onDismissRequest = onDismissRequest,
    userDisplayName = userDisplayName,
    userEmail = userEmail,
    userPhotoUrl = userPhotoUrl?.toString(),
    onRefreshClick = onRefreshClick,
    onSettingsClick = onSettingsClick,
    onSignOutClick = onSignOutClick,
    modifier = modifier
)

@Composable
private fun AccountDialogActionItem(
    icon: ImageVector,
    label: String,
    iconTint: Color = Color(0xFFC4C7C5),
    textColor: Color = Color(0xFFE3E3E3),
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
