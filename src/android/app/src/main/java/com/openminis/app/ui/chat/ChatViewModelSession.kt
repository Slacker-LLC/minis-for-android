package com.openminis.app.ui.chat

import com.openminis.app.agent.*

suspend fun ChatViewModel.ensureSessionForSettings() = agentSession.ensureSessionForSettings()

fun ChatViewModel.updateTitleAndCategory(title: String, category: String?) = agentSession.updateTitleAndCategory(title, category)

internal suspend fun ChatViewModel.ensureSession() = agentSession.ensureSession()

internal fun ChatViewModel.loadSession() = agentSession.loadSession()

fun ChatViewModel.selectEntry(entryId: String) = agentSession.selectEntry(entryId)
