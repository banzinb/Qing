package com.zhousl.aether.data.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserViewerLabelsTest {
    @Test
    fun `host is extracted without scheme or path`() {
        assertEquals("example.com", browserTabHost("https://example.com/a/b?c=1#d"))
        assertEquals("example.com", browserTabHost("http://example.com"))
        assertEquals("news.ycombinator.com", browserTabHost("https://news.ycombinator.com/"))
    }

    @Test
    fun `leading www is dropped`() {
        assertEquals("example.com", browserTabHost("https://www.example.com/x"))
    }

    @Test
    fun `blank and hostless urls have no host`() {
        assertEquals("", browserTabHost(""))
        assertEquals("", browserTabHost("   "))
        assertEquals("", browserTabHost("about:blank"))
        assertEquals("", browserTabHost("data:text/plain,hello"))
    }

    @Test
    fun `bare host without a scheme still works`() {
        assertEquals("example.com", browserTabHost("example.com/path"))
    }

    @Test
    fun `label prefers the title and falls back to the host`() {
        assertEquals(
            "Example Domain",
            browserTabLabel(BrowserViewerTab(1, "https://example.com", "Example Domain")),
        )
        assertEquals(
            "example.com",
            browserTabLabel(BrowserViewerTab(1, "https://example.com", "")),
        )
        // A title equal to the URL is not an improvement over the host.
        assertEquals(
            "example.com",
            browserTabLabel(BrowserViewerTab(1, "https://example.com", "https://example.com")),
        )
    }

    @Test
    fun `long labels are shortened with an ellipsis`() {
        val label = browserTabLabel(
            BrowserViewerTab(1, "https://example.com", "A very long page title that will not fit"),
            maxLength = 12,
        )
        assertEquals("A very long…", label)
        assertEquals(12, label.length)
    }

    @Test
    fun `blank tab still produces something to show`() {
        assertEquals("about:blank", browserTabLabel(BrowserViewerTab(1, "about:blank", "")))
    }
}
