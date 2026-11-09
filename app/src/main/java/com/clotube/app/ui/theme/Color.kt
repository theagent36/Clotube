package com.clotube.app.ui.theme

import androidx.compose.ui.graphics.Color

// ====================================================================
// Obsidian Cinema Palette (Apple TV+ / HBO Style)
// ====================================================================
val CanvasDeep = Color(0xFF08090D)        // Pure dark void background
val SurfaceDefault = Color(0xFF10131B)    // Card backdrops, list tiles
val SurfaceElevated = Color(0xFF181C26)   // Search bar, chip surfaces, dialogs
val SurfaceBorder = Color(0x14FFFFFF)     // Subtle 8% white hairline borders
val AccentPrimary = Color(0xFFE2E8F0)     // High-contrast off-white CTA
val AccentBlue = Color(0xFF3877EE)        // Refined cobalt blue for active states
val AccentGlow = Color(0x333877EE)        // Ambient glow for active items
val TextPrimary = Color(0xFFFFFFFF)       // High-emphasis titles
val TextSecondary = Color(0xFF94A3B8)     // Slate metadata, durations, years
val TextTertiary = Color(0xFF64748B)      // Muted placeholders and subtle icons

// ====================================================================
// Backward-Compatibility Aliases (Ensures zero breakage across existing screens)
// ====================================================================
val BackgroundDeep = CanvasDeep
val PrimaryAccent = AccentBlue
val SecondaryAccent = AccentBlue          // Replaced loud neon cyan (#00E5FF) with refined cobalt blue
val TextHighEmphasis = TextPrimary
val TextMuted = TextSecondary
val BorderOutline = SurfaceBorder
val CardContainer = SurfaceDefault

// Functional accents
val BadgeBackground = Color(0xE608090D)
val BadgeBorder = Color(0x24FFFFFF)
val ScrimDark = Color(0xCC000000)
val LiveFpsGreen = Color(0xFF00E676)
val LiveBitrateBlue = AccentBlue
