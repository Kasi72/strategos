package com.drkkr.strategos.ui.calm.theme

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Neumorphic raised shadow — light top-left, dark bottom-right.
 * Apply before .background() so the shadow renders behind the fill.
 */
fun Modifier.neuRaised(
    lightColor: Color,
    darkColor: Color,
    elevation: Dp = 8.dp,
    cornerRadius: Dp = 16.dp
): Modifier = this.drawBehind {
    val e = elevation.toPx()
    val off = e * 0.65f
    val cr = cornerRadius.toPx()

    // Dark shadow — offset bottom-right
    drawIntoCanvas { canvas ->
        val p = Paint()
        p.asFrameworkPaint().apply {
            isAntiAlias = true
            color = darkColor.toArgb()
            maskFilter = BlurMaskFilter(e, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(off, off, size.width + off, size.height + off, cr, cr, p)
    }

    // Light shadow — offset top-left
    drawIntoCanvas { canvas ->
        val p = Paint()
        p.asFrameworkPaint().apply {
            isAntiAlias = true
            color = lightColor.toArgb()
            maskFilter = BlurMaskFilter(e, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(-off, -off, size.width - off, size.height - off, cr, cr, p)
    }
}

/**
 * Neumorphic pressed/inset shadow — shadows reversed relative to neuRaised.
 * Use for active/selected states to give a "pushed in" feel.
 */
fun Modifier.neuPressed(
    lightColor: Color,
    darkColor: Color,
    elevation: Dp = 5.dp,
    cornerRadius: Dp = 16.dp
): Modifier = this.drawBehind {
    val e = elevation.toPx()
    val off = e * 0.60f
    val cr = cornerRadius.toPx()

    // Dark shadow — offset top-left (reversed)
    drawIntoCanvas { canvas ->
        val p = Paint()
        p.asFrameworkPaint().apply {
            isAntiAlias = true
            color = darkColor.toArgb()
            maskFilter = BlurMaskFilter(e, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(-off, -off, size.width - off, size.height - off, cr, cr, p)
    }

    // Light shadow — offset bottom-right (reversed)
    drawIntoCanvas { canvas ->
        val p = Paint()
        p.asFrameworkPaint().apply {
            isAntiAlias = true
            color = lightColor.toArgb()
            maskFilter = BlurMaskFilter(e, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(off, off, size.width + off, size.height + off, cr, cr, p)
    }
}

/** Convenience wrapper: applies neuRaised + background in one call. */
fun Modifier.neuCard(
    colors: CalmColors,
    elevation: Dp = 8.dp,
    cornerRadius: Dp = 16.dp
): Modifier =
    this.neuRaised(colors.neuLight, colors.neuDark, elevation, cornerRadius)
        .then(
            Modifier.drawBehind {
                drawRoundRect(
                    color = colors.card,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.toPx()),
                )
            }
        )
