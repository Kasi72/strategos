package com.drkkr.strategos.data.repo

enum class MessageRole { USER, ASSISTANT, TOOL_CALL, TOOL_RESULT, EVENT, ERROR }

enum class UiKind { NONE, CHANGE_SET, QUESTIONS, RECOMMENDATION, WHAT_IF, STRESS_TEST, UNDERSTANDING, SUPPORT }

enum class ChangeSetStatus { PENDING, APPLIED, PARTIAL, REJECTED }

enum class MemoryKind { FACT, OPEN_QUESTION, PREFERENCE, DECISION_NOTE }

enum class UiMode { GUIDED, EXPERT }

/**
 * One conversation thread as the Overview's Conversations section needs it: the last visible line
 * (raw text; the screen truncates to one line), its time, and whether the chat button opens it now.
 */
data class ThreadSummary(
    val id: String,
    val title: String,
    val lastLine: String,
    val lastAt: Long,
    val isOpenNow: Boolean
)
