package com.drkkr.strategos.ui.calm.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.calmCard

/** Archived projects: bring one back, or delete it for good. Stateless. */
@Composable
fun ArchivedScreen(items: List<Pair<String, String>>, onBack: () -> Unit, onRestore: (String) -> Unit, onDelete: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Calm.colors
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.KeyboardArrowLeft, "Back", tint = c.ink, modifier = Modifier.size(28.dp))
            }
            Text("Archived", style = Calm.type.display.copy(fontSize = 28.sp, lineHeight = 34.sp), color = c.ink)
        }
        if (items.isEmpty()) {
            Text("Nothing archived. Projects you set aside show up here.", color = c.ink3, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(24.dp))
        } else LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.first }) { (id, name) ->
                Row(Modifier.fillMaxWidth().calmCard(c, RoundedCornerShape(20.dp)).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(vertical = 16.dp))
                    Box(Modifier.heightIn(min = 48.dp).clickable { onRestore(id) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text("Restore", color = c.actionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.heightIn(min = 48.dp).clickable { onDelete(id) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        Text("Delete", color = c.tension, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
