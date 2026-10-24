package com.drkkr.strategos.agent

import com.drkkr.strategos.data.db.entities.JournalInsightPostNoteEntity
import com.drkkr.strategos.data.db.entities.TrajectoryPointEntity
import com.drkkr.strategos.data.repo.JournalEntryView
import com.drkkr.strategos.domain.analysis.TrajectorySnapshot
import com.drkkr.strategos.domain.model.WorkflowStage
import kotlinx.serialization.json.JsonObject

/** Everything the Counsel screen renders (blueprint §10.6). */
data class CounselUiState(
    val aiReady: Boolean = false, val needsConsent: Boolean = false, val hasKey: Boolean = false,
    val conversationId: String? = null, val items: List<ChatItem> = emptyList(),
    val running: Boolean = false, val activity: String? = null, val error: String? = null, val canRetry: Boolean = false,
    val memory: List<MemoryItemUi> = emptyList(), val strip: LiveModelStripUi? = null,
    // §17.4: shown by SafetyKeywordScreen before any LLM call, or by the show_support tool (as a chat item)
    val supportVisible: Boolean = false, val supportText: String = "",
    /** A turn got 401: the Talk tab offers "Update key" (§11.5.1). */
    val keyInvalid: Boolean = false,
    // Argon, Space 1: named threads of the active project. [conversationId] is the selected one.
    val threads: List<ThreadUi> = emptyList(),
    // Argon, Space 3: where the project sits, its history, and the journal notes waiting for a decision.
    val trajectory: TrajectorySnapshot? = null,
    val trajectoryPoints: List<TrajectoryPointEntity> = emptyList(),
    val postNotes: List<JournalInsightPostNoteEntity> = emptyList(),
    // Argon, Space 2: the decrypted journal, newest first. Never log or persist this.
    val journalEntries: List<JournalEntryView> = emptyList(),
    /** "Recorded privately 🍃" after a save; the Journal tab shows it and clears it. */
    val journalAck: String? = null
)

data class ThreadUi(val id: String, val title: String, val topicTag: String, val pinned: Boolean, val updatedAt: Long)

sealed class ChatItem {
    abstract val id: String
    data class UserText(override val id: String, val text: String) : ChatItem()
    data class AssistantText(override val id: String, val text: String) : ChatItem()
    data class Activity(override val id: String, val steps: List<String>) : ChatItem()        // consecutive TOOL_RESULT labels merged
    data class ChangeSetCard(override val id: String, val ui: ChangeSetUi) : ChatItem()
    data class Questions(override val id: String, val questions: List<QuestionUi>, val answered: Boolean) : ChatItem()
    data class Recommendation(override val id: String, val digest: JsonObject) : ChatItem()
    data class WhatIf(override val id: String, val comparison: JsonObject, val whatIfId: String) : ChatItem()
    data class StressTest(override val id: String, val result: JsonObject) : ChatItem()
    // §17.4–17.5 (Guided mode cards)
    data class Understanding(override val id: String, val bullets: List<String>, val answered: Boolean) : ChatItem()
    data class Support(override val id: String) : ChatItem()
    data class EventNote(override val id: String, val text: String) : ChatItem()
    data class Error(override val id: String, val text: String, val retryable: Boolean) : ChatItem()
}

data class ChangeSetUi(val id: String, val summary: String, val status: String, val rows: List<OpRowUi>)

/** [userStated] = the quote was verified against the user's own words ("You said" chip); otherwise "AI". */
data class OpRowUi(val index: Int, val text: String, val rationale: String, val userStated: Boolean)

data class QuestionUi(val id: String, val text: String, val options: List<String>, val allowFreeText: Boolean)

data class MemoryItemUi(val id: String, val kind: String, val text: String, val status: String)

data class LiveModelStripUi(
    val stage: WorkflowStage, val players: Int, val influencers: Int, val actions: Int,
    val payoffsFilled: Int, val payoffsExpected: Int, val aiSharePct: Int, val pendingReviews: Int, val completeness: Int
)
