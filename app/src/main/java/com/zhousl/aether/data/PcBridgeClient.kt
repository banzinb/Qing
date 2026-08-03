package com.zhousl.aether.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

data class PcCodexSession(
    val id: String,
    val title: String,
    val updatedAt: String,
    val messageCount: Int,
    val preview: String,
    val cwd: String = "",
    val model: String = "",
)

data class PcCodexMessage(
    val id: String,
    val kind: String,
    val text: String = "",
    val toolName: String = "",
    val args: String = "",
    val output: String = "",
    val timestamp: Long = 0L,
) {
    val isUser: Boolean get() = kind == "user"
    val isAssistant: Boolean get() = kind == "assistant"
    val isReasoning: Boolean get() = kind == "reasoning"
    val isTool: Boolean get() = kind == "tool"
}

data class PcCodexSessionDetail(
    val id: String,
    val title: String,
    val messages: List<PcCodexMessage>,
    val cwd: String = "",
    val model: String = "",
)

data class PcBridgeTask(
    val id: String,
    val kind: String = "exec",
    val status: String = "queued",
    val sessionId: String = "",
    val resumeSessionId: String = "",
    val lastMessage: String = "",
    val error: String = "",
    val completedAt: Long? = null,
) {
    val isTerminal: Boolean
        get() = status == "completed" || status == "failed" || status == "stopped"
}

class PcBridgeException(
    message: String,
    val statusCode: Int = 0,
) : IOException(message)

class PcBridgeClient(
    private val baseUrl: String = "",
    private val token: String = "",
    private val client: OkHttpClient = defaultPcBridgeHttpClient(),
) {
    private val normalizedBaseUrl: String
        get() = baseUrl.trim().trimEnd('/')

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        try {
            executeGet("/api/health").optBoolean("ok", false)
        } catch (_: IOException) {
            false
        }
    }

    suspend fun listSessions(limit: Int = 50): List<PcCodexSession> = withContext(Dispatchers.IO) {
        val json = executeGet("/api/sessions?limit=$limit")
        val array = json.optJSONArray("sessions") ?: JSONArray()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    PcCodexSession(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        updatedAt = item.optString("updatedAt"),
                        messageCount = item.optInt("messageCount", 0),
                        preview = item.optString("preview"),
                        cwd = item.optString("cwd"),
                        model = item.optString("model"),
                    )
                )
            }
        }
    }

    suspend fun readSession(id: String): PcCodexSessionDetail = withContext(Dispatchers.IO) {
        val json = executeGet("/api/sessions/$id")
        val messagesJson = json.optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (index in 0 until messagesJson.length()) {
                val item = messagesJson.optJSONObject(index) ?: continue
                add(
                    PcCodexMessage(
                        id = item.optString("id"),
                        kind = item.optString("kind"),
                        text = item.optString("text"),
                        toolName = item.optString("toolName"),
                        args = item.optString("args"),
                        output = item.optString("output"),
                        timestamp = item.optLong("timestamp", 0L),
                    )
                )
            }
        }
        PcCodexSessionDetail(
            id = json.optString("id", id),
            title = json.optString("title"),
            messages = messages,
            cwd = json.optString("cwd"),
            model = json.optString("model"),
        )
    }

    suspend fun startExec(prompt: String): PcBridgeTask = withContext(Dispatchers.IO) {
        val body = JSONObject().put("prompt", prompt)
        parseTask(executePost("/api/exec", body))
    }

    suspend fun resume(sessionId: String, prompt: String): PcBridgeTask = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("session_id", sessionId)
            .put("prompt", prompt)
        parseTask(executePost("/api/resume", body))
    }

    suspend fun task(taskId: String): PcBridgeTask = withContext(Dispatchers.IO) {
        parseTask(executeGet("/api/tasks/$taskId"))
    }

    suspend fun stopTask(taskId: String): PcBridgeTask = withContext(Dispatchers.IO) {
        parseTask(executePost("/api/tasks/$taskId/stop", JSONObject()))
    }

    private fun parseTask(json: JSONObject): PcBridgeTask {
        val task = json.optJSONObject("task") ?: JSONObject()
        return PcBridgeTask(
            id = task.optString("id"),
            kind = task.optString("kind"),
            status = task.optString("status"),
            sessionId = task.optString("sessionId"),
            resumeSessionId = task.optString("resumeSessionId"),
            lastMessage = task.optString("lastMessage"),
            error = task.optString("error"),
            completedAt = if (task.has("completedAt") && !task.isNull("completedAt")) {
                task.optLong("completedAt")
            } else {
                null
            },
        )
    }

    private fun executeGet(path: String): JSONObject {
        val request = Request.Builder()
            .url(normalizedBaseUrl + path)
            .header("Accept", "application/json")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        return client.newCall(request).execute().use { response -> parseJson(response) }
    }

    private fun executePost(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(normalizedBaseUrl + path)
            .header("Accept", "application/json")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response -> parseJson(response) }
    }

    private fun parseJson(response: Response): JSONObject {
        val bodyText = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val serverMessage = runCatching {
                JSONObject(bodyText).optString("error")
            }.getOrDefault("").ifBlank { "HTTP ${response.code}" }
            throw PcBridgeException(serverMessage, response.code)
        }
        return runCatching { JSONObject(bodyText) }.getOrElse {
            throw PcBridgeException("Invalid JSON response.", response.code)
        }
    }

    companion object {
        fun defaultPcBridgeHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}