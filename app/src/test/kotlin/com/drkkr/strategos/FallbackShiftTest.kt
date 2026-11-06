package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.human.FallbackShift
import com.drkkr.strategos.domain.human.HumanConstants
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.HumanSensitivity
import com.drkkr.strategos.domain.human.HumanizedView
import com.drkkr.strategos.domain.human.ResponseModel
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.FactStatus
import com.drkkr.strategos.domain.model.GameRole
import com.drkkr.strategos.domain.model.MultiPayoff
import com.drkkr.strategos.domain.model.NegotiationContext
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.PayoffEntry
import com.drkkr.strategos.domain.model.Scenario
import com.drkkr.strategos.domain.model.Stakeholder
import com.drkkr.strategos.domain.negotiation.DealFrame
import com.drkkr.strategos.domain.negotiation.Direction
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent Dispatch ID1.6 (BLUEPRINT §3.5): each side's fallback moves the refusal cells, and the solver does the rest. */
class FallbackShiftTest {
    private val R = PayoffDimension.RELATIONSHIPS
    private val C = PayoffDimension.COST
    private val E = 1e-9

    /**
     * You: "hold" (a BOUNDARY move) and "give" (a `gives_first` move); Karthik: "yes" (receptive), "no" (defensive).
     * [you] and [them] map (mine, theirs) to [RELATIONSHIPS, COST]. By default Karthik is likelier to refuse "hold".
     */
    private fun vendor(
        ctx: HumanContext,
        you: Map<Pair<String, String>, List<Double>> = mapOf(
            ("hold" to "yes") to listOf(8.0, 3.0), ("hold" to "no") to listOf(4.0, 6.0),
            ("give" to "yes") to listOf(7.0, 4.0), ("give" to "no") to listOf(4.0, 6.0)
        ),
        them: Map<Pair<String, String>, List<Double>> = mapOf(
            ("hold" to "yes") to listOf(4.0, 6.0), ("hold" to "no") to listOf(6.0, 5.0),
            ("give" to "yes") to listOf(7.0, 4.0), ("give" to "no") to listOf(5.0, 5.0)
        ),
        giveTags: String = "[posture:repair][f:gives_first]"
    ): Scenario {
        val actions = listOf(
            Action(id = "hold", stakeholderId = "you", label = "Hold firm", description = "[posture:boundary]"),
            Action(id = "give", stakeholderId = "you", label = "Offer extras first", description = giveTags),
            Action(id = "yes", stakeholderId = "k", label = "Agree", description = "[posture:receptive]"),
            Action(id = "no", stakeholderId = "k", label = "Refuse", description = "[posture:defensive]")
        )
        fun rows(who: String, m: Map<Pair<String, String>, List<Double>>) = m.map { (k, v) ->
            PayoffEntry(who, mapOf("you" to k.first, "k" to k.second), MultiPayoff(mapOf(R to v[0], C to v[1])), FactStatus.AI_INFERRED)
        }
        return Scenario(id = "fb", title = "Vendor", decisionOwner = "You",
            stakeholders = listOf(Stakeholder(id = "you", name = "You", gameRole = GameRole.PLAYER), Stakeholder(id = "k", name = "Karthik", gameRole = GameRole.PLAYER)),
            actions = actions, payoffs = rows("you", you) + rows("k", them), humanContext = ctx)
    }

    private fun cell(s: Scenario, who: String, mine: String, theirs: String) =
        s.payoffs.single { it.stakeholderId == who && it.actionProfile["you"] == mine && it.actionProfile["k"] == theirs }.payoff.values

    private fun shift(s: Scenario, deal: DealFrame? = null, support: Boolean = false, lock: Boolean = false) =
        FallbackShift.apply(s, FallbackShift.sides(s, deal, support, lock))

    /** Your expected score for a move: the chance they say yes (from their side of the view) times yes, plus the rest times no. */
    private fun ev(s: Scenario, move: String) = ResponseModel.expectedValue(HumanizedView.apply(s), "you", move, 1.0)!!

    /** A stand-in for the engine: the move with your best expected score. */
    private val pick: (Scenario) -> String? = { v -> listOf("hold", "give").maxByOrNull { ResponseModel.expectedValue(v, "you", it, 1.0)!! } }

    private fun deal(credible: Boolean?) = DealFrame(Direction.LOWER_IS_BETTER, null, null, null, null, null, null, null, null, null, credible, false, emptyList())

    @Test fun TC_ID_ALT_01_aStrongFallbackRaisesYourRefusalCellsAndFavoursTheBoundaryMove() {
        val s = vendor(HumanContext(relationship = "vendor", yourAlternative = "strong"))
        val v = shift(s)
        for (m in listOf("hold", "give")) {
            assertEquals(cell(s, "you", m, "no").getValue(R) + 0.5, cell(v, "you", m, "no").getValue(R), E)
            assertEquals("COST: better means lower", cell(s, "you", m, "no").getValue(C) - 0.5, cell(v, "you", m, "no").getValue(C), E)
            assertEquals("acceptance untouched", cell(s, "you", m, "yes"), cell(v, "you", m, "yes"))
            assertEquals("their side untouched (theirs unknown)", cell(s, "k", m, "no"), cell(v, "k", m, "no"))
        }
        val holdGain = ev(v, "hold") - ev(s, "hold")
        val giveGain = ev(v, "give") - ev(s, "give")
        assertTrue("hold +$holdGain vs give +$giveGain", holdGain > giveGain && giveGain > 0)
    }

    @Test fun TC_ID_ALT_02_weakOrNoneLowersThemAndUnknownLeavesTheViewAlone() {
        for (w in listOf("weak", "none")) {
            val s = vendor(HumanContext(relationship = "vendor", yourAlternative = w))
            val v = shift(s)
            assertEquals(w, cell(s, "you", "hold", "no").getValue(R) - 0.5, cell(v, "you", "hold", "no").getValue(R), E)
            assertEquals(w, cell(s, "you", "give", "no").getValue(C) + 0.5, cell(v, "you", "give", "no").getValue(C), E)
        }
        val unknown = vendor(HumanContext(relationship = "vendor"))
        assertSame(unknown, shift(unknown))
        val odd = vendor(HumanContext(relationship = "vendor", yourAlternative = "maybe"))
        assertSame("never guessed", odd, shift(odd))
    }

    @Test fun TC_ID_ALT_03_theDealFramesCredibilityWins() {
        val s = vendor(HumanContext(relationship = "vendor", yourAlternative = "strong"))
        assertEquals(FallbackShift.Strength.WEAK, FallbackShift.yours(s, deal(false)))
        assertEquals(cell(s, "you", "hold", "no").getValue(R) - 0.5, cell(shift(s, deal(false)), "you", "hold", "no").getValue(R), E)
        val weakWord = vendor(HumanContext(relationship = "vendor", yourAlternative = "weak"))
        assertEquals(FallbackShift.Strength.STRONG, FallbackShift.yours(weakWord, deal(true)))
        assertEquals("no credibility figure: your word decides", FallbackShift.Strength.STRONG, FallbackShift.yours(s, deal(null)))
    }

    @Test fun TC_ID_ALT_04_theirStrongFallbackRaisesTheirRefusalCellsAndThePushbackOdds() {
        val tags = "[posture:repair][f:asks_them_to_give_up]"
        val s = vendor(HumanContext(relationship = "vendor", theirAlternative = "strong"), giveTags = tags)
        val v = shift(s)
        assertEquals(cell(s, "k", "give", "no").getValue(R) + 0.5, cell(v, "k", "give", "no").getValue(R), E)
        assertEquals(cell(s, "k", "give", "no").getValue(C) - 0.5, cell(v, "k", "give", "no").getValue(C), E)
        assertEquals("your side untouched (yours unknown)", cell(s, "you", "give", "no"), cell(v, "you", "give", "no"))
        val before = ResponseModel.receptiveProbability(HumanizedView.apply(s), "you", "give", 1.0)!!
        val after = ResponseModel.receptiveProbability(HumanizedView.apply(v), "you", "give", 1.0)!!
        assertTrue("P(defensive) ${1 - before} -> ${1 - after}", 1 - after > 1 - before)
        val weak = vendor(HumanContext(relationship = "vendor", theirAlternative = "none"), giveTags = tags)
        assertEquals(cell(weak, "k", "give", "no").getValue(R) - 0.5, cell(shift(weak), "k", "give", "no").getValue(R), E)
    }

    @Test fun TC_ID_ALT_05_noShiftInCloseRelationshipsOrSupportTurnsOrUnderTheLock_andRefusalNeverPassesAcceptance() {
        val spouse = vendor(HumanContext(relationship = "spouse", yourAlternative = "strong", theirAlternative = "strong"))
        assertSame(spouse, shift(spouse))
        val family = vendor(HumanContext(relationship = "sibling", yourAlternative = "weak"))
        assertSame(family, shift(family))
        val s = vendor(HumanContext(relationship = "vendor", yourAlternative = "strong"))
        assertSame("support turn", s, shift(s, support = true))
        assertSame("Power lock", s, shift(s, lock = true))
        // a refusal cell 0.3 below acceptance ends AT the acceptance value, not above it (both directions)
        val close = vendor(HumanContext(relationship = "vendor", yourAlternative = "strong"), you = mapOf(
            ("hold" to "yes") to listOf(6.0, 4.0), ("hold" to "no") to listOf(5.7, 4.3),
            ("give" to "yes") to listOf(5.0, 5.0), ("give" to "no") to listOf(5.5, 4.5)
        ))
        val v = shift(close)
        assertEquals(6.0, cell(v, "you", "hold", "no").getValue(R), E)
        assertEquals(4.0, cell(v, "you", "hold", "no").getValue(C), E)
        assertEquals("already above acceptance: not raised further", 5.5, cell(v, "you", "give", "no").getValue(R), E)
        assertEquals(4.5, cell(v, "you", "give", "no").getValue(C), E)
    }

    /** "hold" and "give" close: give leads by a little, and a strong fallback tips it to hold. */
    private val tight = mapOf(
        ("hold" to "yes") to listOf(8.0, 3.0), ("hold" to "no") to listOf(6.0, 3.5),
        ("give" to "yes") to listOf(7.4, 3.6), ("give" to "no") to listOf(4.0, 6.0)
    )

    @Test fun TC_ID_ALT_06_theFixedLineOnlyWhenTheShiftChangedThePick_andTheUnknownFallbackIsAKeyGap() {
        val strong = vendor(HumanContext(relationship = "vendor", yourAlternative = "strong"), you = tight)
        val base = vendor(HumanContext(relationship = "vendor"), you = tight)
        assertEquals("give", pick(HumanizedView.apply(base)))
        val flip = FallbackShift.effect(strong, null, false, false, pick)
        assertEquals("hold", pick(HumanizedView.apply(flip.view)))
        assertTrue(flip.pickChanged)
        assertEquals(FallbackShift.STRONG_LINE, flip.line)
        // no flip: a clear margin keeps the pick, so no line
        val clear = vendor(HumanContext(relationship = "vendor", yourAlternative = "strong"))
        val same = FallbackShift.effect(clear, null, false, false, pick)
        assertFalse(same.pickChanged)
        assertNull(same.line)
        // the weak line, when a thin fallback tips it the other way
        val weakTight = vendor(HumanContext(relationship = "vendor", yourAlternative = "weak"), you = tight + (("give" to "yes") to listOf(7.0, 4.0)))
        assertEquals("hold", pick(HumanizedView.apply(vendor(HumanContext(relationship = "vendor"), you = tight + (("give" to "yes") to listOf(7.0, 4.0))))))
        val w = FallbackShift.effect(weakTight, null, false, false, pick)
        assertEquals("give", pick(HumanizedView.apply(w.view)))
        assertEquals(FallbackShift.WEAK_LINE, w.line)
        // the lines never say BATNA and never carry a figure
        for (l in listOf(FallbackShift.STRONG_LINE, FallbackShift.WEAK_LINE)) {
            assertFalse(l.contains("BATNA", ignoreCase = true)); assertFalse(l.any { it.isDigit() })
        }
        // into the directive: present only when given
        val s = Fx.loanFamily(); val pr = AnalysisPipeline().run(s)
        assertTrue(EngineDirectiveBuilder.build(s, pr, null, fallbackLine = FallbackShift.STRONG_LINE).recommendedWhy.endsWith(FallbackShift.STRONG_LINE))
        val plain = EngineDirectiveBuilder.build(s, pr, null)
        assertFalse(plain.recommendedWhy.contains(FallbackShift.STRONG_LINE) || plain.recommendedWhy.contains(FallbackShift.WEAK_LINE))
        assertNull(plain.fallbackLine)
        // ID2.4's key gap: a negotiation, your fallback unknown, and strong vs weak would change the pick
        assertTrue(FallbackShift.unknownFallbackMatters(base, null, false, false, pick))
        assertFalse("known fallback", FallbackShift.unknownFallbackMatters(strong, null, false, false, pick))
        assertFalse("strong and weak agree", FallbackShift.unknownFallbackMatters(vendor(HumanContext(relationship = "vendor")), null, false, false, pick))
        assertFalse("not a negotiation", FallbackShift.unknownFallbackMatters(vendor(HumanContext(relationship = "spouse"), you = tight), null, false, false, pick))
        assertEquals("If this falls through, what would you do instead?", FallbackShift.QUESTION)
        // the shift is a judgement call, so the sensitivity pass halves and doubles it
        assertEquals(1.0, HumanConstants.DEFAULT.scaled(2.0).alternativeShift, E)
        assertEquals(0.25, HumanConstants.DEFAULT.scaled(0.5).alternativeShift, E)
        // a stand-in pick that turns on hold's refusal cell: hold at the default shift (6.5), give at half of it (6.25)
        val edge: (Scenario) -> String? = { v -> if (cell(v, "you", "hold", "no").getValue(R) > 6.3) "hold" else "give" }
        // (the user's own estimates, so the label-spacing pass leaves them alone and only the shift can move the pick)
        val own = strong.copy(payoffs = strong.payoffs.map { it.copy(factStatus = FactStatus.USER_ESTIMATE) })
        assertTrue("a pick that exists only at the default shift is fragile", HumanSensitivity.pickChanges(own, flip.sides, edge))
        assertFalse("without the shift there is nothing to flip", HumanSensitivity.pickChanges(own, FallbackShift.Sides(null, null), edge))
    }

    @Test fun TC_ID_ALT_07_aPendingFallbackCountsOnlyWhenTheUserWroteThatFallbackInTheirOwnWords() {
        val pendingYours = HumanContext(relationship = "vendor", pending = mapOf("yourAlternative" to "strong"))
        val written = vendor(pendingYours).copy(negotiationContext = NegotiationContext(batna = "the other vendor who quoted lower"))
        assertEquals(FallbackShift.Strength.STRONG, FallbackShift.yours(written, null))
        assertEquals(cell(written, "you", "hold", "no").getValue(R) + 0.5, cell(shift(written), "you", "hold", "no").getValue(R), E)
        val unwritten = vendor(pendingYours)
        assertNull(FallbackShift.yours(unwritten, null))
        assertSame("pending alone stays ignored", unwritten, shift(unwritten))
        // their side: pending + the counterpart's fallback in the user's words
        val pendingTheirs = HumanContext(relationship = "vendor", pending = mapOf("theirAlternative" to "weak"))
        val theirs = vendor(pendingTheirs).copy(negotiationContext = NegotiationContext(counterpartBatna = "no other buyer this quarter"))
        assertEquals(cell(theirs, "k", "give", "no").getValue(R) - 0.5, cell(shift(theirs), "k", "give", "no").getValue(R), E)
        assertTrue("pending without their fallback in words", vendor(pendingTheirs).let { shift(it) === it })
        // a confirmed word still wins over a pending one
        val both = vendor(HumanContext(relationship = "vendor", yourAlternative = "weak", pending = mapOf("yourAlternative" to "strong")))
            .copy(negotiationContext = NegotiationContext(batna = "another vendor"))
        assertEquals(FallbackShift.Strength.WEAK, FallbackShift.yours(both, null))
    }

    @Test fun TC_ID_ALT_08_eachSideGetsItsLineWhenItsShiftAloneChangesThePick_bothWhenOnlyTogether() {
        val ctx = HumanContext(relationship = "vendor", yourAlternative = "strong", theirAlternative = "strong")
        val s = vendor(ctx)
        // a stand-in pick that only their refusal cell can tip: their side alone flips it, yours alone doesn't
        val theirsOnly: (Scenario) -> String? = { v -> if (cell(v, "k", "give", "no").getValue(R) > 5.3) "hold" else "give" }
        val one = FallbackShift.effect(s, null, false, false, theirsOnly)
        assertTrue(one.pickChanged)
        assertEquals(listOf(FallbackShift.THEIR_STRONG_LINE), one.lines)
        // a pick that needs both: 4.0 + 5.0 = 9.0; either alone 9.5, both 10.0, threshold 9.7
        val together: (Scenario) -> String? = { v -> if (cell(v, "you", "hold", "no").getValue(R) + cell(v, "k", "give", "no").getValue(R) > 9.7) "hold" else "give" }
        val two = FallbackShift.effect(s, null, false, false, together)
        assertEquals(listOf(FallbackShift.STRONG_LINE, FallbackShift.THEIR_STRONG_LINE), two.lines)
        assertEquals("${FallbackShift.STRONG_LINE} ${FallbackShift.THEIR_STRONG_LINE}", two.line)
        // their weak line, alone
        val weak = vendor(HumanContext(relationship = "vendor", theirAlternative = "weak"))
        val w = FallbackShift.effect(weak, null, false, false, pick = { v -> if (cell(v, "k", "give", "no").getValue(R) < 4.7) "hold" else "give" })
        assertEquals(listOf(FallbackShift.THEIR_WEAK_LINE), w.lines)
        // no change, no line
        assertTrue(FallbackShift.effect(s, null, false, false, pick = { "give" }).lines.isEmpty())
        for (l in listOf(FallbackShift.THEIR_STRONG_LINE, FallbackShift.THEIR_WEAK_LINE)) {
            assertFalse(l.contains("BATNA", ignoreCase = true)); assertFalse(l.any { it.isDigit() })
        }
    }

    @Test fun TC_ID_ALT_09_aBareWalkAwayIsNotTheirFallbackInTheUsersWords() {
        for (bare in listOf("they could walk away", "no deal", "let you walk", "He'd just leave.")) assertFalse(bare, FallbackShift.namesAlternative(bare))
        assertTrue(FallbackShift.namesAlternative("they have another supplier lined up"))
        fun theirs(text: String) = FallbackShift.theirs(vendor(HumanContext(relationship = "vendor", pending = mapOf("theirAlternative" to "strong")))
            .copy(negotiationContext = NegotiationContext(counterpartBatna = text)))
        assertNull(theirs("they could walk away"))
        assertNull(theirs("no deal"))
        assertEquals(FallbackShift.Strength.STRONG, theirs("they have another supplier lined up"))
        // a confirmed word is the user's own already, whatever the text says
        assertEquals(FallbackShift.Strength.STRONG, FallbackShift.theirs(vendor(HumanContext(relationship = "vendor", theirAlternative = "strong"))
            .copy(negotiationContext = NegotiationContext(counterpartBatna = "no deal"))))
    }

    @Test fun TC_ID_ALT_10_aBareWalkAwayIsNotYourFallbackInYourOwnWordsEither() {
        assertFalse(FallbackShift.namesAlternative("I'd just walk away"))
        assertTrue(FallbackShift.namesAlternative("I have an offer from Infosys"))
        fun yours(text: String, deal: DealFrame? = null, confirmed: String? = null) = FallbackShift.yours(
            vendor(HumanContext(relationship = "vendor", yourAlternative = confirmed, pending = mapOf("yourAlternative" to "strong")))
                .copy(negotiationContext = NegotiationContext(batna = text)), deal)
        assertNull(yours("I'd just walk away"))
        assertEquals(FallbackShift.Strength.STRONG, yours("I have an offer from Infosys"))
        // a confirmed value or a credible deal frame still counts, whatever the text says
        assertEquals(FallbackShift.Strength.STRONG, yours("I'd just walk away", confirmed = "strong"))
        assertEquals(FallbackShift.Strength.STRONG, yours("I'd just walk away", deal = deal(true)))
    }
}
