package com.drkkr.strategos.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.ui.theme.StrategosBrandTokens
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text

/**
 * World-class brand header for Strategos.
 * Embossed three-layer title treatment, optional geometric mark, entry animation.
 */
@Composable
fun StrategosBrandHeader(modifier: Modifier = Modifier) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isDark = isSystemInDarkTheme()

    // Responsive title size
    val titleSize = if (screenWidthDp <= 360)
        StrategosBrandTokens.titleSizeNarrow
    else
        StrategosBrandTokens.titleSizeLarge

    // Entry animation: fade + 4dp upward slide, 200ms
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val animAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "header_alpha"
    )
    val animTranslateY by animateFloatAsState(
        targetValue = if (entered) 0f else 12f,   // 4dp slide up (in px at 3x = 12px)
        animationSpec = tween(durationMillis = 200),
        label = "header_ty"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = animAlpha
                translationY = animTranslateY
            }
            .padding(
                top = StrategosBrandTokens.brandTopPadding,
                bottom = StrategosBrandTokens.headerToContentGap
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Geometric mark
        StrategosMarkCanvas(
            modifier = Modifier
                .size(26.dp)
                .semantics { contentDescription = "Strategos strategic decision node mark" },
            accentBlue = StrategosBrandTokens.accentBlue,
            silverLine = StrategosBrandTokens.ownerSlate
        )

        // Title + owner column
        Column(verticalArrangement = Arrangement.spacedBy(StrategosBrandTokens.titleToOwnerGap)) {
            EmbossedTitle(titleSize = titleSize, isDark = isDark)
            OwnerLine()
        }
    }
}

// ── Embossed title ─────────────────────────────────────────────────────────────
@Composable
private fun EmbossedTitle(titleSize: androidx.compose.ui.unit.TextUnit, isDark: Boolean) {
    val baseStyle = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = titleSize,
        lineHeight = (titleSize.value * 1.1f).sp,
        letterSpacing = (-0.6).sp
    )

    // Shadow offset: +1dp X, +2dp Y
    // Highlight offset: -1dp X, -1dp Y
    Box {
        // Layer 1: shadow
        Text(
            text = "Strategos",
            style = baseStyle.copy(color = StrategosBrandTokens.embossedShadow),
            modifier = Modifier.offset(x = 1.dp, y = 2.dp),
            maxLines = 1
        )
        // Layer 2: highlight
        Text(
            text = "Strategos",
            style = baseStyle.copy(color = StrategosBrandTokens.embossedHighlight),
            modifier = Modifier.offset(x = (-1).dp, y = (-1).dp),
            maxLines = 1
        )
        // Layer 3: main
        Text(
            text = "Strategos",
            style = baseStyle.copy(
                color = if (isDark) StrategosBrandTokens.titleSilver
                        else Color(0xFF1A1A2E)
            ),
            maxLines = 1
        )
    }
}

// ── Owner attribution ──────────────────────────────────────────────────────────
@Composable
private fun OwnerLine() {
    Text(
        text = "Dr KKR",
        style = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = StrategosBrandTokens.ownerSize,
            letterSpacing = 1.sp,
            color = StrategosBrandTokens.ownerSlate
        ),
        maxLines = 1
    )
}

// ── Geometric decision-node mark ───────────────────────────────────────────────
@Composable
private fun StrategosMarkCanvas(
    modifier: Modifier = Modifier,
    accentBlue: Color,
    silverLine: Color
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val stroke2 = 2.dp.toPx()
        val r3 = 3.dp.toPx()   // center dot radius
        val r15 = 1.5.dp.toPx() // arm endpoint dot radius
        val arm = w * 0.36f      // arm length from center

        // Silver secondary: horizontal arms (left + right)
        drawLine(
            color = silverLine.copy(alpha = 0.60f),
            start = Offset(cx - arm, cy),
            end = Offset(cx + arm * 0.4f, cy),
            strokeWidth = stroke2
        )

        // Blue primary: diagonal fork up-right and down-right
        drawLine(
            color = accentBlue,
            start = Offset(cx, cy),
            end = Offset(cx + arm, cy - arm * 0.55f),
            strokeWidth = stroke2
        )
        drawLine(
            color = accentBlue.copy(alpha = 0.65f),
            start = Offset(cx, cy),
            end = Offset(cx + arm, cy + arm * 0.55f),
            strokeWidth = stroke2
        )

        // Endpoint dots (arm tips)
        drawCircle(silverLine.copy(alpha = 0.50f), r15, Offset(cx - arm, cy))
        drawCircle(accentBlue, r15, Offset(cx + arm, cy - arm * 0.55f))
        drawCircle(accentBlue.copy(alpha = 0.65f), r15, Offset(cx + arm, cy + arm * 0.55f))

        // Center node
        drawCircle(accentBlue, r3, Offset(cx, cy))
    }
}
