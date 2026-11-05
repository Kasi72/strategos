package com.drkkr.strategos

import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.domain.agent.ChangeSetApplier
import com.drkkr.strategos.domain.agent.ChangeSetValidator
import com.drkkr.strategos.domain.agent.Decision
import com.drkkr.strategos.domain.agent.OpDecision
import com.drkkr.strategos.domain.agent.OpNormalizer
import com.drkkr.strategos.domain.agent.SafetyKeywordScreen
import com.drkkr.strategos.domain.agent.SafetyReply
import com.drkkr.strategos.domain.agent.SketchConverter
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.human.CoerciveControl
import com.drkkr.strategos.domain.human.EthicsGate
import com.drkkr.strategos.domain.human.HumanContext
import com.drkkr.strategos.domain.human.ManipulationRadar
import com.drkkr.strategos.domain.human.RelationshipDomain
import com.drkkr.strategos.domain.human.RelationshipDomains
import com.drkkr.strategos.domain.human.TacticCatalog
import com.drkkr.strategos.domain.model.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, hl5.3: coercive-control routing as decided at the CP-C safety review. */
class CoerciveRoutingTest {
    private val options = """"options":[
        {"for":"You","label":"Say it calmly","posture":"repair"},{"for":"You","label":"Wait","posture":"boundary"},{"for":"You","label":"Send it now","posture":"reactive"},
        {"for":"Husband","label":"Listen","posture":"receptive"},{"for":"Husband","label":"Shut down","posture":"defensive"}]"""

    private fun turn(relationship: String, vararg tactics: String) =
        """{"people":[{"name":"You","role":"PLAYER"},{"name":"Husband","role":"PLAYER"}],$options,"relationship":"$relationship","their_tactics":[${tactics.joinToString(",") { "\"$it\"" }}]}"""

    /** One message through the same convert -> normalise -> validate -> apply path the app uses. */
    private fun say(s: Scenario, json: String): Scenario {
        val ops = OpNormalizer.normalize(SketchConverter.toOps(SketchParser.parse(json)!!, s).ops, s)
        val v = ChangeSetValidator.validate(s, ops)
        val accepted = v.accepted.map { it.op }
        return ChangeSetApplier.apply(s, accepted, accepted.indices.map { OpDecision(it, Decision.ACCEPT) }, v.refIds, emptyList(), "t").scenario
    }

    private fun routes(s: Scenario, domain: RelationshipDomain = RelationshipDomain.INTIMATE) = CoerciveControl.routes(s.humanContext, domain)

    // ── 1. expiry ────────────────────────────────────────────────────────────────

    @Test
    fun TC_HL_ROUTE_01_routingExpiresAfterFiveMessagesWithNoCoerciveTag_andAnyNewMentionRearmsIt() {
        var s = say(Scenario(title = "t"), turn("spouse", "isolates_you"))
        assertTrue("first mention", routes(s))
        repeat(4) { s = say(s, turn("spouse")); assertTrue("quiet message ${it + 1} of 5", routes(s)) }
        s = say(s, turn("spouse"))
        assertFalse("fifth quiet message: expired", routes(s))
        assertEquals(5, s.humanContext!!.routingQuietTurns)
        s = say(s, turn("spouse", "isolates_you"))
        assertTrue("a new mention re-arms it", routes(s))
        assertEquals(0, s.humanContext!!.routingQuietTurns)
        // a different coercive tag also resets the count
        repeat(3) { s = say(s, turn("spouse")) }
        s = say(s, turn("spouse", "keeps_you_dependent"))
        assertEquals(0, s.humanContext!!.routingQuietTurns)
    }

    @Test
    fun TC_HL_ROUTE_02_onceExpiredTacticsAreAllowedAgain_andTheOldTagsAreNotShownAsWatchFor() {
        var s = say(Scenario(title = "t"), turn("spouse", "isolates_you"))
        val rec = TacticCatalog.offered.first()
        assertFalse(EthicsGate.allowed(rec, s.humanContext, RelationshipDomain.INTIMATE, false))
        repeat(5) { s = say(s, turn("spouse")) }
        assertTrue(EthicsGate.allowed(rec, s.humanContext, RelationshipDomain.INTIMATE, false))
        val radar = ManipulationRadar.scan(s.humanContext!!.copy(tacticTurns = mapOf("isolates_you" to 3)), RelationshipDomain.INTIMATE)
        assertFalse(radar.routeToSupport); assertTrue(radar.watchFor.isEmpty())
    }

    @Test
    fun TC_HL_ROUTE_03_aTurnWithNothingToSayAboutTheContextDoesNotCountTowardsExpiry() {
        val s0 = say(Scenario(title = "t"), turn("spouse", "isolates_you"))
        // a reminder patch (what a held move writes) merges an empty reading: it must not tick the counter
        val ticked = s0.humanContext!!.merge(HumanContext(pendingCooling = "x"))
        assertEquals(s0.humanContext!!.routingQuietTurns, ticked.routingQuietTurns)
    }

    // ── 2. sensitivity and domains ───────────────────────────────────────────────

    @Test
    fun TC_HL_ROUTE_04_isolationAndDependenceRouteOnFirstMention_hotAndColdNeedsTwoTurns() {
        assertTrue(routes(say(Scenario(title = "t"), turn("spouse", "isolates_you"))))
        assertTrue(routes(say(Scenario(title = "t"), turn("spouse", "keeps_you_dependent"))))
        var s = say(Scenario(title = "t"), turn("spouse", "hot_cold"))
        assertFalse("hot_cold alone, once", routes(s))
        s = say(s, turn("spouse", "hot_cold"))
        assertTrue("hot_cold on two turns", routes(s))
        assertTrue("hot_cold together with another coercive tag", routes(say(Scenario(title = "t"), turn("spouse", "hot_cold", "isolates_you"))))
    }

    @Test
    fun TC_HL_ROUTE_05_onlyIntimateAndFamilyRoute_friendshipUsesTheOrdinaryTwoTurnPath() {
        val s = say(Scenario(title = "t"), turn("spouse", "isolates_you"))
        val ctx = s.humanContext!!
        for (d in RelationshipDomain.values()) assertEquals("$d", d == RelationshipDomain.INTIMATE || d == RelationshipDomain.FAMILY, CoerciveControl.routes(ctx, d))
        // in a friendship the same behaviour is an ordinary watch-for, after two turns
        assertTrue(ManipulationRadar.scan(ctx.copy(tacticTurns = mapOf("isolates_you" to 1)), RelationshipDomain.FRIENDSHIP).watchFor.isEmpty())
        val friend = ManipulationRadar.scan(ctx.copy(tacticTurns = mapOf("isolates_you" to 2)), RelationshipDomain.FRIENDSHIP)
        assertFalse(friend.routeToSupport); assertEquals(listOf("isolates_you"), friend.watchFor.map { it.pattern })
        assertTrue(EthicsGate.allowed(TacticCatalog.offered.first(), ctx, RelationshipDomain.FRIENDSHIP, false))
        assertEquals(RelationshipDomain.FAMILY, RelationshipDomains.of("sibling", null))
    }

    // ── 3. keywords, 5. support text, 6. refusal list, 8. wording ─────────────────

    @Test
    fun TC_HL_SAFE_05_theEnglishScreenCatchesControlAndFear_theTamilListIsUnchanged() {
        for (said in listOf("He controls my money", "she takes my money", "He won't let me see my friends", "he won't let me go out", "He checks my phone every night",
            "he tracks my location", "I'm afraid of him", "I am afraid of her"))
            assertTrue(said, SafetyKeywordScreen.check(said))
        assertFalse(SafetyKeywordScreen.check("My manager wants a decision by Friday"))
        assertTrue(SafetyKeywordScreen.check("எனக்கு தற்கொலை எண்ணம் வருகிறது"))
        val tamil = SafetyKeywordScreen.DEFAULT_PHRASES.filter { p -> p.any { it in '஀'..'௿' } }
        assertEquals(listOf("தற்கொலை", "சாக வேண்டும்", "சாகணும்", "உயிரை மாய்த்து", "என்னை நானே காயப்படுத்த", "கொன்று விடுவேன்", "அடிக்கிறார்", "அடிக்கிறான்"), tamil)
    }

    @Test
    fun TC_HL_SAFE_06_theSupportTextNamesTheIndianHelplineOnlyInIndia() {
        val india = SettingsRepository.defaultSupportText("IN")
        assertTrue(india.contains("Tele-MANAS") && india.contains("14416"))
        for (c in listOf("US", "GB", "", null)) {
            val t = SettingsRepository.defaultSupportText(c)
            assertFalse("$c: $t", t.contains("14416") || t.contains("Tele-MANAS"))
            assertTrue(t, t.contains("your local emergency number") && t.contains("someone you trust"))
        }
        assertEquals(india, SettingsRepository.DEFAULT_SUPPORT_TEXT)               // the old constant is the India text, unchanged
    }

    @Test
    fun TC_HL_SAFE_07_theRefusalListCoversTheMovesThatEscalate() {
        for (bad in listOf("Leave tonight while he's out.", "Pack your bags and go.", "Record him when he shouts.", "Try to secretly record the next row.", "Record her if you can.",
            "Check his phone for proof.", "Check her phone.", "Tell his family what he does.", "Tell her family.", "You could threaten to leave.", "Call his boss about it.", "Take the kids and go."))
            assertFalse(bad, SafetyReply.acceptable("I hear you. $bad Please talk to someone you trust."))
        // acknowledging what was done to them is not advice
        assertTrue(SafetyReply.acceptable("Being threatened is frightening, and I'm glad you told me. Please talk to someone you trust; the support card has numbers."))
    }

    @Test
    fun TC_HL_RADAR_11_theWatchForWordingDescribesTheBehaviourNotAMotive() {
        fun w(tag: String) = ManipulationRadar.scan(HumanContext(theirTactics = listOf(tag), tacticTurns = mapOf(tag to 2)), RelationshipDomain.WORK_PEER).watchFor.single()
        assertEquals("The choices you're offered all seem to suit them.", w("controls_the_options").sign)
        assertEquals("Past favours keep coming up when they want a yes.", w("guilt_or_gratitude").sign)
        assertEquals("Long silences keep leaving you to do the chasing.", w("silent_treatment").sign)
        assertEquals("Saying no or leaving keeps getting made harder.", w("adds_friction_to_your_exit").sign)
        assertEquals("Talk to the person directly, and make up your own mind.", w("reaches_you_through_others").protect)
        for (tag in listOf("controls_the_options", "guilt_or_gratitude", "silent_treatment", "adds_friction_to_your_exit", "reaches_you_through_others"))
            assertFalse(tag, w(tag).sign.contains("used to") || w(tag).sign.contains("on purpose") || w(tag).sign.contains("trying to"))
    }
}
