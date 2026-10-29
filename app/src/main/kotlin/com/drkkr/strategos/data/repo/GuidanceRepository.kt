package com.drkkr.strategos.data.repo

import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.domain.agent.GuidanceCodec
import com.drkkr.strategos.domain.agent.GuidanceItem
import com.drkkr.strategos.domain.agent.GuidanceKind
import com.drkkr.strategos.domain.agent.GuidanceSource
import com.drkkr.strategos.domain.agent.StandingGuidance

/**
 * The Overview's "What to do": the latest coach Do's/Don'ts from each thread (stored on the reply's
 * message payload) merged with the journal takeaways the user applied, de-duplicated newest-first.
 */
class GuidanceRepository(private val db: StrategosDatabase) {

    suspend fun standing(scenarioId: String): StandingGuidance {
        val items = buildList {
            db.conversationDao().listThreadsForScenario(scenarioId).forEach { t ->
                val msg = db.messageDao().lastGuided(t.id) ?: return@forEach
                val guide = msg.payloadJson?.let { GuidanceCodec.decode(it) } ?: return@forEach
                guide.doThis.filter { it.isNotBlank() }.forEach {
                    add(GuidanceItem(it, GuidanceKind.DO, GuidanceSource.THREAD, t.title, msg.id, msg.createdAt))
                }
                guide.dontDoThis.filter { it.isNotBlank() }.forEach {
                    add(GuidanceItem(it, GuidanceKind.DONT, GuidanceSource.THREAD, t.title, msg.id, msg.createdAt))
                }
            }
            db.journalDao().appliedPostNotes(scenarioId).forEach { n ->
                if (n.doThis.isNotBlank()) add(GuidanceItem(n.doThis, GuidanceKind.DO, GuidanceSource.JOURNAL, JOURNAL_LABEL, n.journalEntryId, n.createdAt))
                if (n.dontDoThis.isNotBlank()) add(GuidanceItem(n.dontDoThis, GuidanceKind.DONT, GuidanceSource.JOURNAL, JOURNAL_LABEL, n.journalEntryId, n.createdAt))
            }
        }
        return StandingGuidance.of(items)
    }

    companion object { const val JOURNAL_LABEL = "your journal" }
}
