package com.zhousl.aether.data.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiNodeSnapshotTest {
    private fun node(
        index: Int,
        text: String = "",
        desc: String = "",
        viewId: String = "",
        className: String = "android.widget.TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
        bounds: UiNodeBounds = UiNodeBounds(0, 0, 100, 50),
    ) = UiNodeRecord(
        index = index,
        nodeId = "n$index",
        viewId = viewId,
        className = className,
        text = text,
        contentDescription = desc,
        bounds = bounds,
        clickable = clickable,
        editable = editable,
    )

    @Test
    fun `bounds report centre size and emptiness`() {
        val bounds = UiNodeBounds(10, 20, 110, 70)
        assertEquals(60, bounds.centerX)
        assertEquals(45, bounds.centerY)
        assertEquals(100, bounds.width)
        assertEquals(50, bounds.height)
        assertFalse(bounds.isEmpty)
        assertTrue(UiNodeBounds(10, 20, 10, 70).isEmpty)
        assertTrue(UiNodeBounds.Empty.isEmpty)
    }

    @Test
    fun `label prefers text and falls back to the description`() {
        assertEquals("搜索", node(0, text = "搜索").label)
        assertEquals("搜索", node(0, desc = "搜索").label)
    }

    @Test
    fun `empty query matches nothing`() {
        val nodes = listOf(node(0, text = "搜索", clickable = true))
        assertTrue(UiNodeQuery().isEmpty)
        assertEquals(emptyList<UiNodeRecord>(), findUiNodes(nodes, UiNodeQuery()))
        // A filter on its own is a real query ("everything clickable"), not an empty one.
        assertFalse(UiNodeQuery(clickableOnly = true).isEmpty)
        assertEquals(listOf("n0"), findUiNodes(nodes, UiNodeQuery(clickableOnly = true)).map { it.nodeId })
    }

    @Test
    fun `text matches exactly by default and by containment when asked`() {
        val nodes = listOf(node(0, text = "搜索设置"), node(1, text = "搜索"))
        assertEquals(
            listOf("n1"),
            findUiNodes(nodes, UiNodeQuery(text = "搜索")).map { it.nodeId },
        )
        assertEquals(
            listOf("n0", "n1"),
            findUiNodes(nodes, UiNodeQuery(text = "搜索", contains = true)).map { it.nodeId },
        )
    }

    @Test
    fun `content description is searched too`() {
        val nodes = listOf(node(0, desc = "返回"), node(1, text = "返回"))
        assertEquals(
            listOf("n0", "n1"),
            findUiNodes(nodes, UiNodeQuery(text = "返回")).map { it.nodeId },
        )
    }

    @Test
    fun `view id accepts full and shorthand forms`() {
        val nodes = listOf(
            node(0, viewId = "com.android.settings:id/search"),
            node(1, viewId = "com.other:id/search_box"),
        )
        assertEquals(
            listOf("n0"),
            findUiNodes(nodes, UiNodeQuery(viewId = "com.android.settings:id/search")).map { it.nodeId },
        )
        assertEquals(listOf("n0"), findUiNodes(nodes, UiNodeQuery(viewId = "search")).map { it.nodeId })
        assertEquals(1, findUiNodes(nodes, UiNodeQuery(viewId = "search")).count { it.nodeId == "n0" })
    }

    @Test
    fun `class name matches the simple name too`() {
        val nodes = listOf(node(0, className = "android.widget.EditText", editable = true))
        assertEquals(1, findUiNodes(nodes, UiNodeQuery(className = "EditText")).size)
        assertEquals(1, findUiNodes(nodes, UiNodeQuery(className = "android.widget.EditText")).size)
        assertEquals(0, findUiNodes(nodes, UiNodeQuery(className = "Button")).size)
    }

    @Test
    fun `clickable and editable filters narrow the result`() {
        val nodes = listOf(
            node(0, text = "确定", clickable = true),
            node(1, text = "确定", clickable = false),
            node(2, text = "", editable = true, className = "android.widget.EditText"),
        )
        assertEquals(
            listOf("n0"),
            findUiNodes(nodes, UiNodeQuery(text = "确定", clickableOnly = true)).map { it.nodeId },
        )
        assertEquals(
            listOf("n2"),
            findUiNodes(nodes, UiNodeQuery(editableOnly = true)).map { it.nodeId },
        )
    }

    @Test
    fun `all candidates are returned so the caller can choose`() {
        val nodes = listOf(node(0, text = "删除", clickable = true), node(1, text = "删除", clickable = true))
        assertEquals(2, findUiNodes(nodes, UiNodeQuery(text = "删除")).size)
    }

    @Test
    fun `truncation is reported instead of hidden`() {
        val nodes = (0 until 250).map { node(it) }
        val page = truncateUiNodes(nodes, limit = 200)
        assertTrue(page.truncated)
        assertEquals(200, page.nodes.size)
        assertEquals(250, page.totalBeforeTruncation)

        val whole = truncateUiNodes(nodes.take(10), limit = 200)
        assertFalse(whole.truncated)
        assertEquals(10, whole.nodes.size)
    }

    @Test
    fun `a node handle expires after the ttl`() {
        assertFalse(
            nodeReferenceIsStale(
                registeredAtMillis = 1_000,
                nowMillis = 1_000 + UiNodeIdTtlMillis,
                registeredPackage = "com.android.settings",
                currentPackage = "com.android.settings",
                registeredWindowId = 3,
                currentWindowId = 3,
            ),
        )
        assertTrue(
            nodeReferenceIsStale(
                registeredAtMillis = 1_000,
                nowMillis = 1_001 + UiNodeIdTtlMillis,
                registeredPackage = "com.android.settings",
                currentPackage = "com.android.settings",
                registeredWindowId = 3,
                currentWindowId = 3,
            ),
        )
    }

    @Test
    fun `a node handle is stale once the screen moved on`() {
        assertTrue(
            nodeReferenceIsStale(
                registeredAtMillis = 1_000,
                nowMillis = 2_000,
                registeredPackage = "com.android.settings",
                currentPackage = "com.android.chrome",
                registeredWindowId = 3,
                currentWindowId = 3,
            ),
        )
        assertTrue(
            nodeReferenceIsStale(
                registeredAtMillis = 1_000,
                nowMillis = 2_000,
                registeredPackage = "com.android.settings",
                currentPackage = "com.android.settings",
                registeredWindowId = 3,
                currentWindowId = 7,
            ),
        )
        // Unknown window ids must not be treated as a change.
        assertFalse(
            nodeReferenceIsStale(
                registeredAtMillis = 1_000,
                nowMillis = 2_000,
                registeredPackage = null,
                currentPackage = null,
                registeredWindowId = 0,
                currentWindowId = 0,
            ),
        )
    }
}
