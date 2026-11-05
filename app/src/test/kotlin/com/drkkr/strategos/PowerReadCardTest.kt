package com.drkkr.strategos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.human.ManipulationRadar
import com.drkkr.strategos.domain.human.RelationshipDomains
import com.drkkr.strategos.domain.human.power.LawNotes
import com.drkkr.strategos.domain.human.power.PowerPlanner
import com.drkkr.strategos.domain.human.power.PowerRead
import com.drkkr.strategos.domain.human.power.StrategicCatalog
import com.drkkr.strategos.domain.human.power.StrategicId
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import com.drkkr.strategos.ui.calm.talk.PowerReadCard
import com.drkkr.strategos.ui.calm.theme.StrategosTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Power Mode, PM5.2: the Power Read card, the law sheet, and the user's own word on the leverage rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class PowerReadCardTest {
    @get:Rule val compose = createComposeRule()

    private val full = PowerRead(
        leverage = PowerRead.Leverage(theyNeedYou = "high", youNeedThem = "some", yourFallback = "strong", theirFallback = "weak", decides = "Priya's director", clock = "a hard deadline"),
        theirPlays = listOf(PowerRead.Play("They're digging for your limits.", "L14", "Answer questions about your limits with ranges, not your bottom line.", seenOnce = true)),
        yourMoves = listOf(
            PowerRead.Move("Real competition", "L20", 0.9, "little lost — it's true", closeCall = false),
            PowerRead.Move("Hold your number", "L3/L4", 0.6, "little lost — keeping your number private is expected", closeCall = false),
            PowerRead.Move("Let them move first", "L8", 0.5, "little lost — asking first is normal", closeCall = true)
        )
    )

    /** The card as it sits in the conversation: in a full-size column, not shrink-wrapped. */
    @androidx.compose.runtime.Composable
    private fun Host(content: @androidx.compose.runtime.Composable () -> Unit) =
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) { content() }

    private fun plan(s: Scenario) = PowerPlanner.plan(s, RelationshipDomains.of(s.humanContext?.relationship, null), true, false, false, 50.0, 0.0)

    @Test fun TC_PM_UI_04_aFullCardHasThreeRowsLawChipsTheEvAndACloseCall() {
        compose.setContent { StrategosTheme(darkTheme = false) { Host { PowerReadCard(full, { _, _ -> }) } } }
        for (t in listOf("⚡ POWER READ", "Leverage", "Their plays", "Your moves")) compose.onNodeWithText(t).assertExists()
        for (law in listOf("L20", "L3/L4", "L8", "L14")) compose.onNodeWithText(law).assertExists()
        // CP-P3: no score on the card itself (it is in the law sheet); the ranking and "close call" stay
        compose.onNodeWithText("EV", substring = true).assertDoesNotExist()
        compose.onNodeWithText("1. Real competition").assertExists()
        compose.onNodeWithText("close call").assertExists()
        compose.onNodeWithText("Your fallback: strong").assertExists()
        compose.onNodeWithText("seen once", substring = true).assertExists()
        compose.onNodeWithText("If they see through it: little lost — it's true").assertExists()
    }

    @Test fun TC_PM_UI_05_aCloseRelationshipShowsTheNoteInsteadOfMoves() {
        val close = full.copy(yourMoves = emptyList(), closeDomainNote = PowerPlanner.CLOSE_DOMAIN_NOTE)
        compose.setContent { StrategosTheme(darkTheme = false) { Host { PowerReadCard(close, { _, _ -> }) } } }
        compose.onNodeWithText(PowerPlanner.CLOSE_DOMAIN_NOTE).assertExists()
        compose.onNodeWithText("L20").assertDoesNotExist()
        compose.onNodeWithText("Their plays").assertExists()
    }

    @Test fun TC_PM_UI_06_aLawChipOpensItsNoteWithAReadMoreLink() {
        compose.setContent { StrategosTheme(darkTheme = false) { Host { PowerReadCard(full, { _, _ -> }) } } }
        compose.onNodeWithText("L20").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(LawNotes.forLabel("L20").single().text).assertExists()
        compose.onNodeWithText("Read more").assertExists()
        compose.onNodeWithText("Score: 0.90").assertExists()
    }

    @Test fun anUnsetPillSaysTapToSet() {
        compose.setContent { StrategosTheme(darkTheme = false) { Host { PowerReadCard(PowerRead(), { _, _ -> }) } } }
        compose.onNodeWithText("Your fallback: tap to set").assertExists()
        compose.onNodeWithText("They need you: tap to set").assertExists()
    }

    @Test fun tappingYourFallbackSavesTheUsersWord() {
        val calls = mutableListOf<Pair<String, String>>()
        compose.setContent { StrategosTheme(darkTheme = false) { Host { PowerReadCard(full.copy(leverage = full.leverage.copy(yourFallback = null)), { k, v -> calls += k to v }) } } }
        compose.onNodeWithText("Your fallback: tap to set").performClick()
        compose.onNodeWithText("strong").performClick()
        assertEquals(listOf("yourAlternative" to "strong"), calls)
        compose.onNodeWithText("They need you: high").assertExists()
        compose.onNodeWithText("Your fallback: strong (yours)").assertExists()
    }

    // ── the user's word wins (CP-P2b item 4) ────────────────────────────────────────────────────────────────────────

    private fun apply(json: String, s: Scenario): Scenario {
        val o = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(json)!!, s).ops, s)
        val v = ChangeSetValidator.validate(s, o)
        val accepted = v.accepted.map { it.op }
        return ChangeSetApplier.apply(s, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }

    @Test fun afterTheUserSetsAStrongFallbackRealCompetitionIsEligibleOnTheNextTurn() {
        // the salary story as Call 1 read it: no fallback, the dependency on the wrong side
        val salary = Fx.pmManager().let { it.copy(humanContext = it.humanContext!!.copy(theyDependOnYou = null, youDependOnThem = "high")) }
        assertFalse(plan(salary).cardMoves.any { it.tactic.id == StrategicId.REAL_COMPETITION })
        val fixed = salary.copy(humanContext = salary.humanContext!!.setByUser("yourAlternative", "strong").setByUser("theyDependOnYou", "high"))
        // the next turn: Call 1 again says nothing about the fallback (and reads the dependency the wrong way)
        val next = apply("""{"people":[{"name":"You","role":"player"}],"options":[{"who":"You","do":"Ask with a plan","posture":"repair"}],
            "relationship":"manager","power":{"your_alternative":"weak","they_depend_on_you":"none"}}""", fixed)
        assertEquals("strong", next.humanContext!!.yourAlternative)
        assertEquals("high", next.humanContext!!.theyDependOnYou)
        assertTrue(plan(next).cardMoves.map { it.tactic.id }.toString(), plan(next).cardMoves.any { it.tactic.id == StrategicId.REAL_COMPETITION })
    }

    @Test fun aUserFixedValueIgnoresLaterCallOneReadings() {
        val s = Fx.pmVendor().let { it.copy(humanContext = it.humanContext!!.setByUser("yourAlternative", "none")) }
        var cur = s
        repeat(3) { cur = apply("""{"people":[{"name":"You","role":"player"}],"options":[{"who":"You","do":"Ask with a plan","posture":"repair"}],
            "relationship":"vendor","power":{"your_alternative":"strong"}}""", cur) }
        assertEquals("none", cur.humanContext!!.yourAlternative)
        assertFalse("yourAlternative" in cur.humanContext!!.pending)
        // out-of-vocabulary values are ignored
        assertEquals(s.humanContext, s.humanContext!!.setByUser("yourAlternative", "huge"))
    }

    @Test fun everyLawOnTheCardHasANote_andMoveNamesReadWell() {
        val labels = StrategicCatalog.tactics.map { PowerRead.lawLabel(it.tactic.source) } +
            listOf("controls_the_options", "probes_you", "artificial_deadline", "hot_cold", "extreme_anchor", "self_serving_default", "adds_friction_to_your_exit")
                .mapNotNull { ManipulationRadar.describe(it) }.map { PowerRead.lawLabel(it.source) }
        for (l in labels) assertTrue("no note for $l", LawNotes.forLabel(l).isNotEmpty())
        assertEquals("Don't show your hand", PowerRead.nameOf(StrategicId.DONT_SHOW_YOUR_HAND))
        assertEquals("Credible walk-away", PowerRead.nameOf(StrategicId.CREDIBLE_WALKAWAY))
    }

    @Test fun inTheConversation_settingYourFallbackStrongBringsRealCompetitionOnTheNextTurn() {
        // the salary story as Call 1 read it live: the offer only as if_no_deal, the dependency on the wrong side, no your_alternative
        var scenario = Fx.pmManager().let { it.copy(humanContext = it.humanContext!!.copy(theyDependOnYou = null, youDependOnThem = "high"),
            negotiationContext = com.drkkr.strategos.domain.model.NegotiationContext(batna = "take the other company's offer")) }
        fun read(s: Scenario) = PowerRead.build(s, plan(s))
        assertFalse(read(scenario).yourMoves.any { it.name == "Real competition" })
        compose.setContent {
            var card by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(read(scenario)) }
            var turn by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
            NextTurn.go = { turn++ }
            androidx.compose.runtime.LaunchedEffect(turn) { if (turn > 0) card = read(scenario) }
            StrategosTheme(darkTheme = false) {
                com.drkkr.strategos.ui.calm.talk.TalkScreen(
                    com.drkkr.strategos.ui.calm.talk.TalkUiState(projectName = "Raise", loading = false, powerAvailable = true, powerExplained = true, powerMode = true,
                        turns = listOf(com.drkkr.strategos.ui.calm.talk.TalkTurn.PowerRead("p$turn", card))),
                    com.drkkr.strategos.ui.calm.talk.TalkActions(onSetLeverage = { k, v -> scenario = scenario.copy(humanContext = scenario.humanContext!!.setByUser(k, v)) }),
                    androidx.compose.ui.Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithText("1. Real competition").assertDoesNotExist()
        compose.onNodeWithText("Your fallback: tap to set").performClick()
        compose.onNodeWithText("strong").performClick()
        // the next turn: Call 1 again says nothing about the fallback
        scenario = apply("""{"people":[{"name":"You","role":"player"}],"options":[{"who":"You","do":"Ask with a plan","posture":"repair"}],"relationship":"manager"}""", scenario)
        compose.runOnIdle { NextTurn.go() }
        compose.waitForIdle()
        compose.onNodeWithText("1. Real competition").assertExists()
    }

    private object NextTurn { var go: () -> Unit = {} }
}
