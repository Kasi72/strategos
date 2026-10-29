package com.drkkr.strategos.ui.calm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.ui.calm.theme.Calm

/** The three-tab glass dock (DESIGN.md: Projects, Journal, Settings only). */
@Composable
fun StrategosDock(selected: DockTab, onSelect: (DockTab) -> Unit) {
    val c = Calm.colors
    Row(
        Modifier.fillMaxWidth().background(c.card.compositeOver(c.ground)).windowInsetsPadding(WindowInsets.navigationBars).height(80.dp).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DockEntry("Projects", Icons.Outlined.Folder, selected == DockTab.PROJECTS, Modifier.weight(1f)) { onSelect(DockTab.PROJECTS) }
        DockEntry("Journal", Icons.AutoMirrored.Outlined.MenuBook, selected == DockTab.JOURNAL, Modifier.weight(1f)) { onSelect(DockTab.JOURNAL) }
        DockEntry("Settings", Icons.Outlined.Tune, selected == DockTab.SETTINGS, Modifier.weight(1f)) { onSelect(DockTab.SETTINGS) }
    }
}

@Composable
private fun DockEntry(label: String, icon: ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Calm.colors
    Column(
        modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier.size(width = 58.dp, height = 32.dp).clip(com.drkkr.strategos.ui.calm.theme.SquircleShape(14.dp))
                .background(if (on) c.selected else Color.Transparent),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = label, tint = if (on) c.onAction else c.ink3, modifier = Modifier.size(24.dp)) }
        Text(label, style = Calm.type.pill, color = if (on) c.onAction else c.ink3)
    }
}
