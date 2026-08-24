package com.openminis.app.tools

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult as WifiScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.tools.runtime.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only Wi-Fi and Bluetooth capabilities using ordinary Android APIs.
 *
 * Network/Bluetooth mutation is deliberately absent: modern Android requires
 * user-mediated Wi-Fi suggestions/specifiers and disallows app-controlled
 * Bluetooth enable/disable. Those stay separate from this safe query layer.
 */
object AndroidConnectivityOps {

    private suspend fun requirePermissions(context: Context, permissions: List<String>): ToolExecutionResult? {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return null
        val result = OffloadPermissionManager.requestAndroidPermission(missing)
        val granted = result == OffloadPermissionManager.AndroidPermissionResult.GRANTED &&
            missing.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        return if (granted) null else ToolExecutionResult(
            "Error: permission_denied: ${missing.joinToString(", ")} (${result.name.lowercase()})",
            false,
        )
    }

    private fun wifiPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private fun bluetoothPermissions(scan: Boolean): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (scan) add(Manifest.permission.BLUETOOTH_SCAN)
        } else if (scan) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    suspend fun wifiInfo(context: Context): ToolExecutionResult {
        requirePermissions(context, wifiPermissions())?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: return@withContext ToolExecutionResult("Error: wifi_unavailable", false)
                val info = wifi.connectionInfo
                val output = JSONObject()
                    .put("enabled", wifi.isWifiEnabled)
                    .put("ssid", info?.let { it.ssid.cleanSsid() } ?: JSONObject.NULL)
                    .put("bssid", info?.bssid ?: JSONObject.NULL)
                    .put("rssi_dbm", info?.rssi ?: JSONObject.NULL)
                    .put("link_speed_mbps", info?.linkSpeed ?: JSONObject.NULL)
                    .put("frequency_mhz", info?.frequency ?: JSONObject.NULL)
                    .put("ip_address", info?.ipAddress?.toIpv4() ?: JSONObject.NULL)
                ToolExecutionResult(output.toString(2), true)
            } catch (t: Throwable) {
                ToolExecutionResult("Error: wifi_info failed: ${t.message}", false)
            }
        }
    }

    suspend fun wifiScan(
        context: Context,
        groupBySsid: Boolean,
        includeHidden: Boolean,
        timeoutMs: Int,
    ): ToolExecutionResult {
        requirePermissions(context, wifiPermissions())?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: return@withContext ToolExecutionResult("Error: wifi_unavailable", false)
                if (!wifi.isWifiEnabled) {
                    return@withContext ToolExecutionResult(
                        JSONObject().put("enabled", false).put("networks", JSONArray()).toString(2),
                        true,
                    )
                }
                // Android may throttle scans and return false; cached scanResults
                // remain useful, and the response exposes that fact honestly.
                val started = wifi.startScan()
                if (started) delay(timeoutMs.coerceIn(0, 8_000).toLong())
                val raw = wifi.scanResults.orEmpty()
                    .filter { includeHidden || it.SSID.isNotBlank() && it.SSID != WifiManager.UNKNOWN_SSID }
                val selected = if (groupBySsid) raw.groupBy { it.SSID }.values.mapNotNull { rows ->
                    rows.maxByOrNull { it.level }
                } else raw
                val networks = JSONArray()
                selected.sortedByDescending { it.level }.forEach { networks.put(it.toWifiJson()) }
                ToolExecutionResult(
                    JSONObject()
                        .put("enabled", true)
                        .put("scan_started", started)
                        .put("cached_results", !started)
                        .put("networks", networks)
                        .put("count", networks.length())
                        .toString(2),
                    true,
                )
            } catch (t: Throwable) {
                ToolExecutionResult("Error: list_wifi_networks failed: ${t.message}", false)
            }
        }
    }

    suspend fun bluetoothStatus(context: Context): ToolExecutionResult {
        requirePermissions(context, bluetoothPermissions(scan = false))?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = manager?.adapter
                    ?: return@withContext ToolExecutionResult("Error: bluetooth_unavailable", false)
                val profiles = JSONObject()
                listOf(
                    "headset" to BluetoothProfile.HEADSET,
                    "a2dp" to BluetoothProfile.A2DP,
                    "gatt" to BluetoothProfile.GATT,
                ).forEach { (name, profile) ->
                    profiles.put(name, adapter.getProfileConnectionState(profile).toConnectionState())
                }
                ToolExecutionResult(
                    JSONObject()
                        .put("available", true)
                        .put("enabled", adapter.isEnabled)
                        .put("name", adapter.name ?: "")
                        .put("paired_count", adapter.bondedDevices?.size ?: 0)
                        .put("profile_states", profiles)
                        .toString(2),
                    true,
                )
            } catch (t: Throwable) {
                ToolExecutionResult("Error: bluetooth_status failed: ${t.message}", false)
            }
        }
    }

    suspend fun bluetoothPaired(context: Context): ToolExecutionResult {
        requirePermissions(context, bluetoothPermissions(scan = false))?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                    ?: return@withContext ToolExecutionResult("Error: bluetooth_unavailable", false)
                val devices = JSONArray()
                adapter.bondedDevices.orEmpty().sortedBy { it.name.orEmpty() }.forEach { devices.put(it.toBluetoothJson()) }
                ToolExecutionResult(JSONObject().put("devices", devices).put("count", devices.length()).toString(2), true)
            } catch (t: Throwable) {
                ToolExecutionResult("Error: bluetooth_paired_devices failed: ${t.message}", false)
            }
        }
    }

    suspend fun bluetoothScan(context: Context, durationSeconds: Int): ToolExecutionResult {
        requirePermissions(context, bluetoothPermissions(scan = true))?.let { return it }
        return withContext(Dispatchers.IO) {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter ?: return@withContext ToolExecutionResult("Error: bluetooth_unavailable", false)
            if (!adapter.isEnabled) {
                return@withContext ToolExecutionResult(
                    JSONObject().put("enabled", false).put("devices", JSONArray()).toString(2),
                    true,
                )
            }
            val hits = linkedMapOf<String, BluetoothHit>()
            fun record(device: BluetoothDevice, rssi: Int?, source: String) = synchronized(hits) {
                val address = runCatching { device.address }.getOrDefault("unknown-${hits.size}")
                val old = hits[address]
                hits[address] = BluetoothHit(
                    device = device,
                    rssi = rssi ?: old?.rssi,
                    sources = (old?.sources.orEmpty() + source),
                )
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != BluetoothDevice.ACTION_FOUND) return
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    record(device, intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).takeUnless { it == Short.MIN_VALUE }?.toInt(), "classic")
                }
            }
            val scanner = adapter.bluetoothLeScanner
            var bleError: Int? = null
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    record(result.device, result.rssi, "ble")
                }
                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { record(it.device, it.rssi, "ble") }
                }
                override fun onScanFailed(errorCode: Int) {
                    bleError = errorCode
                }
            }
            var receiverRegistered = false
            var bleStarted = false
            var classicStarted = false
            try {
                ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_FOUND), ContextCompat.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
                classicStarted = adapter.startDiscovery()
                if (scanner != null) {
                    scanner.startScan(callback)
                    bleStarted = true
                }
                delay(durationSeconds.coerceIn(1, 30) * 1_000L)
            } catch (t: Throwable) {
                return@withContext ToolExecutionResult("Error: bluetooth_scan failed: ${t.message}", false)
            } finally {
                runCatching { if (classicStarted) adapter.cancelDiscovery() }
                runCatching { if (bleStarted) scanner?.stopScan(callback) }
                runCatching { if (receiverRegistered) context.unregisterReceiver(receiver) }
            }
            val devices = JSONArray()
            synchronized(hits) {
                hits.values.sortedWith(compareByDescending<BluetoothHit> { it.rssi ?: Int.MIN_VALUE }.thenBy { it.device.name.orEmpty() })
                    .forEach { devices.put(it.toJson()) }
            }
            ToolExecutionResult(
                JSONObject()
                    .put("enabled", true)
                    .put("classic_started", classicStarted)
                    .put("ble_started", bleStarted)
                    .put("ble_error", bleError ?: JSONObject.NULL)
                    .put("devices", devices)
                    .put("count", devices.length())
                    .toString(2),
                true,
            )
        }
    }

    private data class BluetoothHit(
        val device: BluetoothDevice,
        val rssi: Int?,
        val sources: Set<String>,
    ) {
        fun toJson(): JSONObject = device.toBluetoothJson().apply {
            put("rssi_dbm", rssi ?: JSONObject.NULL)
            put("sources", JSONArray(sources.sorted()))
        }
    }

    private fun WifiScanResult.toWifiJson(): JSONObject = JSONObject()
        .put("ssid", SSID)
        .put("bssid", BSSID)
        .put("rssi_dbm", level)
        .put("frequency_mhz", frequency)
        .put("capabilities", capabilities)

    private fun BluetoothDevice.toBluetoothJson(): JSONObject = JSONObject()
        .put("name", name ?: "")
        .put("address", address)
        .put("type", type.toDeviceType())
        .put("bond_state", bondState.toBondState())

    private fun Int.toConnectionState(): String = when (this) {
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        else -> "disconnected"
    }

    private fun Int.toDeviceType(): String = when (this) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "le"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
        else -> "unknown"
    }

    private fun Int.toBondState(): String = when (this) {
        BluetoothDevice.BOND_BONDED -> "bonded"
        BluetoothDevice.BOND_BONDING -> "bonding"
        else -> "none"
    }

    private fun String?.cleanSsid(): String? = this
        ?.removePrefix("\"")?.removeSuffix("\"")
        ?.takeUnless { it == WifiManager.UNKNOWN_SSID }

    private fun Int.toIpv4(): String? = if (this == 0) null else listOf(
        this and 0xff,
        this shr 8 and 0xff,
        this shr 16 and 0xff,
        this shr 24 and 0xff,
    ).joinToString(".")
}

class AndroidWifiInfoHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.wifi.info",
        description = "Get current Wi-Fi connection information. SSID/BSSID are privacy-sensitive and require confirmation for MCP callers.",
        parameters = emptyMap(),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AndroidConnectivityOps.wifiInfo(context)
}

class AndroidWifiScanHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.wifi.scan",
        description = "Scan nearby Wi-Fi networks using standard Android APIs. Android may return cached results when scans are throttled.",
        parameters = mapOf(
            "group_by_ssid" to AgentToolParam("boolean", "Keep strongest result per SSID (default true)"),
            "include_hidden" to AgentToolParam("boolean", "Include hidden/empty SSIDs (default false)"),
            "timeout_ms" to AgentToolParam("integer", "Wait up to 8000 ms for a scan (default 3000)"),
        ),
        timeoutMs = 12_000L,
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidConnectivityOps.wifiScan(context, a.optBoolean("group_by_ssid", true), a.optBoolean("include_hidden"), a.optInt("timeout_ms", 3_000))
    }
}

class AndroidBluetoothStatusHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.bluetooth.status",
        description = "Get Bluetooth adapter state, name, paired-device count, and aggregate profile states.",
        parameters = emptyMap(),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AndroidConnectivityOps.bluetoothStatus(context)
}

class AndroidBluetoothPairedHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.bluetooth.paired",
        description = "List paired Bluetooth devices with name, address, type, and bond state.",
        parameters = emptyMap(),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AndroidConnectivityOps.bluetoothPaired(context)
}

class AndroidBluetoothScanHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.bluetooth.scan",
        description = "Discover nearby Classic and BLE Bluetooth devices for a bounded duration.",
        parameters = mapOf("scan_duration" to AgentToolParam("integer", "Scan seconds (default 10, max 30)")),
        timeoutMs = 35_000L,
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AndroidConnectivityOps.bluetoothScan(context, args(argsJson).optInt("scan_duration", 10))
}
