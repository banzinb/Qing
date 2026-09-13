package com.zhousl.aether.data.browser

/**
 * Labels for the browser viewer. Kept free of Android and WebView types so the shortening rules
 * can be unit tested.
 */

private val HostlessSchemePrefixes = listOf(
    "about:",
    "data:",
    "javascript:",
    "blob:",
    "chrome:",
    "content:",
)

/** The host part of [url], or an empty string when there is nothing usable to show. */
internal fun browserTabHost(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return ""
    val lowered = trimmed.lowercase()
    if (HostlessSchemePrefixes.any { lowered.startsWith(it) }) return ""
    val separator = trimmed.indexOf("://")
    if (separator >= 0) {
        val scheme = lowered.substring(0, separator)
        // Anything that is not a network or file URL has no host worth showing.
        if (scheme != "http" && scheme != "https" && scheme != "file") return ""
    }
    val withoutScheme = if (separator >= 0) trimmed.substring(separator + 3) else trimmed
    val host = withoutScheme.takeWhile { character ->
        character != '/' && character != '?' && character != '#'
    }
    return host.removePrefix("www.")
}

/**
 * What a tab chip shows: the page title when there is one, otherwise the host, otherwise the raw
 * URL. Long labels are shortened so three tabs still fit on a phone.
 */
internal fun browserTabLabel(tab: BrowserViewerTab, maxLength: Int = 24): String {
    val host = browserTabHost(tab.url)
    val title = tab.title.trim().takeIf { it.isNotEmpty() && it != tab.url.trim() }
    val label = title ?: host.ifEmpty { tab.url.trim() }
    if (label.length <= maxLength) return label
    return label.take((maxLength - 1).coerceAtLeast(1)).trimEnd() + "…"
}
