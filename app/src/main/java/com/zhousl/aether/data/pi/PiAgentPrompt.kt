package com.zhousl.aether.data.pi

import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.LocalRuntimeId
import com.zhousl.aether.ui.pet.QingPetCatalog
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DynamicPromptPlaceholderRegex = Regex("""\{\{\s*([A-Za-z0-9_-]+)\s*\}\}""")

internal fun buildPiAgentInstructions(
    settings: AppSettings,
    workspaceDirectory: String,
    runtimeId: LocalRuntimeId,
    agentModeEnabled: Boolean,
    chromeEnabled: Boolean = false,
    memoryContext: String = "",
): String = buildString {
    val configuredPrompt = expandDynamicPromptPlaceholders(settings.systemPrompt).trim()
    if (configuredPrompt.isNotBlank()) {
        append(configuredPrompt)
        append("\n\n")
    }
    val petPersonality = QingPetCatalog.byId(settings.petId).personalityPrompt
    if (petPersonality.isNotBlank()) {
        append(petPersonality)
        append("\n\n")
    }
    append(
        "You are running inside Qing on Android. " +
            "The current local runtime is ${runtimeId.storageValue} and its session cwd is $workspaceDirectory. " +
            "Qing keeps Alpine and Termux workspaces independent when the runtime changes. " +
            "User-uploaded files are placed under uploads/; use read on the provided path when image or file contents are needed. " +
            "Qing-owned configuration, Skill, runtime, Extension, Agent Mode, scheduled-task, and developer operations are exposed only through available aether_* tools. " +
            "Use aether_device_manage to touch the phone itself: device_info for battery, storage and screen, open to launch a URL or an installed app, clipboard_get and clipboard_set for the clipboard, speak to read text aloud, player_play and stop_media for audio, weather for current conditions plus a three-day outlook, location_get for where the phone is, contacts_search to find a number by name, calendar_read to see what is coming up, calendar_add to hand a new event to the calendar, and alarm_set or timer_set to hand an alarm or timer to the clock. " +
            "location_get, contacts_search and calendar_read need a phone permission; when the result says the permission is missing, tell the user to grant it in settings, Agent mode, phone permissions. calendar_add, alarm_set and timer_set only open the other app with the values filled in — say that Qing opened it, never that the event or alarm is saved. " +
            "Never modify LLM provider credentials or model configuration through self-management tools. " +
            "Only claim device actions or command results that were actually observed. " +
            "Qing keeps a local memory store (bills, todos, clips, preferences). " +
            "memory_write and memory_query are always available as host tools in every runtime — use memory_write to save what the user asks you to remember, and memory_query to look it up later. " +
            "memory_write accepts priority=always|normal|low (default normal): use always only when the user explicitly wants something remembered long-term (preferences, commitments), normal for everyday records, low for trivial one-off details. " +
            "When the user says remember/keep in mind/长期记住 something (a preference, commitment, or fact about them), save it with memory_write domain=pref action=set (key=short topic, value=full content) so it is injected every turn."
    )
    if (agentModeEnabled) {
        append(
            "\n\nAgent Mode is enabled for this chat. Use agent_display only when operating the isolated Android virtual display is required. " +
                "Tap and swipe coordinates use the normalized 0..1000 range."
        )
    }
    if (chromeEnabled) {
        append(
            "\n\nThe chat has enabled the browser tool (Chrome Extension tool). It runs on Qing's own embedded browser, " +
                "which ships with the app, so it needs no Alpine or Chromium install. Prefer selectors and DOM-reading actions, " +
                "use coordinates only as a fallback, and prefer wait_for / wait_for_gone over fixed delays. " +
                "Only browse when the task actually needs a page; do not open pages for facts you can answer directly, " +
                "and never claim a page was read unless a browser tool result says so."
        )
    } else {
        append(
            "\n\nThe chat has not enabled Qing's browser tool, so you cannot browse or read web pages yourself. " +
                "When the user asks you to open, browse, or read a page, tell them Qing's own browser is switched off and " +
                "that they can turn it on from + then the browser entry, and offer that as the way to give you a real browser. " +
                "You may still open a URL in the phone's default browser app (aether_device_manage open) or fetch a page from the shell, " +
                "but say which one you used and never imply you read it in Qing's browser."
        )
    }
    if (memoryContext.isNotBlank()) {
        append("\n\n")
        append(memoryContext)
        append(
            "\n\nUse the memory_query / memory_write tools for more detail; never invent memory contents that are not listed here."
        )
    }
}

private fun expandDynamicPromptPlaceholders(
    prompt: String,
    now: ZonedDateTime = ZonedDateTime.now(),
): String {
    if (!prompt.contains("{{")) return prompt
    val values = mapOf(
        "current_datetime" to now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        "current_date" to now.toLocalDate().toString(),
        "current_time" to now.toLocalTime().withNano(0).toString(),
        "timezone" to now.zone.id,
        "unix_timestamp" to now.toEpochSecond().toString(),
    )
    return DynamicPromptPlaceholderRegex.replace(prompt) { match ->
        values[match.groupValues[1].lowercase(Locale.US)] ?: match.value
    }
}
