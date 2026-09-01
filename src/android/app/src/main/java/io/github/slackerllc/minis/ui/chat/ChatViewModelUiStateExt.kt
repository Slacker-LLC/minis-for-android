package io.github.slackerllc.minis.ui.chat

// [T-android-split-chat] Small UI-state toggle methods extracted from
// ChatViewModel as extension functions (verbatim): tool-detail sheet,
// browser sheet, memory sheet, attachment list. The 4 backing state fields
// were flipped private->internal. No logic change.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import io.github.slackerllc.minis.agent.Level
import io.github.slackerllc.minis.agent.ToolLoopDetector
import io.github.slackerllc.minis.browser.BrowserActionInput
import io.github.slackerllc.minis.browser.BrowserTabPool
import io.github.slackerllc.minis.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import io.github.slackerllc.minis.data.BPETokenizer
import io.github.slackerllc.minis.data.ContextOffload
import io.github.slackerllc.minis.data.ContextPolicy
import io.github.slackerllc.minis.logging.AppLogger
import io.github.slackerllc.minis.data.FileMentionIndex
import io.github.slackerllc.minis.data.db.CompactMarkerEntity
import io.github.slackerllc.minis.data.model.AgentContentPart
import io.github.slackerllc.minis.data.model.AgentToolDefinition
import io.github.slackerllc.minis.data.model.LLMMessage
import io.github.slackerllc.minis.data.model.LLMModel
import io.github.slackerllc.minis.data.model.LLMStreamChunk
import io.github.slackerllc.minis.data.model.LLMUsage
import io.github.slackerllc.minis.data.model.ModelGroup
import io.github.slackerllc.minis.data.model.ThinkingLevel
import io.github.slackerllc.minis.R
import io.github.slackerllc.minis.data.repository.ChatRepository
import io.github.slackerllc.minis.data.repository.MemoryRepository
import io.github.slackerllc.minis.data.repository.ProviderRepository
import io.github.slackerllc.minis.provider.ImageBudget
import io.github.slackerllc.minis.provider.LLMProvider
import io.github.slackerllc.minis.provider.ProviderFactory
import io.github.slackerllc.minis.runtime.ExecutionCoordinator
import io.github.slackerllc.minis.terminal.MinisOpenUrlBroker
import io.github.slackerllc.minis.terminal.MinisUrlMarker
import io.github.slackerllc.minis.tools.AgentTools
import io.github.slackerllc.minis.tools.FileEditTool
import io.github.slackerllc.minis.tools.FileReadTool
import io.github.slackerllc.minis.tools.FileWriteTool
import io.github.slackerllc.minis.tools.MemoryTools
import io.github.slackerllc.minis.tools.ReadImageTool
import io.github.slackerllc.minis.tools.ToolExecutionResult
import io.github.slackerllc.minis.offload.OffloadPermissionManager
import io.github.slackerllc.minis.service.SessionActivityTracker
import io.github.slackerllc.minis.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal fun ChatViewModel.openToolDetail(toolBlockId: String) {
    _selectedToolDetailId.value = toolBlockId
}

internal fun ChatViewModel.closeToolDetail() {
    _selectedToolDetailId.value = null
}

internal fun ChatViewModel.toggleBrowserSheet() {
    val opening = !_showBrowserSheet.value
    if (opening) browserTabPool.ensureTabForUI()
    _showBrowserSheet.value = opening
}

internal fun ChatViewModel.dismissBrowserSheet() {
    _showBrowserSheet.value = false
}

/**
 * Open the session browser sheet, focused on the tab whose URL matches
 * [url]. If no pool tab currently has that URL, a new tab is created and
 * loaded. Used by the tool-call preview's globe button so the agent's
 * existing browser_use page is reused when available instead of spawning
 * a duplicate tab.
 */
internal fun ChatViewModel.openBrowserSheetForUrl(url: String) {
    if (url.isBlank()) {
        browserTabPool.ensureTabForUI()
    } else {
        browserTabPool.selectOrCreateTabForURL(url)
    }
    _showBrowserSheet.value = true
}

internal fun ChatViewModel.toggleMemorySheet() {
    _showMemorySheet.value = !_showMemorySheet.value
}

internal fun ChatViewModel.dismissMemorySheet() {
    _showMemorySheet.value = false
}

internal fun ChatViewModel.addAttachment(attachment: InputAttachment) {
    _attachments.value = _attachments.value + attachment
}

internal fun ChatViewModel.removeAttachment(id: String) {
    _attachments.value = _attachments.value.filter { it.id != id }
}

internal fun ChatViewModel.clearAttachments() {
    _attachments.value = emptyList()
}
