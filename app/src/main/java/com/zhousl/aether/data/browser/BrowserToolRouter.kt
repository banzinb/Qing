package com.zhousl.aether.data.browser

import kotlinx.coroutines.delay
import org.json.JSONObject

private const val DefaultWaitTimeoutMillis = 15_000
private const val MinWaitTimeoutMillis = 500
private const val MaxWaitTimeoutMillis = 120_000
private const val DefaultWaitPollMillis = 250L

/**
 * Routes `browser` host-tool actions to one of the two backends and implements the condition
 * waits that both backends share.
 *
 * Backends are injected as plain suspend lambdas so routing and waiting stay unit testable
 * without an Android device.
 */
class BrowserToolRouter(
    private val executeWebView: (suspend (String) -> String)? = null,
    private val executeAlpine: (suspend (String) -> String)? = null,
    private val webViewHasTabs: () -> Boolean = { false },
    private val waitPollMillis: Long = DefaultWaitPollMillis,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
) {
    suspend fun execute(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return errorPayload("Arguments were not valid JSON.").toString()
        val action = arguments.optString("action").trim().lowercase()
        if (action.isBlank()) {
            return errorPayload("Missing required 'action' argument.").toString()
        }
        if (!BrowserActionCatalog.isSupported(action)) {
            return errorPayload(
                "Unsupported browser action '$action'. Supported actions: " +
                    (BrowserActionCatalog.BackendActions + BrowserActionCatalog.RouterActions)
                        .sorted()
                        .joinToString(", ") + ".",
            ).toString()
        }
        val request = BrowserBackendSelector.parseRequest(arguments.optString("backend"))
        if (request is BrowserBackendRequest.Unknown) {
            return errorPayload(
                "Unknown browser backend '${request.raw}'. Use \"auto\", \"webview\" or \"alpine\".",
            ).toString()
        }
        val payload = when (action) {
            "status" -> status(request)
            "stop" -> stop(request)
            in BrowserActionCatalog.RouterActions -> waitForElement(action, arguments, request)
            else -> {
                val kind = resolveKind(request)
                when (val unavailable = unavailableMessage(kind)) {
                    null -> callBackend(kind, arguments)
                    else -> errorPayload(unavailable)
                }
            }
        }
        if (!payload.has("ok")) payload.put("ok", true)
        return payload.toString()
    }

    private suspend fun resolveKind(request: BrowserBackendRequest): BrowserBackendKind =
        when (request) {
            is BrowserBackendRequest.Explicit -> request.kind
            else -> {
                val hasTabs = webViewHasTabs()
                // Probing Alpine is a backend round trip, so only ask when the embedded pool is
                // idle; when it already holds a page the decision cannot change.
                BrowserBackendSelector.select(
                    webViewHasTabs = hasTabs,
                    alpineRunning = if (hasTabs) false else alpineRunning(),
                )
            }
        }

    private suspend fun alpineRunning(): Boolean {
        val execute = executeAlpine ?: return false
        val payload = runCatching { parse(execute(STATUS_ARGUMENTS)) }.getOrNull() ?: return false
        return payload.optBoolean("running")
    }

    private fun unavailableMessage(kind: BrowserBackendKind): String? = when (kind) {
        BrowserBackendKind.WebView ->
            if (executeWebView == null) "The embedded WebView browser backend is unavailable." else null
        BrowserBackendKind.Alpine ->
            if (executeAlpine == null) {
                "The Alpine/Chromium browser backend is unavailable on this platform."
            } else {
                null
            }
    }

    private suspend fun callBackend(kind: BrowserBackendKind, arguments: JSONObject): JSONObject {
        val execute = when (kind) {
            BrowserBackendKind.WebView -> executeWebView
            BrowserBackendKind.Alpine -> executeAlpine
        } ?: return errorPayload("The ${kind.wireName} browser backend is unavailable.")
        val payload = runCatching { parse(execute(arguments.toString())) }
            .getOrElse { throwable ->
                return errorPayload(throwable.message ?: "The ${kind.wireName} browser backend failed.")
            }
        payload.put("backend", kind.wireName)
        return payload
    }

    private suspend fun status(request: BrowserBackendRequest): JSONObject {
        val explicit = request as? BrowserBackendRequest.Explicit
        if (explicit != null) {
            return callBackend(explicit.kind, JSONObject().put("action", "status"))
        }
        val backends = JSONObject()
        executeWebView?.let { execute ->
            backends.put(
                BrowserBackendKind.WebView.wireName,
                normalizeStatus(
                    runCatching { parse(execute(STATUS_ARGUMENTS)) }
                        .getOrElse { errorPayload(it.message ?: "unavailable") },
                ),
            )
        }
        executeAlpine?.let { execute ->
            backends.put(
                BrowserBackendKind.Alpine.wireName,
                normalizeStatus(
                    runCatching { parse(execute(STATUS_ARGUMENTS)) }
                        .getOrElse { errorPayload(it.message ?: "unavailable") },
                ),
            )
        }
        val kind = resolveKind(request)
        return JSONObject()
            .put("ok", true)
            .put("backend", kind.wireName)
            .put("active_backend", kind.wireName)
            .put("backends", backends)
            .put(
                "stdout",
                "Active browser backend: ${kind.wireName}. Use the 'backend' argument to pick one explicitly.",
            )
    }

    /** Both backends describe "is a browser running" differently; report it one way. */
    private fun normalizeStatus(payload: JSONObject): JSONObject {
        val active = payload.optBoolean("started") || payload.optBoolean("running")
        return payload.put("started", active).put("running", active)
    }

    private suspend fun stop(request: BrowserBackendRequest): JSONObject {
        val explicit = request as? BrowserBackendRequest.Explicit
        if (explicit != null) {
            return callBackend(explicit.kind, JSONObject().put("action", "stop"))
        }
        val stopped = JSONObject()
        executeWebView?.let { execute ->
            stopped.put(
                BrowserBackendKind.WebView.wireName,
                runCatching { parse(execute(STOP_ARGUMENTS)) }.getOrElse { errorPayload(it.message ?: "unavailable") },
            )
        }
        executeAlpine?.let { execute ->
            stopped.put(
                BrowserBackendKind.Alpine.wireName,
                runCatching { parse(execute(STOP_ARGUMENTS)) }.getOrElse { errorPayload(it.message ?: "unavailable") },
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("stopped", stopped)
            .put("stdout", "Stopped the browser backends.")
    }

    /**
     * `wait_for` / `wait_for_gone`.
     *
     * Implemented on top of `execute_js` so both backends share one implementation. The result is
     * explicit about whether the condition was reached: a timeout is reported as a failure with
     * the elapsed time, never as a silent success.
     */
    private suspend fun waitForElement(
        action: String,
        arguments: JSONObject,
        request: BrowserBackendRequest,
    ): JSONObject {
        val selector = arguments.optString("selector").trim()
        if (selector.isBlank()) return errorPayload("$action requires a 'selector' argument.")
        val timeout = arguments
            .optInt("timeout", DefaultWaitTimeoutMillis)
            .coerceIn(MinWaitTimeoutMillis, MaxWaitTimeoutMillis)
        val visibleOnly = if (arguments.has("visible")) {
            arguments.optBoolean("visible")
        } else {
            action == "wait_for"
        }
        val minCount = arguments.optInt("min_count", 1).coerceIn(0, 500)
        val expectPresent = action == "wait_for"
        val targetCount = if (expectPresent) minCount.coerceAtLeast(1) else minCount
        val kind = resolveKind(request)
        unavailableMessage(kind)?.let { message -> return errorPayload(message).put("backend", kind.wireName) }
        val startedAt = nowMillis()
        var polls = 0
        var lastProbe = JSONObject()
        while (true) {
            polls += 1
            val probePayload = callBackend(
                kind,
                JSONObject()
                    .put("action", "execute_js")
                    .put("script", BrowserProbeScripts.elementProbeScript(selector)),
            )
            lastProbe = unwrapProbe(probePayload)
            val count = lastProbe.optInt("count")
            val visibleCount = lastProbe.optInt("visible_count")
            val matched = if (visibleOnly) visibleCount else count
            val satisfied = if (expectPresent) matched >= targetCount else matched < targetCount
            val elapsed = nowMillis() - startedAt
            if (satisfied) {
                return JSONObject()
                    .put("ok", true)
                    .put("backend", kind.wireName)
                    .put("matched", true)
                    .put(
                        if (expectPresent) "found" else "gone",
                        true,
                    )
                    .put("count", count)
                    .put("visible_count", visibleCount)
                    .put("visible_only", visibleOnly)
                    .put("elapsed_ms", elapsed)
                    .put("polls", polls)
                    .put("url", lastProbe.optString("url"))
                    .put(
                        "stdout",
                        if (expectPresent) {
                            "'$selector' is present${if (visibleOnly) " and visible" else ""} after ${elapsed}ms."
                        } else {
                            "'$selector' is no longer present${if (visibleOnly) " as a visible element" else ""} after ${elapsed}ms."
                        },
                    )
            }
            if (elapsed >= timeout) {
                return JSONObject()
                    .put("ok", false)
                    .put("backend", kind.wireName)
                    .put("matched", false)
                    .put("timed_out", true)
                    .put("count", count)
                    .put("visible_count", visibleCount)
                    .put("visible_only", visibleOnly)
                    .put("elapsed_ms", elapsed)
                    .put("polls", polls)
                    .put("url", lastProbe.optString("url"))
                    .put(
                        "error",
                        if (expectPresent) {
                            "'$selector' did not appear within ${timeout}ms."
                        } else {
                            "'$selector' was still present after ${timeout}ms."
                        },
                    )
            }
            sleep(waitPollMillis)
        }
    }

    private fun unwrapProbe(payload: JSONObject): JSONObject {
        payload.opt("result")?.let { result ->
            when (result) {
                is JSONObject -> return result
                is String -> runCatching { JSONObject(result) }.getOrNull()?.let { return it }
            }
        }
        return payload
    }

    private fun parse(raw: String): JSONObject = runCatching { JSONObject(raw) }
        .getOrElse { errorPayload(raw.ifBlank { "The browser backend returned no result." }) }

    private fun errorPayload(message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("error", message)
        .put("errmsg", message)

    private companion object {
        private const val STATUS_ARGUMENTS = """{"action":"status"}"""
        private const val STOP_ARGUMENTS = """{"action":"stop"}"""
    }
}
