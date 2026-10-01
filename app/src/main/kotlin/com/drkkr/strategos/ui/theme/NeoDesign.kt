package com.drkkr.strategos.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer

// ─────────────────────────────────────────────────────────────────
//  Neo-Tactile Design Tokens
// ─────────────────────────────────────────────────────────────────
object NeoTokens {
    // Accent family
    val blueCore   = Color(0xFF3D8BFF)
    val blueGlow   = Color(0xFF5BA0FF)
    val blueDeep   = Color(0xFF1A54C8)
    val blueDark   = Color(0xFF0A84FF)

    // Surface family (dark)
    val void       = Color(0xFF080A10)   // deepest background
    val dark0      = Color(0xFF0D0F18)   // canvas
    val dark1      = Color(0xFF14162000) // glass surface base (alpha handled in use)
    val dark2      = Color(0xFF1C1F2E)   // raised surface
    val dark3      = Color(0xFF252839)   // elevated card

    // Highlight / shadow for neumorphism
    val neoHighlight  = Color.White.copy(alpha = 0.10f)
    val neoHighlight2 = Color.White.copy(alpha = 0.16f)
    val neoShadow     = Color.Black.copy(alpha = 0.60f)
    val neoShadow2    = Color.Black.copy(alpha = 0.40f)
    val glassBorder   = Color.White.copy(alpha = 0.13f)
    val glassBorder2  = Color.White.copy(alpha = 0.06f)

    // Status glow colors
    val green  = Color(0xFF1DB954)
    val amber  = Color(0xFFFFB340)
    val red    = Color(0xFFFF3B30)
    val purple = Color(0xFFBF5AF2)
    val teal   = Color(0xFF32D2C8)

    // Nav LED colors — one per tab
    val ledColors = listOf(blueCore, teal, purple, amber, green)

    // Radii
    val r8  = RoundedCornerShape(8.dp)
    val r12 = RoundedCornerShape(12.dp)
    val r16 = RoundedCornerShape(16.dp)
    val r20 = RoundedCornerShape(20.dp)
    val r24 = RoundedCornerShape(24.dp)
    val r28 = RoundedCornerShape(28.dp)
    val pill = RoundedCornerShape(50.dp)
}

// ─────────────────────────────────────────────────────────────────
//  Modifier extensions
// ─────────────────────────────────────────────────────────────────

/** Soft radial glow rendered behind the composable via Canvas + BlurMaskFilter. */
fun Modifier.glowBehind(
    color: Color,
    blurRadius: Dp = 18.dp,
    offsetY: Dp = 0.dp
): Modifier = this.drawBehind {
    drawIntoCanvas { canvas ->
        val paint = Paint()
        val fw = paint.asFrameworkPaint()
        fw.isAntiAlias = true
        fw.maskFilter = BlurMaskFilter(blurRadius.toPx(), BlurMaskFilter.Blur.NORMAL)
        paint.color = color
        canvas.drawCircle(
            Offset(size.width / 2f, size.height / 2f + offsetY.toPx()),
            size.minDimension * 0.45f,
            paint
        )
    }
}

/** Horizontal LED strip drawn at the bottom of the composable — used under dock tabs. */
fun Modifier.ledStripBelow(
    color: Color,
    enabled: Boolean,
    blurRadius: Dp = 8.dp
): Modifier {
    if (!enabled) return this
    return this.drawBehind {
        drawIntoCanvas { canvas ->
            val paint = Paint()
            val fw = paint.asFrameworkPaint()
            fw.isAntiAlias = true
            fw.maskFilter = BlurMaskFilter(blurRadius.toPx(), BlurMaskFilter.Blur.NORMAL)
            paint.color = color
            val w = 24.dp.toPx()
            val h = 3.dp.toPx()
            val cx = size.width / 2f
            val bottom = size.height - 2.dp.toPx()
            canvas.drawRoundRect(
                left   = cx - w / 2f,
                top    = bottom - h,
                right  = cx + w / 2f,
                bottom = bottom,
                radiusX = h / 2f,
                radiusY = h / 2f,
                paint  = paint
            )
        }
    }
}

/** Glass card gradient border (top-left bright, bottom-right dark). */
private fun glassBorderBrush() = Brush.linearGradient(
    colors = listOf(NeoTokens.glassBorder, NeoTokens.glassBorder2),
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)

// ─────────────────────────────────────────────────────────────────
//  Glass Card — translucent surface with gradient border
// ─────────────────────────────────────────────────────────────────
@Composable
fun NeoGlassCard(
    modifier: Modifier = Modifier,
    accentColor: Color = NeoTokens.blueCore,
    glowEnabled: Boolean = false,
    cornerRadius: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val isDark = true // always dark Neo

    val bgBrush = Brush.verticalGradient(
        colors = listOf(
            NeoTokens.dark3.copy(alpha = 0.80f),
            NeoTokens.dark2.copy(alpha = 0.65f)
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (glowEnabled)
                    Modifier.glowBehind(accentColor.copy(alpha = 0.18f), blurRadius = 24.dp, offsetY = 4.dp)
                else Modifier
            )
            .clip(shape)
            .background(bgBrush)
            .border(
                width = 1.dp,
                brush = glassBorderBrush(),
                shape = shape
            )
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

// ─────────────────────────────────────────────────────────────────
//  Neo Elevated Card — 3D raised surface (neumorphic-inspired)
// ─────────────────────────────────────────────────────────────────
@Composable
fun NeoElevatedCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    accentGlow: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (accentGlow != null)
                    Modifier.glowBehind(accentGlow.copy(alpha = 0.14f), blurRadius = 20.dp)
                else Modifier
            )
            .shadow(
                elevation = 16.dp,
                shape = shape,
                spotColor = Color.Black.copy(alpha = 0.55f),
                ambientColor = Color.Black.copy(alpha = 0.25f)
            )
            .clip(shape)
            .background(NeoTokens.dark3)
            .border(1.dp, NeoTokens.neoHighlight, shape)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

// ─────────────────────────────────────────────────────────────────
//  Gradient Pill Button — full-width, icon optional
// ─────────────────────────────────────────────────────────────────
@Composable
fun GradientPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    startColor: Color = NeoTokens.blueCore,
    endColor: Color = NeoTokens.blueDeep,
    height: Dp = 52.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, tween(100), label = "gpb")

    val gradient = if (enabled)
        Brush.horizontalGradient(listOf(startColor, endColor))
    else
        Brush.horizontalGradient(listOf(Color(0xFF3A3D4A), Color(0xFF2C2E38)))

    Box(
        modifier = modifier
            .height(height)
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (enabled)
                    Modifier.glowBehind(startColor.copy(alpha = 0.25f), blurRadius = 16.dp, offsetY = 4.dp)
                else Modifier
            )
            .clip(NeoTokens.pill)
            .background(gradient)
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.18f else 0.05f), NeoTokens.pill)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.titleSmall.copy(
                    color = if (enabled) Color.White else Color.White.copy(0.45f),
                    letterSpacing = 0.3.sp
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Neo Icon Squircle — 3D depth icon button
// ─────────────────────────────────────────────────────────────────
@Composable
fun NeoIconSquircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    contentDesc: String = "",
    glowColor: Color = tint
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.90f else 1f, tween(100), label = "ni")
    val elevation by animateDpAsState(if (isPressed) 0.dp else 6.dp, tween(100), label = "nie")

    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (!isPressed)
                    Modifier.glowBehind(glowColor.copy(alpha = 0.20f), blurRadius = 12.dp)
                else Modifier
            )
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(14.dp),
                spotColor = Color.Black.copy(0.5f)
            )
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (isPressed)
                    Modifier.background(NeoTokens.dark2)
                else
                    Modifier.background(Brush.verticalGradient(listOf(NeoTokens.dark3, NeoTokens.dark2)))
            )
            .border(
                1.dp,
                if (isPressed) NeoTokens.glassBorder2 else NeoTokens.neoHighlight,
                RoundedCornerShape(14.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDesc, tint = tint, modifier = Modifier.size(size * 0.45f))
    }
}

// ─────────────────────────────────────────────────────────────────
//  Neo Pill Chip — colored status chip with glow on active
// ─────────────────────────────────────────────────────────────────
@Composable
fun NeoChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    glowing: Boolean = false
) {
    Box(
        modifier = modifier
            .then(if (glowing) Modifier.glowBehind(color.copy(0.25f), 10.dp) else Modifier)
            .clip(NeoTokens.pill)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.35f), NeoTokens.pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = color,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                letterSpacing = 0.2.sp
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  Neo Section Header — uppercase with accent line
// ─────────────────────────────────────────────────────────────────
@Composable
fun NeoSectionHeader(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(NeoTokens.pill)
                .background(NeoTokens.blueCore)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color(0xFF98A2B3),
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                letterSpacing = 1.0.sp
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  Neo Divider
// ─────────────────────────────────────────────────────────────────
@Composable
fun NeoDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(0.10f),
                        Color.White.copy(0.10f),
                        Color.Transparent
                    )
                )
            )
    )
}

// ─────────────────────────────────────────────────────────────────
//  Glow indicator dot  (used for status indicators)
// ─────────────────────────────────────────────────────────────────
@Composable
fun GlowDot(color: Color, size: Dp = 8.dp) {
    Box(
        Modifier
            .size(size)
            .glowBehind(color.copy(0.5f), blurRadius = size)
            .clip(CircleShape)
            .background(color)
    )
}

