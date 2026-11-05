package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.SketchHuman
import com.drkkr.strategos.domain.agent.SketchParser
import com.drkkr.strategos.domain.human.HumanVocab
import com.drkkr.strategos.fixtures.Sketches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Human Layer, HL1.2: the new, lenient ModelSketch fields and the closed vocabularies. */
class HumanSketchTest {

    @Test
    fun TC_HL_SKETCH_01_everyExistingSketchStillParsesWithEmptyHumanFields() {
        val all = listOf(Sketches.loanStory, Sketches.followUp, Sketches.loanStoryIncomplete, Sketches.loanRepair, Sketches.ASK)
        for (json in all) {
            val sk = SketchParser.parse(json)
            assertNotNull("must parse: $json", sk)
            assertNull(sk!!.relationship); assertNull(sk.power); assertNull(sk.statusQuo); assertNull(sk.yourState)
            assertTrue(sk.optionTags.isEmpty()); assertTrue(sk.theirTactics.isEmpty())
            assertNull(SketchHuman.context(sk))
            assertTrue(SketchHuman.optionTags(sk).isEmpty())
        }
    }

    @Test
    fun TC_HL_SKETCH_02_outOfVocabularyValuesAreDropped_neverGuessed() {
        val sk = SketchParser.parse(
            """{"relationship":"Boss","your_state":"Livid","option_tags":{"x":["public","sneaky"]},"their_tactics":["gaslighting","silent treatment"]}"""
        )!!
        val ctx = SketchHuman.context(sk)
        assertNull(ctx?.relationship)
        assertNull(ctx?.yourStateRaw); assertNull(ctx?.yourStateLevel)
        assertEquals(setOf("public"), SketchHuman.optionTags(sk)["x"])
        // "silent treatment" is spelled with a space; it is the same word as silent_treatment. "gaslighting" is not in the list.
        assertEquals(listOf("silent_treatment"), ctx!!.theirTactics)
    }

    @Test
    fun TC_HL_SKETCH_02b_validValuesAreKeptAndNormalised() {
        val sk = SketchParser.parse(
            """{"relationship":" Manager ","power":{"relative":"They Higher","they_depend_on_you":"some","you_depend_on_them":"HIGH",
               "your_alternative":"weak","their_alternative":"strong","setting":"semi-public"},
               "status_quo":"Nothing changes and I stay at my pay.","status_quo_for_them":"keeps_something","deadline":"soft",
               "their_vulnerable":"none","your_state":"Upset","their_state":"unknown","steps_for_them_to_say_yes":"3","goal_is":"lasting_commitment",
               "their_tactics":["none","moves_goalposts"]}"""
        )!!
        val c = SketchHuman.context(sk)!!
        assertEquals("manager", c.relationship)
        assertEquals("they_higher", c.theirPower)
        assertEquals("some", c.theyDependOnYou); assertEquals("high", c.youDependOnThem)
        assertEquals("weak", c.yourAlternative); assertEquals("strong", c.theirAlternative)
        assertEquals("semi_public", c.setting)
        assertEquals("Nothing changes and I stay at my pay.", c.statusQuo)
        assertEquals("keeps_something", c.statusQuoForThem); assertEquals("soft", c.deadline)
        assertEquals("none", c.theirVulnerable)
        assertEquals("upset", c.yourStateRaw); assertEquals(2.0, c.yourStateLevel!!, 0.0)
        assertNull(c.theirStateLevel)                       // "unknown" is no reading
        assertEquals(3, c.stepsForThemToSayYes)
        assertEquals("lasting_commitment", c.goalIs)
        assertEquals(listOf("moves_goalposts"), c.theirTactics)   // "none" is not a tactic
    }

    @Test
    fun TC_HL_SKETCH_02c_wrongShapesNeverSinkTheWholeSketch() {
        val sk = SketchParser.parse(
            """{"goal":"g","power":"they_higher","option_tags":{"a":"public","b":["demonstrates"]},"their_tactics":"hot_cold","steps_for_them_to_say_yes":"lots"}"""
        )
        assertNotNull(sk)
        assertEquals("g", sk!!.goal)
        assertEquals(setOf("public"), SketchHuman.optionTags(sk)["a"])
        assertEquals(setOf("demonstrates"), SketchHuman.optionTags(sk)["b"])
        assertEquals(listOf("hot_cold"), SketchHuman.context(sk)!!.theirTactics)
        assertNull(SketchHuman.context(sk)!!.stepsForThemToSayYes)
    }

    @Test
    fun TC_HL_SKETCH_02d_statusQuoIsPlaceholderSafeAndCapped() {
        assertNull(SketchHuman.context(SketchParser.parse("""{"status_quo":"short"}""")!!))
        val long = SketchParser.parse("""{"status_quo":"${"y".repeat(500)}"}""")!!
        assertEquals(200, SketchHuman.context(long)!!.statusQuo!!.length)
    }

    @Test
    fun TC_HL_VOCAB_01_cleanLowercasesTrimsAndRejects() {
        val v = setOf("semi_public", "private")
        assertEquals("semi_public", HumanVocab.clean(" Semi Public ", v))
        assertEquals("semi_public", HumanVocab.clean("semi-public", v))
        assertNull(HumanVocab.clean("elsewhere", v))
        assertNull(HumanVocab.clean(null, v))
        assertNull(HumanVocab.clean("  ", v))
    }

    @Test
    fun TC_HL_VOCAB_02_vocabulariesMatchTheBlueprint() {
        assertEquals(16, HumanVocab.RELATIONSHIP.size)
        assertEquals(17, HumanVocab.OPTION_TAGS.size)
        assertEquals(17, HumanVocab.THEIR_TACTICS.size)   // the 16 §6.6 tags + "none"
        assertTrue("none" in HumanVocab.THEIR_TACTICS)
        assertEquals(setOf("calm", "tense", "upset", "furious"), HumanVocab.YOUR_STATE)
        assertEquals(HumanVocab.YOUR_STATE + "unknown", HumanVocab.THEIR_STATE)
    }

    @Test
    fun TC_HL_SKETCH_02e_aWordFieldThatArrivesAsAnObjectOrListNeverSinksTheSketch() {
        // seen live (hl1.5b replay): "relationship":{...} made the whole reply unparseable, and the turn failed after the retry
        val sk = SketchParser.parse(
            """{"goal":"g","people":[{"name":"You","role":"player"}],
               "relationship":{"who":"Priya","kind":"manager"},
               "power":{"relative":{"value":"they_higher"},"setting":["private"],"their_alternative":{"x":1}},
               "status_quo_for_them":["keeps_something"],"deadline":{"type":"soft"},"their_vulnerable":{},
               "your_state":{"level":"tense"},"their_state":["upset","calm"],"goal_is":{"a":{"b":"c"}},
               "option_tags":{"x":{"a":"public"}},"their_tactics":{"a":"hot_cold"}}"""
        )
        assertNotNull("must still parse", sk)
        assertEquals("g", sk!!.goal)
        val c = SketchHuman.context(sk)!!
        assertEquals("manager", c.relationship)
        assertEquals("they_higher", c.theirPower)
        assertEquals("private", c.setting)
        assertNull(c.theirAlternative)                 // a number is not a word in the vocabulary
        assertEquals("keeps_something", c.statusQuoForThem)
        assertEquals("soft", c.deadline)
        assertNull(c.theirVulnerable)
        assertEquals("tense", c.yourStateRaw)
        assertEquals("upset", c.theirStateRaw)         // first item of a list
        assertNull(c.goalIs)
        assertTrue(SketchHuman.optionTags(sk).isEmpty())
    }
}
