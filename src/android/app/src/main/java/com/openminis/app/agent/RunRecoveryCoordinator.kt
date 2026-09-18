package com.openminis.app.agent

/**
 * [T-android-run-checkpoint] Reconciles persisted checkpoints into recovery
 * actions.
 *
 * Ported from Eta `ui/app/AgentRunRecoveryCoordinator.kt` (Mangi-11/Eta @
 * c15de97); attribution is centralised in PROVENANCE.md.
 *
 * Eta compares the checkpoint with a terminal outbox and the runtime's live
 * state. Minis is single-process, so the same three roles are played by the
 * checkpoint itself, the live run id this ViewModel owns, and whether this
 * process can answer "is that run still active" at all. The rule that carries
 * over unchanged is the important one: **without both a known active state and
 * a known terminal state, a checkpoint is not called interrupted** - it stays
 * undecided and is kept for the next attempt.
 */
object RunRecoveryCoordinator {

    sealed class Action {
        /** The run reached its terminal state; its result is already durable. */
        data class Completed(val checkpoint: RunCheckpointStore.Checkpoint) : Action()

        /** This process still owns the run; the UI should reattach to it. */
        data class Reattach(val checkpoint: RunCheckpointStore.Checkpoint) : Action()

        /** The run cannot still be live; its tail may be repaired. */
        data class Interrupted(
            val checkpoint: RunCheckpointStore.Checkpoint,
            val reason: String,
        ) : Action()

        /** Nothing may be concluded yet; the record is kept as it is. */
        data class Undecided(
            val checkpoint: RunCheckpointStore.Checkpoint,
            val reason: String,
        ) : Action()
    }

    data class Plan(val actions: List<Action>) {
        val completed: List<Action.Completed> get() = actions.filterIsInstance<Action.Completed>()
        val reattach: Action.Reattach? get() = actions.filterIsInstance<Action.Reattach>().firstOrNull()
        val interrupted: List<Action.Interrupted> get() = actions.filterIsInstance<Action.Interrupted>()
        val undecided: List<Action.Undecided> get() = actions.filterIsInstance<Action.Undecided>()
    }

    /**
     * @param locallyObservedRunId the run this process is currently driving, if any.
     * @param activeRunId the run the runtime reports as active; only meaningful
     *   when [activeStateKnown].
     * @param activeStateKnown false when this process cannot tell whether a run
     *   is still live (for example during a cold start with an unknown owner).
     * @param terminalStateKnown false when the terminal records could not be read.
     */
    fun plan(
        checkpoints: List<RunCheckpointStore.Checkpoint>,
        locallyObservedRunId: String? = null,
        activeRunId: String? = null,
        activeStateKnown: Boolean,
        terminalStateKnown: Boolean,
    ): Plan {
        val actions = mutableListOf<Action>()
        val own = locallyObservedRunId?.takeIf(String::isNotBlank)

        checkpoints.forEach { checkpoint ->
            when {
                checkpoint.runId == own -> Unit // this process owns it; nothing to recover
                checkpoint.terminal -> actions += Action.Completed(checkpoint)
                checkpoint.damaged -> actions += Action.Interrupted(
                    checkpoint,
                    "the checkpoint could not be decoded",
                )
                else -> Unit
            }
        }

        val unresolved = checkpoints.filterNot { it.runId == own || it.terminal || it.damaged }
        val active = if (activeStateKnown) {
            unresolved.singleOrNull { it.runId == activeRunId }
        } else {
            null
        }
        active?.let { actions += Action.Reattach(it) }

        unresolved.filterNot { it.runId == active?.runId }.forEach { checkpoint ->
            if (activeStateKnown && terminalStateKnown) {
                actions += Action.Interrupted(checkpoint, "no live run owns this checkpoint")
            } else {
                actions += Action.Undecided(
                    checkpoint,
                    if (!activeStateKnown) {
                        "the live run state is unknown"
                    } else {
                        "the terminal state is unknown"
                    },
                )
            }
        }
        return Plan(actions)
    }
}
