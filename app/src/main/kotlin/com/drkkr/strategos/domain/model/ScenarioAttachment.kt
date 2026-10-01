package com.drkkr.strategos.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ScenarioAttachment(
    val id: String = UUID.randomUUID().toString(),
    val scenarioId: String = "",
    val displayName: String,
    val mimeType: String,
    val localUri: String = "",
    val sizeBytes: Long = 0L,
    val importedAt: Long = System.currentTimeMillis(),
    val extractedText: String = "",
    val extractionStatus: ExtractionStatus = ExtractionStatus.NONE,
    val sourceType: SourceType = SourceType.IMPORTED_FILE
) {
    @Serializable
    enum class ExtractionStatus { NONE, EXTRACTED, PARTIAL, FAILED, PENDING }

    @Serializable
    enum class SourceType { TYPED, PASTED, DICTATED, IMPORTED_TEXT, IMPORTED_PDF, ATTACHED_IMAGE, IMPORTED_FILE }
}
