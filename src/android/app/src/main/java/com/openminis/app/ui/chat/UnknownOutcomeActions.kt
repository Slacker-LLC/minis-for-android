package com.openminis.app.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.tools.ToolCheckpointStore
import com.openminis.app.tools.ToolOutcomeVerification
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** Human inspection is available even after the old Goal stopped or a legacy record lacks a nonce. */
@Composable
internal fun UnknownOutcomeActions(sessionId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember(sessionId) { mutableStateOf(emptyList<ToolCheckpointStore.IntentRecord>()) }
    var failed by remember(sessionId) { mutableStateOf(false) }
    var checking by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId) {
        while (isActive) {
            try {
                pending = withContext(Dispatchers.IO) { ToolOutcomeVerification.pending(context, sessionId)
                    .filterNot { com.openminis.app.tools.runtime.ToolExecutor.isExecutionInFlight(it.intentId) } }
                failed = false
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            delay(1500)
        }
    }
    if (failed) Text(stringResource(R.string.outcome_unreadable), style = MaterialTheme.typography.bodySmall)
    else if (pending.isNotEmpty()) Column {
        Text(stringResource(R.string.outcome_pending_title), style = MaterialTheme.typography.labelMedium)
        pending.distinctBy { it.intentId ?: ToolCheckpointStore.legacyKey(it) }.take(4).forEach { intent ->
            MinisTextButton(onClick = {
                scope.launch {
                    checking = true
                    try { withContext(Dispatchers.IO) { ToolOutcomeVerification.observeFromUi(context, sessionId, intent) } }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { Toast.makeText(context, R.string.outcome_kept_unknown, Toast.LENGTH_LONG).show() }
                    finally { checking = false }
                }
            }, enabled = !checking) {
                Text(stringResource(R.string.outcome_inspect_action, intent.toolName))
            }
        }
    }
}
