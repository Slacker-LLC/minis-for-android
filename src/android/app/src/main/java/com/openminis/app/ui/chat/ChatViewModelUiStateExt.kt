package com.openminis.app.ui.chat

import com.openminis.app.agent.*

internal fun ChatViewModel.openToolDetail(toolBlockId: String) = agentSession.openToolDetail(toolBlockId)

internal fun ChatViewModel.closeToolDetail() = agentSession.closeToolDetail()

internal fun ChatViewModel.toggleBrowserSheet() = agentSession.toggleBrowserSheet()

internal fun ChatViewModel.dismissBrowserSheet() = agentSession.dismissBrowserSheet()

internal fun ChatViewModel.openBrowserSheetForUrl(url: String) = agentSession.openBrowserSheetForUrl(url)

internal fun ChatViewModel.toggleMemorySheet() = agentSession.toggleMemorySheet()

internal fun ChatViewModel.dismissMemorySheet() = agentSession.dismissMemorySheet()

internal fun ChatViewModel.addAttachment(attachment: InputAttachment) = agentSession.addAttachment(attachment)

internal fun ChatViewModel.removeAttachment(id: String) = agentSession.removeAttachment(id)

internal fun ChatViewModel.clearAttachments() = agentSession.clearAttachments()
