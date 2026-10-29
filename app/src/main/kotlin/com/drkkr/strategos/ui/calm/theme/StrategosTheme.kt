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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
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

// Redesign palette: warm-neutral grouped canvas, a single iris accent, muted pastel semantics.
private val Light = CalmColors(
    ground = Color(0xFFEFEDF3),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFF000000).copy(alpha = 0.06f),
    ink = Color(0xFF17171C),
    ink2 = Color(0xFF6B6B75),
    ink3 = Color(0xFF9A9AA6),
    line = Color(0xFF3C3C43).copy(alpha = 0.10f),
    action = Color(0xFF5B57E0),
    actionText = Color(0xFFFFFFFF),
    onAction = Color(0xFF4B47C9),
    selected = Color(0xFF5B57E0).copy(alpha = 0.12f),
    selectedText = Color(0xFF4B47C9),
    selectedBorder = Color(0xFF5B57E0).copy(alpha = 0.30f),
    coach = Color(0xFFFFFFFF),
    coachInk = Color(0xFF17171C),
    trust = Color(0xFF2E7D53),
    tension = Color(0xFFD1453B),
    rigor = Color(0xFF9A6713),
    rigorGraphic = Color(0xFFE0A23C),
    paper = Color(0xFFEFEDF3),
    paper2 = Color(0xFFFFFFFF),
    coachSkeleton = Color(0xFFDAD8F2),
    yoursBg = Color(0xFFE6E5FB),
    inferredBg = Color(0xFFE0A23C).copy(alpha = 0.18f),
    inferredFg = Color(0xFF9A6713),
    error = Color(0xFFD1453B),
    errorBg = Color(0xFFFBE9E7),
    isDark = false
)

private val Dark = CalmColors(
    ground = Color(0xFF09090C),
    card = Color(0xFF1A1A1E),
    cardBorder = Color(0xFFFFFFFF).copy(alpha = 0.07f),
    ink = Color(0xFFF7F7FA),
    ink2 = Color(0xFFB4B4C0),
    ink3 = Color(0xFF8A8A96),
    line = Color(0xFF8A8A96).copy(alpha = 0.22f),
    action = Color(0xFF8A86FF),
    actionText = Color(0xFFFFFFFF),
    onAction = Color(0xFF9D99FF),
    selected = Color(0xFF8A86FF).copy(alpha = 0.20f),
    selectedText = Color(0xFFBFBCFF),
    selectedBorder = Color(0xFF8A86FF).copy(alpha = 0.40f),
    coach = Color(0xFF1A1A1E),
    coachInk = Color(0xFFF7F7FA),
    trust = Color(0xFF67C495),
    tension = Color(0xFFFF6B63),
    rigor = Color(0xFFF0B64E),
    rigorGraphic = Color(0xFFF0B64E),
    paper = Color(0xFF09090C),
    paper2 = Color(0xFF1A1A1E),
    coachSkeleton = Color(0xFF2E2E3A),
    yoursBg = Color(0xFF2A2A44),
    inferredBg = Color(0xFFF0B64E).copy(alpha = 0.18f),
    inferredFg = Color(0xFFF0B64E),
    error = Color(0xFFFF6B63),
    errorBg = Color(0xFF3A1F1C),
    isDark = true
)

/** Colour for tappable text on the light surfaces (actionText is the white that sits ON an action-coloured button). */
val CalmColors.link: Color get() = if (isDark) onAction else action

private val LocalCalmColors = staticCompositionLocalOf { Light }

/** The sky gradient drawn once in the scaffold as the app background (DESIGN.md §2 "ground"). */
val CalmColors.groundBrush: Brush
    get() = if (isDark) {
        Brush.linearGradient(listOf(Color(0xFF09090C), Color(0xFF09090C)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFEFEDF3), Color(0xFFEFEDF3)))
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
    display = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
    title = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    subtitle = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp),
    body = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    caption = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    pill = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    coachLead = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 25.sp),
    coachBody = TextStyle(fontFamily = CalmFonts.Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
)

private val LocalCalmType = staticCompositionLocalOf { Type }

/** The serif wordmark style ("Strategos"), Source Serif 4 22/600 on the boards. */
val CalmType.wordmark: TextStyle get() = title.copy(fontFamily = CalmFonts.Serif)

/**
 * The lifted glass card: a soft cool-blue shadow, the glass fill, and a bright 1dp border
 * (DESIGN.md §2 "card"). Use everywhere a card, composer or dock surface is drawn.
 */
fun Modifier.calmCard(colors: CalmColors, shape: Shape = SquircleShape(CalmDimens.cardRadius)): Modifier =
    this.shadow(elevation = 1.5.dp, shape = shape, ambientColor = Color(0x14000000), spotColor = Color(0x14000000))
        .background(colors.card.compositeOver(colors.ground), shape)
        .border(0.5.dp, colors.cardBorder, shape)

/**
 * iOS-style continuous-corner squircle (true superellipse, n≈5) as a Compose [Shape].
 * Used for cards, buttons, the tab bar highlight and icon tiles so corners read as one system.
 */
class SquircleShape(private val radius: Dp, private val n: Float = 5f, private val steps: Int = 14) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width; val h = size.height
        val r = minOf(with(density) { radius.toPx() }, w / 2f, h / 2f)
        if (r <= 0f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
        val p = Path()
        val sfn = { v: Double -> sign(v) * abs(v).pow(2.0 / n) }
        fun corner(cx: Float, cy: Float, a0: Double, a1: Double, start: Boolean) {
            for (i in 0..steps) {
                val a = Math.toRadians(a0 + (a1 - a0) * i / steps)
                val x = cx + (r * sfn(cos(a))).toFloat()
                val y = cy + (r * sfn(sin(a))).toFloat()
                if (start && i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
        }
        corner(w - r, r, -90.0, 0.0, true)
        corner(w - r, h - r, 0.0, 90.0, false)
        corner(r, h - r, 90.0, 180.0, false)
        corner(r, r, 180.0, 270.0, false)
        p.close()
        return Outline.Generic(p)
    }
}

/** Convenience squircle factory for components. */
fun squircle(radius: Dp): Shape = SquircleShape(radius)

/** Shape and spacing constants named in DESIGN.md §2 (the only literal dp/sp screens may use). */
object CalmDimens {
    val gutter = 16.dp
    val gridStep = 8.dp
    val cardRadius = 16.dp
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
