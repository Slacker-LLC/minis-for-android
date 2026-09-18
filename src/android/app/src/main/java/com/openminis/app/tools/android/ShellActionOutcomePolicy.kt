package com.openminis.app.tools.android

/** A timed-out shell process cannot prove that an already-sent input action did not run. */
object ShellActionOutcomePolicy {
    enum class Outcome {
        SUCCEEDED,
        FAILED,
        TIMED_OUT,
    }

    fun classify(exitCode: Int): Outcome = when (exitCode) {
        0 -> Outcome.SUCCEEDED
        PROCESS_TIMEOUT_EXIT_CODE -> Outcome.TIMED_OUT
        else -> Outcome.FAILED
    }

    /** Minis' privileged runner reports its own deadline as 124 (see ShizukuOffloadHandler T343). */
    const val PROCESS_TIMEOUT_EXIT_CODE = 124
}
