package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.*
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.model.Action
import com.drkkr.strategos.domain.model.PayoffDimension
import com.drkkr.strategos.domain.model.Scenario
import org.junit.Assert.*
import org.junit.Test

/** Posture tags, the repeated-game penalty, the reactive-move guard, the behavioral label, placeholders and third parties. */
class PostureEngineTest {
    private val calm = "Tell him what you see and ask what would help"
    private val firm = "Hold your line and pause for a day"
    private val chase = "Plead and message him until he answers"
    private val open = "Open up and talk"
    private val shut = "Shut down and leave"

    /** A reply that, left alone, would make chasing the best move: great scores for the user, and a warm reply from him. */
    private fun sketchJson(withPostures: Boolean = true): String {
        fun opt(who: String, label: String, posture: String) = """{"who":"$who","do":"$label"${if (withPostures) ""","posture":"$posture"""" else ""}}"""
        fun row(a: String, b: String, you: String, them: String) = """{"when":["$a","$b"],"for_you":$you,"for_them":$them}"""
        return """{"people":[{"name":"You","role":"player"},{"name":"Husband","role":"player"},{"name":"Auntie","role":"influencer"}],
            "options":[${opt("You", calm, "repair")},${opt("You", firm, "boundary")},${opt("You", chase, "reactive")},${opt("Husband", open, "receptive")},${opt("Husband", shut, "defensive")}],
            "dims":["WELLBEING","RELATIONSHIPS","LONG_TERM_VALUE"],
            "outcomes":[${row(calm, open, "[6,6,6]", "[6,6,6]")},${row(calm, shut, "[4,4,4]", "[5,5,5]")},
              ${row(firm, open, "[6,6,6]", "[6,6,6]")},${row(firm, shut, "[4,4,4]", "[5,5,5]")},
              ${row(chase, open, "[9,9,9]", "[8,8,8]")},${row(chase, shut, "[8,9,9]", "[3,3,3]")}]}"""
    }

    private fun build(json: String): Scenario {
        val base = Scenario(title = "t", description = "Reconnect with my husband")
        val ops = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(json)!!, base).ops, base)
        val v = ChangeSetValidator.validate(base, ops)
        val accepted = v.accepted.map { it.op }
        return ChangeSetApplier.apply(base, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }

    private fun dir(s: Scenario, stress: StressResult? = null): EngineDirective {
        val view = EngineDirectiveBuilder.analysisView(s, 50.0, 55.0)
        return EngineDirectiveBuilder.build(view, AnalysisPipeline().run(view), stress)
    }

    // 1. posture tags and the repeated-game penalty
    @Test fun postureIsKeptOnTheActionAndTheRelationshipIsMarkedRepeated() {
        val s = build(sketchJson())
        assertEquals(Postures.REACTIVE, Postures.of(s.actions.first { it.label == chase }))
        assertEquals(Postures.DEFENSIVE, Postures.of(s.actions.first { it.label == shut }))
        assertTrue(s.repeated)
    }

    @Test fun reactiveMoveIsCappedAndTheOtherSideRespondsDefensively() {
        val s = build(sketchJson())
        val you = s.stakeholders.first { it.name == "You" }.id; val him = s.stakeholders.first { it.name == "Husband" }.id
        fun id(label: String) = s.actions.first { it.label == label }.id
        fun payoff(who: String, mine: String, theirs: String) =
            s.payoffs.first { it.stakeholderId == who && it.actionProfile[you] == id(mine) && it.actionProfile[him] == id(theirs) }.payoff.values
        assertTrue(payoff(you, chase, open)[PayoffDimension.RELATIONSHIPS]!! <= 2.0)
        assertTrue(payoff(you, chase, shut)[PayoffDimension.LONG_TERM_VALUE]!! <= 2.0)
        assertTrue("when you chase, withdrawing is better for him", payoff(him, chase, shut)[PayoffDimension.WELLBEING]!! > payoff(him, chase, open)[PayoffDimension.WELLBEING]!!)
        assertTrue("when you repair, engaging is better for him", payoff(him, calm, open)[PayoffDimension.WELLBEING]!! > payoff(him, calm, shut)[PayoffDimension.WELLBEING]!!)
        assertTrue(payoff(him, firm, open)[PayoffDimension.WELLBEING]!! > payoff(him, firm, shut)[PayoffDimension.WELLBEING]!!)
    }

    @Test fun withoutPosturesNothingIsAdjusted() {
        val s = build(sketchJson(withPostures = false))
        val you = s.stakeholders.first { it.name == "You" }.id
        val chaseId = s.actions.first { it.label == chase }.id
        assertTrue(s.payoffs.filter { it.stakeholderId == you && it.actionProfile[you] == chaseId }.all { it.payoff.values[PayoffDimension.RELATIONSHIPS] == 9.0 })
    }

    // 1b. the reactive guard
    @Test fun aReactiveMoveIsNeverRecommendedOrFallbackAndIsAlwaysWarnedAbout() {
        val d = dir(build(sketchJson()))
        assertTrue(d.ready)
        assertNotEquals(chase, d.recommendedMove); assertNotEquals(chase, d.moveToImplement)
        assertNotEquals(chase, d.safeFallbackMove); assertNotEquals(chase, d.complementaryMove)
        val warned = d.blockedOrDominatedMoves.first()
        assertEquals(chase, warned.move)
        assertEquals("Husband would most likely respond: $shut", warned.triggers)       // 3. an honest prediction for the Don't list
    }

    // 2. fragile only for a reactive or excluded flip, or guessed outcomes; and it falls back
    @Test fun aFlipToTheReactiveMoveMakesItFragileAndFallsBackToACalmMove() {
        val s = build(sketchJson())
        val stress = StressResult(StressVerdict.FRAGILE, 4, listOf(StressFlip("You", "x / y", StressDirection.IMPROVE, chase)), 1, 3, false)
        val d = dir(s, stress)
        assertEquals("FRAGILE", d.stressVerdict)
        assertNotEquals(chase, d.moveToImplement)
        assertTrue(d.badge().endsWith("Fragile"))
    }

    @Test fun missingOutcomesMakeItFragile() {
        val s = build(sketchJson()); val trimmed = s.copy(payoffs = s.payoffs.drop(2))
        assertEquals("FRAGILE", dir(trimmed).stressVerdict)
    }

    @Test fun aSteadyPickStaysTheMainMoveAndACloseSecondIsBlended() {
        val d = dir(build(sketchJson()))
        assertEquals("ROBUST", d.stressVerdict)
        assertEquals(d.recommendedMove, d.moveToImplement)
        assertTrue(d.complementaryMove != null && d.complementaryMove != d.recommendedMove)    // repair and boundary score alike in this model
    }

    // 4. the badge names a behavioral pattern in plain words, not generic equilibrium
    @Test fun theBadgeNamesABehavioralPattern() {
        val d = dir(build(sketchJson()))
        assertNotNull(d.dynamicLabel)
        assertNotEquals("A stable pattern", d.dynamicLabel)
        assertFalse(d.badge().contains("Nash")); assertFalse(d.badge().contains("Equilibrium"))
        assertTrue(d.badge(), d.badge().startsWith("Engine: ") && d.badge().contains(" · ${d.dynamicLabel} · "))
    }

    @Test fun trustAndTensionDecideWhoTrustsWhom() {
        val s = build(sketchJson())
        fun trusted(trust: Double, tension: Double) = EngineDirectiveBuilder.analysisView(s, trust, tension).stakeholders.associate { it.name to it.trustRelationships.size }
        assertEquals(mapOf("You" to 1, "Husband" to 1, "Auntie" to 0), trusted(70.0, 40.0))
        assertEquals(1, trusted(45.0, 40.0).getValue("You")); assertEquals(0, trusted(45.0, 40.0).getValue("Husband"))
        assertEquals(0, trusted(70.0, 80.0).getValue("You"))          // high tension: neither is recorded as trusting
        assertTrue(EngineDirectiveBuilder.analysisView(s, 50.0, 50.0).repeated)
    }

    // 5. placeholders
    @Test fun bracketedPlaceholdersNeverSurvive() {
        assertTrue(Placeholders.has("I'll take on [name one concrete task] from now"))
        assertEquals("I'll take on from now", Placeholders.strip("I'll take on [name one concrete task] from now"))
        assertEquals("I see you handling and — thank you.", Placeholders.strip("I see you handling [thing] and [thing] — thank you."))
        val r = CoachReply.parse("""{"engine_move":"x","clear_win_move":"Say it.","exact_words":"I notice [specific effort], thank you.","do_this":["Name [one task]"],"dont_do_this":["Don't plead"]}""")!!
        assertTrue(r.hasPlaceholder())
        val c = r.stripped()
        assertFalse(c.hasPlaceholder()); assertEquals("I notice, thank you.", c.exactWords); assertEquals(listOf("Name"), c.doThis)
    }

    // 6. third parties
    @Test fun influencersAreHandedToTheCoach() {
        val d = dir(build(sketchJson()))
        assertEquals(listOf("Auntie"), d.influencersInPlay)
        assertEquals(listOf("Auntie"), d.toJson().getValue("influencers_in_play").let { (it as kotlinx.serialization.json.JsonArray).map { e -> (e as kotlinx.serialization.json.JsonPrimitive).content } })
        assertTrue(ArgonPrompts.ENGINE_COACH_PROMPT.contains("influencers_in_play"))
    }

    // 7. Phase F.5: a stated fear is never re-asked as a "discovery" move, by either the math or the wording step
    private val discover = "Ask him one gentle question about how he's feeling about things lately, then let it rest"

    private fun sketchJsonWithKnownFear(): String {
        fun opt(who: String, label: String, posture: String) = """{"who":"$who","do":"$label","posture":"$posture"}"""
        fun row(a: String, b: String, you: String, them: String) = """{"when":["$a","$b"],"for_you":$you,"for_them":$them}"""
        return """{"people":[{"name":"You","role":"player"},{"name":"Husband","role":"player","fears":"Eating dinner alone every night"}],
            "options":[${opt("You", calm, "repair")},${opt("You", discover, "repair")},${opt("You", chase, "reactive")},${opt("Husband", open, "receptive")},${opt("Husband", shut, "defensive")}],
            "dims":["WELLBEING","RELATIONSHIPS","LONG_TERM_VALUE"],
            "outcomes":[${row(calm, open, "[7,7,7]", "[7,7,7]")},${row(calm, shut, "[5,5,5]", "[5,5,5]")},
              ${row(discover, open, "[7,7,7]", "[7,7,7]")},${row(discover, shut, "[5,5,5]", "[5,5,5]")},
              ${row(chase, open, "[9,9,9]", "[8,8,8]")},${row(chase, shut, "[8,9,9]", "[3,3,3]")}]}"""
    }

    @Test fun aDiscoveryMoveIsNeverPickedOnceTheFearIsAlreadyKnown() {
        val s = build(sketchJsonWithKnownFear())
        val d = dir(s)
        // Without the fix "discover" ties with "calm" (both score [7,7,7]) and would be blended in as the complement.
        assertNotEquals(discover, d.complementaryMove)
        assertNotEquals(discover, d.moveToImplement)
        assertNotEquals(discover, d.safeFallbackMove)
    }

    @Test fun postureDetectsDiscoveryQuestionsButNotConcreteOnes() {
        assertTrue(Postures.isDiscoveryQuestion("Ask him one gentle question about how he's feeling about the two of you lately, then let it rest"))
        assertTrue(Postures.isDiscoveryQuestion("Find out what's really wrong before deciding anything"))
        assertFalse(Postures.isDiscoveryQuestion(calm))              // "ask what would help" has no feeling/worry word
        assertFalse(Postures.isDiscoveryQuestion("Co-design a weekly dinner schedule together"))
    }

    @Test fun knownFactsCarryFearsGoalsLimitsAndThePreviousMove() {
        val s = build(sketchJsonWithKnownFear())
        val d = dir(s)
        assertTrue(d.knownFacts.toString(), d.knownFacts.any { it.contains("Husband") && it.contains("Eating dinner alone") })
        assertTrue(d.toJson().containsKey("known_stakeholder_facts"))

        val view = EngineDirectiveBuilder.analysisView(s, 50.0, 55.0)
        val withPrevious = EngineDirectiveBuilder.build(view, AnalysisPipeline().run(view), null, previousMove = "An earlier move")
        assertTrue(withPrevious.knownFacts.any { it == "Last turn's recommended move: An earlier move" })

        val samePrevious = EngineDirectiveBuilder.build(view, AnalysisPipeline().run(view), null, previousMove = withPrevious.recommendedMove)
        assertFalse(samePrevious.knownFacts.any { it.startsWith("Last turn's recommended move:") })
    }

    @Test fun previousRecommendedMoveReadsTheStoredDigest() {
        val json = """{"pathways":[{"label":"Recommended","action":"Keep today light and warm"},{"label":"Safe Fallback","action":"Other move"}]}"""
        assertEquals("Keep today light and warm", EngineDirectiveBuilder.previousRecommendedMove(json))
        assertNull(EngineDirectiveBuilder.previousRecommendedMove(null))
        assertNull(EngineDirectiveBuilder.previousRecommendedMove("not json"))
    }

    @Test fun promptsCoverStageProgressionAndKnownFacts() {
        assertTrue(ArgonPrompts.EXTRACTION_PROMPT.contains("STAGE PROGRESSION"))
        assertTrue(ArgonPrompts.ENGINE_COACH_PROMPT.contains("known_stakeholder_facts"))
    }

    // Phase F.7: a blocked move unrelated to the counterpart never gets a fabricated "<counterpart> would respond"
    // line. Seen live: "Keep scrolling rental listings" (a solo habit, nothing to do with the ex) was warned against
    // with "Amudha would most likely respond: ..." -- the engine only ever tracks one counterpart for the whole
    // conversation, so once the topic moved past that relationship the attributed reaction became incoherent.
    private val chaseNamed = "Call Husband and beg him to pick up"
    private val chaseUnrelated = "Keep scrolling job listings instead of sleeping"

    private fun sketchJsonWithAnUnrelatedReactiveMove(): String {
        fun opt(who: String, label: String, posture: String) = """{"who":"$who","do":"$label","posture":"$posture"}"""
        fun row(a: String, b: String, you: String, them: String) = """{"when":["$a","$b"],"for_you":$you,"for_them":$them}"""
        return """{"people":[{"name":"You","role":"player"},{"name":"Husband","role":"player"}],
            "options":[${opt("You", calm, "repair")},${opt("You", firm, "boundary")},${opt("You", chaseNamed, "reactive")},${opt("You", chaseUnrelated, "reactive")},${opt("Husband", open, "receptive")},${opt("Husband", shut, "defensive")}],
            "dims":["WELLBEING","RELATIONSHIPS","LONG_TERM_VALUE"],
            "outcomes":[${row(calm, open, "[7,7,7]", "[7,7,7]")},${row(calm, shut, "[5,5,5]", "[5,5,5]")},
              ${row(firm, open, "[6,6,6]", "[6,6,6]")},${row(firm, shut, "[5,5,5]", "[5,5,5]")},
              ${row(chaseNamed, open, "[9,9,9]", "[8,8,8]")},${row(chaseNamed, shut, "[8,9,9]", "[3,3,3]")},
              ${row(chaseUnrelated, open, "[4,4,4]", "[6,6,6]")},${row(chaseUnrelated, shut, "[3,3,3]", "[4,4,4]")}]}"""
    }

    @Test fun aBlockedMoveThatNeverMentionsTheCounterpartGetsNoFabricatedReaction() {
        val d = dir(build(sketchJsonWithAnUnrelatedReactiveMove()))
        val named = d.blockedOrDominatedMoves.firstOrNull { it.move == chaseNamed }
        val unrelated = d.blockedOrDominatedMoves.firstOrNull { it.move == chaseUnrelated }
        assertNotNull("expected both reactive moves to be warned against", named); assertNotNull(unrelated)
        assertNotNull("a move naming Husband should still get a predicted reaction", named!!.triggers)
        assertTrue(named.triggers!!.startsWith("Husband would"))
        assertNull("a move with nothing to do with Husband must not get one", unrelated!!.triggers)
    }
}
