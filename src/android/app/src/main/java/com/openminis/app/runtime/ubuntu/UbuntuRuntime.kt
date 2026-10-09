package com.openminis.app.runtime.ubuntu

import android.content.Context
import android.util.Log
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.runtime.ExecutionCoordinator
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.sandbox.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Compatibility facade over [UbuntuKernel].
 *
 * There is no daemon/Unix-socket lifecycle here anymore. `running` means the
 * direct Root backend is authorized and the Ubuntu rootfs is healthy; actual
 * guest processes are per-session [RootPersistentShell] instances owned by
 * [ExecutionCoordinator].
 */
object UbuntuRuntime {
    private const val TAG = "UbuntuRuntime"

    data class Snapshot(
        val running: Boolean = false,
        val available: Boolean = false,
        val provisioned: Boolean = false,
        val lastError: String? = null,
        val statusFresh: Boolean = false,
    )

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    private var appContext: Context? = null

    private val lifecycleLock = Mutex()
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        UbuntuKernel.init(ctx)
        isInitialized = true
        Log.i(TAG, "initialized direct Ubuntu backend uid=${ctx.applicationInfo.uid}")
    }

    /**
     * Readiness is a full set of Root probes (a `su` process each), so it is not repeated for every command:
     * after one succeeds the runtime counts as ready for [ReadinessLease.IDLE_MS], and every call inside that
     * window pushes the end out again. Ten idle minutes, a stop, a maintenance run or a failed command end it,
     * and the next call checks everything again. [force] always checks.
     */
    private val lease = ReadinessLease()

    suspend fun ensureReady(force: Boolean = false): Snapshot = lifecycleLock.withLock {
        val current = _snapshot.value
        if (!force && current.running && lease.renewIfLive()) return@withLock current
        checkLocked()
    }

    /** The runtime may have changed under the lease (a command failed, mounts moved): check again next time. */
    fun invalidateReadiness() = lease.invalidate()

    private suspend fun checkLocked(): Snapshot {
        val next = ensureReadyLocked()
        if (next.running) lease.renew() else lease.invalidate()
        return next
    }

    /**
     * Prepare a guest shell while holding the same lifecycle gate used by
     * Stop and rootfs/mount maintenance. This prevents a launch from being
     * assembled in the gap between readiness and namespace creation.
     */
    internal suspend fun prepareLaunch(
        sessionId: String?,
        interactive: Boolean,
    ): UbuntuKernel.Launch = RuntimePathRegistry.withMountMutationLock {
        lifecycleLock.withLock {
            val ready = checkLocked()
            check(ready.running && ready.lastError == null) {
                ready.lastError ?: "Ubuntu runtime is not ready"
            }
            UbuntuKernel.prepareLaunch(sessionId, interactive)
        }
    }

    /** Run a Root-owned rootfs/config maintenance operation with guest work stopped. */
    internal suspend fun <T> withRuntimeStopped(block: suspend () -> T): T = lifecycleLock.withLock {
        stopOwnedProcessesLocked()
        block()
    }

    private suspend fun ensureReadyLocked(): Snapshot {
        if (!isInitialized) {
            val error = "UbuntuRuntime.init(context) has not been called"
            return fail(error)
        }
        val status = UbuntuKernel.ensureReady()
        if (!status.ready) {
            // A failed readiness check must not leave an old shell using a
            // stale rootfs, mount set, or proxy. Stop is idempotent and the
            // generation gate prevents a shell that was starting concurrently
            // from being published after this failure.
            stopOwnedProcessesLocked()
        }
        val uid = status.appUid ?: appContext?.applicationInfo?.uid
        val next = if (status.ready && uid != null) {
            Snapshot(
                running = true,
                available = true,
                provisioned = true,
                lastError = null,
                statusFresh = true,
            )
        } else {
            _snapshot.value.copy(
                running = false,
                available = false,
                lastError = status.error ?: "direct Ubuntu backend unavailable",
                statusFresh = true,
            )
        }
        _snapshot.value = next
        return next
    }

    suspend fun stop(): Snapshot = lifecycleLock.withLock {
        stopOwnedProcessesLocked()
    }

    suspend fun reconcileExternalMounts(entries: List<MountedFoldersStore.Entry>? = null): Boolean =
        lifecycleLock.withLock { UbuntuKernel.reconcileExternalMounts(entries) }

    private fun fail(detail: String): Snapshot {
        val next = _snapshot.value.copy(
            running = false,
            available = false,
            lastError = detail,
            statusFresh = false,
        )
        _snapshot.value = next
        return next
    }

    private suspend fun stopOwnedProcessesLocked(): Snapshot {
        lease.invalidate()
        TerminalSession.stopAllAndJoin()
        ExecutionCoordinator.stopCurrentCommand()
        RootNetworkProxy.stop()
        val next = _snapshot.value.copy(running = false, available = false, statusFresh = true)
        _snapshot.value = next
        return next
    }
}

/** Sliding "checked recently" window for [UbuntuRuntime.ensureReady]; the clock is injectable for tests. */
internal class ReadinessLease(
    private val idleMs: Long = IDLE_MS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile
    private var until = Long.MIN_VALUE

    /** True and extended when the window is still open; false when it has run out or was invalidated. */
    fun renewIfLive(): Boolean {
        val t = now()
        if (t >= until) return false
        until = t + idleMs
        return true
    }

    fun renew() {
        until = now() + idleMs
    }

    fun invalidate() {
        until = Long.MIN_VALUE
    }

    companion object {
        const val IDLE_MS = 10L * 60 * 1000
    }
}
