package com.drkkr.strategos.domain.framework

data class ExecutiveStatusReason(
    val status: ExecutiveStatus,
    val blockingReasons: List<String> = emptyList(),
    val requiredActions: List<String> = emptyList(),
    val nextReviewDate: Long? = null,
    val accountableOwner: String = ""
) {
    fun hasBlockers() = blockingReasons.isNotEmpty()
    fun primaryBlocker() = blockingReasons.firstOrNull()
}
