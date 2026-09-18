package com.openminis.app.tools

enum class ToolFailureKind {
    TOOL_TIMEOUT,
    TRANSPORT_TIMEOUT,
    PROCESS_KILLED,
    USER_CANCELLATION,
    CLEANUP_FAILURE,
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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ToolExecutionResult) return false
        return output == other.output && success == other.success &&
            imageData.contentEquals(other.imageData) &&
            imageMimeType == other.imageMimeType && toolTitle == other.toolTitle &&
            pageURL == other.pageURL && imageFilePath == other.imageFilePath &&
            imageLinuxPath == other.imageLinuxPath && timedOut == other.timedOut &&
            failureKind == other.failureKind && cleanupFailure == other.cleanupFailure
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
        return result
    }
}
