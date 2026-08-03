package com.zhousl.aether.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PcBridgeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: PcBridgeClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = PcBridgeClient(baseUrl = server.url("/").toString(), token = "")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun healthReturnsTrueForOkBridge() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"name":"aether-pc-bridge"}"""))
        assertTrue(client.health())
        assertEquals("/api/health", server.takeRequest().path)
    }

    @Test
    fun healthReturnsFalseForErrorResponse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        assertFalse(client.health())
    }

    @Test
    fun listSessionsParsesSessionsNewestFirst() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "ok": true,
                  "total": 2,
                  "sessions": [
                    {
                      "id": "11111111-1111-1111-1111-111111111111",
                      "title": "Fix bug",
                      "updatedAt": "2026-08-02T12:00:00.000Z",
                      "messageCount": 5,
                      "preview": "Fixed the parser.",
                      "cwd": "C:\\work",
                      "model": "gpt-5.5"
                    },
                    {
                      "id": "22222222-2222-2222-2222-222222222222",
                      "title": "Design docs",
                      "updatedAt": "2026-08-01T10:00:00.000Z",
                      "messageCount": 3,
                      "preview": "Wrote the design.",
                      "cwd": "",
                      "model": ""
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        val sessions = client.listSessions(limit = 20)
        assertEquals(2, sessions.size)
        assertEquals("Fix bug", sessions[0].title)
        assertEquals(5, sessions[0].messageCount)
        assertEquals("C:\\work", sessions[0].cwd)
        assertEquals("Design docs", sessions[1].title)
        assertEquals("/api/sessions?limit=20", server.takeRequest().path)
    }

    @Test
    fun readSessionParsesMessagesInOrder() = runBlocking {
        val sessionId = "33333333-3333-3333-3333-333333333333"
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "ok": true,
                  "id": "$sessionId",
                  "title": "History",
                  "cwd": "C:\\work",
                  "model": "gpt-5.5",
                  "messages": [
                    {"id": "user-0", "kind": "user", "text": "hello", "timestamp": 1000},
                    {"id": "assistant-0", "kind": "assistant", "text": "hi there", "timestamp": 2000},
                    {"id": "reasoning-0", "kind": "reasoning", "text": "thinking", "timestamp": 1500},
                    {"id": "tool-0", "kind": "tool", "toolName": "read", "args": "{}", "output": "out", "timestamp": 1700}
                  ]
                }
                """.trimIndent()
            )
        )
        val detail = client.readSession(sessionId)
        assertEquals("History", detail.title)
        assertEquals(4, detail.messages.size)
        assertTrue(detail.messages[0].isUser)
        assertTrue(detail.messages[1].isAssistant)
        assertTrue(detail.messages[2].isReasoning)
        assertTrue(detail.messages[3].isTool)
        assertEquals("read", detail.messages[3].toolName)
        assertEquals("/api/sessions/$sessionId", server.takeRequest().path)
    }

    @Test
    fun startExecPostsPromptAndParsesTask() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "ok": true,
                  "task": {
                    "id": "task-1",
                    "kind": "exec",
                    "status": "running",
                    "sessionId": "44444444-4444-4444-4444-444444444444",
                    "lastMessage": "",
                    "error": ""
                  }
                }
                """.trimIndent()
            )
        )
        val task = client.startExec("write a test")
        assertEquals("task-1", task.id)
        assertEquals("running", task.status)
        assertEquals("44444444-4444-4444-4444-444444444444", task.sessionId)
        val request = server.takeRequest()
        assertEquals("/api/exec", request.path)
        assertEquals("""{"prompt":"write a test"}""", request.body.readUtf8())
    }

    @Test
    fun resumePostsSessionAndPrompt() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"task":{"id":"task-2","status":"running"}}"""))
        val task = client.resume("55555555-5555-5555-5555-555555555555", "continue")
        assertEquals("task-2", task.id)
        val request = server.takeRequest()
        assertEquals("/api/resume", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("""session_id":"55555555-5555-5555-5555-555555555555"""))
        assertTrue(body.contains("""prompt":"continue"""))
    }

    @Test
    fun pollTaskExposesTerminalState() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "ok": true,
                  "task": {
                    "id": "task-3",
                    "status": "completed",
                    "sessionId": "66666666-6666-6666-6666-666666666666",
                    "completedAt": 1754123456789,
                    "lastMessage": "done",
                    "error": ""
                  }
                }
                """.trimIndent()
            )
        )
        val task = client.task("task-3")
        assertTrue(task.isTerminal)
        assertEquals("completed", task.status)
        assertEquals("done", task.lastMessage)
        assertEquals(1754123456789L, task.completedAt)
    }

    @Test
    fun errorResponseThrowsBridgeExceptionWithServerMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"error":"Session not found."}"""))
        var caught: PcBridgeException? = null
        try {
            client.readSession("missing")
        } catch (error: PcBridgeException) {
            caught = error
        }
        assertEquals("Session not found.", caught?.message)
        assertEquals(404, caught?.statusCode)
    }

    @Test
    fun tokenIsSentAsBearerHeaderWhenConfigured() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"name":"aether-pc-bridge"}"""))
        val authorizedClient = PcBridgeClient(baseUrl = server.url("/").toString(), token = "secret-token")
        assertTrue(authorizedClient.health())
        assertEquals("Bearer secret-token", server.takeRequest().getHeader("Authorization"))
    }
}