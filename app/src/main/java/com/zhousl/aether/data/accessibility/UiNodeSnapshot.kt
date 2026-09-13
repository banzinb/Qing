package com.zhousl.aether.data.accessibility

/**
 * Screen-reading model for Qing's accessibility bridge.
 *
 * Everything in this file is deliberately free of Android types: the service walks the real
 * `AccessibilityNodeInfo` tree and produces [UiNodeRecord]s, and all of the decision making
 * (matching, truncation, staleness) happens here so it can be unit tested.
 *
 * Portions of the screen-reading approach are adapted from OpenMinis (GPLv3); see NOTICE.
 */

/** Screen-space bounds of one node. */
data class UiNodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    /** A node with no area cannot be tapped; treat it as unusable rather than guessing. */
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    companion object {
        val Empty = UiNodeBounds(0, 0, 0, 0)
    }
}

/** One flattened node from the active window. */
data class UiNodeRecord(
    val index: Int,
    val nodeId: String = "",
    val packageName: String = "",
    val viewId: String = "",
    val className: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val bounds: UiNodeBounds = UiNodeBounds.Empty,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean = true,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val depth: Int = 0,
) {
    /** What a human (or the model) would call this node. */
    val label: String get() = text.ifBlank { contentDescription }

    /** Short class name, e.g. `EditText` for `android.widget.EditText`. */
    val simpleClassName: String get() = className.substringAfterLast('.')
}

/** What the caller is looking for. An empty query matches nothing, never everything. */
data class UiNodeQuery(
    val text: String = "",
    val contains: Boolean = false,
    val viewId: String = "",
    val className: String = "",
    val clickableOnly: Boolean = false,
    val editableOnly: Boolean = false,
) {
    val isEmpty: Boolean
        get() = text.isBlank() && viewId.isBlank() && className.isBlank() &&
            !clickableOnly && !editableOnly
}

/** Nodes plus whether the listing was cut short, so callers can say so instead of pretending. */
data class UiNodePage(
    val nodes: List<UiNodeRecord>,
    val truncated: Boolean,
    val totalBeforeTruncation: Int,
)

/** Default cap on how many nodes one read returns. */
const val DefaultUiNodeLimit: Int = 200

/** How long a node id stays usable, in milliseconds. */
const val UiNodeIdTtlMillis: Long = 60_000L

internal fun UiNodeRecord.matchesQuery(query: UiNodeQuery): Boolean {
    if (query.isEmpty) return false
    if (query.clickableOnly && !clickable) return false
    if (query.editableOnly && !editable) return false
    if (query.text.isNotBlank()) {
        val wanted = query.text
        val hit = if (query.contains) {
            text.contains(wanted) || contentDescription.contains(wanted)
        } else {
            text == wanted || contentDescription == wanted
        }
        if (!hit) return false
    }
    if (query.viewId.isNotBlank()) {
        // Accept both the full "package:id/name" form and the bare "name".
        val wanted = query.viewId
        val hit = viewId == wanted ||
            (wanted.contains('/').not() && viewId.substringAfterLast('/') == wanted) ||
            viewId.endsWith(":$wanted")
        if (!hit) return false
    }
    if (query.className.isNotBlank()) {
        val wanted = query.className
        val hit = className == wanted || simpleClassName == wanted
        if (!hit) return false
    }
    return true
}

/**
 * Every node matching [query], in tree order. Returns all candidates on purpose: the caller
 * decides which one to act on instead of silently taking the first.
 */
internal fun findUiNodes(nodes: List<UiNodeRecord>, query: UiNodeQuery): List<UiNodeRecord> =
    if (query.isEmpty) emptyList() else nodes.filter { it.matchesQuery(query) }

internal fun truncateUiNodes(nodes: List<UiNodeRecord>, limit: Int = DefaultUiNodeLimit): UiNodePage {
    val effective = limit.coerceAtLeast(1)
    if (nodes.size <= effective) {
        return UiNodePage(nodes = nodes, truncated = false, totalBeforeTruncation = nodes.size)
    }
    return UiNodePage(
        nodes = nodes.take(effective),
        truncated = true,
        totalBeforeTruncation = nodes.size,
    )
}

/**
 * Whether a node id handed out earlier can still be trusted.
 *
 * `AccessibilityNodeInfo` has no durable identity, and the window it came from can be gone by the
 * time the model acts on it. A different foreground package or window means the id refers to a
 * screen the user is no longer looking at.
 */
internal fun nodeReferenceIsStale(
    registeredAtMillis: Long,
    nowMillis: Long,
    registeredPackage: String?,
    currentPackage: String?,
    registeredWindowId: Int,
    currentWindowId: Int,
    ttlMillis: Long = UiNodeIdTtlMillis,
): Boolean {
    if (nowMillis - registeredAtMillis > ttlMillis) return true
    if (!registeredPackage.isNullOrBlank() && !currentPackage.isNullOrBlank() &&
        registeredPackage != currentPackage
    ) {
        return true
    }
    if (registeredWindowId != 0 && currentWindowId != 0 && registeredWindowId != currentWindowId) {
        return true
    }
    return false
}
