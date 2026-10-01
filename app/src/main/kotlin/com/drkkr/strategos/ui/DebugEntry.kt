package com.drkkr.strategos.ui

data class DebugEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,
    val level: Level,
    val message: String,
    val detail: String = ""
) {
    enum class Level { ERROR, WARNING, INFO }
}
