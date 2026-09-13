package com.zhousl.aether.data.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBackendSelectorTest {
    @Test
    fun `blank and auto mean auto`() {
        assertEquals(BrowserBackendRequest.Auto, BrowserBackendSelector.parseRequest(null))
        assertEquals(BrowserBackendRequest.Auto, BrowserBackendSelector.parseRequest(""))
        assertEquals(BrowserBackendRequest.Auto, BrowserBackendSelector.parseRequest("  "))
        assertEquals(BrowserBackendRequest.Auto, BrowserBackendSelector.parseRequest("AUTO"))
    }

    @Test
    fun `embedded aliases select the webview backend`() {
        listOf("webview", "WEB_VIEW", "embedded", "embedded_webview", "internal").forEach { raw ->
            assertEquals(
                raw,
                BrowserBackendRequest.Explicit(BrowserBackendKind.WebView),
                BrowserBackendSelector.parseRequest(raw),
            )
        }
    }

    @Test
    fun `chromium aliases select the alpine backend`() {
        listOf("alpine", "Chrome", "chromium", "cdp").forEach { raw ->
            assertEquals(
                raw,
                BrowserBackendRequest.Explicit(BrowserBackendKind.Alpine),
                BrowserBackendSelector.parseRequest(raw),
            )
        }
    }

    @Test
    fun `unknown backend names are reported, not guessed`() {
        val request = BrowserBackendSelector.parseRequest("firefox")
        assertEquals(BrowserBackendRequest.Unknown("firefox"), request)
    }

    @Test
    fun `auto keeps using the embedded pool while it holds a page`() {
        assertEquals(
            BrowserBackendKind.WebView,
            BrowserBackendSelector.select(webViewHasTabs = true, alpineRunning = true),
        )
    }

    @Test
    fun `auto does not strand a running alpine session`() {
        assertEquals(
            BrowserBackendKind.Alpine,
            BrowserBackendSelector.select(webViewHasTabs = false, alpineRunning = true),
        )
    }

    @Test
    fun `auto starts fresh sessions on the embedded pool`() {
        assertEquals(
            BrowserBackendKind.WebView,
            BrowserBackendSelector.select(webViewHasTabs = false, alpineRunning = false),
        )
    }

    @Test
    fun `condition waits belong to the shared action vocabulary`() {
        assertTrue(BrowserActionCatalog.isSupported("wait_for"))
        assertTrue(BrowserActionCatalog.isSupported("wait_for_gone"))
        assertTrue(BrowserActionCatalog.isSupported("screenshot"))
        assertTrue(BrowserActionCatalog.isSupported("list_tabs"))
        assertFalse(BrowserActionCatalog.isSupported("key"))
        assertFalse(BrowserActionCatalog.isSupported(""))
    }
}
