package com.openminis.app.ui.sandbox

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.runtime.distribution.RuntimeDistributionManager
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
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
    val hasBackup: Boolean = false,
    /** Current install phase + 0..1 progress (null when not installing). */
    val installProgress: Float? = null,
)

class RootfsManagementViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(RootfsManagementUiState())
    val uiState: StateFlow<RootfsManagementUiState> = _uiState.asStateFlow()

    fun refresh(context: Context) {
        val manager = RootfsManager.getInstance(context)

        _uiState.value = _uiState.value.copy(
            isInstalled = false,
            rootfsPath = manager.rootfsDir.absolutePath,
        )

        viewModelScope.launch {
            runCatching { manager.checkHealth() }.onSuccess { health ->
                val size = if (health.healthy) {
                    runCatching { manager.getRootfsSize() }.getOrDefault(0L)
                } else {
                    0L
                }
                _uiState.value = _uiState.value.copy(
                    isInstalled = health.healthy,
                    rootfsSize = size,
                )
            }
        }
    }

    fun install(context: Context) {
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = "Installing rootfs...",
            resultMessage = null,
            installProgress = 0f,
        )

        viewModelScope.launch {
            try {
                val snapshot = UbuntuRuntime.ensureReady()
                check(snapshot.running && snapshot.provisioned) {
                    snapshot.lastError ?: "runtime did not become ready"
                }
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = true,
                    resultMessage = "Rootfs installed successfully",
                    installProgress = null,
                )
                refresh(context)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = false,
                    resultMessage = "Installation failed: ${e.message}",
                    installProgress = null,
                )
            }
        }
    }

    fun resetRootfs(context: Context, keepUserData: Boolean) {
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = "Resetting rootfs...",
            resultMessage = null,
            installProgress = 0f,
        )

        viewModelScope.launch {
            try {
                val result = UbuntuRuntime.resetRootfs()
                check(result.outcome == RuntimeDistributionManager.DeploymentOutcome.RESET) {
                    result.detail
                }
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = true,
                    hasBackup = false,
                    resultMessage = if (keepUserData) {
                        "Rootfs reset; persistent user data was preserved"
                    } else {
                        "Rootfs reset complete"
                    },
                    installProgress = null,
                )
                refresh(context)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = false,
                    resultMessage = "Reset failed: ${e.message}",
                    installProgress = null,
                )
            }
        }
    }

    fun restoreBackup(context: Context) {
        _uiState.value = _uiState.value.copy(
            resultMessage = "Persistent user data is stored outside the rootfs; no backup is required",
            lastOperationSuccess = true,
        )
    }
}
