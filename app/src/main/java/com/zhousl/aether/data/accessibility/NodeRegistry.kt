package com.zhousl.aether.data.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Short-lived map from opaque ids to live `AccessibilityNodeInfo` handles.
 *
 * `AccessibilityNodeInfo` has no durable identity across calls — every re-read of the tree hands
 * out fresh objects — but the model refers to a node seconds later ("tap node 3"). Alongside the
 * handle we remember which package and window it came from, so a handle surviving a screen change
 * is reported as stale instead of being clicked on the wrong screen.
 *
 * Adapted from OpenMinis `accessibility/NodeRegistry.kt` (GPLv3) — see NOTICE. Qing adds the
 * package/window check and reports *why* a handle was rejected.
 */
class NodeRegistry {
    private class Entry(
        val node: AccessibilityNodeInfo,
        val createdAt: Long,
        val packageName: String?,
        val windowId: Int,
    )

    /** Outcome of resolving an id. [reason] is null when the handle is usable. */
    data class Lookup(
        val node: AccessibilityNodeInfo?,
        val reason: String?,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val sequence = AtomicLong(0)

    fun put(node: AccessibilityNodeInfo): String {
        evictExpired()
        val id = nextId()
        entries[id] = Entry(
            node = node,
            createdAt = System.currentTimeMillis(),
            packageName = runCatching { node.packageName?.toString() }.getOrNull(),
            windowId = runCatching { node.windowId }.getOrDefault(0),
        )
        return id
    }

    /**
     * @param currentPackage foreground package right now
     * @param currentWindowId active window right now (0 when unknown)
     */
    fun lookup(
        id: String,
        currentPackage: String?,
        currentWindowId: Int,
    ): Lookup {
        val entry = entries[id] ?: return Lookup(node = null, reason = null)
        val now = System.currentTimeMillis()
        if (now - entry.createdAt > UiNodeIdTtlMillis) {
            entries.remove(id)
            return Lookup(node = null, reason = "expired")
        }
        val stale = nodeReferenceIsStale(
            registeredAtMillis = entry.createdAt,
            nowMillis = now,
            registeredPackage = entry.packageName,
            currentPackage = currentPackage,
            registeredWindowId = entry.windowId,
            currentWindowId = currentWindowId,
        )
        if (stale) {
            return Lookup(node = null, reason = "screen_changed")
        }
        return Lookup(node = entry.node, reason = null)
    }

    /** Drops every handle: called when the tree is re-read or the service goes away. */
    fun clear() {
        entries.clear()
    }

    private fun evictExpired() {
        val now = System.currentTimeMillis()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.createdAt > UiNodeIdTtlMillis) iterator.remove()
        }
    }

    private fun nextId(): String =
        java.lang.Long.toString(sequence.incrementAndGet() and 0xFFFFFL, 36).padStart(4, '0')
}
