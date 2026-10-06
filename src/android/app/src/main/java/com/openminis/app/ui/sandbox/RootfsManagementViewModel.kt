package com.openminis.app.ui.sandbox

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.R
import com.openminis.app.sandbox.RootfsInstallState
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.runtime.ubuntu.RootfsHealth
import com.openminis.app.runtime.ubuntu.RootfsHealthCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RootfsManagementUiState(
    val isInstalled: Boolean = false,
    val isProcessing: Boolean = false,
    val statusMessage: String = "",
    val resultMessage: String? = null,
    val lastOperationSuccess: Boolean = false,
    val rootfsSize: Long = 0L,
    val rootfsPath: String = "",
    val rootfsHealthCode: RootfsHealthCode = RootfsHealthCode.UNKNOWN,
    val rootfsHealthDetail: String? = null,
    /** Current install phase + 0..1 progress (null when not installing). */
    val installProgress: Float? = null,
)

internal fun RootfsManagementUiState.withHealth(health: RootfsHealth): RootfsManagementUiState = copy(
    isInstalled = health.healthy,
    rootfsHealthCode = health.code,
    rootfsHealthDetail = health.detail,
    rootfsSize = if (health.healthy) health.sizeBytes ?: 0L else 0L,
)

class RootfsManagementViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(RootfsManagementUiState())
    val uiState: StateFlow<RootfsManagementUiState> = _uiState.asStateFlow()

    private var progressJob: Job? = null

    /**
     * Subscribe to the manager's installState and mirror progress + status
     * text into [_uiState]. Cancelled on completion so we don't leak a job
     * across multiple install() calls.
     */
    private fun observeInstallProgress(manager: RootfsManager, context: Context) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            manager.installState.collect { state ->
                when (state) {
                    is RootfsInstallState.Idle -> Unit
                    is RootfsInstallState.Preparing ->
                        _uiState.value = _uiState.value.copy(
                            statusMessage = context.getString(R.string.rootfs_status_preparing),
                            installProgress = 0f,
                        )
                    is RootfsInstallState.Extracting ->
                        _uiState.value = _uiState.value.copy(
                            statusMessage = context.getString(R.string.rootfs_status_extracting, (state.progress * 100).toInt()),
                            installProgress = state.progress,
                        )
                    is RootfsInstallState.Finalizing ->
                        _uiState.value = _uiState.value.copy(
                            statusMessage = context.getString(R.string.rootfs_status_finalizing),
                            installProgress = 1f,
                        )
                    is RootfsInstallState.Installed,
                    is RootfsInstallState.Failed -> {
                        _uiState.value = _uiState.value.copy(installProgress = null)
                        progressJob?.cancel()
                    }
                }
            }
        }
    }

    fun refresh(context: Context) {
        val manager = RootfsManager.getInstance(context)

        _uiState.value = _uiState.value.copy(
            rootfsPath = manager.rootfsDir.absolutePath,
        )

        viewModelScope.launch {
            runCatching {
                val health = manager.checkHealth()
                _uiState.value = _uiState.value.withHealth(health)
            }
        }
    }

    fun install(context: Context) {
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = context.getString(R.string.rootfs_status_installing),
            resultMessage = null,
            installProgress = 0f,
        )

        val manager = RootfsManager.getInstance(context)
        observeInstallProgress(manager, context)
        viewModelScope.launch {
            try {
                manager.installIfNeeded()
                val health = manager.checkHealth()
                val healthState = _uiState.value.withHealth(health)
                _uiState.value = _uiState.value.copy(
                    isInstalled = healthState.isInstalled,
                    isProcessing = false,
                    lastOperationSuccess = health.healthy,
                    resultMessage = if (health.healthy) {
                        context.getString(R.string.rootfs_installed_successfully)
                    } else {
                        context.getString(R.string.rootfs_install_failed_detail, health.code, health.detail)
                    },
                    rootfsHealthCode = health.code,
                    rootfsHealthDetail = health.detail,
                    installProgress = null,
                )
                refresh(context)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = false,
                    resultMessage = context.getString(R.string.rootfs_install_failed, e.message ?: ""),
                    installProgress = null,
                )
            }
        }
    }

    fun resetRootfs(context: Context) {
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = context.getString(R.string.rootfs_status_resetting),
            resultMessage = null,
            installProgress = 0f,
        )

        val manager = RootfsManager.getInstance(context)
        observeInstallProgress(manager, context)
        viewModelScope.launch {
            try {
                manager.reset()

                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = true,
                    resultMessage = context.getString(R.string.rootfs_reset_complete),
                    installProgress = null,
                )
                refresh(context)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = false,
                    resultMessage = context.getString(R.string.rootfs_reset_failed, e.message ?: ""),
                    installProgress = null,
                )
            }
        }
    }
}
