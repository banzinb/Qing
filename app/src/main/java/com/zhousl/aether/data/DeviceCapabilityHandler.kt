package com.zhousl.aether.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android capability offload for Qing's host tools (v2, device batch 1).
 *
 * Only capabilities that need no runtime permission live here, so a fresh
 * install is useful without a permission tour: device info, opening a URL or an
 * installed app, and the clipboard. Every entry point returns a JSON payload
 * with "ok" plus either the data or "errmsg", matching the other host tools.
 */
class DeviceCapabilityHandler(private val context: Context) {

    fun deviceInfo(): JSONObject = JSONObject().apply {
        put("ok", true)
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("android_release", Build.VERSION.RELEASE)
        put("sdk_int", Build.VERSION.SDK_INT)
        put("locale", Locale.getDefault().toLanguageTag())
        put("timezone", TimeZone.getDefault().id)
        put("uptime_minutes", TimeUnit.MILLISECONDS.toMinutes(SystemClock.elapsedRealtime()))
        put("battery", batteryState())
        put("storage", storageState())
        put("screen", screenState())
    }

    /**
     * Opens [value] as a URL (target "url") or as an installed app (target
     * "app", where [value] is a package name). Returns ok=false with a readable
     * reason instead of throwing, so the model learns what actually happened.
     */
    fun open(target: String, value: String): JSONObject {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return failure("'value' is required (a URL, or a package name for target=app).")
        val intent = when (target.lowercase(Locale.US)) {
            "app" -> context.packageManager.getLaunchIntentForPackage(trimmed)
                ?: return failure("No launchable app found for package '$trimmed'.")
            else -> {
                val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
                    ?: return failure("'$trimmed' could not be parsed as a URI.")
                if (uri.scheme.isNullOrBlank()) {
                    return failure("'$trimmed' has no scheme; pass something like https://example.com.")
                }
                Intent(Intent.ACTION_VIEW, uri)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            JSONObject().apply {
                put("ok", true)
                put("target", target.lowercase(Locale.US).ifBlank { "url" })
                put("opened", trimmed)
            }
        }.getOrElse { error ->
            failure("Could not open '$trimmed': ${error.message ?: error::class.java.simpleName}")
        }
    }

    suspend fun readClipboard(): JSONObject = withContext(Dispatchers.Main) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@withContext failure("Clipboard service is unavailable.")
        val clip = manager.primaryClip
        val text = clip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        if (text.isNullOrEmpty()) {
            return@withContext failure(
                "Clipboard is empty, or Android blocked the read because Qing is not in the foreground.",
            )
        }
        JSONObject().apply {
            put("ok", true)
            put("clipboard", text)
            put("length", text.length)
        }
    }

    suspend fun writeClipboard(text: String): JSONObject = withContext(Dispatchers.Main) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@withContext failure("Clipboard service is unavailable.")
        runCatching {
            manager.setPrimaryClip(ClipData.newPlainText("Qing", text))
        }.getOrElse { error ->
            return@withContext failure("Could not write to the clipboard: ${error.message ?: error::class.java.simpleName}")
        }
        JSONObject().apply {
            put("ok", true)
            put("copied", text.length)
        }
    }

    suspend fun speak(text: String): JSONObject = DeviceMediaController.speak(context, text)

    suspend fun playAudio(url: String): JSONObject = DeviceMediaController.play(context, url)

    fun stopMedia(): JSONObject = DeviceMediaController.stop()

    /**
     * Current conditions plus a three-day outlook from Open-Meteo, which needs
     * no API key. Accepts explicit coordinates or a city name, in which case the
     * free Open-Meteo geocoder resolves it first.
     */
    suspend fun weather(city: String, latitude: Double?, longitude: Double?): JSONObject {
        val place = when {
            latitude != null && longitude != null -> Place(latitude, longitude, city.trim().ifBlank { null })
            city.isNotBlank() -> resolvePlace(city) ?: return failure("Could not find a place named '$city'.")
            else -> return failure("Provide latitude and longitude, or a city name.")
        }
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast?latitude=")
            append(place.latitude)
            append("&longitude=")
            append(place.longitude)
            append("&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m")
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min")
            append("&timezone=auto&forecast_days=3")
        }
        val payload = fetchJson(url)
            ?: return failure("The weather service could not be reached; check the network connection.")
        return forecastJson(payload, place)
    }

    private suspend fun resolvePlace(city: String): Place? {
        val query = runCatching { URLEncoder.encode(city, "UTF-8") }.getOrNull() ?: return null
        val payload = fetchJson(
            "https://geocoding-api.open-meteo.com/v1/search?name=$query&count=1&language=zh&format=json",
        ) ?: return null
        val first = payload.optJSONArray("results")?.optJSONObject(0) ?: return null
        return Place(
            latitude = first.optDouble("latitude"),
            longitude = first.optDouble("longitude"),
            label = first.optString("name").ifBlank { city },
        )
    }

    private fun forecastJson(payload: JSONObject, place: Place): JSONObject {
        val current = payload.optJSONObject("current")
        val daily = payload.optJSONObject("daily")
        if (current == null && daily == null) {
            return failure("The weather service returned no forecast data.")
        }
        return JSONObject().apply {
            put("ok", true)
            put("place", place.label ?: "${place.latitude},${place.longitude}")
            put("source", "open-meteo.com")
            if (current != null) {
                val code = current.optInt("weather_code")
                put(
                    "now",
                    JSONObject().apply {
                        putFinite("temperature_c", current.optDouble("temperature_2m", Double.NaN))
                        putFinite("feels_like_c", current.optDouble("apparent_temperature", Double.NaN))
                        put("humidity_percent", current.optInt("relative_humidity_2m"))
                        putFinite("wind_kmh", current.optDouble("wind_speed_10m", Double.NaN))
                        put("weather_code", code)
                        put("condition", weatherCodeLabel(code))
                    },
                )
            }
            if (daily != null) {
                val dates = daily.optJSONArray("time")
                val codes = daily.optJSONArray("weather_code")
                val highs = daily.optJSONArray("temperature_2m_max")
                val lows = daily.optJSONArray("temperature_2m_min")
                val days = JSONArray()
                for (index in 0 until (dates?.length() ?: 0)) {
                    val code = codes?.optInt(index, -1) ?: -1
                    days.put(
                        JSONObject().apply {
                            put("date", dates?.optString(index).orEmpty())
                            putFinite("high_c", highs?.optDouble(index, Double.NaN) ?: Double.NaN)
                            putFinite("low_c", lows?.optDouble(index, Double.NaN) ?: Double.NaN)
                            put("weather_code", code)
                            put("condition", weatherCodeLabel(code))
                        },
                    )
                }
                put("daily", days)
            }
        }
    }

    private suspend fun fetchJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.let { JSONObject(it) }
            }
        }.getOrNull()
    }

    private fun batteryState(): JSONObject = JSONObject().apply {
        val status = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val state = status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (level >= 0 && scale > 0) {
            put("percent", level * 100 / scale)
        }
        put(
            "charging",
            state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    private fun storageState(): JSONObject = JSONObject().apply {
        val stats = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        if (stats != null) {
            put("total_bytes", stats.totalBytes)
            put("free_bytes", stats.availableBytes)
        }
    }

    private fun screenState(): JSONObject = JSONObject().apply {
        val metrics = context.resources.displayMetrics
        put("width_px", metrics.widthPixels)
        put("height_px", metrics.heightPixels)
        put("density_dpi", metrics.densityDpi)
    }

    private fun failure(message: String): JSONObject =
        JSONObject().apply {
            put("ok", false)
            put("errmsg", message)
        }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

private data class Place(
    val latitude: Double,
    val longitude: Double,
    val label: String?,
)

private fun JSONObject.putFinite(key: String, value: Double): JSONObject {
    if (value.isFinite()) put(key, value)
    return this
}

/** WMO weather interpretation codes, as documented by Open-Meteo. */
private fun weatherCodeLabel(code: Int): String = when (code) {
    0 -> "晴"
    1 -> "大部晴朗"
    2 -> "局部多云"
    3 -> "阴"
    45, 48 -> "雾"
    51, 53, 55 -> "毛毛雨"
    56, 57 -> "冻毛毛雨"
    61 -> "小雨"
    63 -> "中雨"
    65 -> "大雨"
    66, 67 -> "冻雨"
    71 -> "小雪"
    73 -> "中雪"
    75 -> "大雪"
    77 -> "米雪"
    80, 81, 82 -> "阵雨"
    85, 86 -> "阵雪"
    95 -> "雷阵雨"
    96, 99 -> "雷阵雨伴冰雹"
    else -> "未知"
}
