package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.quota.Balance
import com.openminis.app.provider.quota.ProviderQuota
import com.openminis.app.provider.quota.ProviderQuotaRepository
import com.openminis.app.provider.quota.QuotaLevel
import com.openminis.app.provider.quota.UsageWindow
import kotlinx.coroutines.launch
import java.util.Locale

/** What the provider says is left: balances, or usage windows of a subscription. Shown only where a service answers. */
@Composable
fun ProviderQuotaSection(instance: ProviderInstance, providerRepository: ProviderRepository) {
    val context = LocalContext.current
    if (!ProviderQuotaRepository.supported(context, instance)) return
    val scope = rememberCoroutineScope()
    val states by ProviderQuotaRepository.all.collectAsState()
    val state = states[instance.id]
    LaunchedEffect(instance.id) { ProviderQuotaRepository.refresh(context, providerRepository, instance) }

    SettingsSection(header = stringResource(R.string.quota_header)) {
        val refresh: () -> Unit = {
            scope.launch { ProviderQuotaRepository.refresh(context, providerRepository, instance, force = true) }
        }
        when (state) {
            null, ProviderQuotaRepository.State.Loading ->
                SettingsRow(title = stringResource(R.string.quota_reading), showDivider = false)
            ProviderQuotaRepository.State.Unsupported -> Unit
            is ProviderQuotaRepository.State.Failed -> {
                state.last?.let { QuotaRows(it, refresh, showRefresh = false) }
                SettingsRow(
                    title = stringResource(R.string.quota_failed),
                    subtitle = state.message,
                    onClick = refresh,
                    showChevron = false,
                    showDivider = false,
                    titleColor = MaterialTheme.colorScheme.error,
                    trailing = { RefreshIcon() },
                )
            }
            is ProviderQuotaRepository.State.Ready -> QuotaRows(state.quota, refresh, showRefresh = true)
        }
    }
}

@Composable
private fun RefreshIcon() {
    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.quota_refresh), tint = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ColumnScope.QuotaRows(quota: ProviderQuota, refresh: () -> Unit, showRefresh: Boolean) {
    val rows = ArrayList<@Composable (Boolean) -> Unit>()
    if (quota.level == QuotaLevel.EMPTY) {
        rows.add { last -> SettingsRow(title = stringResource(R.string.quota_empty), showDivider = !last, titleColor = MaterialTheme.colorScheme.error) }
    }
    quota.plan?.let { plan -> rows.add { last -> SettingsRow(title = stringResource(R.string.quota_plan, plan), showDivider = !last) } }
    quota.balances.forEach { balance -> rows.add { last -> BalanceRow(balance, last) } }
    quota.windows.forEach { window -> rows.add { last -> WindowRow(window, last) } }
    if (showRefresh) {
        rows.add { last ->
            SettingsRow(
                title = stringResource(R.string.quota_refresh),
                onClick = refresh,
                showChevron = false,
                showDivider = !last,
                trailing = { RefreshIcon() },
            )
        }
    }
    rows.forEachIndexed { index, row -> row(index == rows.lastIndex) }
}

@Composable
private fun BalanceRow(balance: Balance, last: Boolean) {
    val detail = listOfNotNull(
        balance.granted?.let { stringResource(R.string.quota_granted, money(it)) },
        balance.toppedUp?.let { stringResource(R.string.quota_topped_up, money(it)) },
    ).joinToString(" · ").ifEmpty { null }
    SettingsRow(
        title = stringResource(R.string.quota_available, balance.currency, money(balance.total)),
        subtitle = detail,
        showDivider = !last,
    )
}

@Composable
private fun WindowRow(window: UsageWindow, last: Boolean) {
    val resets = window.resetAtEpochSec?.let { stringResource(R.string.quota_resets_in, duration(it * 1000L - System.currentTimeMillis())) }
    SettingsRow(
        title = stringResource(R.string.quota_window_used, window.label, window.usedPercent),
        subtitle = resets,
        showDivider = !last,
    )
}

private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)

/** "3h 12m", "2d 4h", "45m". */
internal fun duration(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0L)
    val days = minutes / 1_440L
    val hours = (minutes % 1_440L) / 60L
    val mins = minutes % 60L
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${mins}m"
        else -> "${mins}m"
    }
}
