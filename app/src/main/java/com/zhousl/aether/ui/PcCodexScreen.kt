package com.zhousl.aether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhousl.aether.R
import com.zhousl.aether.data.PcCodexMessage
import com.zhousl.aether.data.PcCodexSession
import com.zhousl.aether.ui.theme.AetherBackground
import com.zhousl.aether.ui.theme.AetherError
import com.zhousl.aether.ui.theme.AetherMessageBubble
import com.zhousl.aether.ui.theme.AetherOnPrimary
import com.zhousl.aether.ui.theme.AetherOnSurface
import com.zhousl.aether.ui.theme.AetherOnSurfaceVariant
import com.zhousl.aether.ui.theme.AetherOutlineSoft
import com.zhousl.aether.ui.theme.AetherPrimary
import com.zhousl.aether.ui.theme.AetherSecondary
import com.zhousl.aether.ui.theme.AetherSurfaceHigh
import com.zhousl.aether.ui.theme.AetherSurfaceHigher
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PcBridgeTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@Composable
fun PcCodexScreen(
    onBack: () -> Unit,
    viewModel: PcCodexViewModel = viewModel(),
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(Unit) {
        viewModel.open()
    }
    BackHandler {
        when {
            state.showSettings -> viewModel.toggleSettings(false)
            state.selectedSessionId != null -> viewModel.backToList()
            else -> onBack()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            PcCodexTopBar(
                title = if (state.showSettings) {
                    stringResource(R.string.pc_codex_settings)
                } else {
                    stringResource(R.string.pc_codex_title)
                },
                connected = state.isConnected,
                checkingConnection = state.isCheckingConnection,
                canGoBack = state.showSettings || state.selectedSessionId != null,
                onBack = {
                    when {
                        state.showSettings -> viewModel.toggleSettings(false)
                        state.selectedSessionId != null -> viewModel.backToList()
                        else -> onBack()
                    }
                },
                onRefresh = viewModel::refreshSessions,
                onSettings = { viewModel.toggleSettings(true) },
            )
            Box(modifier = Modifier.weight(1f)) {
            when {
                state.showSettings -> PcCodexSettingsPanel(
                    baseUrl = state.baseUrl,
                    token = state.token,
                    isCheckingConnection = state.isCheckingConnection,
                    connectionTestMessage = state.connectionTestMessage,
                    onTestConnection = viewModel::checkConnection,
                    onSave = viewModel::saveSettings,
                    onBack = { viewModel.toggleSettings(false) },
                )

                state.selectedSessionId != null -> PcCodexChatContent(
                    title = state.selectedSessionTitle,
                    messages = state.messages,
                    isLoadingHistory = state.isLoadingHistory,
                    hasActiveTask = state.activeTaskId != null,
                )

                else -> PcCodexSessionList(
                    sessions = state.sessions,
                    isRefreshing = state.isRefreshingSessions,
                    baseUrlConfigured = state.baseUrl.isNotBlank(),
                    onSelectSession = viewModel::selectSession,
                    onRefresh = viewModel::refreshSessions,
                    onOpenSettings = { viewModel.toggleSettings(true) },
                )
            }
            }
            if (!state.showSettings) {
                PcCodexStatusStrip(
                    error = state.error,
                    activeTaskStatus = state.activeTaskStatus,
                    hasActiveTask = state.activeTaskId != null,
                    onClearError = viewModel::clearError,
                )
                PcCodexComposer(
                    input = state.draftInput,
                    isSending = state.isSending,
                    hasActiveTask = state.activeTaskId != null,
                    placeholder = stringResource(
                        if (state.selectedSessionId != null) {
                            R.string.pc_codex_resume_hint
                        } else {
                            R.string.pc_codex_new_task_hint
                        }
                    ),
                    onInputChanged = viewModel::setDraftInput,
                    onSend = viewModel::sendDraft,
                    onStop = viewModel::stopActiveTask,
                )
            }
        }
    }
}
@Composable
private fun PcCodexTopBar(
    title: String,
    connected: Boolean?,
    checkingConnection: Boolean,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (canGoBack) {
            PcCodexIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                onClick = onBack,
            )
        } else {
            Spacer(Modifier.width(40.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AetherOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PcCodexConnectionDot(
            connected = connected,
            checkingConnection = checkingConnection,
        )
        PcCodexIconButton(
            icon = Icons.Rounded.Refresh,
            contentDescription = stringResource(R.string.pc_codex_refresh),
            onClick = onRefresh,
        )
        PcCodexIconButton(
            icon = Icons.Rounded.Settings,
            contentDescription = stringResource(R.string.pc_codex_settings),
            onClick = onSettings,
        )
    }
}

@Composable
private fun PcCodexIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = AetherOnSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun PcCodexConnectionDot(
    connected: Boolean?,
    checkingConnection: Boolean,
) {
    val dotColor = when {
        checkingConnection -> AetherOutlineSoft
        connected == true -> Color(0xFF34C759)
        connected == false -> AetherError
        else -> AetherOutlineSoft
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(dotColor),
    )
}

@Composable
private fun PcCodexSessionList(
    sessions: List<PcCodexSession>,
    isRefreshing: Boolean,
    baseUrlConfigured: Boolean,
    onSelectSession: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when {
        isRefreshing && sessions.isEmpty() -> PcCodexCenteredState(
            text = stringResource(R.string.pc_codex_loading_sessions),
            showProgress = true,
        )

        sessions.isEmpty() && !baseUrlConfigured -> PcCodexCenteredState(
            text = stringResource(R.string.pc_codex_configure_first),
            showProgress = false,
            actionLabel = stringResource(R.string.pc_codex_settings),
            onAction = onOpenSettings,
        )

        sessions.isEmpty() -> PcCodexCenteredState(
            text = stringResource(R.string.pc_codex_no_sessions),
            showProgress = false,
            actionLabel = stringResource(R.string.pc_codex_refresh),
            onAction = onRefresh,
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(sessions, key = { it.id }) { session ->
                PcCodexSessionRow(
                    session = session,
                    onClick = { onSelectSession(session.id) },
                )
            }
        }
    }
}

@Composable
private fun PcCodexSessionRow(
    session: PcCodexSession,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AetherSurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AetherSurfaceHigher),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.DesktopWindows,
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title.ifBlank { session.id },
                style = MaterialTheme.typography.bodyLarge,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.preview.isNotBlank()) {
                Text(
                    text = session.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    val formatted = formatPcBridgeTime(session.updatedAt)
                    if (formatted.isNotBlank()) append(formatted)
                    append(" · ")
                    append(session.messageCount)
                    append(" ")
                    append(stringResource(R.string.pc_codex_message_count))
                },
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
            contentDescription = null,
            tint = AetherOnSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PcCodexCenteredState(
    text: String,
    showProgress: Boolean,
    actionLabel: String = "",
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = AetherPrimary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.height(16.dp))
        } else {
            Icon(
                imageVector = Icons.Rounded.DesktopWindows,
                contentDescription = null,
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(14.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = AetherOnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}
@Composable
private fun PcCodexChatContent(
    title: String,
    messages: List<PcCodexMessage>,
    isLoadingHistory: Boolean,
    hasActiveTask: Boolean,
) {
    if (isLoadingHistory && messages.isEmpty()) {
        PcCodexCenteredState(
            text = stringResource(R.string.pc_codex_loading_history),
            showProgress = true,
        )
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, hasActiveTask) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DesktopWindows,
                    contentDescription = null,
                    tint = AetherPrimary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = title.ifBlank { stringResource(R.string.pc_codex_title) },
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (messages.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.pc_codex_new_task_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(messages, key = { it.id }) { message ->
            PcCodexMessageBubble(message)
        }
    }
}

@Composable
private fun PcCodexMessageBubble(message: PcCodexMessage) {
    when {
        message.isTool -> PcCodexToolRow(message)
        message.isReasoning -> PcCodexReasoningRow(message)
        message.isUser -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 6.dp,
                            bottomEnd = 20.dp,
                            bottomStart = 20.dp,
                        )
                    )
                    .background(AetherMessageBubble)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                MarkdownContent(
                    markdown = message.text,
                    color = AetherOnSurface,
                )
            }
        }

        else -> Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 6.dp,
                            topEnd = 20.dp,
                            bottomEnd = 20.dp,
                            bottomStart = 20.dp,
                        )
                    )
                    .background(AetherSurfaceHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                MarkdownContent(
                    markdown = message.text,
                    color = AetherOnSurface,
                )
            }
        }
    }
}

@Composable
private fun PcCodexToolRow(message: PcCodexMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AetherSurfaceHigh.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = buildString {
                append("tool · ")
                append(message.toolName.ifBlank { "unknown" })
            },
            style = MaterialTheme.typography.labelMedium,
            color = AetherOnSurfaceVariant,
        )
        if (message.args.isNotBlank()) {
            Text(
                text = message.args.take(300),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PcCodexReasoningRow(message: PcCodexMessage) {
    Text(
        text = message.text,
        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
        color = AetherOnSurfaceVariant.copy(alpha = 0.8f),
        maxLines = 8,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
@Composable
private fun PcCodexStatusStrip(
    error: String,
    activeTaskStatus: String,
    hasActiveTask: Boolean,
    onClearError: () -> Unit,
) {
    when {
        error.isNotBlank() -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AetherError.copy(alpha = 0.10f))
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = AetherError,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onClearError,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.common_close),
                    tint = AetherError,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        hasActiveTask -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AetherPrimary.copy(alpha = 0.08f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = AetherPrimary,
                strokeWidth = 2.dp,
            )
            Text(
                text = pcTaskStatusLabel(activeTaskStatus),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun pcTaskStatusLabel(status: String): String = when (status) {
    "completed" -> stringResource(R.string.pc_codex_completed)
    "failed" -> stringResource(R.string.pc_codex_failed)
    "stopped" -> stringResource(R.string.pc_codex_stopped)
    else -> stringResource(R.string.pc_codex_running)
}

@Composable
private fun PcCodexComposer(
    input: String,
    isSending: Boolean,
    hasActiveTask: Boolean,
    placeholder: String,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AetherBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(AetherSurfaceHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (input.isBlank()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AetherOnSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = input,
                onValueChange = onInputChanged,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = AetherOnSurface),
                cursorBrush = SolidColor(AetherPrimary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val sendEnabled = input.isNotBlank() && !isSending
        if (hasActiveTask) {
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(AetherSurfaceHigher),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Stop,
                    contentDescription = stringResource(R.string.pc_codex_stop),
                    tint = AetherError,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            IconButton(
                onClick = onSend,
                enabled = sendEnabled,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (sendEnabled) AetherPrimary else AetherSurfaceHigher),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(R.string.pc_codex_send),
                    tint = if (sendEnabled) AetherOnPrimary else AetherOnSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
@Composable
private fun PcCodexSettingsPanel(
    baseUrl: String,
    token: String,
    isCheckingConnection: Boolean,
    connectionTestMessage: String,
    onTestConnection: (String, String) -> Unit,
    onSave: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var urlValue by rememberSaveable(baseUrl) { mutableStateOf(baseUrl) }
    var tokenValue by rememberSaveable(token) { mutableStateOf(token) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.pc_codex_settings),
            style = MaterialTheme.typography.titleLarge,
            color = AetherOnSurface,
        )
        PcCodexLabeledField(
            label = stringResource(R.string.pc_codex_base_url_label),
            value = urlValue,
            onValueChange = { urlValue = it },
            placeholder = "http://192.168.1.10:8899",
            keyboardType = KeyboardType.Uri,
        )
        PcCodexLabeledField(
            label = stringResource(R.string.pc_codex_token_label),
            value = tokenValue,
            onValueChange = { tokenValue = it },
            placeholder = "",
            password = !showToken,
            trailingIcon = {
                IconButton(
                    onClick = { showToken = !showToken },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (showToken) {
                            Icons.Rounded.VisibilityOff
                        } else {
                            Icons.Rounded.Visibility
                        },
                        contentDescription = stringResource(
                            if (showToken) R.string.common_hide_password else R.string.common_show_password
                        ),
                        tint = AetherOnSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )
        if (connectionTestMessage.isNotBlank()) {
            Text(
                text = connectionTestMessage,
                style = MaterialTheme.typography.bodySmall,
                color = AetherSecondary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = { onTestConnection(urlValue, tokenValue) },
                enabled = urlValue.isNotBlank() && !isCheckingConnection,
                modifier = Modifier.weight(1f),
            ) {
                if (isCheckingConnection) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.pc_codex_test_connection))
                }
            }
            Button(
                onClick = { onSave(urlValue, tokenValue) },
                enabled = urlValue.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.pc_codex_save))
            }
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.common_back))
        }
    }
}

@Composable
private fun PcCodexLabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = AetherOnSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AetherSurfaceHigh)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.isBlank()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherOnSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = AetherOnSurface),
                    cursorBrush = SolidColor(AetherPrimary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    visualTransformation = if (password) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            trailingIcon?.invoke()
        }
    }
}

private fun formatPcBridgeTime(value: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        PcBridgeTimeFormatter.format(Instant.parse(value).atZone(ZoneId.systemDefault()))
    }.getOrDefault(value.take(16))
}
