package com.zhousl.aether.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhousl.aether.R
import com.zhousl.aether.aetherRuntime
import com.zhousl.aether.data.PcBridgeClient
import com.zhousl.aether.data.PcBridgeTask
import com.zhousl.aether.data.PcCodexMessage
import com.zhousl.aether.data.PcCodexSession
import com.zhousl.aether.data.PcCodexSessionDetail
import com.zhousl.aether.data.normalizePcBridgeUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PcCodexUiState(
    val baseUrl: String = "",
    val token: String = "",
    val isCheckingConnection: Boolean = false,
    val isConnected: Boolean? = null,
    val connectionTestMessage: String = "",
    val isRefreshingSessions: Boolean = false,
    val sessions: List<PcCodexSession> = emptyList(),
    val selectedSessionId: String? = null,
    val selectedSessionTitle: String = "",
    val messages: List<PcCodexMessage> = emptyList(),
    val isLoadingHistory: Boolean = false,
    val draftInput: String = "",
    val isSending: Boolean = false,
    val activeTaskId: String? = null,
    val activeTaskStatus: String = "",
    val error: String = "",
    val showSettings: Boolean = false,
)

class PcCodexViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = application.aetherRuntime.settingsRepository
    private val _uiState = MutableStateFlow(PcCodexUiState())
    val uiState: StateFlow<PcCodexUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null
    private var terminalReadJob: Job? = null
    private var didAutoRefresh = false

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val normalizedUrl = normalizePcBridgeUrl(settings.pcBridgeUrl)
                _uiState.value = _uiState.value.copy(
                    baseUrl = normalizedUrl,
                    token = settings.pcBridgeToken,
                )
                if (!didAutoRefresh && normalizedUrl.isNotBlank() && _uiState.value.sessions.isEmpty()) {
                    didAutoRefresh = true
                    refreshSessions()
                }
            }
        }
    }

    fun open() {
        if (_uiState.value.baseUrl.isNotBlank() && _uiState.value.sessions.isEmpty()) {
            refreshSessions()
        }
    }

    fun refreshSessions() {
        val baseUrl = _uiState.value.baseUrl
        if (baseUrl.isBlank()) {
            _uiState.update {
                it.copy(isRefreshingSessions = false, isConnected = false, error = "")
            }
            return
        }
        _uiState.update { it.copy(isRefreshingSessions = true, error = "") }
        viewModelScope.launch {
            val result = safeCall { clientFor(_uiState.value).listSessions(50) }
            result.onSuccess { sessions ->
                _uiState.update {
                    it.copy(
                        isRefreshingSessions = false,
                        isConnected = true,
                        sessions = sessions,
                        error = "",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isRefreshingSessions = false,
                        isConnected = false,
                        error = error.message ?: "Failed to load sessions",
                    )
                }
            }
        }
    }

    fun checkConnection(baseUrl: String, token: String) {
        val normalizedUrl = normalizePcBridgeUrl(baseUrl)
        if (normalizedUrl.isBlank()) return
        _uiState.update { it.copy(isCheckingConnection = true, connectionTestMessage = "") }
        viewModelScope.launch {
            val ok = safeCall {
                PcBridgeClient(baseUrl = normalizedUrl, token = token.trim()).health()
            }.getOrDefault(false)
            val app = getApplication<Application>()
            _uiState.update {
                it.copy(
                    isCheckingConnection = false,
                    isConnected = ok,
                    connectionTestMessage = app.getString(
                        if (ok) R.string.pc_codex_connection_test_ok else R.string.pc_codex_connection_test_failed,
                    ),
                )
            }
        }
    }

    fun selectSession(sessionId: String) {
        terminalReadJob?.cancel()
        _uiState.update {
            it.copy(
                selectedSessionId = sessionId,
                selectedSessionTitle = "",
                messages = emptyList(),
                isLoadingHistory = true,
                error = "",
            )
        }
        viewModelScope.launch {
            val result = safeCall { clientFor(_uiState.value).readSession(sessionId) }
            result.onSuccess { detail ->
                _uiState.update {
                    it.copy(
                        isLoadingHistory = false,
                        selectedSessionTitle = detail.title,
                        messages = detail.messages,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoadingHistory = false,
                        error = error.message ?: "Failed to load history",
                    )
                }
            }
        }
    }

    fun backToList() {
        pollJob?.cancel()
        terminalReadJob?.cancel()
        _uiState.update {
            it.copy(
                selectedSessionId = null,
                selectedSessionTitle = "",
                messages = emptyList(),
                isLoadingHistory = false,
                activeTaskId = null,
                activeTaskStatus = "",
            )
        }
        refreshSessions()
    }

    fun setDraftInput(text: String) {
        _uiState.update { it.copy(draftInput = text) }
    }

    fun toggleSettings(show: Boolean) {
        _uiState.update { it.copy(showSettings = show, connectionTestMessage = "") }
    }

    fun clearError() {
        _uiState.update { it.copy(error = "") }
    }

    fun saveSettings(url: String, token: String) {
        val normalizedUrl = normalizePcBridgeUrl(url)
        _uiState.update {
            it.copy(
                baseUrl = normalizedUrl,
                token = token.trim(),
                showSettings = false,
                connectionTestMessage = "",
                error = "",
            )
        }
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            settingsRepository.updateSettings(
                current.copy(
                    pcBridgeUrl = normalizedUrl,
                    pcBridgeToken = token.trim(),
                )
            )
        }
        refreshSessions()
    }

    fun sendDraft() {
        terminalReadJob?.cancel()
        val text = _uiState.value.draftInput.trim()
        if (text.isBlank() || _uiState.value.isSending) return
        if (_uiState.value.baseUrl.isBlank()) {
            _uiState.update { it.copy(error = "Configure the PC bridge URL first") }
            return
        }
        val sessionId = _uiState.value.selectedSessionId
        _uiState.update { it.copy(isSending = true, draftInput = "", error = "") }
        viewModelScope.launch {
            val result = if (sessionId == null) {
                safeCall { clientFor(_uiState.value).startExec(text) }
            } else {
                safeCall { clientFor(_uiState.value).resume(sessionId, text) }
            }
            result.onSuccess { task ->
                _uiState.update {
                    it.copy(
                        isSending = false,
                        activeTaskId = task.id,
                        activeTaskStatus = task.status,
                    )
                }
                startPolling(task.id)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSending = false,
                        error = error.message ?: "Failed to send task",
                    )
                }
            }
        }
    }

    fun stopActiveTask() {
        terminalReadJob?.cancel()
        val taskId = _uiState.value.activeTaskId ?: return
        pollJob?.cancel()
        viewModelScope.launch {
            safeCall { clientFor(_uiState.value).stopTask(taskId) }
            _uiState.update { it.copy(activeTaskId = null, activeTaskStatus = "stopped") }
            refreshSessions()
        }
    }

    private fun startPolling(taskId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                val result = safeCall { clientFor(_uiState.value).task(taskId) }
                result.onSuccess { task ->
                    _uiState.update { it.copy(activeTaskStatus = task.status) }
                    val sessionId = task.sessionId.ifBlank { task.resumeSessionId }
                    if (sessionId.isNotBlank() && _uiState.value.selectedSessionId == null) {
                        selectSession(sessionId)
                    }
                    if (task.isTerminal) {
                        onTaskTerminal(task)
                        break
                    }
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(error = error.message ?: "Task polling failed")
                    }
                    break
                }
                delay(1500)
            }
        }
    }

    private fun onTaskTerminal(task: PcBridgeTask) {
        val sessionId = task.sessionId.ifBlank { task.resumeSessionId }
        _uiState.update {
            it.copy(
                activeTaskId = null,
                activeTaskStatus = task.status,
                error = if (task.status == "failed") {
                    task.error.ifBlank { "Task failed" }
                } else {
                    it.error
                },
            )
        }
        if (sessionId.isNotBlank()) {
            val current = _uiState.value
            if (current.selectedSessionId == sessionId || current.selectedSessionId == null) {
                terminalReadJob?.cancel()
                terminalReadJob = viewModelScope.launch {
                    val result = readSessionWithRetry(sessionId, task.lastMessage)
                    result.onSuccess { detail ->
                        _uiState.update {
                            it.copy(
                                messages = detail.messages,
                                selectedSessionTitle = detail.title,
                                selectedSessionId = sessionId,
                            )
                        }
                    }.onFailure { error ->
                        _uiState.update {
                            it.copy(
                                error = error.message ?: "Failed to sync latest reply",
                            )
                        }
                    }
                    refreshSessions()
                }
                return
            }
        }
        refreshSessions()
    }

    private suspend fun readSessionWithRetry(
        sessionId: String,
        expectedLastMessage: String = "",
        attempts: Int = 3,
        initialDelayMs: Long = 1500,
        retryDelayMs: Long = 1000,
    ): Result<PcCodexSessionDetail> {
        delay(initialDelayMs)
        var lastError: Throwable? = null
        var lastDetail: PcCodexSessionDetail? = null
        repeat(attempts) { attempt ->
            val result = safeCall { clientFor(_uiState.value).readSession(sessionId) }
            result.onSuccess { detail ->
                lastDetail = detail
                val hasFinalReply = expectedLastMessage.isBlank() || detail.messages.any { message ->
                    message.isAssistant && message.text.contains(expectedLastMessage)
                }
                if (hasFinalReply) return result
            }.onFailure { error ->
                lastError = error
            }
            if (attempt < attempts - 1) {
                delay(retryDelayMs)
            }
        }
        val detail = lastDetail
        return if (detail != null) Result.success(detail) else Result.failure(lastError ?: Exception("Failed to read session"))
    }

    private fun clientFor(state: PcCodexUiState): PcBridgeClient =
        PcBridgeClient(baseUrl = state.baseUrl, token = state.token)

    private suspend fun <T> safeCall(block: suspend () -> T): Result<T> {
        return try {
            Result.success(withContext(Dispatchers.IO) { block() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
