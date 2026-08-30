package io.github.slackerllc.minis.tools

import android.content.Context
import android.location.Geocoder
import io.github.slackerllc.minis.data.model.AgentToolDefinition
import io.github.slackerllc.minis.data.model.AgentToolParam
import io.github.slackerllc.minis.runtime.guest.LocationOffloadHandler
import io.github.slackerllc.minis.runtime.guest.WeatherOffloadHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/** Structured adapter over the existing Open-Meteo weather and location handlers. */
object AndroidWeatherOps {
    private val coordinates = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*[, ]\s*(-?\d+(?:\.\d+)?)\s*$""")

    suspend fun forecast(
        context: Context,
        sessionId: String,
        location: String?,
        forecastDays: Int,
        units: String,
    ): ToolExecutionResult {
        val (point, error) = resolvePoint(context, sessionId, location)
        if (point == null) return error ?: ToolExecutionResult("Error: unable to resolve weather location", false)
        val argv = listOf(
            "android-weather", "report", "--lat", point.first.toString(), "--lon", point.second.toString(),
            "--days", forecastDays.coerceIn(1, 10).toString(), "--units", units,
        )
        return AndroidSystemOps.offload(context, sessionId, WeatherOffloadHandler(context), argv)
    }

    private suspend fun resolvePoint(
        context: Context,
        sessionId: String,
        location: String?,
    ): Pair<Pair<Double, Double>?, ToolExecutionResult?> {
        val requested = location?.trim().orEmpty()
        coordinates.matchEntire(requested)?.let { match ->
            val lat = match.groupValues[1].toDouble()
            val lon = match.groupValues[2].toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return (lat to lon) to null
            return null to ToolExecutionResult("Error: coordinates out of range", false)
        }
        if (requested.isNotEmpty()) {
            if (!Geocoder.isPresent()) {
                return null to ToolExecutionResult("Error: geocoder_unavailable; supply coordinates instead", false)
            }
            val point = withContext(Dispatchers.IO) {
                runCatching { Geocoder(context, Locale.getDefault()).getFromLocationName(requested, 1)?.firstOrNull() }
                    .getOrNull()
            }
            if (point != null) return (point.latitude to point.longitude) to null
            return null to ToolExecutionResult("Error: location_not_found: $requested", false)
        }
        val current = AndroidSystemOps.offload(
            context, sessionId, LocationOffloadHandler(context),
            listOf("android-location", "current", "--timeout", "10"),
        )
        if (!current.success) return null to current
        val data = runCatching { JSONObject(current.output) }.getOrNull()
        val lat = data?.optDouble("latitude", Double.NaN) ?: Double.NaN
        val lon = data?.optDouble("longitude", Double.NaN) ?: Double.NaN
        if (lat.isNaN() || lon.isNaN()) {
            return null to ToolExecutionResult("Error: current location returned no coordinates", false)
        }
        return (lat to lon) to null
    }
}

class AndroidWeatherHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.weather",
        description = "Get current weather and forecast from Open-Meteo for a city, coordinates, or the current device location.",
        parameters = mapOf(
            "location" to AgentToolParam("string", "Optional city name or 'latitude,longitude'; omit for current device location"),
            "forecast_days" to AgentToolParam("integer", "Forecast days (default 7, max 10)"),
            "units" to AgentToolParam("string", "metric (default) or imperial", listOf("metric", "imperial")),
        ),
        timeoutMs = 30_000L,
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        return AndroidWeatherOps.forecast(
            context,
            sessionId,
            a.optString("location").ifBlank { null },
            a.optInt("forecast_days", 7),
            a.optString("units", "metric"),
        )
    }
}
