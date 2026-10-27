package com.drkkr.strategos.ui.calm.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The calm surface theme for the redesign (DESIGN.md §1–§2). Lives in its own package so it builds
 * alongside the old `ui.theme.StrategosTheme` while screens migrate; the X-ray gets its own SheetTheme.
 *
 * Fonts: the boards use Figtree / Source Serif 4 / IBM Plex Mono. Those are not bundled and we add no
 * font dependency, so we stand in with the platform SansSerif / Serif / Monospace families and keep
 * the sizes and weights exact. Swapping in the real faces is a later, isolated change.
 */

@Immutable
data class CalmColors(
    val ground: Color,
    val card: Color,
    val cardBorder: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val action: Color,
    val actionText: Color,
    val onAction: Color,
    val selected: Color,
    val selectedText: Color,
    val selectedBorder: Color,
    val coach: Color,
    val coachInk: Color,
    val trust: Color,
    val tension: Color,
    /** Engine-only amber ("rigor"): the pill, the model strip, and the X-ray may use it — nothing else. */
    val rigor: Color,
    val rigorGraphic: Color,
    /** Quiet surfaces from the prototype: chips (paper2), coach skeleton, "yours" badge, inferred badge, sheets. */
    val paper: Color,
    val paper2: Color,
    val coachSkeleton: Color,
    val yoursBg: Color,
    val inferredBg: Color,
    val inferredFg: Color,
    val error: Color,
    val errorBg: Color,
    val isDark: Boolean
)

private val Light = CalmColors(
    ground = Color(0xFFF3F7FC),
    card = Color(0xFFFFFFFF).copy(alpha = 0.74f),
    cardBorder = Color(0xFFFFFFFF).copy(alpha = 0.90f),
    ink = Color(0xFF0F1B2D),
    ink2 = Color(0xFF33445E),
    ink3 = Color(0xFF526079),
    line = Color(0xFF284070).copy(alpha = 0.16f),
    action = Color(0xFF2150C8),
    actionText = Color(0xFFFFFFFF),
    onAction = Color(0xFF123A8F),
    selected = Color(0xFFCADBFA),
    selectedText = Color(0xFF123A8F),
    selectedBorder = Color(0xFF8CAEEB),
    coach = Color(0xFFD9E6FC),
    coachInk = Color(0xFF0F2347),
    trust = Color(0xFF07644C),
    tension = Color(0xFFA93509),
    rigor = Color(0xFF7A4F0C),
    rigorGraphic = Color(0xFFB27A10),
    paper = Color(0xFFF3F7FC),
    paper2 = Color(0xFFFFFFFF).copy(alpha = 0.58f),
    coachSkeleton = Color(0xFFB8CFF4),
    yoursBg = Color(0xFFE3EAF6),
    inferredBg = Color(0xFFF6E6BC),
    inferredFg = Color(0xFF7A4F0C),
    error = Color(0xFF9A3412),
    errorBg = Color(0xFFFCE5DB),
    isDark = false
)

private val Dark = CalmColors(
    ground = Color(0xFF0E1626),
    card = Color(0xFF162238),
    cardBorder = Color(0xFFFFFFFF).copy(alpha = 0.08f),
    ink = Color(0xFFEAF0FB),
    ink2 = Color(0xFFC3CFE4),
    ink3 = Color(0xFF93A3C0),
    line = Color(0xFF93A3C0).copy(alpha = 0.18f),
    action = Color(0xFF3F6CE0),
    actionText = Color(0xFFFFFFFF),
    onAction = Color(0xFF9DB8FF),
    selected = Color(0xFF1F3F7A),
    selectedText = Color(0xFFCADBFA),
    selectedBorder = Color(0xFF3F6CE0),
    coach = Color(0xFF172A4D),
    coachInk = Color(0xFFDCE8FF),
    trust = Color(0xFF8EC9B5),
    tension = Color(0xFFFB9A6C),
    rigor = Color(0xFFE6B450),
    rigorGraphic = Color(0xFFE6B450),
    paper = Color(0xFF0E1626),
    paper2 = Color(0xFF162238).copy(alpha = 0.68f),
    coachSkeleton = Color(0xFF264275),
    yoursBg = Color(0xFFFFFFFF).copy(alpha = 0.10f),
    inferredBg = Color(0xFFE6B450).copy(alpha = 0.18f),
    inferredFg = Color(0xFFE6B450),
    error = Color(0xFFFB9A6C),
    errorBg = Color(0xFF3A2218),
    isDark = true
)

/** Colour for tappable text on the light surfaces (actionText is the white that sits ON an action-coloured button). */
val CalmColors.link: Color get() = if (isDark) onAction else action

private val LocalCalmColors = staticCompositionLocalOf { Light }

/** The sky gradient drawn once in the scaffold as the app background (DESIGN.md §2 "ground"). */
val CalmColors.groundBrush: Brush
    get() = if (isDark) {
        Brush.linearGradient(listOf(Color(0xFF0E1626), Color(0xFF13203A), Color(0xFF141A30)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFD6E6F8), Color(0xFFE9EFFA), Color(0xFFDDE3F7)))
    }

/** Type ramp from DESIGN.md §2. Coach styles are serif; everything else is the UI sans. */
@Immutable
data class CalmType(
    val display: TextStyle,
    val title: TextStyle,
    val subtitle: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val pill: TextStyle,
    val coachLead: TextStyle,
    val coachBody: TextStyle
)

private val Type = CalmType(
    display = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    title = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    subtitle = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 24.sp),
    body = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    caption = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    pill = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    coachLead = TextStyle(fontFamily = CalmFonts.Serif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),
    coachBody = TextStyle(fontFamily = CalmFonts.Serif, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 27.sp)
)

private val LocalCalmType = staticCompositionLocalOf { Type }

/** The serif wordmark style ("Strategos"), Source Serif 4 22/600 on the boards. */
val CalmType.wordmark: TextStyle get() = title.copy(fontFamily = CalmFonts.Serif)

/**
 * The lifted glass card: a soft cool-blue shadow, the glass fill, and a bright 1dp border
 * (DESIGN.md §2 "card"). Use everywhere a card, composer or dock surface is drawn.
 */
fun Modifier.calmCard(colors: CalmColors, shape: Shape = RoundedCornerShape(CalmDimens.cardRadius)): Modifier =
    this.shadow(elevation = 10.dp, shape = shape, ambientColor = Color(0xFF18306A), spotColor = Color(0xFF18306A))
        // Opaque base first: a translucent fill lets the drop shadow show through as an inner rectangle.
        .background(colors.card.compositeOver(colors.ground), shape)
        .border(1.dp, colors.cardBorder, shape)

/** Shape and spacing constants named in DESIGN.md §2 (the only literal dp/sp screens may use). */
object CalmDimens {
    val gutter = 16.dp
    val gridStep = 8.dp
    val cardRadius = 20.dp
    val cardPadding = 16.dp
    val sheetTopRadius = 28.dp
    val cellHeight = 48.dp
    val cellRadius = 10.dp
    val rowMinHeight = 56.dp
    val chipHeight = 32.dp
    val touchTarget = 48.dp
    val fabHeight = 56.dp
}

/** Access point for the calm tokens inside a `StrategosTheme { }`. */
object Calm {
    val colors: CalmColors @Composable get() = LocalCalmColors.current
    val type: CalmType @Composable get() = LocalCalmType.current
    val dimens = CalmDimens
}

private fun colorScheme(c: CalmColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.action, onPrimary = c.actionText, background = c.ground, onBackground = c.ink,
        surface = c.card, onSurface = c.ink, surfaceVariant = c.card, onSurfaceVariant = c.ink2,
        secondaryContainer = c.selected, onSecondaryContainer = c.selectedText, error = c.tension, outline = c.line
    )
} else {
    lightColorScheme(
        primary = c.action, onPrimary = c.actionText, background = c.ground, onBackground = c.ink,
        surface = c.card, onSurface = c.ink, surfaceVariant = c.card, onSurfaceVariant = c.ink2,
        secondaryContainer = c.selected, onSecondaryContainer = c.selectedText, error = c.tension, outline = c.line
    )
}

@Composable
fun StrategosTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) Dark else Light
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCalmColors provides colors,
        LocalCalmType provides Type
    ) {
        MaterialTheme(
            colorScheme = colorScheme(colors),
            typography = Typography(
                displayMedium = Type.display, titleLarge = Type.title, titleMedium = Type.subtitle,
                bodyLarge = Type.body, labelMedium = Type.caption, labelSmall = Type.pill
            ),
            content = content
        )
    }
}
