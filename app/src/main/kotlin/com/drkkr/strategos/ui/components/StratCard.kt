package com.drkkr.strategos.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.ui.theme.NeoTokens

val SquircleSmall = RoundedCornerShape(12.dp)
val SquircleButton = RoundedCornerShape(16.dp)
val SquircleCard = RoundedCornerShape(20.dp)
val SquircleFeature = RoundedCornerShape(24.dp)

/** Primary card — glassmorphic in dark, clean in light. */
@Composable
fun StratCard(
    modifier: Modifier = Modifier,
    accentGlow: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val shape = SquircleCard

    if (isDark) {
        val bgGradient = Brush.verticalGradient(
            listOf(NeoTokens.dark3.copy(0.85f), NeoTokens.dark2.copy(0.70f))
        )
        val borderGradient = Brush.linearGradient(
            listOf(Color.White.copy(0.13f), Color.White.copy(0.04f)),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
        Box(
            modifier
                .fillMaxWidth()
                .shadow(12.dp, shape, spotColor = Color.Black.copy(0.5f))
                .clip(shape)
                .background(bgGradient)
                .border(1.dp, borderGradient, shape)
        ) {
            Column(Modifier.padding(16.dp), content = content)
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(2.dp)
        ) { Column(Modifier.padding(16.dp), content = content) }
    }
}

/** Larger feature card with stronger depth. */
@Composable
fun FeatureCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val shape = SquircleFeature

    if (isDark) {
        Box(
            modifier
                .fillMaxWidth()
                .shadow(18.dp, shape, spotColor = Color.Black.copy(0.6f))
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(NeoTokens.dark3, NeoTokens.dark2)
                    )
                )
                .border(1.dp, NeoTokens.neoHighlight, shape)
        ) {
            Column(Modifier.padding(20.dp), content = content)
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(2.dp)
        ) { Column(Modifier.padding(20.dp), content = content) }
    }
}

@Composable
fun SectionHeader(text: String) {
    val isDark = isSystemInDarkTheme()
    if (isDark) {
        com.drkkr.strategos.ui.theme.NeoSectionHeader(text)
    } else {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                letterSpacing = 0.5.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

@Composable
fun StratDivider() {
    val isDark = isSystemInDarkTheme()
    if (isDark) {
        com.drkkr.strategos.ui.theme.NeoDivider(Modifier.padding(vertical = 2.dp))
    } else {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

@Composable
fun SemanticChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    if (isDark) {
        com.drkkr.strategos.ui.theme.NeoChip(label, color, modifier)
    } else {
        Box(
            modifier = modifier
                .background(color.copy(alpha = 0.12f), SquircleSmall)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = color)
        }
    }
}

@Composable
fun IosListRow(
    title: String,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leadingIconTint: Color? = null,
    showChevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, null,
                modifier = Modifier.size(20.dp),
                tint = leadingIconTint ?: MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ChevronRight, null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun ChipRow(chips: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        chips.forEach { label ->
            SuggestionChip(onClick = {}, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
        }
    }
}
