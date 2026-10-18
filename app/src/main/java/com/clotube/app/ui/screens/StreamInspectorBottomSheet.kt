package com.clotube.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.app.model.DriveVideoFile
import com.clotube.app.ui.theme.BackgroundDeep
import com.clotube.app.ui.theme.BorderOutline
import com.clotube.app.ui.theme.BottomSheetShape
import com.clotube.app.ui.theme.CardContainer
import com.clotube.app.ui.theme.PillShape
import com.clotube.app.ui.theme.PrimaryAccent
import com.clotube.app.ui.theme.SecondaryAccent
import com.clotube.app.ui.theme.SurfaceDefault
import com.clotube.app.ui.theme.SurfaceElevated
import com.clotube.app.ui.theme.TextHighEmphasis
import com.clotube.app.ui.theme.TextMuted
import com.clotube.app.ui.theme.VideoCardShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamInspectorBottomSheet(
    video: DriveVideoFile,
    isHardwareAccelerationEnabled: Boolean,
    onHardwareAccelerationChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onPlayFullQuality: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = BottomSheetShape,
        containerColor = SurfaceDefault,
        dragHandle = {
            // Styled Dark Cinema Drag Handle
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(44.dp)
                    .height(4.dp)
                    .background(BorderOutline, PillShape)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Header with Title and Format Extension
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextHighEmphasis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "MIME: ${video.mimeType} • Size: ${video.sizeFormatted} • Duration: ${video.durationFormatted}",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }

                Box(
                    modifier = Modifier
                        .background(PrimaryAccent.copy(alpha = 0.15f), PillShape)
                        .border(1.dp, PrimaryAccent, PillShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (video.resolutionBadge.contains("4K", ignoreCase = true) || video.width >= 3840) "DIRECT 4K" else "DIRECT RAW",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SecondaryAccent
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2x2 Technical Specs Grid
            Text(
                text = "STREAM TECHNICAL SPECIFICATIONS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = TextMuted
            )

            Spacer(modifier = Modifier.height(10.dp))

            val resolutionDisplay = buildString {
                if (video.width > 0 && video.height > 0) {
                    append("${video.width} x ${video.height}")
                    if (video.resolutionBadge.isNotBlank()) {
                        append(" (${video.resolutionBadge})")
                    }
                } else {
                    append(video.resolutionBadge.ifBlank { "HD" })
                }
                if (video.fpsBadge.isNotBlank()) {
                    append(" @ ${video.fpsBadge}")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TechSpecTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.HighQuality,
                    label = "Resolution",
                    value = resolutionDisplay
                )
                TechSpecTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Speed,
                    label = "Bitrate",
                    value = video.bitrateFormatted
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TechSpecTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Videocam,
                    label = "Codec",
                    value = video.codec
                )
                TechSpecTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Audiotrack,
                    label = "Audio Profile",
                    value = video.audioProfile
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Hardware Acceleration Toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = VideoCardShape,
                colors = CardDefaults.cardColors(containerColor = CardContainer),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderOutline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(SurfaceDefault, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = "Hardware Acceleration",
                                tint = SecondaryAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Hardware Acceleration",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextHighEmphasis
                            )
                            Text(
                                text = "MediaCodec Hardware Decoder",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }

                    Switch(
                        checked = isHardwareAccelerationEnabled,
                        onCheckedChange = onHardwareAccelerationChanged,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextHighEmphasis,
                            checkedTrackColor = PrimaryAccent,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = SurfaceElevated
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Primary Action: "Play in Full Quality"
            Button(
                onClick = onPlayFullQuality,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryAccent,
                    contentColor = TextHighEmphasis
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = TextHighEmphasis,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Play in Full Quality (Raw 4K)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TechSpecTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String
) {
    Card(
        modifier = modifier,
        shape = VideoCardShape,
        colors = CardDefaults.cardColors(containerColor = CardContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderOutline)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = SecondaryAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextHighEmphasis
            )
        }
    }
}
