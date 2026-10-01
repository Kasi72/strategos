package com.drkkr.strategos.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.domain.framework.BlockerAction
import com.drkkr.strategos.domain.framework.BlockerSeverity

@Composable
fun BlockerActionCard(
    blocker: BlockerAction,
    onFix: (BlockerAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val (borderColor, iconColor, bgColor) = when (blocker.severity) {
        BlockerSeverity.CRITICAL -> Triple(
            Color(0xFFDC2626), Color(0xFFDC2626), Color(0xFFDC2626).copy(alpha = 0.06f)
        )
        BlockerSeverity.HIGH -> Triple(
            Color(0xFFD97706), Color(0xFFD97706), Color(0xFFD97706).copy(alpha = 0.06f)
        )
        BlockerSeverity.MEDIUM -> Triple(
            Color(0xFFD97706), Color(0xFFD97706), Color(0xFFD97706).copy(alpha = 0.04f)
        )
        BlockerSeverity.LOW -> Triple(
            MaterialTheme.colorScheme.outlineVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Color.Transparent
        )
    }

    val icon = when (blocker.severity) {
        BlockerSeverity.CRITICAL -> Icons.Default.Error
        BlockerSeverity.HIGH -> Icons.Default.Warning
        BlockerSeverity.MEDIUM -> Icons.Default.Warning
        BlockerSeverity.LOW -> Icons.Default.CheckCircle
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        blocker.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        blocker.impact,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                "Now: ${blocker.currentState}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp)
            )

            if (blocker.technicalDetail != null) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 0.dp)
                ) {
                    Text(
                        if (expanded) "Hide detail" else "Show detail",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                AnimatedVisibility(expanded) {
                    Text(
                        blocker.technicalDetail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp, top = 2.dp, bottom = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { onFix(blocker) },
                modifier = Modifier.align(Alignment.End).height(34.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = iconColor,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(blocker.fixLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
