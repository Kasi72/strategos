package com.drkkr.strategos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// iOS-inspired design language: clean whites, Cupertino blues, SF-like system font fallback.

// Light palette — soft tactile intelligence
private val StratBlue = Color(0xFF2367D1)          // strategic accent blue
private val StratBlue2 = Color(0xFF1850A8)
private val StratBlueContainer = Color(0xFFE8F0FD)
private val StratCanvas = Color(0xFFF4F3EF)         // warm parchment canvas
private val StratSurface = Color(0xFFFBFAF7)        // warm white surface
private val StratRaised = Color(0xFFFFFFFF)         // raised card
private val StratOnSurface = Color(0xFF1A1D26)      // deep ink
private val StratSecondaryText = Color(0xFF6B7280)
private val StratSeparator = Color(0xFFE2E0DA)
private val StratGreen = Color(0xFF16A34A)
private val StratOrange = Color(0xFFD97706)
private val StratRed = Color(0xFFDC2626)

val LightColors = lightColorScheme(
    primary = StratBlue,
    onPrimary = Color.White,
    primaryContainer = StratBlueContainer,
    onPrimaryContainer = StratBlue2,
    secondary = StratGreen,
    onSecondary = Color.White,
    tertiary = StratOrange,
    onTertiary = Color.White,
    error = StratRed,
    onError = Color.White,
    background = StratCanvas,
    onBackground = StratOnSurface,
    surface = StratSurface,
    onSurface = StratOnSurface,
    surfaceVariant = StratCanvas,
    onSurfaceVariant = StratSecondaryText,
    outline = StratSeparator
)

private val DarkBlue = Color(0xFF3D8BFF)          // Neo electric blue
private val DarkBackground = Color(0xFF0D0F18)    // deep space navy
private val DarkSurface = Color(0xFF1C1F2E)        // elevated glass surface
private val DarkSurface2 = Color(0xFF252839)       // raised card

val DarkColors = darkColorScheme(
    primary = DarkBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A2A4A),
    onPrimaryContainer = Color(0xFF9EC5FF),
    secondary = Color(0xFF1DB954),                 // Neo green
    onSecondary = Color.White,
    tertiary = Color(0xFFFFB340),                  // Neo amber
    onTertiary = Color.Black,
    error = Color(0xFFFF3B30),                     // Neo red
    onError = Color.White,
    background = DarkBackground,
    onBackground = Color(0xFFF0F2FA),
    surface = DarkSurface,
    onSurface = Color(0xFFF0F2FA),
    surfaceVariant = DarkSurface2,
    onSurfaceVariant = Color(0xFF98A2B3),
    outline = Color.White.copy(alpha = 0.10f),
    outlineVariant = Color.White.copy(alpha = 0.06f)
)

val StrategosTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 0.37.sp),
    displayMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    displaySmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, letterSpacing = (-0.41).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = (-0.41).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp, letterSpacing = (-0.41).sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp)
)

object StrategosBrandTokens {
    // Layout
    val pageHorizontalPadding: Dp = 24.dp
    val brandTopPadding: Dp = 28.dp
    val titleToOwnerGap: Dp = 7.dp
    val headerToContentGap: Dp = 24.dp

    // Typography (values — apply via TextStyle at call site)
    val titleSizeLarge = 40.sp
    val titleSizeNarrow = 34.sp   // ≤360dp screens
    val ownerSize = 15.sp

    // Colours — dark primary palette
    val accentBlue = Color(0xFF0A84FF)
    val titleSilver = Color(0xFFF2F4F7)
    val ownerSlate = Color(0xFF98A2B3)
    val graphiteBackground = Color(0xFF090A0D)
    val elevatedSurface = Color(0xFF16181D)
    val divider = Color(0xFF98A2B3).copy(alpha = 0.18f)

    // Emboss layers
    val embossedHighlight = Color.White.copy(alpha = 0.12f)
    val embossedShadow = Color.Black.copy(alpha = 0.50f)
}

object StratTokens {
    // Spacing
    val screenPad = 20.dp
    val cardPadMain = 20.dp
    val cardPadStd = 16.dp
    val sectionGap = 20.dp
    val itemGap = 12.dp

    // Radii
    val radiusMain = 24.dp
    val radiusCard = 18.dp
    val radiusBtn = 16.dp
    val radiusDock = 24.dp
    val radiusPill = 50.dp

    // Nav dock
    val dockHeight = 68.dp
    val dockSideMargin = 20.dp
    val dockBottomMargin = 16.dp
    val dockTotalBottomPad = 68.dp + 16.dp + 16.dp  // height + margin + breathing room

    // Elevation / depth  (used as border alpha tokens)
    val borderSubtle = 0.08f
    val borderMid = 0.12f

    // Light palette direct
    val canvasLight = Color(0xFFF4F3EF)
    val surfaceLight = Color(0xFFFBFAF7)
    val raisedLight = Color(0xFFFFFFFF)
    val inkLight = Color(0xFF1A1D26)
    val accentLight = Color(0xFF2367D1)
    val accentLightBg = Color(0xFFE8F0FD)

    // Dark palette direct
    val canvasDark = Color(0xFF0B0D12)
    val surfaceDark = Color(0xFF151820)
    val raisedDark = Color(0xFF1E2230)
    val inkDark = Color(0xFFF2F4F7)
    val accentDark = Color(0xFF4D8EFF)
    val accentDarkBg = Color(0xFF1A2A4A)

    // Health grid colors
    val healthGreen = Color(0xFF16A34A)
    val healthAmber = Color(0xFFD97706)
    val healthRed = Color(0xFFDC2626)
    val healthBlue = Color(0xFF2367D1)
}

@Composable
fun StrategosTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = StrategosTypography,
        content = content
    )
}
