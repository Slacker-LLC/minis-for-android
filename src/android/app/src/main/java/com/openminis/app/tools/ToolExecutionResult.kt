package com.openminis.app.tools

enum class ToolFailureKind {
    UNAVAILABLE,
    INVALID_ARGUMENTS,
    PERMISSION_DENIED,
    APPROVAL_REQUIRED,
    APPROVAL_DENIED,
    CHECKPOINT_FAILURE,
    EXECUTION_FAILED,
    OBSERVATION_FAILED,
    OUTCOME_UNKNOWN,
    TOOL_TIMEOUT,
    TRANSPORT_TIMEOUT,
    PROCESS_KILLED,
    USER_CANCELLATION,
    CLEANUP_FAILURE,
    ;

    /** A saved reply does not settle whether an already-started action took effect. */
    val outcomeUnknown: Boolean
        get() = when (this) {
            TOOL_TIMEOUT, TRANSPORT_TIMEOUT, PROCESS_KILLED, USER_CANCELLATION,
            CLEANUP_FAILURE, OBSERVATION_FAILED, OUTCOME_UNKNOWN -> true
            else -> false
        }
}

data class ToolExecutionResult(
    val output: String,
    val success: Boolean,
    val imageData: ByteArray? = null,
    val imageMimeType: String? = null,
    val toolTitle: String = "",
    /** Page URL at time of browser action (for display in preview). */
    val pageURL: String? = null,
    /** Local file path to screenshot JPEG (for thumbnail/detail view). */
    val imageFilePath: String? = null,
    /** Agent-visible linux path for persisted image bytes. */
    val imageLinuxPath: String? = null,
    /** True only for the tool execution budget, not transport/user cancellation. */
    val timedOut: Boolean = false,
    /** Machine-diagnosable execution failure classification. */
    val failureKind: ToolFailureKind? = null,
    /** Cleanup failure is reported separately so it never masks the primary result. */
    val cleanupFailure: String? = null,
    val executionIntentId: String? = null,
    val executionRunId: String? = null,
    val executionTaskId: String? = null,
    /** null for legacy/domain results which do not prove dispatch; set by the shared executor. */
    val executionStarted: Boolean? = null,
) {
    /** Shared fallback after a handler returned; explicit domain facts always take precedence. */
    fun classifiedAfterExecution(): ToolExecutionResult {
        val kind = failureKind ?: when {
            timedOut -> ToolFailureKind.TOOL_TIMEOUT
            !success -> ToolFailureKind.EXECUTION_FAILED
            else -> null
        }
        return copy(failureKind = kind, executionStarted = executionStarted ?: true,
            timedOut = timedOut || kind == ToolFailureKind.TOOL_TIMEOUT)
    }

    /** Transport facts; local model/run identities are deliberately not part of this payload. */
    fun executionFacts(): org.json.JSONObject = org.json.JSONObject()
        .put("success", success)
        .put("execution_started", executionStarted ?: org.json.JSONObject.NULL)
        .put("failure_kind", failureKind?.name ?: org.json.JSONObject.NULL)
        .put("outcome_unknown", failureKind?.outcomeUnknown == true)
        .put("timed_out", timedOut)
        .put("cleanup_failed", cleanupFailure != null)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ToolExecutionResult) return false
        return output == other.output && success == other.success &&
            imageData.contentEquals(other.imageData) &&
            imageMimeType == other.imageMimeType && toolTitle == other.toolTitle &&
            pageURL == other.pageURL && imageFilePath == other.imageFilePath &&
            imageLinuxPath == other.imageLinuxPath && timedOut == other.timedOut &&
            failureKind == other.failureKind && cleanupFailure == other.cleanupFailure &&
            executionIntentId == other.executionIntentId && executionRunId == other.executionRunId && executionTaskId == other.executionTaskId &&
            executionStarted == other.executionStarted
    }

    override fun hashCode(): Int {
        var result = output.hashCode()
        result = 31 * result + success.hashCode()
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        result = 31 * result + (imageMimeType?.hashCode() ?: 0)
        result = 31 * result + toolTitle.hashCode()
        result = 31 * result + (pageURL?.hashCode() ?: 0)
        result = 31 * result + (imageFilePath?.hashCode() ?: 0)
        result = 31 * result + (imageLinuxPath?.hashCode() ?: 0)
        result = 31 * result + timedOut.hashCode()
        result = 31 * result + (failureKind?.hashCode() ?: 0)
        result = 31 * result + (cleanupFailure?.hashCode() ?: 0)
        result = 31 * result + (executionIntentId?.hashCode() ?: 0)
        result = 31 * result + (executionRunId?.hashCode() ?: 0)
        result = 31 * result + (executionTaskId?.hashCode() ?: 0)
        result = 31 * result + (executionStarted?.hashCode() ?: 0)
        return result
    }
}
