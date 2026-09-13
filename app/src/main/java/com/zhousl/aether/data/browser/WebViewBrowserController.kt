package com.zhousl.aether.data.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.zhousl.aether.data.AetherDiagnosticLogger
import com.zhousl.aether.data.pi.BrowserPageInfoScript
import com.zhousl.aether.data.pi.BrowserReadableScript
import com.zhousl.aether.data.pi.browserBackboneScript
import com.zhousl.aether.data.pi.browserFindElementsScript
import com.zhousl.aether.data.pi.browserGetTextScript
import com.zhousl.aether.data.pi.browserHoverScript
import com.zhousl.aether.data.pi.browserScrollScript
import com.zhousl.aether.data.pi.browserSetViewportScript
import com.zhousl.aether.data.pi.browserTypeScript
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private const val MaxBrowserTabs = 3
private const val IdleTabRecycleMillis = 10 * 60 * 1000L
private const val NavigationTimeoutMillis = 30_000L
private const val HistoryTimeoutMillis = 10_000L
private const val NavigationPollMillis = 150L
private const val DomStableQuietWindowMillis = 600L
private const val DomStablePollMillis = 200L
private const val DomStableMaxMillis = 60_000L
private const val ScrollAndCollectPauseMillis = 220L
private const val DefaultViewportWidthPx = 1080
private const val DefaultViewportHeightPx = 1920
private const val MaxCollectedTextChars = 50_000
private const val ScreenshotQuality = 82

/**
 * One tab as the browser viewer shows it. [url] and [title] are already resolved (title falls back
 * to the URL), so the UI never has to touch the WebView itself.
 */
data class BrowserViewerTab(
    val id: Int,
    val url: String,
    val title: String,
)

/** Snapshot of the pooled tabs, published to the browser viewer. */
data class BrowserViewerState(
    val tabs: List<BrowserViewerTab> = emptyList(),
    val activeTabId: Int? = null,
) {
    val isEmpty: Boolean get() = tabs.isEmpty()
}

private val ScrollPositionProbeScript = """
    (() => {
      const scrolling = document.scrollingElement || document.documentElement;
      return JSON.stringify({
        ok:true,
        scroll_y:window.scrollY,
        scroll_height:scrolling ? scrolling.scrollHeight : 0,
        inner_height:window.innerHeight,
        url:location.href
      });
    })()
""".trimIndent()

/**
 * Embedded Android WebView browser pool.
 *
 * This is the primary ("B") browser backend for Qing: it ships with the app, needs no Alpine or
 * Chromium install, and speaks the same action vocabulary as the shared browser protocol so the
 * Alpine/CDP backend stays a drop-in alternative.
 *
 * Threading: WebView may only be touched on the main thread, so every access is wrapped in
 * `withContext(Dispatchers.Main)`. Actions are serialized by [operationMutex] like the Alpine
 * backend, which keeps the pool state simple to reason about.
 */
class WebViewBrowserController(
    context: Context,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
) {
    private val appContext = context.applicationContext
    private val captureDirectory = File(appContext.filesDir, "webview-browser/captures").apply { mkdirs() }
    private val operationMutex = Mutex()
    private val poolLock = Any()
    private val tabIdCounter = AtomicInteger(0)
    private val tabs = linkedMapOf<Int, BrowserTab>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeTabId: Int? = null

    @Volatile
    private var hostContainer: ViewGroup? = null

    @Volatile
    private var displayContainer: ViewGroup? = null

    @Volatile
    private var liveTabCount: Int = 0

    private val _viewerState = MutableStateFlow(BrowserViewerState())
    val viewerState: StateFlow<BrowserViewerState> = _viewerState.asStateFlow()

    val hasOpenTabs: Boolean get() = liveTabCount > 0

    val isHosted: Boolean get() = hostContainer != null

    val isDisplayed: Boolean get() = displayContainer != null

    // ---------------------------------------------------------------------------------------
    // Host attachment. The app keeps a hidden container behind its Compose surface so the pool
    // is really attached to a window: that is what makes layout viewport, rendering and
    // screenshots behave like a normal browser.
    // ---------------------------------------------------------------------------------------

    fun attachHost(container: ViewGroup) {
        hostContainer = container
        val existing = snapshotTabs()
        runOnMainThread {
            existing.forEach { tab ->
                if (tab.webView.parent == null) {
                    container.addView(tab.webView, matchParentParams())
                }
            }
            reparentTabsOnMain()
            publishViewerState()
        }
    }

    fun detachHost() {
        val container = hostContainer ?: return
        hostContainer = null
        val existing = snapshotTabs()
        runOnMainThread {
            existing.forEach { tab ->
                if (tab.webView.parent === container) container.removeView(tab.webView)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Display surface. The viewer hands us its own container while it is on screen, and the
    // active tab is moved into it (same WebView instance, so page state survives). When the
    // viewer closes the tab goes back behind the Compose surface.
    // ---------------------------------------------------------------------------------------

    fun attachDisplayHost(container: ViewGroup) {
        displayContainer = container
        runOnMainThread {
            reparentTabsOnMain()
            publishViewerState()
        }
    }

    fun detachDisplayHost(container: ViewGroup) {
        if (displayContainer === container) displayContainer = null
        runOnMainThread {
            // Pull anything still parented to the viewer out, even if it is no longer tracked.
            snapshotTabs().forEach { tab ->
                if (tab.webView.parent === container) {
                    container.removeView(tab.webView)
                    hostContainer?.addView(tab.webView, matchParentParams())
                }
            }
            reparentTabsOnMain()
            publishViewerState()
        }
    }

    /**
     * Puts the active tab in the viewer (when one is attached) and every other tab back in the
     * hidden host. Tabs are only ever moved, never recreated, so nothing re-renders from scratch.
     */
    private fun reparentTabsOnMain() {
        val display = displayContainer
        val hidden = hostContainer
        val activeId = activeTab()?.id
        snapshotTabs().forEach { tab ->
            val target = if (display != null && tab.id == activeId) display else hidden
            if (target == null || tab.webView.parent === target) return@forEach
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            target.addView(tab.webView, matchParentParams())
        }
    }

    private fun publishViewerState() {
        val snapshot = runCatching {
            synchronized(poolLock) {
                tabs.values.map { tab ->
                    val url = tab.webView.url.orEmpty().ifBlank { tab.lastUrl }
                    BrowserViewerTab(
                        id = tab.id,
                        url = url,
                        title = tab.webView.title.orEmpty().ifBlank { url },
                    )
                }
            }
        }.getOrDefault(emptyList())
        _viewerState.value = BrowserViewerState(
            tabs = snapshot,
            activeTabId = synchronized(poolLock) { activeTabId },
        )
    }

    /** Moves tabs to their correct host and refreshes the viewer snapshot. */
    private fun syncPoolUi() {
        runOnMainThread {
            reparentTabsOnMain()
            publishViewerState()
        }
    }

    /** Destroys every pooled WebView. Call from the main thread (activity teardown). */
    fun destroyAllTabs() {
        val existing = snapshotTabs()
        synchronized(poolLock) {
            tabs.clear()
            activeTabId = null
            liveTabCount = 0
        }
        existing.forEach { tab -> destroyTabOnMain(tab) }
        _viewerState.value = BrowserViewerState()
    }

    // ---------------------------------------------------------------------------------------
    // Action execution
    // ---------------------------------------------------------------------------------------

    suspend fun execute(argumentsJson: String): String =
        operationMutex.withLock { executeLocked(argumentsJson) }

    /**
     * Runs one action. Callers must already hold [operationMutex]; that is how the browser viewer
     * shares the queue with the agent instead of racing it.
     */
    private suspend fun executeLocked(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return errorResult("Arguments were not valid JSON.").toString()
        val action = arguments.optString("action").trim().lowercase()
        if (!BrowserActionCatalog.isSupported(action)) {
            return errorResult("Unsupported browser action '$action'.").toString()
        }
        val payload = runCatching { dispatch(action, arguments) }.getOrElse { throwable ->
            diagnosticLogger.exception(
                category = "webview_browser",
                event = "action_failed",
                throwable = throwable,
                details = mapOf("action" to action),
            )
            return errorResult(throwable.message ?: "Browser action failed.").toString()
        }
        payload.put("backend", BrowserBackendKind.WebView.wireName)
        if (!payload.has("ok")) payload.put("ok", true)
        syncPoolUi()
        return payload.toString()
    }

    // ---------------------------------------------------------------------------------------
    // Browser viewer actions. These reuse [executeLocked] so they queue behind (and ahead of)
    // agent actions instead of fighting over the same page.
    // ---------------------------------------------------------------------------------------

    suspend fun viewerNavigate(url: String): String =
        executeViewer("navigate", JSONObject().put("url", url))

    suspend fun viewerGoBack(): String = executeViewer("back")

    suspend fun viewerGoForward(): String = executeViewer("forward")

    suspend fun viewerReload(): String = executeViewer("reload")

    suspend fun viewerNewTab(): String = executeViewer("new_tab")

    suspend fun viewerCloseTab(tabId: Int): String =
        executeViewer("close_tab", JSONObject().put("tab_id", tabId.toString()))

    /** Makes [tabId] the tab the agent and the viewer both operate on. */
    suspend fun selectTab(tabId: Int): Unit = operationMutex.withLock {
        val tab = tabById(tabId) ?: return@withLock
        tab.lastUsedAt = System.currentTimeMillis()
        synchronized(poolLock) { activeTabId = tabId }
        syncPoolUi()
    }

    /**
     * Stops the current page load on purpose *without* taking [operationMutex]: waiting for the
     * lock would mean waiting for the very navigation the user is trying to abort. The agent's
     * in-flight navigation then reports a failure or timeout, which is the honest outcome.
     */
    fun viewerStopLoading() {
        runOnMainThread {
            val tab = activeTab() ?: return@runOnMainThread
            runCatching {
                tab.pendingNavigation?.cancel()
                tab.pendingNavigation = null
                tab.webView.stopLoading()
            }
            publishViewerState()
        }
    }

    private suspend fun executeViewer(action: String, extra: JSONObject = JSONObject()): String {
        val arguments = JSONObject().put("action", action)
        extra.keys().forEach { key -> arguments.put(key, extra.get(key)) }
        return operationMutex.withLock { executeLocked(arguments.toString()) }
    }

    private suspend fun dispatch(action: String, arguments: JSONObject): JSONObject = when (action) {
        "start" -> start()
        "status" -> status()
        "navigate", "open" -> navigate(arguments)
        "new_tab" -> newTab(arguments)
        "close_tab" -> closeTab(arguments)
        "list_tabs" -> listTabs()
        "back" -> history(forward = false)
        "forward" -> history(forward = true)
        "reload" -> reload()
        "click", "tap" -> click(arguments)
        "hover" -> hover(arguments)
        "type", "text" -> type(arguments)
        "get_text" -> script(activeTabOrThrow(), browserGetTextScript(arguments.optString("selector")))
        "get_page_info" -> script(activeTabOrThrow(), BrowserPageInfoScript)
        "find_elements" -> {
            val selector = arguments.optString("selector").trim()
            if (selector.isBlank()) {
                errorResult("find_elements requires 'selector'.")
            } else {
                script(activeTabOrThrow(), browserFindElementsScript(selector))
            }
        }
        "get_readable" -> script(activeTabOrThrow(), BrowserReadableScript)
        "get_backbone" -> script(
            activeTabOrThrow(),
            browserBackboneScript(arguments.optInt("max_depth", 5)),
        )
        "scroll_and_collect" -> scrollAndCollect(arguments)
        "scroll", "swipe" -> script(
            activeTabOrThrow(),
            browserScrollScript(
                selector = arguments.optString("selector"),
                direction = arguments.optString("direction", "down"),
                amount = arguments.optInt("amount", 600).coerceIn(1, 20_000),
            ),
        )
        "execute_js", "evaluate" -> executeScript(
            activeTabOrThrow(),
            arguments.optString("script").ifBlank { arguments.optString("expression") },
        )
        "set_user_agent" -> setUserAgent(activeTabOrThrow(), arguments)
        "set_viewport" -> setViewport(activeTabOrThrow(), arguments)
        "wait_for_dom_stable" -> waitForDomStable(
            activeTabOrThrow(),
            arguments.optInt("timeout", 5_000).coerceIn(1_000, DomStableMaxMillis.toInt()),
        )
        "screenshot" -> screenshot(activeTabOrThrow())
        "stop" -> stop()
        else -> errorResult("Unsupported browser action '$action'.")
    }

    private suspend fun start(): JSONObject {
        val tab = ensureActiveTab()
        return JSONObject()
            .put("ok", true)
            .put("started", true)
            .put("active_tab_id", tab.id.toString())
            .put("hosted", isHosted)
            .put("tabs", tabsJson())
            .put(
                "stdout",
                if (isHosted) {
                    "Started the embedded browser."
                } else {
                    "Started the embedded browser without a visible host; layout and screenshots may be degraded."
                },
            )
    }

    private suspend fun status(): JSONObject {
        val tab = activeTab()
        val base = JSONObject()
            .put("ok", true)
            .put("hosted", isHosted)
            .put("tabs", tabsJson())
        if (tab == null) {
            return base
                .put("started", false)
                .put("active_tab_id", JSONObject.NULL)
                .put("stdout", "The embedded browser is stopped.")
        }
        val probe = runCatching { evaluateJson(tab, BrowserProbeScripts.readyStateProbeScript) }
            .getOrDefault(JSONObject())
        return base
            .put("started", true)
            .put("active_tab_id", tab.id.toString())
            .put("url", probe.optString("url").ifBlank { tab.lastUrl })
            .put("title", probe.optString("title"))
            .put("ready_state", probe.optString("ready_state"))
            .put("stdout", "The embedded browser is running.")
    }

    private suspend fun navigate(arguments: JSONObject): JSONObject {
        val url = normalizeUrl(arguments.optString("url"))
        if (url.isBlank()) {
            return errorResult("Missing required 'url' argument.")
        }
        val tab = if (arguments.optBoolean("new_tab", false)) {
            createTab()
        } else {
            ensureActiveTab()
        }
        val startedAt = System.currentTimeMillis()
        val outcome = awaitNavigation(tab = tab, timeoutMillis = NavigationTimeoutMillis) { webView ->
            webView.loadUrl(url)
        }
        val location = tabLocation(tab)
        return outcome
            .put("active_tab_id", tab.id.toString())
            .put("requested_url", url)
            .put("url", location.first.ifBlank { url })
            .put("title", location.second)
            .put("elapsed_ms", System.currentTimeMillis() - startedAt)
            .put(
                "stdout",
                if (outcome.optBoolean("ok") && !outcome.optBoolean("timed_out")) {
                    "Opened ${location.first.ifBlank { url }}"
                } else {
                    outcome.optString("error")
                },
            )
    }

    private suspend fun newTab(arguments: JSONObject): JSONObject {
        val tab = createTab()
        val url = normalizeUrl(arguments.optString("url"))
        if (url.isBlank()) {
            return JSONObject()
                .put("ok", true)
                .put("tab_id", tab.id.toString())
                .put("active_tab_id", tab.id.toString())
                .put("tabs", tabsJson())
                .put("stdout", "Opened a new browser tab.")
        }
        val outcome = awaitNavigation(tab = tab, timeoutMillis = NavigationTimeoutMillis) { webView ->
            webView.loadUrl(url)
        }
        return outcome
            .put("tab_id", tab.id.toString())
            .put("active_tab_id", tab.id.toString())
            .put("requested_url", url)
            .put("tabs", tabsJson())
            .put(
                "stdout",
                if (outcome.optBoolean("ok")) {
                    "Opened a new browser tab."
                } else {
                    outcome.optString("error")
                },
            )
    }

    private suspend fun closeTab(arguments: JSONObject): JSONObject {
        val requested = arguments.optString("tab_id").trim()
        val tab = if (requested.isBlank()) {
            activeTab()
        } else {
            val id = requested.toIntOrNull()
            if (id == null) null else tabById(id)
        } ?: return errorResult("No matching browser tab to close.")
        withContext(Dispatchers.Main) { destroyTabOnMain(tab) }
        synchronized(poolLock) {
            tabs.remove(tab.id)
            if (activeTabId == tab.id) {
                activeTabId = tabs.values.maxByOrNull { it.lastUsedAt }?.id
            }
            liveTabCount = tabs.size
        }
        return JSONObject()
            .put("ok", true)
            .put("closed_tab_id", tab.id.toString())
            .put("active_tab_id", activeTabId?.toString() ?: JSONObject.NULL)
            .put("tabs", tabsJson())
            .put("stdout", "Closed browser tab ${tab.id}.")
    }

    private suspend fun listTabs(): JSONObject = JSONObject()
        .put("ok", true)
        .put("active_tab_id", activeTabId?.toString() ?: JSONObject.NULL)
        .put("tabs", tabsJson())
        .put("stdout", "Listed browser tabs.")

    private suspend fun history(forward: Boolean): JSONObject {
        val tab = activeTabOrThrow()
        val available = withContext(Dispatchers.Main) {
            if (forward) tab.webView.canGoForward() else tab.webView.canGoBack()
        }
        if (!available) {
            return errorResult(
                if (forward) {
                    "The embedded browser cannot go forward from this page."
                } else {
                    "The embedded browser cannot go back from this page."
                },
            )
        }
        val startedAt = System.currentTimeMillis()
        val outcome = awaitNavigation(
            tab = tab,
            timeoutMillis = HistoryTimeoutMillis,
            allowSameDocument = true,
        ) { webView ->
            if (forward) webView.goForward() else webView.goBack()
        }
        val location = tabLocation(tab)
        return outcome
            .put("active_tab_id", tab.id.toString())
            .put("url", location.first)
            .put("title", location.second)
            .put("elapsed_ms", System.currentTimeMillis() - startedAt)
            .put(
                "stdout",
                if (outcome.optBoolean("ok")) {
                    if (forward) "Went forward." else "Went back."
                } else {
                    outcome.optString("error")
                },
            )
    }

    private suspend fun reload(): JSONObject {
        val tab = activeTabOrThrow()
        val startedAt = System.currentTimeMillis()
        val outcome = awaitNavigation(tab = tab, timeoutMillis = NavigationTimeoutMillis) { webView ->
            webView.reload()
        }
        val location = tabLocation(tab)
        return outcome
            .put("active_tab_id", tab.id.toString())
            .put("url", location.first)
            .put("title", location.second)
            .put("elapsed_ms", System.currentTimeMillis() - startedAt)
            .put("stdout", if (outcome.optBoolean("ok")) "Reloaded the page." else outcome.optString("error"))
    }

    private suspend fun click(arguments: JSONObject): JSONObject {
        val selector = arguments.optString("selector").trim()
        val x = arguments.optDoubleOrNull("x")
        val y = arguments.optDoubleOrNull("y")
        if (selector.isBlank() && (x == null || y == null)) {
            return errorResult("Provide a CSS selector or both x and y using normalized 0..1000 coordinates.")
        }
        val payload = script(
            activeTabOrThrow(),
            BrowserProbeScripts.clickVerifyScript(selector, x, y),
        )
        if (!payload.has("stdout")) {
            payload.put(
                "stdout",
                if (payload.optBoolean("clicked")) {
                    "Clicked ${payload.optString("tag")}."
                } else {
                    "Click was not dispatched: ${payload.optString("blocked_reason").ifBlank { payload.optString("error") }}"
                },
            )
        }
        return payload
    }

    private suspend fun hover(arguments: JSONObject): JSONObject {
        val selector = arguments.optString("selector").trim()
        val x = arguments.optDoubleOrNull("x")
        val y = arguments.optDoubleOrNull("y")
        if (selector.isBlank() && (x == null || y == null)) {
            return errorResult("Provide a CSS selector or both x and y using normalized 0..1000 coordinates.")
        }
        return script(activeTabOrThrow(), browserHoverScript(selector, x, y))
    }

    private suspend fun type(arguments: JSONObject): JSONObject {
        val text = arguments.optString("text")
        if (text.isEmpty()) return errorResult("Missing required 'text' argument.")
        return script(activeTabOrThrow(), browserTypeScript(arguments.optString("selector"), text))
    }

    private suspend fun setUserAgent(tab: BrowserTab, arguments: JSONObject): JSONObject {
        val userAgent = arguments.optString("user_agent").trim()
        if (userAgent.isBlank()) return errorResult("set_user_agent requires 'user_agent'.")
        withContext(Dispatchers.Main) {
            tab.webView.settings.userAgentString = userAgent
        }
        return JSONObject()
            .put("ok", true)
            .put("user_agent", userAgent)
            .put("stdout", "Updated the WebView User-Agent; it applies to the next navigation.")
    }

    private suspend fun setViewport(tab: BrowserTab, arguments: JSONObject): JSONObject {
        val width = arguments.optInt("width", 390).coerceIn(240, 4_096)
        val height = arguments.optInt("height", 844).coerceIn(240, 4_096)
        val density = appContext.resources.displayMetrics.density
        withContext(Dispatchers.Main) {
            val widthPx = (width * density).toInt().coerceAtLeast(1)
            val heightPx = (height * density).toInt().coerceAtLeast(1)
            (tab.webView.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.width = widthPx
                params.height = heightPx
                tab.webView.layoutParams = params
            } ?: run {
                tab.webView.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
                )
                tab.webView.layout(0, 0, widthPx, heightPx)
            }
        }
        val measured = runCatching {
            evaluateJson(
                tab,
                """(() => JSON.stringify({ok:true,width:window.innerWidth,height:window.innerHeight,url:location.href}))()""",
            )
        }.getOrDefault(JSONObject())
        val viewportScript = runCatching { browserSetViewportScript(width, height) }
            .getOrDefault("")
        if (viewportScript.isNotBlank()) {
            runCatching { evaluateJson(tab, viewportScript) }
        }
        return JSONObject()
            .put("ok", true)
            .put("requested_width", width)
            .put("requested_height", height)
            .put("measured_width", measured.optInt("width"))
            .put("measured_height", measured.optInt("height"))
            .put("stdout", "Resized the embedded browser viewport.")
    }

    private suspend fun scrollAndCollect(arguments: JSONObject): JSONObject {
        val tab = activeTabOrThrow()
        val steps = arguments.optInt("steps", 10).coerceIn(1, 30)
        val amount = arguments.optInt("amount", 600).coerceIn(100, 5_000)
        val chunks = LinkedHashSet<String>()
        var completedSteps = 0
        var reachedBottom = false
        for (index in 0 until steps) {
            val readable = runCatching { evaluateJson(tab, BrowserReadableScript) }.getOrDefault(JSONObject())
            val text = readable.optString("text").trim()
            if (text.isNotBlank()) chunks.add(text)
            val before = runCatching { evaluateJson(tab, ScrollPositionProbeScript) }.getOrDefault(JSONObject())
            runCatching {
                evaluateJson(tab, browserScrollScript("", "down", amount))
            }
            delay(ScrollAndCollectPauseMillis)
            val after = runCatching { evaluateJson(tab, ScrollPositionProbeScript) }.getOrDefault(JSONObject())
            completedSteps = index + 1
            val bottomReached = before.optDouble("scroll_y") + before.optDouble("inner_height") >=
                before.optDouble("scroll_height")
            if (bottomReached || after.optDouble("scroll_y") <= before.optDouble("scroll_y")) {
                reachedBottom = true
                break
            }
        }
        val joined = chunks.joinToString("\n\n")
        val text = joined.take(minOf(joined.length, MaxCollectedTextChars))
        return JSONObject()
            .put("ok", true)
            .put("text", text)
            .put("length", joined.length)
            .put("truncated", joined.length > text.length)
            .put("steps", completedSteps)
            .put("reached_bottom", reachedBottom)
            .put("stdout", text)
    }

    private suspend fun executeScript(tab: BrowserTab, source: String): JSONObject {
        if (source.isBlank()) return errorResult("Missing required 'script' argument.")
        val wrapped = runCatching { evaluateJson(tab, wrapExpressionScript(source)) }.getOrDefault(JSONObject())
        if (wrapped.optBoolean("script_error")) {
            return JSONObject()
                .put("ok", false)
                .put("error", wrapped.optString("error").ifBlank { "JavaScript evaluation failed." })
        }
        val value = wrapped.opt("result")
        if (value == null || value === JSONObject.NULL) {
            // Either the script is a statement-style snippet (not an expression) or it really
            // evaluated to null/undefined. One raw re-evaluation settles it.
            val raw = runCatching { evaluateJson(tab, source) }.getOrDefault(JSONObject())
            if (raw.has("result") || raw.has("text")) return enrich(raw, tab)
        }
        return enrich(wrapped, tab)
    }

    private suspend fun waitForDomStable(tab: BrowserTab, timeoutMillis: Int): JSONObject {
        val startedAt = System.currentTimeMillis()
        runCatching { evaluateJson(tab, BrowserProbeScripts.domMutationInstallScript) }
        var lastMutations = -1
        var lastMutationAt = System.currentTimeMillis()
        var stable = false
        var probe = JSONObject()
        while (System.currentTimeMillis() - startedAt < timeoutMillis) {
            probe = runCatching { evaluateJson(tab, BrowserProbeScripts.domMutationProbeScript) }
                .getOrDefault(JSONObject())
            val mutations = probe.optInt("mutations", -1)
            if (mutations != lastMutations) {
                lastMutations = mutations
                lastMutationAt = System.currentTimeMillis()
            }
            val ready = probe.optString("ready_state") != "loading"
            if (ready && System.currentTimeMillis() - lastMutationAt >= DomStableQuietWindowMillis) {
                stable = true
                break
            }
            delay(DomStablePollMillis)
        }
        return JSONObject()
            .put("ok", true)
            .put("stable", stable)
            .put("timed_out", !stable)
            .put("elapsed_ms", System.currentTimeMillis() - startedAt)
            .put("mutation_count", lastMutations.coerceAtLeast(0))
            .put("ready_state", probe.optString("ready_state"))
            .put("element_count", probe.optInt("element_count"))
            .put("url", probe.optString("url").ifBlank { tab.lastUrl })
            .put(
                "stdout",
                if (stable) {
                    "The DOM stopped changing."
                } else {
                    "The DOM was still changing when the timeout expired."
                },
            )
    }

    private suspend fun screenshot(tab: BrowserTab): JSONObject = withContext(Dispatchers.Main) {
        val width = tab.webView.width.takeIf { it > 0 } ?: DefaultViewportWidthPx
        val height = tab.webView.height.takeIf { it > 0 } ?: DefaultViewportHeightPx
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        tab.webView.draw(canvas)
        if (looksBlank(bitmap)) {
            bitmap.recycle()
            return@withContext JSONObject()
                .put("ok", false)
                .put("hosted", isHosted)
                .put("error", "The embedded WebView produced a uniform image: either the page is blank or the pooled WebView is not being rendered. Retry with backend \"alpine\" if you need a visual capture.")
        }
        val file = File(captureDirectory, "webview-${System.currentTimeMillis()}.jpg")
        val encoded = runCatching {
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, ScreenshotQuality, stream)
            }
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrElse { throwable ->
            bitmap.recycle()
            return@withContext JSONObject()
                .put("ok", false)
                .put("error", throwable.message ?: "Could not encode the screenshot.")
        }
        bitmap.recycle()
        JSONObject()
            .put("ok", true)
            .put("path", file.absolutePath)
            .put("size", file.length())
            .put("screenshot_base64", encoded)
            .put("screenshot_mime_type", "image/jpeg")
            .put("stdout", "Captured the embedded browser.")
    }

    private suspend fun stop(): JSONObject {
        withContext(Dispatchers.Main) { destroyAllTabs() }
        return JSONObject().put("ok", true).put("stdout", "Stopped the embedded browser.")
    }

    // ---------------------------------------------------------------------------------------
    // Navigation helpers
    // ---------------------------------------------------------------------------------------

    private suspend fun awaitNavigation(
        tab: BrowserTab,
        timeoutMillis: Long,
        allowSameDocument: Boolean = false,
        trigger: (WebView) -> Unit,
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        val previousUrl = tabUrl(tab)
        val pending = withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<NavigationOutcome>()
            tab.pendingNavigation?.cancel()
            tab.pendingNavigation = deferred
            tab.lastNavigationError = null
            trigger(tab.webView)
            deferred
        }
        var sameDocument = false
        val deadline = startedAt + timeoutMillis
        while (!pending.isCompleted && System.currentTimeMillis() < deadline) {
            delay(NavigationPollMillis)
            if (!allowSameDocument || sameDocument) continue
            val currentUrl = tabUrl(tab)
            if (currentUrl.isNotBlank() && currentUrl != previousUrl) {
                val probe = runCatching { evaluateJson(tab, BrowserProbeScripts.readyStateProbeScript) }
                    .getOrDefault(JSONObject())
                if (probe.optString("ready_state") == "complete") {
                    sameDocument = true
                    break
                }
            }
        }
        val outcome = if (pending.isCompleted) pending.getCompleted() else null
        if (outcome == null) {
            withContext(Dispatchers.Main) { tab.pendingNavigation = null }
            if (sameDocument) {
                return JSONObject()
                    .put("ok", true)
                    .put("timed_out", false)
                    .put("same_document", true)
                    .put("elapsed_ms", System.currentTimeMillis() - startedAt)
            }
            return JSONObject()
                .put("ok", false)
                .put("timed_out", true)
                .put("elapsed_ms", System.currentTimeMillis() - startedAt)
                .put("error", "The page did not finish loading within ${timeoutMillis}ms.")
        }
        tab.lastUsedAt = System.currentTimeMillis()
        if (!outcome.error.isNullOrBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("elapsed_ms", System.currentTimeMillis() - startedAt)
                .put("url", outcome.url)
                .put("error", outcome.error)
        }
        return JSONObject()
            .put("ok", true)
            .put("timed_out", false)
            .put("elapsed_ms", System.currentTimeMillis() - startedAt)
            .put("url", outcome.url)
    }

    private suspend fun tabUrl(tab: BrowserTab): String = withContext(Dispatchers.Main) {
        tab.webView.url.orEmpty().ifBlank { tab.lastUrl }
    }

    private suspend fun tabLocation(tab: BrowserTab): Pair<String, String> = withContext(Dispatchers.Main) {
        val url = tab.webView.url.orEmpty().ifBlank { tab.lastUrl }
        val title = tab.webView.title.orEmpty()
        tab.lastUrl = url
        tab.lastUsedAt = System.currentTimeMillis()
        url to title
    }

    // ---------------------------------------------------------------------------------------
    // Tab pool
    // ---------------------------------------------------------------------------------------

    private fun snapshotTabs(): List<BrowserTab> = synchronized(poolLock) { tabs.values.toList() }

    private fun activeTab(): BrowserTab? = synchronized(poolLock) {
        activeTabId?.let { tabs[it] } ?: tabs.values.maxByOrNull { it.lastUsedAt }
    }

    private fun tabById(id: Int): BrowserTab? = synchronized(poolLock) { tabs[id] }

    private suspend fun activeTabOrThrow(): BrowserTab = activeTab() ?: ensureActiveTab()

    private suspend fun ensureActiveTab(): BrowserTab {
        recycleIdleTabs()
        return activeTab() ?: createTab()
    }

    private suspend fun createTab(): BrowserTab {
        recycleIdleTabs()
        val tab = withContext(Dispatchers.Main) {
            val overflow = synchronized(poolLock) {
                if (tabs.size >= MaxBrowserTabs) {
                    tabs.values.filter { it.id != activeTabId }.minByOrNull { it.lastUsedAt }
                } else {
                    null
                }
            }
            overflow?.let { oldest ->
                destroyTabOnMain(oldest)
                synchronized(poolLock) {
                    tabs.remove(oldest.id)
                    if (activeTabId == oldest.id) activeTabId = null
                }
            }
            val id = tabIdCounter.incrementAndGet()
            val webView = buildWebView()
            val created = BrowserTab(id = id, webView = webView)
            synchronized(poolLock) {
                tabs[id] = created
                activeTabId = id
                liveTabCount = tabs.size
            }
            syncPoolUi()
            created
        }
        return tab
    }

    private suspend fun recycleIdleTabs() {
        val cutoff = System.currentTimeMillis() - IdleTabRecycleMillis
        val stale = synchronized(poolLock) {
            tabs.values.filter { it.id != activeTabId && it.lastUsedAt < cutoff }
        }
        if (stale.isEmpty()) return
        withContext(Dispatchers.Main) {
            stale.forEach { tab ->
                destroyTabOnMain(tab)
                synchronized(poolLock) {
                    tabs.remove(tab.id)
                    liveTabCount = tabs.size
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val webView = WebView(appContext)
        webView.setBackgroundColor(Color.WHITE)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = false
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = BrowserWebViewClient()
        webView.webChromeClient = BrowserWebChromeClient()
        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }
        webView.onResume()
        webView.resumeTimers()
        val host = hostContainer
        if (host != null) {
            host.addView(webView, matchParentParams())
        } else {
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(DefaultViewportWidthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(DefaultViewportHeightPx, View.MeasureSpec.EXACTLY),
            )
            webView.layout(0, 0, DefaultViewportWidthPx, DefaultViewportHeightPx)
        }
        return webView
    }

    private fun destroyTabOnMain(tab: BrowserTab) {
        synchronized(poolLock) {
            if (tabs.remove(tab.id) != null) {
                liveTabCount = tabs.size
                if (activeTabId == tab.id) activeTabId = tabs.values.maxByOrNull { it.lastUsedAt }?.id
            }
        }
        runCatching { tab.pendingNavigation?.cancel() }
        runCatching { (tab.webView.parent as? ViewGroup)?.removeView(tab.webView) }
        runCatching { tab.webView.stopLoading() }
        runCatching { tab.webView.destroy() }
        syncPoolUi()
    }

    private fun matchParentParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private inner class BrowserWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            val tab = tabFor(view) ?: return
            if (!url.isNullOrBlank()) tab.lastUrl = url
            tab.lastNavigationError = null
            publishViewerState()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val tab = tabFor(view) ?: return
            if (!url.isNullOrBlank()) tab.lastUrl = url
            tab.lastUsedAt = System.currentTimeMillis()
            val pending = tab.pendingNavigation
            tab.pendingNavigation = null
            pending?.complete(NavigationOutcome(url = url.orEmpty(), error = tab.lastNavigationError))
            syncPoolUi()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame != true) return
            val tab = tabFor(view) ?: return
            tab.lastNavigationError = error?.description?.toString().orEmpty()
                .ifBlank { "The page failed to load." }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest?): Boolean = false

        @Suppress("OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean = false
    }

    /** Titles arrive through the chrome client, usually before the page finishes loading. */
    private inner class BrowserWebChromeClient : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            if (title.isNullOrBlank() || tabFor(view) == null) return
            publishViewerState()
        }
    }

    private fun tabFor(view: WebView): BrowserTab? =
        synchronized(poolLock) { tabs.values.firstOrNull { it.webView === view } }

    // ---------------------------------------------------------------------------------------
    // JavaScript evaluation
    // ---------------------------------------------------------------------------------------

    private suspend fun script(tab: BrowserTab, source: String): JSONObject {
        if (source.isBlank()) return errorResult("Missing JavaScript source.")
        val payload = evaluateJson(tab, source)
        tab.lastUsedAt = System.currentTimeMillis()
        return enrich(payload, tab)
    }

    private suspend fun enrich(payload: JSONObject, tab: BrowserTab): JSONObject {
        if (!payload.has("url") || payload.optString("url").isBlank()) {
            payload.put("url", tabUrl(tab))
        }
        if (!payload.has("title") || payload.optString("title").isBlank()) {
            payload.put("title", withContext(Dispatchers.Main) { tab.webView.title.orEmpty() })
        }
        if (!payload.has("stdout")) {
            val text = payload.optString("text")
            payload.put("stdout", text.ifBlank { payload.toString() })
        }
        payload.put("hosted", isHosted)
        return payload
    }

    private suspend fun evaluateJson(tab: BrowserTab, source: String): JSONObject =
        withContext(Dispatchers.Main) {
            val raw = suspendCancellableCoroutine { continuation ->
                tab.webView.evaluateJavascript(source) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            }
            decodeEvaluatedValue(raw)
        }

    private fun decodeEvaluatedValue(raw: String?): JSONObject {
        if (raw.isNullOrBlank() || raw == "null") {
            return JSONObject().put("ok", true).put("result", JSONObject.NULL)
        }
        val decoded = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        val text = when (decoded) {
            is String -> decoded
            null -> raw
            else -> decoded.toString()
        }
        return runCatching { JSONObject(text) }
            .getOrElse { JSONObject().put("ok", true).put("result", text) }
    }

    private fun wrapExpressionScript(source: String): String = """
        (() => {
          try {
            const value = (function () { return ($source); })();
            return JSON.stringify({ok:true,result:value === undefined ? null : value});
          } catch (error) {
            return JSON.stringify({
              ok:false,
              script_error:true,
              error:String(error && error.message ? error.message : error)
            });
          }
        })()
    """.trimIndent()

    // ---------------------------------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------------------------------

    private suspend fun tabsJson(): JSONArray {
        val snapshot = snapshotTabs()
        val active = activeTabId
        val array = JSONArray()
        snapshot.forEach { tab ->
            array.put(
                JSONObject()
                    .put("id", tab.id.toString())
                    .put("url", tabUrl(tab))
                    .put("title", withContext(Dispatchers.Main) { tab.webView.title.orEmpty() })
                    .put("active", tab.id == active),
            )
        }
        return array
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim().replace(" ", "%20")
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("about:", ignoreCase = true) || trimmed.contains("://")) return trimmed
        return "https://" + trimmed.removePrefix("//")
    }

    private fun looksBlank(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 8).coerceAtLeast(1)
        val stepY = (bitmap.height / 12).coerceAtLeast(1)
        var reference: Int? = null
        var index = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val referencePixel = reference
                if (referencePixel == null) {
                    reference = pixel
                } else if (!colorsAreClose(referencePixel, pixel)) {
                    return false
                }
                index += 1
                x += stepX
            }
            y += stepY
        }
        return index > 0
    }

    private fun colorsAreClose(first: Int, second: Int): Boolean {
        val red = kotlin.math.abs(Color.red(first) - Color.red(second))
        val green = kotlin.math.abs(Color.green(first) - Color.green(second))
        val blue = kotlin.math.abs(Color.blue(first) - Color.blue(second))
        return red <= 6 && green <= 6 && blue <= 6
    }

    private fun errorResult(message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("error", message)
        .put("errmsg", message)

    private class BrowserTab(
        val id: Int,
        val webView: WebView,
    ) {
        var lastUsedAt: Long = System.currentTimeMillis()
        var lastUrl: String = "about:blank"
        var lastNavigationError: String? = null
        var pendingNavigation: CompletableDeferred<NavigationOutcome>? = null
    }

    private data class NavigationOutcome(
        val url: String,
        val error: String?,
    )
}

private fun JSONObject.optDoubleOrNull(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    val value = optDouble(name, Double.NaN)
    return if (value.isNaN()) null else value
}
