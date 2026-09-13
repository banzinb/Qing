package com.zhousl.aether.data.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The `aether_ui_manage` action handler: reads the screen the user is looking at and acts on it.
 *
 * Two rules shape every branch here:
 *
 *  1. **Report what actually happened.** A tap that was never accepted, a text field that did not
 *     change, a wait that timed out — all of them come back as `ok=false` with a code, never as a
 *     success. The reference implementation this was written against returned success for a tap it
 *     never verified; that is exactly the behaviour being avoided.
 *  2. **Only the foreground screen.** Everything below works off `rootInActiveWindow`. Qing's Agent
 *     Mode renders into a virtual display, and reading the user's screen while Shizuku taps a
 *     different one would be worse than doing nothing.
 *
 * Portions of the screen-reading approach are adapted from OpenMinis (GPLv3); see NOTICE.
 */
class QingUiToolHandler(private val context: Context) {

    companion object {
        private const val MaxDepth = 25
        private const val DefaultWaitMillis = 10_000L
        private const val MaxWaitMillis = 60_000L
        private const val PollMillis = 200L
        private const val LongPressMillis = 700L
        private const val TapMillis = 60L
    }

    /** Readable snapshot of what the service can see right now. */
    suspend fun status(): JSONObject {
        val state = AccessibilityStatus.state(context)
        val service = QingAccessibilityService.getInstance()
        val payload = JSONObject()
            .put("ok", true)
            .put("state", state.name.lowercase(Locale.US))
            .put("state_detail", AccessibilityStatus.explain(state))
        service?.let { live ->
            payload.put("foreground_package", live.foregroundPackage().orEmpty())
            payload.put("active_window_id", live.activeWindowId())
        }
        return payload
    }

    suspend fun execute(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return failure("invalid_arguments", "Arguments were not valid JSON.").toString()
        val action = arguments.optString("action").trim().lowercase(Locale.US)
        val payload = try {
            when (action) {
                "status" -> status()
                "read", "dump" -> read(arguments)
                "find" -> find(arguments)
                "tap", "click" -> tap(arguments)
                "set_text", "type", "input_text" -> setText(arguments)
                "scroll" -> scroll(arguments)
                "wait_for", "wait" -> waitFor(arguments)
                "back" -> performGlobal(AccessibilityService.GLOBAL_ACTION_BACK, "back")
                "home" -> performGlobal(AccessibilityService.GLOBAL_ACTION_HOME, "home")
                "recents" -> performGlobal(AccessibilityService.GLOBAL_ACTION_RECENTS, "recents")
                else -> failure("unsupported_action", "Unsupported screen action '$action'.")
            }
        } catch (error: Throwable) {
            failure(
                "action_failed",
                "${error::class.java.simpleName}: ${error.message ?: "the screen action failed"}",
            )
        }
        return payload.toString()
    }

    private fun unavailable(): JSONObject {
        val state = AccessibilityStatus.state(context)
        return failure("service_not_ready", AccessibilityStatus.explain(state)).apply {
            put("state", state.name.lowercase(Locale.US))
        }
    }

    private fun requireService(): QingAccessibilityService? =
        if (QingAccessibilityService.isRunning()) QingAccessibilityService.getInstance() else null

    private fun failure(code: String, message: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("errmsg", message)

    // ---------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------

    private suspend fun read(arguments: JSONObject): JSONObject = withContext(Dispatchers.Default) {
        val service = requireService() ?: return@withContext unavailable()
        val limit = arguments.optInt("limit", DefaultUiNodeLimit)
        val clickableOnly = arguments.optBoolean("clickable_only", false)
        val collected = collectNodes(service, clickableOnly = clickableOnly)
        val page = truncateUiNodes(collected, limit)
        JSONObject()
            .put("ok", true)
            .put("foreground_package", service.foregroundPackage().orEmpty())
            .put("node_count", page.nodes.size)
            .put("total_before_truncation", page.totalBeforeTruncation)
            .put("truncated", page.truncated)
            .put("nodes", nodesToJson(page.nodes))
            .put(
                "stdout",
                if (page.truncated) {
                    "Read ${page.nodes.size} of ${page.totalBeforeTruncation} nodes; the list was cut at " +
                        "the limit, so narrow the query with clickable_only or act on what you need."
                } else {
                    "Read ${page.nodes.size} nodes."
                },
            )
    }

    private suspend fun find(arguments: JSONObject): JSONObject = withContext(Dispatchers.Default) {
        val service = requireService() ?: return@withContext unavailable()
        val query = queryFrom(arguments)
        if (query.isEmpty) {
            return@withContext failure(
                "empty_query",
                "Provide text, view_id or class_name (clickable_only / editable_only also count as a query).",
            )
        }
        val matches = findUiNodes(collectNodes(service, clickableOnly = false), query)
        val page = truncateUiNodes(matches, arguments.optInt("limit", DefaultUiNodeLimit))
        JSONObject()
            .put("ok", true)
            .put("match_count", matches.size)
            .put("truncated", page.truncated)
            .put("candidates", nodesToJson(page.nodes))
            .put("stdout", "Found ${matches.size} matching node(s).")
    }

    // ---------------------------------------------------------------------------------------
    // Acting
    // ---------------------------------------------------------------------------------------

    private suspend fun tap(arguments: JSONObject): JSONObject = withContext(Dispatchers.Default) {
        val service = requireService() ?: return@withContext unavailable()
        val longPress = arguments.optBoolean("long_press", false)
        val explicitNode = arguments.optString("node_id").trim()
        if (explicitNode.isNotBlank()) {
            val resolved = resolveNodeId(service, explicitNode) ?: return@withContext nodeIdFailure(service, explicitNode)
            return@withContext tapNode(service, resolved, longPress)
        }
        val x = arguments.optInt("x", Int.MIN_VALUE)
        val y = arguments.optInt("y", Int.MIN_VALUE)
        if (x != Int.MIN_VALUE && y != Int.MIN_VALUE) {
            val ok = service.tapPoint(x, y, if (longPress) LongPressMillis else TapMillis)
            return@withContext if (ok) {
                JSONObject()
                    .put("ok", true)
                    .put("performed_via", "gesture")
                    .put("verified", false)
                    .put("stdout", "Tapped ($x, $y). Read the screen again to confirm what happened.")
            } else {
                failure("gesture_rejected", "The system rejected the tap at ($x, $y) or it timed out.")
            }
        }
        val query = queryFrom(arguments)
        if (query.isEmpty) {
            return@withContext failure(
                "empty_query",
                "Give node_id, or x and y, or a query such as text / view_id / class_name.",
            )
        }
        val candidates = findUiNodes(collectNodes(service, clickableOnly = false), query)
        when {
            candidates.isEmpty() -> failure("node_not_found", describeMissing(query))
            candidates.size > 1 -> failure(
                "ambiguous_target",
                "Several nodes match; pick one by node_id instead of letting Qing guess.",
            ).put("candidates", nodesToJson(candidates))
            else -> {
                val record = candidates.first()
                val node = resolveNodeId(service, record.nodeId)
                    ?: return@withContext failure(
                        "stale_node",
                        "The matching node changed before it could be used. Read the screen again.",
                    )
                tapNode(service, node, longPress)
            }
        }
    }

    private suspend fun tapNode(
        service: QingAccessibilityService,
        node: AccessibilityNodeInfo,
        longPress: Boolean,
    ): JSONObject {
        val action = if (longPress) {
            AccessibilityNodeInfo.ACTION_LONG_CLICK
        } else {
            AccessibilityNodeInfo.ACTION_CLICK
        }
        val performed = runCatching { node.performAction(action) }.getOrDefault(false)
        if (performed) {
            return JSONObject()
                .put("ok", true)
                .put("performed_via", "node_action")
                .put("verified", false)
                .put("stdout", "The node accepted the ${if (longPress) "long press" else "click"}. " +
                    "Read the screen again to confirm the result; this only means the action was delivered.")
        }
        // The node refused the action: fall back to a coordinate tap, and say so.
        val bounds = boundsOf(node)
        if (bounds.isEmpty) {
            return failure(
                "action_failed",
                "The node rejected the ${if (longPress) "long press" else "click"} and has no tappable area " +
                    "(bounds ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}).",
            )
        }
        val ok = service.tapPoint(
            bounds.centerX,
            bounds.centerY,
            if (longPress) LongPressMillis else TapMillis,
        )
        return if (ok) {
            JSONObject()
                .put("ok", true)
                .put("performed_via", "gesture_fallback")
                .put("verified", false)
                .put("stdout", "The node rejected the action, so Qing tapped its centre " +
                    "(${bounds.centerX}, ${bounds.centerY}) instead. Read the screen again to confirm.")
        } else {
            failure(
                "action_failed",
                "Neither the node action nor a tap at (${bounds.centerX}, ${bounds.centerY}) was accepted.",
            )
        }
    }

    private suspend fun setText(arguments: JSONObject): JSONObject = withContext(Dispatchers.Default) {
        val service = requireService() ?: return@withContext unavailable()
        if (!arguments.has("text")) {
            return@withContext failure("missing_text", "'text' is required; pass an empty string to clear a field.")
        }
        val text = arguments.optString("text")
        val explicitNode = arguments.optString("node_id").trim()
        val target: AccessibilityNodeInfo? = if (explicitNode.isNotBlank()) {
            resolveNodeId(service, explicitNode)
                ?: return@withContext nodeIdFailure(service, explicitNode)
        } else {
            val query = queryFrom(arguments)
            if (query.isEmpty) {
                return@withContext failure(
                    "empty_query",
                    "Give node_id, or a query such as text / view_id / class_name, to choose the field.",
                )
            }
            val candidates = findUiNodes(collectNodes(service, clickableOnly = false), query)
            when {
                candidates.isEmpty() -> return@withContext failure("node_not_found", describeMissing(query))
                candidates.size > 1 -> return@withContext failure(
                    "ambiguous_target",
                    "Several nodes match; pick the field by node_id.",
                ).put("candidates", nodesToJson(candidates))
                else -> resolveNodeId(service, candidates.first().nodeId)
            }
        }
        if (target == null) {
            return@withContext failure(
                "stale_node",
                "The field changed before it could be used; read the screen again.",
            )
        }
        val editable = editableSelfOrAncestor(target)
            ?: return@withContext failure(
                "not_editable",
                "That node cannot take text (class ${target.className ?: "unknown"}). " +
                    "Read the screen and target an editable field.",
            )
        val ok = runCatching { service.setNodeText(editable, text) }.getOrDefault(false)
        if (!ok) {
            return@withContext failure(
                "action_failed",
                "The field rejected the text. It may be read-only, or the screen may have changed.",
            )
        }
        val observed = runCatching { editable.text?.toString().orEmpty() }.getOrDefault("")
        JSONObject()
            .put("ok", true)
            .put("performed_via", "node_action")
            .put("requested_text", text)
            .put("observed_text", observed)
            .put("verified", observed == text)
            .put(
                "stdout",
                if (observed == text) {
                    "The field now reads \"$observed\"."
                } else {
                    "The field accepted the text but reports \"$observed\"; read the screen again if this looks wrong."
                },
            )
    }

    private suspend fun scroll(arguments: JSONObject): JSONObject = withContext(Dispatchers.Default) {
        val service = requireService() ?: return@withContext unavailable()
        val direction = arguments.optString("direction", "forward").trim().lowercase(Locale.US)
        val action = when (direction) {
            "forward", "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "backward", "back", "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            // Horizontal scrolling only exists as an AccessibilityAction, not as a plain int.
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            else -> return@withContext failure(
                "unsupported_direction",
                "direction must be forward, backward, left or right (got '$direction').",
            )
        }
        val explicitNode = arguments.optString("node_id").trim()
        val target: AccessibilityNodeInfo? = if (explicitNode.isNotBlank()) {
            val node = resolveNodeId(service, explicitNode)
                ?: return@withContext nodeIdFailure(service, explicitNode)
            scrollableSelfOrAncestor(node)
        } else {
            firstScrollable(service.activeRoot())
        }
        if (target == null) {
            return@withContext failure(
                "no_scrollable",
                "No scrollable list was found on this screen. Give node_id if you know which one to scroll.",
            )
        }
        val performed = runCatching { target.performAction(action) }.getOrDefault(false)
        if (!performed) {
            return@withContext failure(
                "action_failed",
                "The list rejected a '$direction' scroll; it may already be at the end.",
            )
        }
        JSONObject()
            .put("ok", true)
            .put("performed_via", "node_action")
            .put("direction", direction)
            .put("verified", false)
            .put("stdout", "Scrolled $direction. Read the screen again to see what came into view.")
    }

    private suspend fun performGlobal(action: Int, name: String): JSONObject = withContext(Dispatchers.Default) {
        val service = requireService() ?: return@withContext unavailable()
        val ok = runCatching { service.performGlobalAction(action) }.getOrDefault(false)
        if (!ok) {
            return@withContext failure("action_failed", "The system rejected the '$name' navigation action.")
        }
        JSONObject()
            .put("ok", true)
            .put("performed_via", "global_action")
            .put("verified", false)
            .put("stdout", "Sent '$name'. Read the screen again to see where it went.")
    }

    private suspend fun waitFor(arguments: JSONObject): JSONObject = withContext(Dispatchers.Default) {
        val service = requireService() ?: return@withContext unavailable()
        val query = queryFrom(arguments)
        if (query.isEmpty) {
            return@withContext failure("empty_query", "wait_for needs text, view_id or class_name.")
        }
        val expectingPresent = arguments.optBoolean("present", true)
        val timeout = arguments.optLong("timeout", DefaultWaitMillis).coerceIn(PollMillis, MaxWaitMillis)
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + timeout
        var lastSeen = 0
        while (true) {
            val matches = findUiNodes(collectNodes(service, clickableOnly = false), query)
            lastSeen = matches.size
            val satisfied = if (expectingPresent) matches.isNotEmpty() else matches.isEmpty()
            if (satisfied) {
                val payload = JSONObject()
                    .put("ok", true)
                    .put("present", expectingPresent)
                    .put("elapsed_ms", System.currentTimeMillis() - startedAt)
                if (expectingPresent) payload.put("node", nodeToJson(matches.first()))
                return@withContext payload.put(
                    "stdout",
                    if (expectingPresent) {
                        "The target appeared after ${System.currentTimeMillis() - startedAt}ms."
                    } else {
                        "The target is gone (waited ${System.currentTimeMillis() - startedAt}ms)."
                    },
                )
            }
            if (System.currentTimeMillis() >= deadline) break
            delay(PollMillis)
        }
        failure(
            "timed_out",
            if (expectingPresent) {
                "The target did not appear within ${timeout}ms (${describeMissing(query)})."
            } else {
                "The target was still on screen after ${timeout}ms ($lastSeen node(s) still match)."
            },
        ).put("timed_out", true).put("elapsed_ms", System.currentTimeMillis() - startedAt)
    }

    // ---------------------------------------------------------------------------------------
    // Node helpers
    // ---------------------------------------------------------------------------------------

    private fun queryFrom(arguments: JSONObject): UiNodeQuery {
        val text = arguments.optString("text").takeIf { it.isNotBlank() }
            ?: arguments.optString("text_contains").takeIf { it.isNotBlank() }.orEmpty()
        val contains = arguments.optBoolean("contains", false) ||
            arguments.optString("text_contains").isNotBlank()
        return UiNodeQuery(
            text = text,
            contains = contains,
            viewId = arguments.optString("view_id").trim(),
            className = arguments.optString("class_name").trim(),
            clickableOnly = arguments.optBoolean("clickable_only", false),
            editableOnly = arguments.optBoolean("editable_only", false),
        )
    }

    private fun describeMissing(query: UiNodeQuery): String = buildString {
        append("No node matched ")
        val parts = buildList {
            if (query.text.isNotBlank()) {
                add(if (query.contains) "text containing \"${query.text}\"" else "text \"${query.text}\"")
            }
            if (query.viewId.isNotBlank()) add("view_id ${query.viewId}")
            if (query.className.isNotBlank()) add("class ${query.className}")
            if (query.clickableOnly) add("clickable=true")
            if (query.editableOnly) add("editable=true")
        }
        append(parts.joinToString(", "))
        append(". Read the screen first to see what is actually there.")
    }

    private fun resolveNodeId(
        service: QingAccessibilityService,
        nodeId: String,
    ): AccessibilityNodeInfo? {
        val lookup = service.nodeRegistry.lookup(
            id = nodeId,
            currentPackage = service.foregroundPackage(),
            currentWindowId = service.activeWindowId(),
        )
        return lookup.node
    }

    private fun nodeIdFailure(service: QingAccessibilityService, nodeId: String): JSONObject {
        val lookup = service.nodeRegistry.lookup(
            id = nodeId,
            currentPackage = service.foregroundPackage(),
            currentWindowId = service.activeWindowId(),
        )
        return when (lookup.reason) {
            "expired" -> failure(
                "expired_node",
                "Node $nodeId is older than a minute and no longer usable. Read the screen again.",
            )
            "screen_changed" -> failure(
                "stale_node",
                "Node $nodeId came from a different screen; the foreground window changed. Read the screen again.",
            )
            else -> failure(
                "node_not_found",
                "No node with id '$nodeId' is known. Ids come from read / find and do not survive a re-read.",
            )
        }
    }

    /**
     * Walks the active window and flattens it into records.
     *
     * Nodes that cannot be seen, are empty containers, or are password fields are skipped: the
     * first two are noise, and a password field must never reach the model.
     */
    private fun collectNodes(
        service: QingAccessibilityService,
        clickableOnly: Boolean,
    ): List<UiNodeRecord> {
        val root = service.activeRoot() ?: return emptyList()
        val out = ArrayList<UiNodeRecord>()
        val registry = service.nodeRegistry
        walk(root, 0, registry, clickableOnly, out)
        return out
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        depth: Int,
        registry: NodeRegistry,
        clickableOnly: Boolean,
        out: MutableList<UiNodeRecord>,
    ) {
        if (node == null || depth > MaxDepth) return
        val record = recordOf(node, depth, registry)
        if (record != null && (!clickableOnly || record.clickable)) out.add(record)
        for (index in 0 until runCatching { node.childCount }.getOrDefault(0)) {
            walk(runCatching { node.getChild(index) }.getOrNull(), depth + 1, registry, clickableOnly, out)
        }
    }

    private fun recordOf(
        node: AccessibilityNodeInfo,
        depth: Int,
        registry: NodeRegistry,
    ): UiNodeRecord? {
        val visible = runCatching { node.isVisibleToUser }.getOrDefault(false)
        if (!visible) return null
        if (runCatching { node.isPassword }.getOrDefault(false)) return null
        val text = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
        val description = runCatching { node.contentDescription?.toString().orEmpty() }.getOrDefault("")
        val clickable = runCatching { node.isClickable }.getOrDefault(false)
        val longClickable = runCatching { node.isLongClickable }.getOrDefault(false)
        val scrollable = runCatching { node.isScrollable }.getOrDefault(false)
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        // Skip pure layout containers: no label, nothing to do with them.
        if (text.isBlank() && description.isBlank() && !clickable && !longClickable &&
            !scrollable && !editable
        ) {
            return null
        }
        return UiNodeRecord(
            index = 0,
            nodeId = registry.put(node),
            packageName = runCatching { node.packageName?.toString().orEmpty() }.getOrDefault(""),
            viewId = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault(""),
            className = runCatching { node.className?.toString().orEmpty() }.getOrDefault(""),
            text = text,
            contentDescription = description,
            bounds = boundsOf(node),
            clickable = clickable,
            longClickable = longClickable,
            scrollable = scrollable,
            editable = editable,
            enabled = runCatching { node.isEnabled }.getOrDefault(true),
            checkable = runCatching { node.isCheckable }.getOrDefault(false),
            checked = runCatching { node.isChecked }.getOrDefault(false),
            focused = runCatching { node.isFocused }.getOrDefault(false),
            depth = depth,
        )
    }

    private fun nodesToJson(nodes: List<UiNodeRecord>): JSONArray {
        val array = JSONArray()
        nodes.forEachIndexed { index, record -> array.put(nodeToJson(record, index)) }
        return array
    }

    private fun nodeToJson(record: UiNodeRecord, index: Int = 0): JSONObject {
        val json = JSONObject()
            .put("index", index)
            .put("node_id", record.nodeId)
            .put("bounds", JSONArray(listOf(record.bounds.left, record.bounds.top, record.bounds.right, record.bounds.bottom)))
            .put("center", JSONArray(listOf(record.bounds.centerX, record.bounds.centerY)))
        if (record.text.isNotBlank()) json.put("text", record.text)
        if (record.contentDescription.isNotBlank()) json.put("desc", record.contentDescription)
        if (record.simpleClassName.isNotBlank()) json.put("class", record.simpleClassName)
        if (record.viewId.isNotBlank()) json.put("view_id", record.viewId)
        if (record.clickable) json.put("clickable", true)
        if (record.longClickable) json.put("long_clickable", true)
        if (record.scrollable) json.put("scrollable", true)
        if (record.editable) json.put("editable", true)
        if (record.checked) json.put("checked", true)
        if (record.focused) json.put("focused", true)
        if (!record.enabled) json.put("enabled", false)
        return json
    }

    private fun boundsOf(node: AccessibilityNodeInfo): UiNodeBounds {
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        return UiNodeBounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun editableSelfOrAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        repeat(4) {
            val candidate = current ?: return null
            if (runCatching { candidate.isEditable }.getOrDefault(false)) return candidate
            current = runCatching { candidate.parent }.getOrNull()
        }
        return null
    }

    private fun scrollableSelfOrAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        repeat(6) {
            val candidate = current ?: return null
            if (runCatching { candidate.isScrollable }.getOrDefault(false)) return candidate
            current = runCatching { candidate.parent }.getOrNull()
        }
        return null
    }

    private fun firstScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (runCatching { root.isScrollable }.getOrDefault(false)) return root
        for (index in 0 until runCatching { root.childCount }.getOrDefault(0)) {
            val found = firstScrollable(runCatching { root.getChild(index) }.getOrNull())
            if (found != null) return found
        }
        return null
    }
}
