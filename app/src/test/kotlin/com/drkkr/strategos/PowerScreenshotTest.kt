package com.drkkr.strategos

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.human.RelationshipDomains
import com.drkkr.strategos.domain.human.power.PowerPlanner
import com.drkkr.strategos.domain.human.power.PowerRead
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.ui.calm.talk.CoachText
import com.drkkr.strategos.ui.calm.talk.TalkActions
import com.drkkr.strategos.ui.calm.talk.TalkScreen
import com.drkkr.strategos.ui.calm.talk.TalkTurn
import com.drkkr.strategos.ui.calm.talk.TalkUiState
import com.drkkr.strategos.ui.calm.theme.StrategosTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * CP-P3: screenshots of the Talk header switch, the banner and the Power Read card (light and dark), and the salary sequence
 * (tap "Your fallback" -> strong, the next turn's card shows Real competition). The domain is real (converter, planner, card); the
 * Call 1 readings are scripted. Runs only when SCREENSHOT_OUT names a folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PowerScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = System.getenv("SCREENSHOT_OUT")?.let { File(it).apply { mkdirs() } }

    /**
     * A screenshot generator, like the live replay: drawing the window natively crashes Robolectric's native runtime when it shares a
     * JVM with the rest of the suite, so it runs only when SCREENSHOT_OUT is set (run it on its own). The same sequence is asserted,
     * without drawing, in PowerReadCardTest.
     */
    @org.junit.Before fun onlyWhenAsked() = org.junit.Assume.assumeTrue("set SCREENSHOT_OUT to write the CP-P3 screenshots", out != null)

    private fun shot(name: String) {
        compose.waitForIdle()
        // captureToImage() waits for a redraw that never comes under Robolectric; draw the window's view directly instead.
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(android.graphics.Canvas(bmp)) }
        out?.let { dir -> File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private val coach = TalkTurn.Coach("c1", CoachText(
        lead = "You hold more leverage than it feels like: two systems only you understand, and a real written offer.",
        body = "**What to say:** \"Leela, I'd like to stay and see the launch through. I have a written offer that's meaningfully higher, so I'd like us to close that gap.\"",
        bullets = emptyList(), closing = "Want help with the first line for Monday?"), null)

    private fun salaryRead(s: Scenario): PowerRead =
        PowerRead.build(s, PowerPlanner.plan(s, RelationshipDomains.of(s.humanContext?.relationship, null), true, false, false, 50.0, 0.0))
            .let { it.copy(ask = com.drkkr.strategos.domain.human.power.PowerCardGate.ask(it, s, true, emptySet())) }

    private fun apply(json: String, s: Scenario): Scenario {
        val o = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(json)!!, s).ops, s)
        val v = ChangeSetValidator.validate(s, o)
        val accepted = v.accepted.map { it.op }
        return ChangeSetApplier.apply(s, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }

    /** The salary story as Call 1 read it live: the user's offer only as if_no_deal, the dependency on the wrong side. */
    private val salaryTurn = """{"people":[{"name":"You","role":"player"},{"name":"Leela","role":"player"}],
      "options":[{"who":"You","do":"Name the two systems and ask for a raise that reflects the offer","posture":"repair"},{"who":"You","do":"Wait until after the launch","posture":"boundary"},
        {"who":"You","do":"Hint you might leave","posture":"reactive"},{"who":"Leela","do":"Engage","posture":"receptive"},{"who":"Leela","do":"Put it off","posture":"defensive"}],
      "dims":["COST","RELATIONSHIPS","LONG_TERM_VALUE"],
      "outcomes":[{"when":["Name the two systems and ask for a raise that reflects the offer","Engage"],"for_you":[8,7,8],"for_them":[6,7,7]},{"when":["Name the two systems and ask for a raise that reflects the offer","Put it off"],"for_you":[5,5,5],"for_them":[6,5,5]},
        {"when":["Wait until after the launch","Engage"],"for_you":[6,6,6],"for_them":[6,6,6]},{"when":["Wait until after the launch","Put it off"],"for_you":[4,5,4],"for_them":[6,6,5]},
        {"when":["Hint you might leave","Engage"],"for_you":[5,3,5],"for_them":[4,3,4]},{"when":["Hint you might leave","Put it off"],"for_you":[3,2,3],"for_them":[5,3,4]}],
      "relationship":"manager","deadline":"hard","power":{"relative":"they_higher","you_depend_on_them":"high"},
      "their_tactics":["probes_you"],"negotiation":{"if_no_deal":"take the other company's offer"}}"""

    private fun screen(dark: Boolean, state: TalkUiState, actions: TalkActions = TalkActions()) {
        compose.setContent { StrategosTheme(darkTheme = dark) { TalkScreen(state, actions, Modifier.fillMaxSize()) } }
    }

    private fun talk(read: PowerRead?, power: Boolean) = TalkUiState(
        projectName = "Raise", loading = false, powerAvailable = true, powerExplained = true, powerMode = power,
        turns = listOfNotNull(TalkTurn.User("u1", "I'm going to talk to Leela on Monday."), read?.let { TalkTurn.PowerRead("p1", it) }, coach)
    )

    @Test fun headerSwitchBannerAndCard_lightAndDark() {
        val read = salaryRead(apply(salaryTurn, Scenario(id = "salary", title = "Raise")))
        var dark by mutableStateOf(false)
        var power by mutableStateOf(false)
        // the card on its own first (the conversation scrolls to the newest turn, so the full card shows best alone)
        var cardOnly by mutableStateOf(false)
        compose.setContent {
            StrategosTheme(darkTheme = dark) {
                if (cardOnly) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(com.drkkr.strategos.ui.calm.theme.Calm.colors.ground).padding(16.dp)) {
                    com.drkkr.strategos.ui.calm.talk.PowerReadCard(read, { _, _ -> })
                } else TalkScreen(talk(if (power) read else null, power = power), TalkActions(), Modifier.fillMaxSize())
            }
        }
        for (d in listOf(false, true)) {
            val theme = if (d) "dark" else "light"
            dark = d; power = false; cardOnly = false
            shot("1-header-switch-off-$theme")
            compose.onNodeWithText("⚡ Power").assertExists()
            compose.onNodeWithText("Power Mode — hardball moves for work and negotiations.").assertDoesNotExist()
            power = true
            shot("2-header-switch-on-banner-$theme")
            compose.onNodeWithText("Power Mode — hardball moves for work and negotiations.").assertExists()
            cardOnly = true
            shot("3-power-read-card-$theme")
            compose.onNodeWithText("⚡ POWER READ").assertExists()
        }
    }

    @Test fun salarySequence_light() = salarySequence(dark = false)
    @Test fun salarySequence_dark() = salarySequence(dark = true)

    private fun salarySequence(dark: Boolean) {
        val theme = if (dark) "dark" else "light"
        var scenario = apply(salaryTurn, Scenario(id = "salary", title = "Raise"))
        val before = salaryRead(scenario)
        assertFalse(before.yourMoves.any { it.name == "Real competition" })
        compose.setContent {
            var read by remember { mutableStateOf(before) }
            var nextTurn by remember { mutableStateOf(false) }
            StrategosTheme(darkTheme = dark) {
                TalkScreen(talk(read, power = true).let { if (nextTurn) it.copy(turns = it.turns + TalkTurn.User("u2", "I talked to her. She asked what I'd want.")) else it },
                    TalkActions(onSetLeverage = { k, v ->
                        // saved through the userFixed path, as TalkRoute does
                        scenario = scenario.copy(humanContext = scenario.humanContext!!.setByUser(k, v))
                    }), Modifier.fillMaxSize())
            }
            // the "next turn": Call 1 again says nothing about the fallback; the card is rebuilt from the saved scenario
            androidx.compose.runtime.LaunchedEffect(nextTurn) { if (nextTurn) read = salaryRead(scenario) }
            NextTurnTrigger.trigger = { nextTurn = true }
        }
        shot("4-salary-before-$theme")
        compose.onNodeWithText("Your fallback: tap to set").performClick(); shot("5-salary-tap-your-fallback-$theme")
        compose.onNodeWithText("strong").performClick(); shot("6-salary-fallback-strong-$theme")
        scenario = apply("""{"people":[{"name":"You","role":"player"}],"options":[{"who":"You","do":"Name the two systems and ask for a raise that reflects the offer","posture":"repair"}],"relationship":"manager"}""", scenario)
        compose.runOnIdle { NextTurnTrigger.trigger() }
        shot("7-salary-next-turn-real-competition-$theme")
        compose.onNodeWithText("1. Real competition").assertExists()
        assertTrue(salaryRead(scenario).yourMoves.any { it.name == "Real competition" })
    }

    private object NextTurnTrigger { var trigger: () -> Unit = {} }
}
