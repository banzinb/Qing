package com.zhousl.aether.data.pi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * How long a tool call waits for the user before it gives up.
 *
 * The kernel blocks the call while this is pending, so an unanswered prompt
 * must expire on its own. A timeout is reported to the model as a refusal, not
 * as a silent retry.
 */
private const val ApprovalTimeoutMillis = 120_000L

/**
 * A tool call waiting for the user's yes/no.
 *
 * The kernel sends facts only, because the wording shown in the dialog comes
 * from the app's string resources and has to be localizable.
 */
data class ToolApprovalRequest(
    val id: String,
    val toolName: String,
    val subjectKind: String,
    val scopeKey: String,
    val preview: String,
    val argumentsJson: String,
) {
    companion object {
        fun fromPayload(payload: JSONObject): ToolApprovalRequest? {
            val id = payload.optString("approval_id").trim()
            if (id.isBlank()) return null
            return ToolApprovalRequest(
                id = id,
                toolName = payload.optString("name").trim(),
                subjectKind = payload.optString("kind").trim(),
                scopeKey = payload.optString("scope_key").trim(),
                preview = payload.optString("preview"),
                argumentsJson = payload.optString("arguments_json"),
            )
        }
    }
}

enum class ToolApprovalDecision(val storageValue: String) {
    Approved("approved"),
    ApprovedForSession("approved_for_session"),
    Denied("denied"),
    TimedOut("timed_out"),
}

/**
 * Bridge between the Pi kernel's approval requests and the conversation UI.
 *
 * Only one tool call can be waiting on the user at a time, so the pending
 * request is app-global rather than per session. The waiting coroutine lives in
 * [PiAgentRunner]; if that coroutine is cancelled (a new turn, an abort, the
 * app going away) the pending slot is cleared by the `finally` in [await], so
 * the dialog never outlives the turn that asked for it.
 */
internal object ToolApprovalGate {
    private val _pending = MutableStateFlow<ToolApprovalRequest?>(null)
    val pending: StateFlow<ToolApprovalRequest?> = _pending.asStateFlow()

    private var waiting: CompletableDeferred<ToolApprovalDecision>? = null

    suspend fun await(
        request: ToolApprovalRequest,
        timeoutMillis: Long = ApprovalTimeoutMillis,
    ): ToolApprovalDecision {
        val deferred = CompletableDeferred<ToolApprovalDecision>()
        waiting = deferred
        _pending.value = request
        return try {
            withTimeoutOrNull(timeoutMillis) { deferred.await() }
                ?: ToolApprovalDecision.TimedOut
        } finally {
            waiting = null
            _pending.value = null
        }
    }

    fun submit(requestId: String, decision: ToolApprovalDecision): Boolean {
        if (_pending.value?.id != requestId) return false
        val deferred = waiting ?: return false
        return deferred.complete(decision)
    }
}
