package com.drkkr.strategos.domain.framework

enum class ModuleRunStatus {
    SUCCESS,
    SKIPPED_NOT_APPLICABLE,
    SKIPPED_INSUFFICIENT_INPUT,
    FAILED_RECOVERABLY
}

data class ModuleRunResult(
    val moduleId: String,
    val moduleName: String,
    val status: ModuleRunStatus,
    val result: AnalysisResult?,
    val userMessage: String?,
    val diagnosticId: String?,
    val durationMs: Long
) {
    fun isRequired(): Boolean = result?.frameworkCategory == FrameworkCategory.CORE

    fun toAnalysisResult(): AnalysisResult? = result

    companion object {
        fun success(result: AnalysisResult, durationMs: Long) = ModuleRunResult(
            moduleId = result.moduleId,
            moduleName = result.moduleName,
            status = ModuleRunStatus.SUCCESS,
            result = result,
            userMessage = null,
            diagnosticId = null,
            durationMs = durationMs
        )

        fun skippedNotApplicable(module: FrameworkModule, reason: String) = ModuleRunResult(
            moduleId = module.id,
            moduleName = module.name,
            status = ModuleRunStatus.SKIPPED_NOT_APPLICABLE,
            result = null,
            userMessage = reason,
            diagnosticId = null,
            durationMs = 0L
        )

        fun skippedInsufficientInput(module: FrameworkModule, reason: String) = ModuleRunResult(
            moduleId = module.id,
            moduleName = module.name,
            status = ModuleRunStatus.SKIPPED_INSUFFICIENT_INPUT,
            result = null,
            userMessage = reason,
            diagnosticId = null,
            durationMs = 0L
        )

        @Suppress("UNUSED_PARAMETER")
        fun failed(module: FrameworkModule, e: Exception, diagnosticId: String): ModuleRunResult {
            val safeMessage = "${module.name} could not run. Other compatible analyses may still be available."
            return ModuleRunResult(
                moduleId = module.id,
                moduleName = module.name,
                status = ModuleRunStatus.FAILED_RECOVERABLY,
                result = null,
                userMessage = safeMessage,
                diagnosticId = diagnosticId,
                durationMs = 0L
            )
        }
    }
}
