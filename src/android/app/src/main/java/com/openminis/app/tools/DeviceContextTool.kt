package com.openminis.app.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.accessibility.PackageWindowVisibility
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.context.DeviceContextPolicy
import com.openminis.app.tools.runtime.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * [T-eta-device-context] One call for the environment a model needs before acting: what time it
 * is here, what is on screen, whether the screen is even on, how much battery is left, which
 * transport is up, and where the device last was.
 *
 * Ported from Eta `agent/tool/DeviceContextTool.kt` (`get_current_context`) and the alarm-table
 * hint that tells callers to use it before relative-date math (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md. Eta returns the time environment plus the latest known
 * location; this app also reports the foreground app, battery, screen and transport because its
 * turns run against a device it is actively driving.
 *
 * Nothing here prompts or forces a fix: location is a last-known read and only when the
 * permission is already granted. A caller that needs a fresh fix uses android.location.get.
 */
object DeviceContextTool {
    const val NAME = "android.context"

    val aliases = listOf("get_current_context", "device_context")

    internal fun snapshot(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): JSONObject = JSONObject()
        .put("ok", true)
        .put("time", DeviceContextPolicy.timeEnvironment(nowMillis, zone, locale))
        .put("screen", screenState(context))
        .put("battery", batteryState(context))
        .put("network", networkState(context))
        .put("foreground", foregroundState())
        .put("location", locationState(context))

    private fun screenState(context: Context): JSONObject = runCatching {
        val power = context.getSystemService(PowerManager::class.java)
        JSONObject().put("interactive", power?.isInteractive ?: false)
    }.getOrElse { error ->
        JSONObject().put("status", "unavailable").put("detail", error.message ?: "power manager unavailable")
    }

    private fun batteryState(context: Context): JSONObject = runCatching {
        val manager = context.getSystemService(BatteryManager::class.java)
        val status = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        JSONObject()
            .put("percent", manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            .put("charging", charging)
    }.getOrElse { error ->
        JSONObject().put("status", "unavailable").put("detail", error.message ?: "battery manager unavailable")
    }

    private fun networkState(context: Context): JSONObject = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        val transport = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        JSONObject()
            .put("transport", transport)
            .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
    }.getOrElse { error ->
        JSONObject().put("status", "unavailable").put("detail", error.message ?: "connectivity unavailable")
    }

    private fun foregroundState(): JSONObject {
        val service = MinisAccessibilityService.getInstance()
            ?: return JSONObject().put("status", "accessibility_not_connected")
        val window = service.foregroundWindow()
        if (window.visibility != PackageWindowVisibility.VISIBLE) {
            return JSONObject().put("status", "unknown").put("visibility", window.visibility.name)
        }
        return JSONObject()
            .put("package_name", window.packageName ?: JSONObject.NULL)
            .put("window", window.className ?: JSONObject.NULL)
    }

    private fun locationState(context: Context): JSONObject {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            return JSONObject()
                .put("status", "permission_not_granted")
                .put("hint", "use " + "android.location.get" + " if a fresh fix is actually needed")
        }
        return runCatching {
            val manager = context.getSystemService(LocationManager::class.java)
            val freshest = manager?.getProviders(true)
                ?.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                ?.maxByOrNull { it.time }
            if (freshest == null) {
                JSONObject().put("status", "unavailable")
            } else {
                JSONObject()
                    .put("status", "last_known")
                    .put("provider", freshest.provider ?: JSONObject.NULL)
                    .put("latitude", freshest.latitude)
                    .put("longitude", freshest.longitude)
                    .put("accuracy_m", freshest.accuracy.toDouble())
                    .put("age_s", ((System.currentTimeMillis() - freshest.time) / 1000L).coerceAtLeast(0L))
            }
        }.getOrElse { error ->
            JSONObject().put("status", "unavailable").put("detail", error.message ?: "location unavailable")
        }
    }
}

class DeviceContextHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = DeviceContextTool.NAME,
        description = "Read the current device environment in one call: local time with offset, weekday and " +
            "timezone (convert relative dates with this, never by guessing), screen on/off, battery, network " +
            "transport, the foreground app when Accessibility is connected, and the last known location without " +
            "prompting or forcing a fix. A fresh location fix needs android.location.get.",
        parameters = emptyMap(),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        withContext(Dispatchers.IO) {
            ToolExecutionResult(DeviceContextTool.snapshot(context).toString(2), true)
        }
}
