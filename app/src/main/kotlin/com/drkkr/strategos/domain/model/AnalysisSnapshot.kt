package com.drkkr.strategos.domain.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class AnalysisSnapshot(
    val snapshotId: String = UUID.randomUUID().toString(),
    val scenarioId: String,
    val scenarioVersion: Int,
    val scenarioHash: String,
    val timestamp: Long = System.currentTimeMillis(),
    val appVersion: String = "0.1.0",
    val engineVersion: String = "1.0",
    val selectedModuleIds: List<String>,
    val executiveStatus: String,
    val candidateCount: Int,
    val overallConfidence: Double,
    val reportHash: String,
    // Phase 4: full scenario capture for audit and reproduction
    val scenarioJson: String = "",
    val selectedModuleVersions: Map<String, String> = emptyMap()
) {
    companion object {
        fun hashString(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
