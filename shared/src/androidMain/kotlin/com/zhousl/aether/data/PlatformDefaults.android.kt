package com.zhousl.aether.data

import android.os.SystemClock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()

actual fun platformUptimeMillis(): Long = try {
    SystemClock.uptimeMillis()
} catch (_: RuntimeException) {
    // Android's local unit-test stubs throw here; nanoTime remains monotonic on the host JVM.
    System.nanoTime() / 1_000_000L
}

actual fun platformRandomUuid(): String = UUID.randomUUID().toString()

actual fun platformLanguageTag(): String = Locale.getDefault().toLanguageTag()

actual fun platformDefaultSystemPrompt(): String =
    "You are Qing, the user's personal mobile agent. Your phone is home base; a connected PC is your heavy-lifting hands." +
        "\n\nWork principles:\n" +
        "- Be concise and practical. Lead with the answer, then give only the detail needed.\n" +
        "- Use available tools (shell, file, web_search, web_fetch, skills, MCP, PC bridge) instead of guessing. Inspect real state before making claims.\n" +
        "- Prefer quick phone-side local work. For large development or long-running jobs, use the PC Codex bridge when available.\n" +
        "- Never claim a command, file change, or device action happened unless you observed the result.\n" +
        "- Link local files with an absolute path or file:// target.\n" +
        "- If a request is ambiguous, make a reasonable assumption, do the useful thing, and state what you assumed; ask only when guessing would be risky or wasteful.\n" +
        "- Match the user's language and keep replies scannable."

actual fun platformDefaultLlmUserAgent(): String = "Aether/1.0 (Android)"

actual fun platformDynamicPromptValues(): Map<String, String> {
    val now = ZonedDateTime.now()
    return mapOf(
        "current_datetime" to now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        "current_date" to now.toLocalDate().toString(),
        "current_time" to now.toLocalTime().withNano(0).toString(),
        "timezone" to now.zone.id,
        "unix_timestamp" to now.toEpochSecond().toString(),
    )
}

fun defaultAppLanguage(locale: Locale): AppLanguage = when {
    locale.language.equals("zh", ignoreCase = true) -> AppLanguage.SimplifiedChinese
    locale.language.equals("fa", ignoreCase = true) -> AppLanguage.Persian
    else -> AppLanguage.English
}
