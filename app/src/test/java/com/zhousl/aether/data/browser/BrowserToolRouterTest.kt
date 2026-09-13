package com.zhousl.aether.data.browser

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserToolRouterTest {
    private var probeCalls = 0

    @Test
    fun `auto runs on the embedded backend when nothing is running`() = runHarness {
        val response = navigate("https://example.com")

        assertTrue(response.optBoolean("ok"))
        assertEquals("webview", response.optString("backend"))
        assertEquals(listOf("navigate"), webView.actions)
        assertTrue(alpine.actions.contains("status"))
        assertFalse(alpine.actions.contains("navigate"))
    }

    @Test
    fun `auto keeps an alpine session that is already running`() = runHarness(
        alpineStatus = { JSONObject().put("ok", true).put("running", true) },
    ) {
        val response = getText()

        assertEquals("alpine", response.optString("backend"))
        assertEquals(listOf("get_text"), alpine.actions.filter { it != "status" })
        assertTrue(webView.actions.isEmpty())
    }

    @Test
    fun `auto prefers the embedded pool once it holds a page`() = runHarness(
        webViewHasTabs = true,
        alpineStatus = { JSONObject().put("ok", true).put("running", true) },
    ) {
        val response = getText()

        assertEquals("webview", response.optString("backend"))
        assertEquals(listOf("get_text"), webView.actions)
        assertTrue(alpine.actions.isEmpty())
    }

    @Test
    fun `an explicit backend wins over the auto rule`() = runHarness(webViewHasTabs = true) {
        val response = JSONObject(router.execute("""{"action":"reload","backend":"alpine"}"""))

        assertEquals("alpine", response.optString("backend"))
        assertEquals(listOf("reload"), alpine.actions)
        assertTrue(webView.actions.isEmpty())
    }

    @Test
    fun `an unknown backend is rejected with the accepted values`() = runHarness {
        val response = JSONObject(router.execute("""{"action":"reload","backend":"firefox"}"""))

        assertFalse(response.optBoolean("ok"))
        assertTrue(response.optString("error").contains("firefox"))
        assertTrue(webView.actions.isEmpty())
    }

    @Test
    fun `an unsupported action lists what is available`() = runHarness {
        val response = JSONObject(router.execute("""{"action":"teleport"}"""))

        assertFalse(response.optBoolean("ok"))
        assertTrue(response.optString("error").contains("wait_for"))
        assertTrue(webView.actions.isEmpty())
    }

    @Test
    fun `an unavailable backend is reported instead of silently rerouted`() = runHarness(
        withoutWebView = true,
    ) {
        val response = JSONObject(router.execute("""{"action":"navigate","url":"https://a.b"}"""))

        assertFalse(response.optBoolean("ok"))
        assertTrue(response.optString("error").contains("WebView"))
        assertFalse(alpine.actions.contains("navigate"))
    }

    @Test
    fun `a backend failure becomes an honest error payload`() = runHarness(
        webViewResponse = { _, _ -> throw IllegalStateException("WebView is not ready.") },
    ) {
        val response = navigate("https://a.b")

        assertFalse(response.optBoolean("ok"))
        assertEquals("WebView is not ready.", response.optString("error"))
    }

    @Test
    fun `wait_for polls until the element appears`() = runHarness(
        webViewResponse = { action, _ ->
            if (action != "execute_js") {
                JSONObject().put("ok", true)
            } else {
                val poll = probeCalls++
                JSONObject().put("ok", true).put("result", probeJson(count = if (poll >= 2) 1 else 0))
            }
        },
    ) {
        val response = JSONObject(
            router.execute("""{"action":"wait_for","selector":".result","timeout":5000}"""),
        )

        assertTrue(response.optBoolean("ok"))
        assertTrue(response.optBoolean("found"))
        assertEquals(3, response.optInt("polls"))
        assertEquals("webview", response.optString("backend"))
        assertEquals(500L, response.optLong("elapsed_ms"))
    }

    @Test
    fun `wait_for times out instead of claiming success`() = runHarness(
        webViewResponse = { action, _ ->
            if (action != "execute_js") {
                JSONObject().put("ok", true)
            } else {
                JSONObject().put("ok", true).put("result", probeJson(count = 0))
            }
        },
    ) {
        val response = JSONObject(
            router.execute("""{"action":"wait_for","selector":".result","timeout":1000}"""),
        )

        assertFalse(response.optBoolean("ok"))
        assertTrue(response.optBoolean("timed_out"))
        assertFalse(response.optBoolean("found"))
        assertTrue(response.optInt("polls") >= 2)
        assertTrue(response.optString("error").contains(".result"))
    }

    @Test
    fun `wait_for_gone succeeds once the selector disappears`() = runHarness(
        webViewResponse = { action, _ ->
            if (action != "execute_js") {
                JSONObject().put("ok", true)
            } else {
                val poll = probeCalls++
                JSONObject().put("ok", true).put("result", probeJson(count = if (poll >= 2) 0 else 3))
            }
        },
    ) {
        val response = JSONObject(
            router.execute("""{"action":"wait_for_gone","selector":".spinner","timeout":5000}"""),
        )

        assertTrue(response.optBoolean("ok"))
        assertTrue(response.optBoolean("gone"))
        assertEquals(3, response.optInt("polls"))
    }

    @Test
    fun `wait_for counts only visible elements by default`() = runHarness(
        webViewResponse = { action, _ ->
            if (action != "execute_js") {
                JSONObject().put("ok", true)
            } else {
                JSONObject().put("ok", true).put("result", probeJson(count = 2, visible = 0))
            }
        },
    ) {
        val response = JSONObject(
            router.execute("""{"action":"wait_for","selector":".ghost","timeout":600}"""),
        )

        assertFalse(response.optBoolean("ok"))
        assertTrue(response.optBoolean("timed_out"))
        assertTrue(response.optBoolean("visible_only"))
    }

    @Test
    fun `wait_for can be told to ignore visibility`() = runHarness(
        webViewResponse = { action, _ ->
            if (action != "execute_js") {
                JSONObject().put("ok", true)
            } else {
                JSONObject().put("ok", true).put("result", probeJson(count = 2, visible = 0))
            }
        },
    ) {
        val response = JSONObject(
            router.execute("""{"action":"wait_for","selector":".ghost","visible":false,"timeout":600}"""),
        )

        assertTrue(response.optBoolean("ok"))
        assertFalse(response.optBoolean("visible_only"))
        assertEquals(2, response.optInt("count"))
    }

    @Test
    fun `a double encoded probe result from the alpine backend still counts`() = runHarness(
        alpineStatus = { JSONObject().put("ok", true).put("running", true) },
        alpineResponse = { action, _ ->
            if (action == "execute_js") {
                // Alpine answers execute_js with the JSON string nested under "result".
                JSONObject().put("ok", true).put("result", probeJson(count = 1))
            } else {
                JSONObject().put("ok", true).put("running", true)
            }
        },
    ) {
        val response = JSONObject(
            router.execute("""{"action":"wait_for","selector":".result","timeout":2000}"""),
        )

        assertTrue(response.optBoolean("ok"))
        assertEquals("alpine", response.optString("backend"))
        assertEquals(1, response.optInt("polls"))
    }

    @Test
    fun `wait_for requires a selector`() = runHarness {
        val response = JSONObject(router.execute("""{"action":"wait_for"}"""))

        assertFalse(response.optBoolean("ok"))
        assertTrue(response.optString("error").contains("selector"))
    }

    @Test
    fun `status reports both backends and the active one`() = runHarness {
        val response = JSONObject(router.execute("""{"action":"status"}"""))

        assertTrue(response.optBoolean("ok"))
        assertEquals("webview", response.optString("active_backend"))
        assertTrue(response.getJSONObject("backends").has("webview"))
        assertTrue(response.getJSONObject("backends").has("alpine"))
    }

    @Test
    fun `stop with auto stops both backends`() = runHarness {
        val response = JSONObject(router.execute("""{"action":"stop"}"""))

        assertTrue(response.optBoolean("ok"))
        assertTrue(webView.actions.contains("stop"))
        assertTrue(alpine.actions.contains("stop"))
    }

    // -----------------------------------------------------------------------------------------

    private suspend fun Harness.navigate(url: String): JSONObject =
        JSONObject(router.execute("""{"action":"navigate","url":"$url"}"""))

    private suspend fun Harness.getText(): JSONObject =
        JSONObject(router.execute("""{"action":"get_text"}"""))

    private fun runHarness(
        webViewHasTabs: Boolean = false,
        withoutWebView: Boolean = false,
        alpineStatus: (JSONObject) -> JSONObject = { JSONObject().put("ok", true).put("running", false) },
        webViewResponse: (String, JSONObject) -> JSONObject = { _, _ -> JSONObject().put("ok", true) },
        alpineResponse: (String, JSONObject) -> JSONObject = { _, _ -> JSONObject().put("ok", true) },
        block: suspend Harness.() -> Unit,
    ) {
        probeCalls = 0
        val webView = FakeBrowserBackend(webViewResponse)
        val alpine = FakeBrowserBackend { action, arguments ->
            if (action == "status") alpineStatus(arguments) else alpineResponse(action, arguments)
        }
        var clock = 0L
        val router = BrowserToolRouter(
            executeWebView = if (withoutWebView) null else webView::execute,
            executeAlpine = alpine::execute,
            webViewHasTabs = { webViewHasTabs },
            waitPollMillis = 250L,
            nowMillis = { clock },
            sleep = { millis -> clock += millis },
        )
        val harness = Harness(router, webView, alpine)
        runBlocking { harness.block() }
    }

    private fun probeJson(count: Int, visible: Int = count): String = JSONObject()
        .put("ok", true)
        .put("count", count)
        .put("visible_count", visible)
        .put("url", "https://example.com")
        .toString()

    private class Harness(
        val router: BrowserToolRouter,
        val webView: FakeBrowserBackend,
        val alpine: FakeBrowserBackend,
    )

    private class FakeBrowserBackend(
        private val respond: (String, JSONObject) -> JSONObject = { _, _ -> JSONObject().put("ok", true) },
    ) {
        val actions = mutableListOf<String>()

        suspend fun execute(argumentsJson: String): String {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action")
            actions.add(action)
            return respond(action, arguments).toString()
        }
    }
}
