package com.zhousl.aether.data

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

actual fun platformCurrentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1_000.0).toLong()

actual fun platformUptimeMillis(): Long =
    (NSProcessInfo.processInfo.systemUptime * 1_000.0).toLong()

actual fun platformRandomUuid(): String = NSUUID().UUIDString()

actual fun platformLanguageTag(): String =
    (NSUserDefaults.standardUserDefaults.arrayForKey("AppleLanguages")
        ?.firstOrNull() as? String)
        ?: NSLocale.currentLocale.languageCode

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

actual fun platformDefaultLlmUserAgent(): String = "Aether/1.0 (iOS)"

actual fun platformDynamicPromptValues(): Map<String, String> {
    val now = NSDate()
    fun format(pattern: String): String = NSDateFormatter().run {
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
        timeZone = NSTimeZone.localTimeZone
        dateFormat = pattern
        stringFromDate(now)
    }
    return mapOf(
        "current_datetime" to format("yyyy-MM-dd'T'HH:mm:ssXXX"),
        "current_date" to format("yyyy-MM-dd"),
        "current_time" to format("HH:mm:ss"),
        "timezone" to NSTimeZone.localTimeZone.name,
        "unix_timestamp" to now.timeIntervalSince1970.toLong().toString(),
    )
}
