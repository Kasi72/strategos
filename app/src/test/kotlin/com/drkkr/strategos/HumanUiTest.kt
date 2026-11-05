package com.drkkr.strategos

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.drkkr.strategos.domain.agent.AiReview
import com.drkkr.strategos.domain.agent.ReviewKind
import com.drkkr.strategos.domain.agent.XRayGridBuilder
import com.drkkr.strategos.domain.human.BannedWords
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.HumanLens
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.fixtures.Fx
import com.drkkr.strategos.ui.calm.overview.OverviewChart
import com.drkkr.strategos.ui.calm.theme.StrategosTheme
import com.drkkr.strategos.ui.calm.xray.XRayContent
import com.drkkr.strategos.ui.calm.xray.XRayUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Human Layer, HL6.2: the X-ray lens, the Review rows, and the smoothed Overview chart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HumanUiTest {
    @get:Rule val compose = createComposeRule()

    private fun move(vararg tags: String) = Action(stakeholderId = "you", label = "x", description = if (tags.isEmpty()) "" else "[f:${tags.sorted().joinToString(",")}]")

    // ── the lens text ────────────────────────────────────────────────────────────

    @Test
    fun TC_HL_LENS_01_theStrongestSingleEffectBecomesOnePlainLine() {
        assertEquals("They'd be giving something up, so a later start helps.", HumanLens.of(HumanContext(), move("asks_them_to_give_up"))!!.line)
        assertEquals("They'd be giving something up, and a later start softens it.", HumanLens.of(HumanContext(), move("asks_them_to_give_up", "future_commitment"))!!.line)
        // two effects: the public correction (1.5) beats the open-ended ask (0.5)
        val both = HumanLens.of(HumanContext(theirPower = "they_higher", deadline = "none"), move("corrects_them", "public", "open_ended"))!!
        assertTrue(both.line, both.line.contains("face"))
        assertNull(HumanLens.of(HumanContext(), move()))
        assertNull(HumanLens.of(null, move("asks_them_to_give_up")))
        assertNull(HumanLens.of(HumanContext(), null))
    }

    @Test
    fun TC_HL_LENS_02_aHabitInTheLastAttemptCountsToo_andEveryLineIsPlainWithARealLink() {
        val habit = HumanLens.of(HumanContext(theirPower = "they_higher", lastAttemptTags = listOf("public", "corrects_them")), move())!!
        assertTrue(habit.line, habit.line.contains("face"))
        val all = listOf(
            HumanLens.of(HumanContext(), move("asks_them_to_give_up")), HumanLens.of(HumanContext(theirPower = "they_higher"), move("corrects_them", "public")),
            HumanLens.of(HumanContext(theirPower = "they_higher"), move("corrects_them")), HumanLens.of(HumanContext(relationship = "manager"), move("appeals_to_fairness_or_gratitude")),
            HumanLens.of(HumanContext(), move("their_interest")), HumanLens.of(HumanContext(deadline = "none"), move("open_ended")),
            HumanLens.of(HumanContext(goalIs = "break_from_group"), move("public")), HumanLens.of(HumanContext(goalIs = "lasting_commitment"), move("public")),
            HumanLens.of(HumanContext(), move("gives_first")), HumanLens.of(HumanContext(), move("withdraws"))
        )
        for (l in all) {
            assertNotNull(l)
            assertFalse(l!!.line, BannedWords.containedIn(l.line))
            assertTrue(l.url, l.url.startsWith("https://en.wikipedia.org/wiki/")); assertTrue(l.topic.isNotBlank())
        }
        assertEquals(10, all.map { it!!.line }.distinct().size)
    }

    @Test
    fun TC_HL_LENS_03_aNeutralStatusQuoOrACloseRelationshipSwitchOffTheEffectsThatDoNotApply() {
        assertNull(HumanLens.of(HumanContext(statusQuoForThem = "neutral"), move("asks_them_to_give_up")))
        assertNull("fairness is a fair appeal at home", HumanLens.of(HumanContext(relationship = "sibling"), move("appeals_to_fairness_or_gratitude")))
        assertNull("a hard deadline is not open-ended", HumanLens.of(HumanContext(deadline = "hard"), move("open_ended")))
    }

    // ── the sheet ────────────────────────────────────────────────────────────────

    private fun ui(lens: String?): XRayUiState {
        val s = Fx.loanFamily()
        return XRayUiState(loading = false, grid = XRayGridBuilder.build(s, 0.6), humanLens = lens, humanLensTopic = "Loss aversion", humanLensUrl = "https://en.wikipedia.org/wiki/Loss_aversion")
    }

    @Test
    fun TC_HL_UI_01_theLensLineIsShownOnTheXRaySheetWhenSet_andHiddenWhenNull() {
        compose.setContent { StrategosTheme { XRayContent(ui("They'd be giving something up, so a later start helps."), {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("They'd be giving something up, so a later start helps.").assertExists()
        compose.onNodeWithText("Read more: Loss aversion").assertExists()
    }

    @Test
    fun TC_HL_UI_01b_noLensMeansNoLensLine() {
        compose.setContent { StrategosTheme { XRayContent(ui(null), {}, {}, {}, {}, {}) } }
        compose.onAllNodesWithText("HUMAN LENS").assertCountEquals(0)
        compose.onAllNodesWithText("Read more: Loss aversion").assertCountEquals(0)
    }

    // ── chart smoothing ──────────────────────────────────────────────────────────

    @Test
    fun TC_HL_UI_02_theChartShowsAThreePointMovingAverage_theSavedPointsAreUntouched() {
        val saved = listOf(50f, 60f, 70f, 80f, 40f)
        val copy = saved.toList()
        assertEquals(listOf(50f, 55f, 60f, 70f, 63.333332f), OverviewChart.smooth(saved))       // trailing window, shorter at the start
        assertEquals("the saved points are not changed", copy, saved)
        assertTrue(OverviewChart.smooth(emptyList()).isEmpty())
        assertEquals(listOf(42f), OverviewChart.smooth(listOf(42f)))
        val chart = OverviewChart.ui(saved, saved)
        assertEquals(OverviewChart.smooth(saved), chart.trustPast)
        assertEquals(OverviewChart.hold(OverviewChart.smooth(saved)), chart.trustHold)
        assertEquals(OverviewChart.revert(OverviewChart.smooth(saved)), chart.tensionRevert)
    }

    // ── Review rows ──────────────────────────────────────────────────────────────

    private fun inferred(): Scenario = Fx.hlRaise().copy(humanContext = HumanContext(
        relationship = "manager", theirPower = "they_higher", setting = "private", theyDependOnYou = "some", theirAlternative = "strong", deadline = "none", yourStateLevel = 2.0
    ))

    @Test
    fun TC_HL_REVIEW_01_theInferredPowerFieldsAppearAsReviewRows() {
        val rows = AiReview.items(inferred()).filter { it.kind == ReviewKind.POWER }
        assertEquals(setOf("relationship", "theirPower", "setting", "theyDependOnYou", "theirAlternative"), rows.map { it.key }.toSet())
        assertTrue(rows.single { it.key == "theirPower" }.title.contains("more say than you"))
        assertTrue(AiReview.items(Fx.hlRaise().copy(humanContext = null)).none { it.kind == ReviewKind.POWER })
    }

    @Test
    fun TC_HL_REVIEW_02_keepingOrFixingMakesItTheUsersAndItWinsOverLaterGuesses() {
        var s = inferred()
        val item = AiReview.items(s).single { it.key == "theirPower" }
        s = AiReview.keepAsMine(s, item)
        assertTrue("theirPower" in s.humanContext!!.userFixed)
        assertTrue(AiReview.items(s).none { it.key == "theirPower" })                                  // no longer a guess
        val later = s.humanContext!!.merge(HumanContext(theirPower = "you_higher", setting = "public"))
        assertEquals("the user's word wins", "they_higher", later.theirPower)
        // fields the user did not touch still follow the model (through the usual two-turn confirmation)
        assertEquals(setOf("setting"), later.pending.keys.filter { it == "setting" }.toSet())
    }

    @Test
    fun TC_HL_REVIEW_03_removingClearsItAndStopsItComingBack_acceptAllKeepsEverything() {
        var s = inferred()
        s = AiReview.remove(s, AiReview.items(s).single { it.key == "theirAlternative" })
        assertNull(s.humanContext!!.theirAlternative)
        val again = s.humanContext!!.merge(HumanContext(theirAlternative = "strong")).merge(HumanContext(theirAlternative = "strong"))
        assertNull("not re-inferred after the user removed it", again.theirAlternative)
        val all = AiReview.acceptAll(inferred())
        assertTrue(AiReview.items(all).none { it.kind == ReviewKind.POWER })
        assertEquals(setOf("relationship", "theirPower", "setting", "theyDependOnYou", "theirAlternative"), all.humanContext!!.userFixed)
    }
}
