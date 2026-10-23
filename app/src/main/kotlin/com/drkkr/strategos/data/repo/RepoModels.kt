package com.drkkr.strategos.data.repo

enum class MessageRole { USER, ASSISTANT, TOOL_CALL, TOOL_RESULT, EVENT, ERROR }

enum class UiKind { NONE, CHANGE_SET, QUESTIONS, RECOMMENDATION, WHAT_IF, STRESS_TEST, UNDERSTANDING, SUPPORT }

enum class ChangeSetStatus { PENDING, APPLIED, PARTIAL, REJECTED }

enum class MemoryKind { FACT, OPEN_QUESTION, PREFERENCE, DECISION_NOTE }

enum class UiMode { GUIDED, EXPERT }
