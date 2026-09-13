package com.zhousl.aether.data.browser

/**
 * Which browser backend executes a `browser` host-tool action.
 *
 * [WebView] is the embedded Android WebView pool that ships with the app; [Alpine] is the
 * Alpine/Chromium + CDP controller that predates it. Both stay supported.
 */
enum class BrowserBackendKind(val wireName: String) {
    WebView("webview"),
    Alpine("alpine"),
}

sealed interface BrowserBackendRequest {
    data object Auto : BrowserBackendRequest
    data class Explicit(val kind: BrowserBackendKind) : BrowserBackendRequest
    data class Unknown(val raw: String) : BrowserBackendRequest
}

/**
 * Pure decision logic for backend routing. Kept free of Android types so it can be unit tested.
 */
object BrowserBackendSelector {
    const val AutoValue = "auto"

    fun parseRequest(raw: String?): BrowserBackendRequest {
        val normalized = raw?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "", AutoValue -> BrowserBackendRequest.Auto
            "webview", "web_view", "embedded", "embedded_webview", "internal" ->
                BrowserBackendRequest.Explicit(BrowserBackendKind.WebView)
            "alpine", "chrome", "chromium", "cdp" ->
                BrowserBackendRequest.Explicit(BrowserBackendKind.Alpine)
            else -> BrowserBackendRequest.Unknown(normalized)
        }
    }

    /**
     * Auto routing keeps an in-flight browsing session on one backend: if the embedded pool
     * already holds a page we stay there, and if Alpine is the one currently running we do not
     * strand the model on a second, empty browser. Fresh sessions start on the embedded pool.
     */
    fun select(
        webViewHasTabs: Boolean,
        alpineRunning: Boolean,
    ): BrowserBackendKind = when {
        webViewHasTabs -> BrowserBackendKind.WebView
        alpineRunning -> BrowserBackendKind.Alpine
        else -> BrowserBackendKind.WebView
    }
}

/**
 * Action vocabulary of the shared browser protocol plus the two condition waits added for Qing.
 */
object BrowserActionCatalog {
    /** Handled by every backend implementation. */
    val BackendActions: Set<String> = setOf(
        "start",
        "status",
        "navigate",
        "open",
        "click",
        "tap",
        "type",
        "text",
        "get_text",
        "scroll",
        "swipe",
        "get_page_info",
        "execute_js",
        "evaluate",
        "find_elements",
        "hover",
        "get_readable",
        "get_backbone",
        "scroll_and_collect",
        "set_user_agent",
        "set_viewport",
        "new_tab",
        "close_tab",
        "list_tabs",
        "back",
        "forward",
        "reload",
        "screenshot",
        "wait_for_dom_stable",
        "stop",
    )

    /**
     * Implemented once in the router by polling `execute_js`, so both backends get condition
     * waits without duplicating the loop.
     */
    val RouterActions: Set<String> = setOf("wait_for", "wait_for_gone")

    fun isSupported(action: String): Boolean = action in BackendActions || action in RouterActions
}
