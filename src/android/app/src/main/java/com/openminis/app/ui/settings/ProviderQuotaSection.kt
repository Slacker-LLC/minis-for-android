package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
            is ProviderQuotaRepository.State.Console -> SettingsRow(
                title = stringResource(R.string.quota_open_console),
                subtitle = state.url,
                onClick = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(state.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                showDivider = false,
            )
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
    if (quota.unlimited) rows.add { last -> SettingsRow(title = stringResource(R.string.quota_unlimited), showDivider = !last) }
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
        title = stringResource(R.string.quota_available, balance.currency, money(balance.total)).replace("  ", " ").trim(),
        subtitle = detail,
        showDivider = !last,
    )
}

@Composable
private fun WindowRow(window: UsageWindow, last: Boolean) {
    val resets = window.resetAtEpochSec?.let { stringResource(R.string.quota_resets_in, duration(it * 1000L - System.currentTimeMillis())) }
    SettingsRow(
        title = stringResource(R.string.quota_window_left, window.label, (100 - window.usedPercent).coerceIn(0, 100)),
        subtitle = resets,
        showDivider = !last,
    )
}

private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)

internal fun duration(ms: Long): String = com.openminis.app.provider.quota.ProviderQuotaText.duration(ms)

private val LowColor = Color(0xFFF5A623)
private val EmptyColor = Color(0xFFE5484D)

private fun levelColor(level: QuotaLevel, normal: Color): Color = when (level) {
    QuotaLevel.OK -> normal
    QuotaLevel.LOW -> LowColor
    QuotaLevel.EMPTY -> EmptyColor
}

/** The one short "what is left" for a pill: the biggest balance, else the tightest window, else "no limit". */
@Composable
private fun quotaHeadline(quota: ProviderQuota): String? = when {
    quota.balances.isNotEmpty() -> {
        val b = quota.balances.maxBy { it.total }
        stringResource(R.string.quota_available, b.currency, money(b.total)).replace("  ", " ").trim()
    }
    quota.windows.isNotEmpty() -> {
        val tightest = quota.windows.maxBy { it.usedPercent }
        stringResource(R.string.quota_window_left, tightest.label, (100 - tightest.usedPercent).coerceIn(0, 100))
    }
    quota.unlimited -> stringResource(R.string.quota_unlimited)
    else -> null
}

/**
 * What is left on [instance], as a small pill (model picker, model groups). Reads when shown; the repository's short cache
 * keeps this from asking again on every recomposition. Shows nothing where the service has no balance source.
 */
@Composable
fun QuotaPill(instance: ProviderInstance, providerRepository: ProviderRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (!ProviderQuotaRepository.supported(context, instance)) return
    val states by ProviderQuotaRepository.all.collectAsState()
    LaunchedEffect(instance.id) { ProviderQuotaRepository.refresh(context, providerRepository, instance) }
    val quota = ((states[instance.id] as? ProviderQuotaRepository.State.Ready)?.quota
        ?: (states[instance.id] as? ProviderQuotaRepository.State.Failed)?.last) ?: return
    val text = quotaHeadline(quota) ?: return
    androidx.compose.material3.Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = levelColor(quota.level, MaterialTheme.colorScheme.onSurfaceVariant),
        maxLines = 1,
        modifier = modifier
            .background(levelColor(quota.level, MaterialTheme.colorScheme.onSurface).copy(alpha = 0.08f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A small dot beside the model name when the active provider is running low (amber) or out (red); nothing while all is well. */
@Composable
fun QuotaDot(instanceId: String?, providerRepository: ProviderRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val config by providerRepository.config.collectAsState()
    val instance = config.instances.firstOrNull { it.id == instanceId } ?: return
    if (!ProviderQuotaRepository.supported(context, instance)) return
    val states by ProviderQuotaRepository.all.collectAsState()
    LaunchedEffect(instance.id) { ProviderQuotaRepository.refresh(context, providerRepository, instance) }
    val quota = (states[instance.id] as? ProviderQuotaRepository.State.Ready)?.quota ?: return
    if (quota.level == QuotaLevel.OK) return
    androidx.compose.foundation.layout.Box(
        modifier = modifier.size(7.dp).background(levelColor(quota.level, Color.Unspecified), CircleShape),
    )
}
