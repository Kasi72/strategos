package com.drkkr.strategos

import com.drkkr.strategos.domain.agent.ArgonPrompts
import com.drkkr.strategos.domain.agent.EngineDirectiveBuilder
import com.drkkr.strategos.domain.analysis.AnalysisPipeline
import com.drkkr.strategos.domain.human.Delivery
import com.drkkr.strategos.domain.human.PlannedTactic
import com.drkkr.strategos.domain.human.TacticId
import com.drkkr.strategos.domain.human.power.DeclineNotes
import com.drkkr.strategos.domain.human.power.StrategicCatalog
import com.drkkr.strategos.domain.human.power.StrategicId
import com.drkkr.strategos.fixtures.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Power Mode, PM4.2: what the coach gets in Power Mode, and rule 23. */
class PowerDirectiveTest {
    private val s = Fx.pmVendor()
    private val base = EngineDirectiveBuilder.build(s, AnalysisPipeline().run(s), null)
    private fun hint(id: StrategicId) = StrategicCatalog.tactics.single { it.id == id }.hint(s, s.humanContext)

    @Test fun TC_PM_DIR_01_powerModeSendsTheFlagAndHintsOnly_neverAnIdALawOrABook() {
        val d = base.copy(
            powerMode = true, declineNote = DeclineNotes.of("false_facts"),
            delivery = Delivery(tactics = listOf(
                PlannedTactic(StrategicId.REAL_COMPETITION, hint(StrategicId.REAL_COMPETITION), 0.0),
                PlannedTactic(StrategicId.HOLD_YOUR_NUMBER, hint(StrategicId.HOLD_YOUR_NUMBER), 0.0),
                PlannedTactic(TacticId.PUT_IT_IN_WRITING, "Send a one-line written summary.", 0.05)
            ))
        )
        val json = d.toJson().toString()
        assertTrue(json, json.contains("\"power_mode\":true"))
        for (id in StrategicId.values().map { it.name } + TacticId.values().map { it.name }) assertFalse(id, json.contains(id, ignoreCase = true))
        assertFalse(json, Regex("""L\d""").containsMatchIn(json))
        for (w in listOf("Ch.", "48 Laws", "Greene", "Nudge")) assertFalse(w, json.contains(w))
        assertTrue(json.contains(hint(StrategicId.REAL_COMPETITION)))
    }

    @Test fun TC_PM_DIR_02_offMeansNoPowerModeKey() {
        assertFalse(base.toJson().containsKey("power_mode"))
        assertFalse(base.copy(powerMode = false).toJson().containsKey("power_mode"))
    }

    @Test fun TC_PM_DIR_03_rule23() {
        val p = ArgonPrompts.ENGINE_COACH_PROMPT
        assertTrue(p.contains("23. POWER MODE"))
        assertTrue(p.contains("never invent facts", ignoreCase = true))
        assertTrue(p.contains("decline_note"))
        assertTrue(p.contains("power_mode"))
    }
}
