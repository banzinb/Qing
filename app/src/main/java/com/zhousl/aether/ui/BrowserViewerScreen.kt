package com.zhousl.aether.ui

import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zhousl.aether.R
import com.zhousl.aether.data.browser.BrowserViewerState
import com.zhousl.aether.data.browser.BrowserViewerTab
import com.zhousl.aether.data.browser.WebViewBrowserController
import com.zhousl.aether.data.browser.browserTabLabel
import com.zhousl.aether.ui.theme.AetherOnSurface
import com.zhousl.aether.ui.theme.AetherOnSurfaceVariant
import com.zhousl.aether.ui.theme.AetherOutline
import com.zhousl.aether.ui.theme.AetherSurface
import com.zhousl.aether.ui.theme.AetherSurfaceHigh
import kotlinx.coroutines.launch

/**
 * The visible side of Qing's embedded browser.
 *
 * The pooled WebView is not recreated here: the controller moves the *same* instance into the
 * container that [AndroidView] provides, so page state, cookies and scroll position survive, and
 * the container fills the whole screen (the toolbar floats on top) so the page keeps the exact
 * viewport it had while hidden. Every action goes through the controller, which serializes them
 * against the agent's actions.
 */
@Composable
internal fun BrowserViewerOverlay(
    controller: WebViewBrowserController,
    state: BrowserViewerState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val hostRef = remember { arrayOfNulls<FrameLayout>(1) }
    var addressText by remember { mutableStateOf("") }
    var addressFocused by remember { mutableStateOf(false) }
    var toolbarExpanded by remember { mutableStateOf(true) }

    val activeTab = state.tabs.firstOrNull { it.id == state.activeTabId } ?: state.tabs.firstOrNull()

    LaunchedEffect(activeTab?.url, addressFocused) {
        if (!addressFocused) addressText = activeTab?.url.orEmpty()
    }

    DisposableEffect(controller) {
        onDispose { hostRef[0]?.let(controller::detachDisplayHost) }
    }

    BackHandler { onClose() }

    Box(modifier = modifier.fillMaxSize().background(AetherSurface)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                FrameLayout(context).also { container ->
                    hostRef[0] = container
                    controller.attachDisplayHost(container)
                }
            },
        )

        if (state.isEmpty) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.browser_viewer_empty),
                    color = AetherOnSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (toolbarExpanded) {
            BrowserViewerToolbar(
                modifier = Modifier.align(Alignment.TopCenter),
                state = state,
                activeTab = activeTab,
                addressText = addressText,
                addressFocused = addressFocused,
                onAddressChanged = { addressText = it },
                onAddressFocusedChanged = { addressFocused = it },
                onNavigate = { url ->
                    addressFocused = false
                    scope.launch { controller.viewerNavigate(url) }
                },
                onBack = { scope.launch { controller.viewerGoBack() } },
                onForward = { scope.launch { controller.viewerGoForward() } },
                onReload = { scope.launch { controller.viewerReload() } },
                onStop = { controller.viewerStopLoading() },
                onSelectTab = { tabId -> scope.launch { controller.selectTab(tabId) } },
                onCloseTab = { tabId -> scope.launch { controller.viewerCloseTab(tabId) } },
                onNewTab = { scope.launch { controller.viewerNewTab() } },
                onCollapse = { toolbarExpanded = false },
                onClose = onClose,
            )
        } else {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp),
                color = AetherSurface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 3.dp,
            ) {
                IconButton(onClick = { toolbarExpanded = true }) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.browser_viewer_show_toolbar),
                        tint = AetherOnSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserViewerToolbar(
    state: BrowserViewerState,
    activeTab: BrowserViewerTab?,
    addressText: String,
    addressFocused: Boolean,
    onAddressChanged: (String) -> Unit,
    onAddressFocusedChanged: (Boolean) -> Unit,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onNewTab: () -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AetherSurface.copy(alpha = 0.95f),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.browser_viewer_close),
                        tint = AetherOnSurface,
                    )
                }
                TextField(
                    value = addressText,
                    onValueChange = onAddressChanged,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState -> onAddressFocusedChanged(focusState.isFocused) },
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.browser_viewer_address_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = AetherSurfaceHigh,
                        unfocusedContainerColor = AetherSurfaceHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = AetherOnSurface,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onNavigate(addressText) }),
                )
                if (addressFocused) {
                    IconButton(onClick = { onNavigate(addressText) }) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowForward,
                            contentDescription = stringResource(R.string.browser_viewer_go),
                            tint = AetherOnSurface,
                        )
                    }
                } else {
                    IconButton(onClick = onReload) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.browser_viewer_reload),
                            tint = AetherOnSurface,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.browser_viewer_back),
                        tint = AetherOnSurface,
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = stringResource(R.string.browser_viewer_stop),
                        tint = AetherOnSurface,
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.tabs.forEach { tab ->
                        BrowserViewerTabChip(
                            tab = tab,
                            selected = tab.id == activeTab?.id,
                            onSelect = { onSelectTab(tab.id) },
                            onClose = { onCloseTab(tab.id) },
                        )
                    }
                }
                IconButton(onClick = onNewTab) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.browser_viewer_new_tab),
                        tint = AetherOnSurface,
                    )
                }
                IconButton(onClick = onCollapse) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.browser_viewer_hide_toolbar),
                        tint = AetherOnSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserViewerTabChip(
    tab: BrowserViewerTab,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onSelect),
        color = if (selected) AetherSurfaceHigh else Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) AetherOutline else Color.Transparent,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = browserTabLabel(tab),
                color = if (selected) AetherOnSurface else AetherOnSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(112.dp),
            )
            Spacer(modifier = Modifier.size(4.dp))
            IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.browser_viewer_close_tab),
                    tint = AetherOnSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
