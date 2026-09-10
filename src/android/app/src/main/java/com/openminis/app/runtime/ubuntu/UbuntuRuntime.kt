package com.openminis.app.runtime.ubuntu

import android.content.Context
import android.util.Log
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.runtime.ExecutionCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

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
        val pid: Int? = null,
        val version: String? = null,
        val provisioned: Boolean = false,
        val guestUid: Int? = null,
        val guestGid: Int? = null,
        val sessionsRoot: String? = null,
        val layoutKnown: Boolean = false,
        val hostWorkspace: String? = null,
        val hostMemory: String? = null,
        val hostSkills: String? = null,
        val hostShared: String? = null,
        val externalMountDigest: String? = null,
        val externalMountVerified: Boolean = false,
        val lastError: String? = null,
        val mock: Boolean = false,
        val statusFresh: Boolean = false,
    )

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var redirectPaths: Boolean = false
        private set

    @Volatile
    private var appContext: Context? = null

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        UbuntuKernel.init(ctx)
        isInitialized = true
        redirectPaths = true
        Log.i(TAG, "initialized direct Ubuntu backend uid=${ctx.applicationInfo.uid}")
    }

    suspend fun ensureReady(): Snapshot {
        if (!isInitialized) {
            val error = "UbuntuRuntime.init(context) has not been called"
            return fail(error)
        }
        val status = UbuntuKernel.ensureReady()
        if (!status.ready) {
            RootNetworkProxy.stop()
        }
        val uid = status.appUid ?: appContext?.applicationInfo?.uid
        val next = if (status.ready && uid != null) {
            Snapshot(
                running = true,
                available = true,
                pid = null,
                version = status.version,
                provisioned = true,
                guestUid = uid,
                guestGid = uid,
                sessionsRoot = UbuntuPaths.hostSessions,
                layoutKnown = true,
                hostWorkspace = UbuntuPaths.hostWorkspace,
                hostMemory = UbuntuPaths.hostMemory,
                hostSkills = UbuntuPaths.hostSkills,
                hostShared = UbuntuPaths.hostShared,
                externalMountVerified = true,
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
        redirectPaths = next.running
        return next
    }

    suspend fun refresh(): Snapshot = ensureReady()

    suspend fun start(): Snapshot = ensureReady()

    suspend fun stop(): Snapshot {
        ExecutionCoordinator.stopCurrentCommand()
        RootNetworkProxy.stop()
        val next = _snapshot.value.copy(running = false, available = false, statusFresh = true)
        _snapshot.value = next
        redirectPaths = false
        return next
    }

    suspend fun inspectRootfs(): RootfsHealth = UbuntuKernel.inspectRootfs()

    suspend fun refreshDns(nameservers: List<String> = emptyList()): Boolean {
        if (!isInitialized) return false
        return UbuntuKernel.refreshDns(nameservers)
    }

    suspend fun reconcileExternalMounts(entries: List<MountedFoldersStore.Entry>? = null): Boolean =
        UbuntuKernel.reconcileExternalMounts(entries)

    fun findSu(): String? = UbuntuKernel.findSu()

    fun paths(): JSONObject = JSONObject()
        .put("hostWorkspace", UbuntuPaths.hostWorkspace)
        .put("hostSessions", UbuntuPaths.hostSessions)
        .put("guestWorkspace", "/workspace")
        .put("rootfs", UbuntuPaths.HOST_ROOTFS)
        .put("backend", "direct-chroot")
        .put("guestUid", appContext?.applicationInfo?.uid)

    private fun fail(detail: String): Snapshot {
        val next = _snapshot.value.copy(
            running = false,
            available = false,
            lastError = detail,
            statusFresh = false,
        )
        _snapshot.value = next
        redirectPaths = false
        return next
    }
}
