package com.zhousl.aether.data

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

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

    /**
     * Where the phone is right now (device batch 2).
     *
     * A ten-minute-old fix is good enough to answer "what's the weather here",
     * so this only pays for a fresh fix when the cached one is stale.
     */
    @Suppress("DEPRECATION")
    suspend fun location(): JSONObject = withContext(Dispatchers.Main) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            return@withContext missingPermission("定位", "位置信息")
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext failure("Location service is unavailable on this device.")
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (providers.none { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }) {
            return@withContext failure(
                "The phone's location switch is off. Turn on Location in system settings and try again.",
            )
        }
        val lastKnown = providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        val now = System.currentTimeMillis()
        if (lastKnown != null && now - lastKnown.time <= LastKnownFixFreshMillis) {
            return@withContext locationJson(lastKnown, stale = false)
        }
        val fix = requestSingleLocation(manager, providers)
        when {
            fix != null -> locationJson(fix, stale = false)
            lastKnown != null -> locationJson(lastKnown, stale = true)
            else -> failure("No location fix within ${LocationFixTimeoutMillis / 1000} seconds. Indoors or a restricted device can do this; try again near a window.")
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun requestSingleLocation(
        manager: LocationManager,
        providers: List<String>,
    ): Location? = withTimeoutOrNull(LocationFixTimeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (continuation.isActive) continuation.resume(location)
                }

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            val provider = providers.firstOrNull {
                runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
            }
            if (provider == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                runCatching { manager.removeUpdates(listener) }
            }
            runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
                .onFailure { if (continuation.isActive) continuation.resume(null) }
        }
    }

    private fun locationJson(fix: Location, stale: Boolean): JSONObject = JSONObject().apply {
        put("ok", true)
        putFinite("latitude", fix.latitude)
        putFinite("longitude", fix.longitude)
        if (fix.hasAccuracy()) putFinite("accuracy_m", fix.accuracy.toDouble())
        put("provider", fix.provider ?: "")
        put("fix_age_seconds", (System.currentTimeMillis() - fix.time) / 1000)
        put("stale", stale)
        if (stale) {
            put(
                "note",
                "This is the last known fix, not a fresh one: no new location arrived in time. Say so if it matters.",
            )
        }
    }

    /**
     * Looks a person up by name and returns their numbers. Capped at
     * [ContactLimit] people so a broad query cannot dump the whole address book
     * into the conversation.
     */
    suspend fun searchContacts(query: String): JSONObject = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext failure("'query' is required: the name (or part of a name) to look for.")
        }
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            return@withContext missingPermission("联系人", "联系人")
        }
        val numbersByName = linkedMapOf<String, MutableList<String>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val failureReason = runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%${escapeLike(trimmed)}%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex).orEmpty()
                    val number = cursor.getString(numberIndex).orEmpty()
                    if (name.isBlank()) continue
                    if (name !in numbersByName && numbersByName.size >= ContactLimit) break
                    val numbers = numbersByName.getOrPut(name) { mutableListOf() }
                    if (number.isNotBlank() && numbers.size < ContactNumbersPerPerson) {
                        numbers += number.replace(ContactNumberSeparator, "")
                    }
                }
            }
        }.exceptionOrNull()
        if (failureReason != null) {
            return@withContext failure("Could not read contacts: ${failureReason.message ?: failureReason::class.java.simpleName}")
        }
        JSONObject().apply {
            put("ok", true)
            put("query", trimmed)
            put("count", numbersByName.size)
            if (numbersByName.isEmpty()) {
                put("note", "No contact name matched '$trimmed'.")
            }
            put(
                "contacts",
                JSONArray().apply {
                    numbersByName.forEach { (name, numbers) ->
                        put(
                            JSONObject().apply {
                                put("name", name)
                                put("phones", JSONArray(numbers))
                            },
                        )
                    }
                },
            )
        }
    }

    /**
     * Lists photos from the shared image store, newest first (device batch 2-c).
     *
     * Only metadata comes back — Qing cannot show the model a picture by listing
     * it, so [photoById] plus the workspace copy in the tool layer is what makes
     * this actually useful. The listing stops at [limit] rows and says whether
     * more existed, so a big camera roll cannot flood the conversation.
     *
     * @param album exact album (bucket) name, e.g. `Camera`
     * @param contains part of the file name
     * @param days only photos taken in the last N days (0 or null for all)
     */
    suspend fun photosRecent(
        limit: Int,
        album: String,
        contains: String,
        days: Int?,
    ): JSONObject = withContext(Dispatchers.IO) {
        if (!hasPermission(photoPermissionForSdk(Build.VERSION.SDK_INT))) {
            return@withContext missingPermission("相册", "照片")
        }
        val cappedLimit = limit.coerceIn(1, PhotoListMaxLimit)
        val query = buildPhotoQuery(
            album = album,
            nameContains = contains,
            days = days,
            nowMillis = System.currentTimeMillis(),
        )
        val photos = mutableListOf<JSONObject>()
        var truncated = false
        val failureReason = runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                PhotoProjection,
                query.selection.ifBlank { null },
                query.arguments.toTypedArray().ifEmpty { null },
                "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val columns = PhotoColumns(cursor)
                while (photos.size < cappedLimit && cursor.moveToNext()) {
                    photos += photoJson(cursor, columns)
                }
                // One row past the cap means the roll is longer than the answer.
                truncated = cursor.moveToNext()
            }
        }.exceptionOrNull()
        if (failureReason != null) {
            return@withContext failure(
                "Could not read the photo library: ${failureReason.message ?: failureReason::class.java.simpleName}",
            )
        }
        JSONObject().apply {
            put("ok", true)
            put("count", photos.size)
            put("truncated", truncated)
            put("photos", JSONArray(photos))
            if (photos.isEmpty()) {
                put("note", "No photo matched. Try a wider filter, or ask the user to grant the photos permission.")
            } else if (truncated) {
                put(
                    "note",
                    "Only the newest $cappedLimit photo(s) are listed; more exist. Narrow it with album, contains or days.",
                )
            }
        }
    }

    /**
     * Resolves a photo id from [photosRecent] back to the picture itself, so the
     * tool layer can copy it into the workspace where the model can open it.
     * Returns null when the id is unknown or the library is not readable.
     */
    suspend fun photoById(id: String): DevicePhoto? = withContext(Dispatchers.IO) {
        val numericId = id.trim().toLongOrNull() ?: return@withContext null
        if (!hasPermission(photoPermissionForSdk(Build.VERSION.SDK_INT))) return@withContext null
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                PhotoProjection,
                "${MediaStore.Images.Media._ID} = ?",
                arrayOf(numericId.toString()),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val columns = PhotoColumns(cursor)
                DevicePhoto(
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, numericId),
                    displayName = cursor.getString(columns.name)
                        .orEmpty()
                        .ifBlank { "photo-$numericId.jpg" },
                    takenAtMillis = cursor.getLong(columns.takenAt)
                        .takeIf { it > 0L }
                        ?: (cursor.getLong(columns.addedAt) * 1_000L),
                )
            }
        }.getOrNull()
    }

    /**
     * Opens the contacts app with a new contact filled in (device batch 2-c).
     *
     * This needs no WRITE_CONTACTS permission because the contacts app does the
     * writing. It also returns before the user taps save, so the result says
     * "handed over" and never "added".
     */
    fun addContact(name: String, phone: String, email: String): JSONObject {
        val trimmedName = name.trim()
        val trimmedPhone = phone.trim().replace(ContactNumberSeparator, "")
        val trimmedEmail = email.trim()
        if (trimmedName.isEmpty() && trimmedPhone.isEmpty()) {
            return failure("Provide at least a name or a phone number for the new contact.")
        }
        val intent = Intent(Intent.ACTION_INSERT)
            .setType(ContactsContract.Contacts.CONTENT_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (trimmedName.isNotEmpty()) {
            intent.putExtra(ContactsContract.Intents.Insert.NAME, trimmedName)
        }
        if (trimmedPhone.isNotEmpty()) {
            intent.putExtra(ContactsContract.Intents.Insert.PHONE, trimmedPhone)
        }
        if (trimmedEmail.isNotEmpty()) {
            intent.putExtra(ContactsContract.Intents.Insert.EMAIL, trimmedEmail)
        }
        return runCatching {
            context.startActivity(intent)
            launchResult(
                app = "contacts",
                detail = JSONObject()
                    .put("name", trimmedName)
                    .put("phone", trimmedPhone)
                    .put("email", trimmedEmail),
            )
        }.getOrElse { error ->
            failure("No contacts app accepted the new contact: ${error.message ?: error::class.java.simpleName}")
        }
    }

    /**
     * Reads the calendar for the next [days] days. Instances (not events) so
     * recurring entries show up on the day they actually happen.
     */
    suspend fun readCalendar(days: Int): JSONObject = withContext(Dispatchers.IO) {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            return@withContext missingPermission("日历", "日历")
        }
        val windowDays = days.coerceIn(1, CalendarWindowMaxDays)
        val fromMillis = System.currentTimeMillis()
        val toMillis = fromMillis + windowDays * DayMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .also { builder ->
                ContentUris.appendId(builder, fromMillis)
                ContentUris.appendId(builder, toMillis)
            }
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val events = JSONArray()
        val failureReason = runCatching {
            context.contentResolver.query(
                uri,
                projection,
                "${CalendarContract.Instances.VISIBLE} = 1",
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val titleIndex = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIndex = cursor.getColumnIndex(CalendarContract.Instances.END)
                val allDayIndex = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                val locationIndex = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val calendarIndex = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                while (cursor.moveToNext() && events.length() < CalendarEventLimit) {
                    events.put(
                        JSONObject().apply {
                            put("title", cursor.getString(titleIndex).orEmpty())
                            put("begin_millis", cursor.getLong(beginIndex))
                            put("end_millis", cursor.getLong(endIndex))
                            put("all_day", cursor.getInt(allDayIndex) == 1)
                            put("location", cursor.getString(locationIndex).orEmpty())
                            put("calendar", cursor.getString(calendarIndex).orEmpty())
                        },
                    )
                }
            }
        }.exceptionOrNull()
        if (failureReason != null) {
            return@withContext failure("Could not read the calendar: ${failureReason.message ?: failureReason::class.java.simpleName}")
        }
        JSONObject().apply {
            put("ok", true)
            put("from_millis", fromMillis)
            put("to_millis", toMillis)
            put("days", windowDays)
            put("count", events.length())
            put("events", events)
        }
    }

    /**
     * Hands a new event to the system calendar.
     *
     * Qing cannot save it itself without WRITE_CALENDAR, and it must not claim
     * the event exists: the answer says the editor was opened.
     */
    fun addCalendarEvent(
        title: String,
        start: String,
        durationMinutes: Int,
        description: String,
        location: String,
    ): JSONObject {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return failure("'title' is required.")
        val begin = parseLocalDateTimeMillis(start)
            ?: return failure("'start' should look like 2026-09-13 15:00 (local time), or 2026-09-13 for a whole day.")
        val minutes = durationMinutes.coerceIn(1, CalendarDurationMaxMinutes)
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, trimmedTitle)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + minutes * 60_000L)
            .putExtra(CalendarContract.Events.DESCRIPTION, description.trim())
            .putExtra(CalendarContract.Events.EVENT_LOCATION, location.trim())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            launchResult(
                app = "calendar",
                detail = JSONObject()
                    .put("title", trimmedTitle)
                    .put("begin_millis", begin)
                    .put("duration_minutes", minutes),
            )
        }.getOrElse { error ->
            failure("No calendar app accepted the new event: ${error.message ?: error::class.java.simpleName}")
        }
    }

    /** Opens the clock app's alarm screen with the time filled in. */
    fun setAlarm(hour: Int, minute: Int, message: String): JSONObject {
        if (hour !in 0..23 || minute !in 0..59) {
            return failure("'hour' must be 0-23 and 'minute' 0-59.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, message.trim())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            launchResult(
                app = "clock",
                detail = JSONObject()
                    .put("hour", hour)
                    .put("minute", minute)
                    .put("message", message.trim()),
            )
        }.getOrElse { error ->
            failure("No clock app accepted the alarm: ${error.message ?: error::class.java.simpleName}")
        }
    }

    /** Opens the clock app's timer screen with the length filled in. */
    fun setTimer(seconds: Int, message: String): JSONObject {
        if (seconds !in 1..TimerMaxSeconds) {
            return failure("'seconds' must be between 1 and $TimerMaxSeconds.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, message.trim())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            launchResult(
                app = "clock",
                detail = JSONObject().put("seconds", seconds).put("message", message.trim()),
            )
        }.getOrElse { error ->
            failure("No clock app accepted the timer: ${error.message ?: error::class.java.simpleName}")
        }
    }

    /**
     * The shared shape for "handed this to another app" results. `requested`
     * says Qing filled the screen in; it never means the user saved it.
     */
    private fun launchResult(app: String, detail: JSONObject): JSONObject = JSONObject().apply {
        put("ok", true)
        put("requested", true)
        put("app", app)
        put("detail", detail)
        put(
            "note",
            "Qing opened the $app app with these values filled in. It cannot confirm the user saved it, so do not say it is done.",
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun missingPermission(capability: String, label: String): JSONObject = failure(
        "Qing does not have the $capability permission yet, so it cannot read this. " +
            "The user can grant it in Qing: settings, Agent mode, phone permissions ($label).",
    )

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

private const val LastKnownFixFreshMillis = 10 * 60_000L
private const val LocationFixTimeoutMillis = 10_000L
private const val ContactLimit = 10
private const val ContactNumbersPerPerson = 3
private const val PhotoListMaxLimit = 50
private const val CalendarEventLimit = 20
private const val CalendarWindowMaxDays = 30
private const val CalendarDurationMaxMinutes = 24 * 60
private const val TimerMaxSeconds = 24 * 60 * 60
private const val DayMillis = 24 * 60 * 60 * 1000L

private val ContactNumberSeparator = Regex("""[\s\-()]""")

/** Column set for every photo read, so the indices are resolved in one place. */
private val PhotoProjection = arrayOf(
    MediaStore.Images.Media._ID,
    MediaStore.Images.Media.DISPLAY_NAME,
    MediaStore.Images.Media.DATE_TAKEN,
    MediaStore.Images.Media.DATE_ADDED,
    MediaStore.Images.Media.WIDTH,
    MediaStore.Images.Media.HEIGHT,
    MediaStore.Images.Media.SIZE,
    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
)

private class PhotoColumns(cursor: Cursor) {
    val id: Int = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
    val name: Int = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
    val takenAt: Int = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
    val addedAt: Int = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
    val width: Int = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
    val height: Int = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
    val size: Int = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
    val album: Int = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
}

private fun photoJson(cursor: Cursor, columns: PhotoColumns): JSONObject = JSONObject()
    .put("id", cursor.getLong(columns.id).toString())
    .put("name", cursor.getString(columns.name).orEmpty())
    .put("album", cursor.getString(columns.album).orEmpty())
    .put(
        "taken_at_millis",
        cursor.getLong(columns.takenAt).takeIf { it > 0L }
            ?: (cursor.getLong(columns.addedAt) * 1_000L),
    )
    .put("width", cursor.getInt(columns.width))
    .put("height", cursor.getInt(columns.height))
    .put("size_bytes", cursor.getLong(columns.size))

/** A photo resolved back to a readable uri, so the tool layer can copy it. */
data class DevicePhoto(
    val uri: Uri,
    val displayName: String,
    val takenAtMillis: Long,
)

/** What an album read asks the image store for. Pure, so it can be unit tested. */
internal data class PhotoQuery(
    val selection: String,
    val arguments: List<String>,
)

internal fun buildPhotoQuery(
    album: String,
    nameContains: String,
    days: Int?,
    nowMillis: Long,
): PhotoQuery {
    val clauses = mutableListOf<String>()
    val arguments = mutableListOf<String>()
    val trimmedAlbum = album.trim()
    if (trimmedAlbum.isNotEmpty()) {
        clauses += "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
        arguments += trimmedAlbum
    }
    val trimmedContains = nameContains.trim()
    if (trimmedContains.isNotEmpty()) {
        clauses += "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
        arguments += "%${escapeLike(trimmedContains)}%"
    }
    if (days != null && days > 0) {
        // DATE_TAKEN is 0 on some imports, so fall back to DATE_ADDED, which is
        // stored in seconds rather than milliseconds.
        clauses += "(CASE WHEN ${MediaStore.Images.Media.DATE_TAKEN} > 0 THEN " +
            "${MediaStore.Images.Media.DATE_TAKEN} ELSE ${MediaStore.Images.Media.DATE_ADDED} * 1000 END) >= ?"
        arguments += (nowMillis - days * DayMillis).toString()
    }
    return PhotoQuery(selection = clauses.joinToString(" AND "), arguments = arguments)
}

/** Android 13 split photo access out of storage; older devices keep the legacy one. */
internal fun photoPermissionForSdk(sdkInt: Int): String =
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private val LocalDateTimeFormats = listOf(
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd HH:mm",
    "yyyy-MM-dd'T'HH:mm",
    "yyyy-MM-dd",
)

/** Keeps user text from turning into a wildcard match-all query. */
internal fun escapeLike(raw: String): String =
    raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/**
 * Reads a local date-time the way a person writes one. Returns null for
 * anything ambiguous, so the tool answers "use this format" instead of booking
 * an event at an accidental time.
 */
internal fun parseLocalDateTimeMillis(raw: String): Long? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    LocalDateTimeFormats.forEach { pattern ->
        val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
        val position = ParsePosition(0)
        val parsed = format.parse(text, position) ?: return@forEach
        if (position.index == text.length) return parsed.time
    }
    return null
}

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
