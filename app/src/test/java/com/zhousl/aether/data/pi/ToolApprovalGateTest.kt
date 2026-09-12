package com.zhousl.aether.data.pi

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalGateTest {

    private fun request(id: String = "approval-test-1") = ToolApprovalRequest(
        id = id,
        toolName = "bash",
        subjectKind = "runtime",
        scopeKey = "runtime:bash",
        preview = "rm -rf /tmp/x",
        argumentsJson = "",
    )

    @Test
    fun exposesThePendingRequestAndReturnsTheUsersAnswer() = runBlocking {
        val answer = async { ToolApprovalGate.await(request(), timeoutMillis = 5_000) }
        awaitPending("approval-test-1")

        assertEquals(request(), ToolApprovalGate.pending.value)
        assertTrue(ToolApprovalGate.submit("approval-test-1", ToolApprovalDecision.Approved))

        assertEquals(ToolApprovalDecision.Approved, answer.await())
        assertNull(ToolApprovalGate.pending.value)
    }

    @Test
    fun aStaleAnswerCannotDismissANewerPrompt() = runBlocking {
        val answer = async { ToolApprovalGate.await(request(), timeoutMillis = 5_000) }
        awaitPending("approval-test-1")

        assertFalse(ToolApprovalGate.submit("approval-from-an-older-turn", ToolApprovalDecision.Approved))
        assertEquals(request(), ToolApprovalGate.pending.value)

        assertTrue(ToolApprovalGate.submit("approval-test-1", ToolApprovalDecision.Denied))
        assertEquals(ToolApprovalDecision.Denied, answer.await())
    }

    @Test
    fun anUnansweredPromptExpiresInsteadOfParkingTheToolCallForever() = runBlocking {
        val decision = ToolApprovalGate.await(request(), timeoutMillis = 50)

        assertEquals(ToolApprovalDecision.TimedOut, decision)
        assertNull(ToolApprovalGate.pending.value)
        assertFalse(ToolApprovalGate.submit("approval-test-1", ToolApprovalDecision.Approved))
    }

    @Test
    fun aSessionWideAnswerIsCarriedThroughToTheKernel() = runBlocking {
        val answer = async { ToolApprovalGate.await(request(), timeoutMillis = 5_000) }
        awaitPending("approval-test-1")

        assertTrue(
            ToolApprovalGate.submit("approval-test-1", ToolApprovalDecision.ApprovedForSession),
        )
        assertEquals(ToolApprovalDecision.ApprovedForSession, answer.await())
        assertEquals("approved_for_session", ToolApprovalDecision.ApprovedForSession.storageValue)
    }

    private suspend fun awaitPending(id: String) {
        withTimeout(2_000) {
            while (ToolApprovalGate.pending.value?.id != id) delay(5)
        }
    }
}
