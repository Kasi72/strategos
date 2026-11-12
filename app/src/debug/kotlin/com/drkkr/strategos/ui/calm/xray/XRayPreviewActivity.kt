package com.drkkr.strategos.ui.calm.xray

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.drkkr.strategos.domain.agent.ArenaInfluencer
import com.drkkr.strategos.domain.agent.ArenaLine
import com.drkkr.strategos.domain.agent.ArenaPerson
import com.drkkr.strategos.domain.agent.XRayArena
import com.drkkr.strategos.domain.agent.XRayGrid
import com.drkkr.strategos.domain.agent.XRayRow
import com.drkkr.strategos.ui.calm.theme.Calm
import com.drkkr.strategos.ui.calm.theme.StrategosTheme

/**
 * Debug-only harness: the X-ray sheet over demo data, so the board, arena and slider can be checked on a
 * device without an AI key or a saved project.
 *
 *   adb shell am start -n com.drkkr.strategos/.ui.calm.xray.XRayPreviewActivity [--es case long]
 *
 * "long" uses long names and no outsider, to stress wrapping and the two-platform arena.
 */
class XRayPreviewActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val long = intent.getStringExtra("case") == "long"
        val coach = 0.55f
        val base = if (long) XRayGrid(
            listOf(
                XRayRow("Offer a six-month trial with a written review date agreed up front", 79, 62, 0),
                XRayRow("Refuse to move under any circumstances, full stop", 88, 28, 0),
                XRayRow("Agree to move now", 50, 20, 0)
            ), 0, true, "Agrees and stays engaged with the plan", "Shuts down and stops talking", coach.toDouble()
        ).at(coach.toDouble()) else XRayGrid(
            listOf(XRayRow("Hold 27.5L, 2-yr prepay", 78, 60, 0), XRayRow("Hard floor: 27L or walk", 90, 34, 0), XRayRow("Match their 25L now", 48, 22, 0)),
            0, true, "Accepts", "Escalates to CFO", coach.toDouble()
        ).at(coach.toDouble())
        val arena = if (long) XRayArena(
            ArenaPerson("You", "", listOf(ArenaLine("You want", "Keep my job and move only with remote work"))),
            ArenaPerson("Maximilian Alexander", "Partner", listOf(ArenaLine("Worried about", "Letting his mother down", inferred = true))),
            null, 71, 49
        ) else XRayArena(
            ArenaPerson("You", "", listOf(ArenaLine("Floor", "27L"), ArenaLine("Target", "29L"))),
            ArenaPerson("Vikram", "FinCorp procurement", listOf(ArenaLine("Wants", "A lower yearly number"), ArenaLine("Worried about", "CFO optics", inferred = true))),
            ArenaInfluencer("CFO", "Signs off above 26L", "can block this", "high", false), 58, 46
        )
        setContent {
            StrategosTheme {
                var odds by remember { mutableFloatStateOf(coach) }
                var open by remember { mutableStateOf(false) }
                // Opened a beat after launch, the way a tap would, once the window is ready to host a sheet.
                androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(400); open = true }
                val unchanged = kotlin.math.abs(odds - coach) <= 0.004f
                val ui = XRayUiState(
                    loading = false, grid = if (unchanged) base else base.at(odds.toDouble()), odds = odds, coachOdds = coach,
                    coachBestLabel = base.best.label, oddsLabel = "${arena.them.name} takes it well", modelStrip = "3 people · 6 options · 12/12 outcomes",
                    engineRobust = true, humanLens = "They'd be giving something up, so a later start helps.", humanLensTopic = "Loss aversion",
                    humanLensUrl = "https://en.wikipedia.org/wiki/Loss_aversion", arena = arena, flips = base.flipPoints()
                )
                Box(Modifier.fillMaxSize().background(Calm.colors.ground).statusBarsPadding()) {
                    Text("Open the X-Ray", color = Calm.colors.ink, style = Calm.type.subtitle, modifier = Modifier.padding(24.dp).clickable { open = true })
                }
                if (open) ModalBottomSheet(
                    onDismissRequest = { open = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    containerColor = Color(0xFF101014), scrimColor = Color(0xFF09090C).copy(alpha = 0.64f),
                    dragHandle = { Box(Modifier.padding(top = 8.dp, bottom = 2.dp).size(width = 32.dp, height = 4.dp).background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(2.dp))) }
                ) { XRayContent(ui, onOdds = { odds = it }, onKeep = { }, onReset = { odds = coach }, onReadMore = {}, onClose = { open = false }) }
            }
        }
    }
}
