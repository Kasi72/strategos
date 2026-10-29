package com.drkkr.strategos.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.drkkr.strategos.ui.theme.StratTokens

/** The same light and dark colours HomeScreen picks, so new screens match without repeating the six lines. */
data class UiPalette(
    val isDark: Boolean, val canvas: Color, val surface: Color, val raised: Color,
    val ink: Color, val muted: Color, val accent: Color, val border: Color
)

@Composable
fun rememberPalette(): UiPalette {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) StratTokens.inkDark else StratTokens.inkLight
    return UiPalette(
        isDark = dark,
        canvas = if (dark) StratTokens.canvasDark else StratTokens.canvasLight,
        surface = if (dark) StratTokens.surfaceDark else StratTokens.surfaceLight,
        raised = if (dark) StratTokens.raisedDark else StratTokens.raisedLight,
        ink = ink,
        muted = ink.copy(alpha = 0.62f),
        accent = if (dark) StratTokens.accentDark else StratTokens.accentLight,
        border = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.07f)
    )
}
