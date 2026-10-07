package com.openminis.app.ui.settings

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.modelEntryPickerItems
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.data.repository.addAgentLoopEntry

/**
 * T185 — full-screen picker for adding model entries to the agent-loop
 * usable set. Replaces the T182 ModalBottomSheet with the same
 * Scaffold + sectioned multi-select shape `AddModelsToGroupScreen` uses,
 * via the shared [modelEntryPickerItems] in `ui/components/`. Mirrors
 * iOS `AddAgentLoopModelsSheet` (which is a NavigationStack-wrapped
 * sheet content — same sectioned-list visual we land here).
 *
 * Multi-select with a confirm button in the top bar (vs the pre-T185
 * tap-and-add-immediately bottom sheet) so a user adding 5 models from
 * the same provider doesn't bounce in and out of the picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAgentLoopModelsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()

    val pinnedEntries = config.agentLoopModelEntryIds.toSet()
    val availableEntries = config.modelEntries.filter {
        !it.isHidden && it.id !in pinnedEntries
    }

    val searchQuery = remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val collapsedInstanceIds = remember(config) {
        mutableStateOf(config.instances.map { it.id }.toSet())
    }

    Scaffold(
        topBar = {
            MinisTopBar(
                title = { Text(stringResource(R.string.agent_loop_section_add_models_title)) },
                navigation = {
                    MinisTextButton(onClick = onBack) { Text(stringResource(R.string.common_cancel), fontSize = 17.sp) }
                },
                actions = {
                    MinisTextButton(
                        onClick = {
                            // Add in stable order so the section renders
                            // pinned items in the order the user saw them
                            // in the picker, not insertion-set order.
                            val orderedToAdd = availableEntries
                                .map { it.id }
                                .filter { it in selectedIds }
                            for (id in orderedToAdd) {
                                providerRepository.addAgentLoopEntry(id)
                            }
                            onBack()
                        },
                        enabled = selectedIds.isNotEmpty(),
                    ) {
                        Text(
                            stringResource(R.string.agent_loop_add_models_count, selectedIds.size),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
            )
        },
        containerColor = com.openminis.app.ui.settings.settingsPageBackground(),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            modelEntryPickerItems(
                instances = config.instances,
                availableEntries = availableEntries,
                selectedIds = selectedIds,
                onToggleSelection = { id ->
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                },
                searchQuery = searchQuery,
                collapsedInstanceIds = collapsedInstanceIds,
                emptyTextRes = R.string.agent_loop_section_no_available_models,
                emptySearchTextRes = R.string.agent_loop_search_no_match,
                searchPlaceholderRes = R.string.agent_loop_search_placeholder,
                clearContentDescriptionRes = R.string.agent_loop_search_clear,
            )
        }
    }
}
