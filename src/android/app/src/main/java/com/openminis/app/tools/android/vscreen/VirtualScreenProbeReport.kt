package com.openminis.app.tools.android.vscreen

/** A compact, serializable result for one VScreen capability probe. */
internal data class VirtualScreenProbeStep(
    val id: String,
    val status: String,
    val code: String,
    val detail: String = "",
)

internal data class VirtualScreenProbeReport(
    val timestampMs: Long,
    val fingerprint: String,
    val steps: List<VirtualScreenProbeStep>,
) {
    val passed: Boolean get() = steps.isNotEmpty() && steps.all { it.status == "pass" || it.status == "warning" }

    fun toJson(): String = buildString {
        append("{\"schemaVersion\":1,\"passed\":").append(passed)
        append(",\"timestampMs\":").append(timestampMs)
        append(",\"fingerprint\":\"").append(jsonEscape(fingerprint)).append("\",\"steps\":[")
        steps.forEachIndexed { index, step ->
            if (index > 0) append(',')
            append("{\"id\":\"").append(jsonEscape(step.id))
            append("\",\"status\":\"").append(jsonEscape(step.status))
            append("\",\"code\":\"").append(jsonEscape(step.code))
            append("\",\"detail\":\"").append(jsonEscape(step.detail)).append("\"}")
        }
        append("]}")
    }

    companion object {
        fun jsonEscape(value: String): String = buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
        }
    }
}

/** Isolates each probe stage so a failure is recorded rather than aborting later diagnostics. */
internal class VirtualScreenProbeRecorder {
    private val steps = mutableListOf<VirtualScreenProbeStep>()

    fun record(id: String, status: String, code: String, detail: String = "") {
        require(status in setOf("pass", "warning", "fail", "skipped"))
        steps += VirtualScreenProbeStep(id, status, code, detail.take(512))
    }

    fun check(id: String, passCode: String, block: () -> String) {
        try {
            record(id, "pass", passCode, block())
        } catch (error: Throwable) {
            val cause = rootCause(error)
            val code = (error as? VirtualScreenProbeFailure)?.reasonCode ?: classify(cause)
            record(id, "fail", code, cause.message ?: error.message ?: error::class.java.simpleName)
        }
    }

    fun warning(id: String, code: String, detail: String) = record(id, "warning", code, detail)
    fun skipped(id: String, code: String, detail: String = "") = record(id, "skipped", code, detail)
    fun report(timestampMs: Long, fingerprint: String) =
        VirtualScreenProbeReport(timestampMs, fingerprint, steps.toList())

    private fun rootCause(error: Throwable): Throwable =
        generateSequence(error) { it.cause }.take(8).last()

    private fun classify(error: Throwable): String = when (error) {
        is SecurityException -> "permission_denied"
        is java.util.concurrent.TimeoutException -> "timeout"
        is ClassNotFoundException, is NoSuchMethodException, is NoSuchFieldException -> "hidden_api_unavailable"
        else -> "probe_step_failed"
    }
}

internal class VirtualScreenProbeFailure(
    val reasonCode: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
