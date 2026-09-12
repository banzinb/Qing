package com.zhousl.aether.ui

import com.zhousl.aether.data.SessionExecutionState
import com.zhousl.aether.data.completedReconnectStatus
import com.zhousl.aether.data.completePendingReconnectBlocks
import com.zhousl.aether.data.upsertDurableStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseGenerationStateTest {
    @Test
    fun finalizedTurnReplacesPendingUiInSameStateUpdate() {
        val sessionId = "session"
        val finalized = ChatSession(
            id = sessionId,
            title = "Chat",
            preview = "Partial answer",
            messages = listOf(
                ChatMessage(
                    id = "assistant",
                    author = MessageAuthor.Agent,
                    text = "Partial answer",
                    statusText = completedReconnectStatus("Reconnecting... 2/5"),
                    statusDetail = "fetch failed: connect timed out (ETIMEDOUT)",
                    thoughtDurationMillis = 2_000,
                )
            ),
        )
        val stoppedExecution = SessionExecutionState(sessionId = sessionId)
        val initial = AetherUiState(
            currentSessionId = sessionId,
            sessions = listOf(finalized.copy(messages = emptyList())),
            isSending = true,
            pendingAssistantText = "Partial answer",
            pendingStatusText = "Reconnecting... 2/5",
        )

        val updated = initial.withFinalizedPausedSession(
            finalizedSession = finalized,
            executionStates = mapOf(sessionId to stoppedExecution),
        )

        assertFalse(updated.isSending)
        assertTrue(updated.pendingAssistantText.isEmpty())
        assertTrue(updated.pendingStatusText.isEmpty())
        assertEquals(finalized.messages, updated.sessions.single().messages)
        assertEquals("Reconnected 2/5", updated.sessions.single().messages.single().statusText)
        assertEquals(
            "fetch failed: connect timed out (ETIMEDOUT)",
            updated.sessions.single().messages.single().statusDetail,
        )
    }

    @Test
    fun stoppingOnlyCompletesReconnectStatus() {
        assertEquals("Reconnected 5/5", completedReconnectStatus("Reconnecting... 5/5"))
        assertEquals("Waiting for approval", completedReconnectStatus("Waiting for approval"))
    }

    @Test
    fun reconnectBlockCompletesInPlaceBeforeLaterTimelineWork() {
        val blocks = completePendingReconnectBlocks(
            listOf(
                AssistantResponseBlock.Text("before", "Before"),
                AssistantResponseBlock.Status("retry", "Reconnecting... 3/5", "timed out"),
                AssistantResponseBlock.ToolGroup(
                    "after",
                    listOf(ChatToolInvocation(id = "tool", toolName = "read", argumentsJson = "{}")),
                ),
            )
        )

        val status = blocks[1] as AssistantResponseBlock.Status
        assertEquals("Reconnected 3/5", status.text)
        assertEquals("timed out", status.detail)
        assertTrue(blocks[2] is AssistantResponseBlock.ToolGroup)
    }

    @Test
    fun durableStatusRepeatsUpdateOneBlockInPlace() {
        val blocks = emptyList<AssistantResponseBlock>()
            .upsertDurableStatus(id = "status-1", text = LoopWarningText, detail = "bash × 6")
            .upsertDurableStatus(id = "status-2", text = LoopWarningText, detail = "bash × 7")

        assertEquals(1, blocks.size)
        val status = blocks.single() as AssistantResponseBlock.Status
        assertEquals("status-1", status.id)
        assertEquals(LoopWarningText, status.text)
        assertEquals("bash × 7", status.detail)
    }

    @Test
    fun durableStatusSurvivesLaterProgressAndEscalatesToItsOwnBlock() {
        val blocks = listOf<AssistantResponseBlock>(
            AssistantResponseBlock.Text("answer", "working on it"),
        )
            .upsertDurableStatus(id = "status-1", text = LoopWarningText, detail = "bash × 6")
            .upsertDurableStatus(id = "status-2", text = LoopAbortText, detail = "bash × 12")

        assertEquals(3, blocks.size)
        assertEquals(LoopWarningText, (blocks[1] as AssistantResponseBlock.Status).text)
        assertEquals(LoopAbortText, (blocks[2] as AssistantResponseBlock.Status).text)
        assertEquals("bash × 12", (blocks[2] as AssistantResponseBlock.Status).detail)
        // Ordinary progress handling must not rewrite or drop the durable blocks.
        assertEquals(blocks, completePendingReconnectBlocks(blocks))
    }

    private companion object {
        const val LoopWarningText = "检测到重复的工具调用"
        const val LoopAbortText = "检测到工具死循环，已中止本次任务"
    }
}
