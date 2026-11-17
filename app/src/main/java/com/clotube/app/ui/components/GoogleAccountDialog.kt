package com.clotube.app.ui.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clotube.app.data.SavedAccountProfile

/**
 * Obsidian Cinema styled Google Account & Multi-Profile Switcher Dialog.
 * Allows users to inspect their active profile, switch between isolated Google Drive
 * accounts with zero data bleeding, add a new Google account, or manage app settings.
 */
@Composable
fun GoogleAccountDialog(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    userDisplayName: String,
    userEmail: String,
    userPhotoUrl: String?,
    savedProfiles: List<SavedAccountProfile> = emptyList(),
    onSwitchProfile: (String) -> Unit = {},
    onAddAccountClick: () -> Unit = {},
    onRefreshClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onSignOutClick: () -> Unit = {},
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
                .background(Color.Black.copy(alpha = 0.68f))
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
                    .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 24.dp)
                    .fillMaxWidth()
                    .widthIn(max = 400.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Absorb clicks inside dialog */ },
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF13151B),
                border = BorderStroke(1.dp, Color(0xFF262A36)),
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Bar: Header label & Close Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Google Account",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp
                        )

                        IconButton(
                            onClick = onDismissRequest,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Active User Profile Header Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF1A1E29),
                        border = BorderStroke(1.dp, Color(0xFF2A3142)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!userPhotoUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = userPhotoUrl,
                                    contentDescription = "Profile Photo",
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.AccountCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF3877EE),
                                    modifier = Modifier.size(46.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = userDisplayName.ifBlank { "Clotube User" },
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )

                                    Spacer(modifier = Modifier.width(6.dp))

                                    Surface(
                                        color = Color(0xFF064E3B),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "Active",
                                            color = Color(0xFF34D399),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = userEmail.ifBlank { "account@gmail.com" },
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Saved Accounts Section
                    val otherProfiles = savedProfiles.filter {
                        !it.email.equals(userEmail, ignoreCase = true)
                    }

                    if (otherProfiles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SWITCH ACCOUNT",
                                color = Color(0xFF64748B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF181C26))
                                .border(BorderStroke(1.dp, Color(0xFF222838)), RoundedCornerShape(14.dp))
                        ) {
                            otherProfiles.forEachIndexed { index, profile ->
                                Surface(
                                    onClick = {
                                        onDismissRequest()
                                        onSwitchProfile(profile.email)
                                    },
                                    color = Color.Transparent,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (!profile.photoUrl.isNullOrBlank()) {
                                            AsyncImage(
                                                model = profile.photoUrl,
                                                contentDescription = profile.displayName,
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Outlined.AccountCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF94A3B8),
                                                modifier = Modifier.size(34.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = profile.displayName.ifBlank { "User" },
                                                color = Color(0xFFE2E8F0),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = profile.email,
                                                color = Color(0xFF64748B),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                if (index < otherProfiles.size - 1) {
                                    HorizontalDivider(color = Color(0xFF222838), thickness = 0.8.dp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Add Another Account Item
                    AccountDialogActionItem(
                        icon = Icons.Default.PersonAdd,
                        label = "Add another account",
                        iconTint = Color(0xFF3877EE),
                        textColor = Color(0xFF3877EE),
                        onClick = {
                            onDismissRequest()
                            onAddAccountClick()
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = Color(0xFF222838), thickness = 0.8.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Menu Action 1: Refresh Library
                    AccountDialogActionItem(
                        icon = Icons.Default.Refresh,
                        label = "Refresh Library",
                        iconTint = Color(0xFF3877EE),
                        onClick = {
                            onDismissRequest()
                            onRefreshClick()
                        }
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    // Menu Action 2: Settings
                    AccountDialogActionItem(
                        icon = Icons.Default.Settings,
                        label = "Settings",
                        iconTint = Color(0xFF94A3B8),
                        onClick = {
                            onDismissRequest()
                            onSettingsClick()
                        }
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    // Menu Action 3: Sign out
                    AccountDialogActionItem(
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        label = "Sign out",
                        iconTint = Color(0xFFEF4444),
                        textColor = Color(0xFFEF4444),
                        onClick = {
                            onDismissRequest()
                            onSignOutClick()
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = Color(0xFF222838), thickness = 0.8.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Legal Footer
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Privacy Policy",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "  •  ",
                            color = Color(0xFF334155),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Terms of Service",
                            color = Color(0xFF64748B),
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
    savedProfiles: List<SavedAccountProfile> = emptyList(),
    onSwitchProfile: (String) -> Unit = {},
    onAddAccountClick: () -> Unit = {},
    onRefreshClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onSignOutClick: () -> Unit = {},
    modifier: Modifier = Modifier
) = GoogleAccountDialog(
    isOpen = isOpen,
    onDismissRequest = onDismissRequest,
    userDisplayName = userDisplayName,
    userEmail = userEmail,
    userPhotoUrl = userPhotoUrl?.toString(),
    savedProfiles = savedProfiles,
    onSwitchProfile = onSwitchProfile,
    onAddAccountClick = onAddAccountClick,
    onRefreshClick = onRefreshClick,
    onSettingsClick = onSettingsClick,
    onSignOutClick = onSignOutClick,
    modifier = modifier
)

@Composable
private fun AccountDialogActionItem(
    icon: ImageVector,
    label: String,
    iconTint: Color = Color(0xFF94A3B8),
    textColor: Color = Color(0xFFE2E8F0),
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
                .padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
