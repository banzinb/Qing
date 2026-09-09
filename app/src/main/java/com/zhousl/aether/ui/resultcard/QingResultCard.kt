package com.zhousl.aether.ui.resultcard

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 聊天内结果卡片：chart / stat / list / status。点卡片可全屏展开。 */
@Composable
fun QingResultCard(
    card: QingCard,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(card) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = true },
    ) {
        QingResultCardSurface(card = card)
        Icon(
            imageVector = Icons.Rounded.OpenInFull,
            contentDescription = "展开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(16.dp),
        )
    }
    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = card.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { expanded = false }) {
                            Icon(imageVector = Icons.Rounded.Close, contentDescription = "关闭")
                        }
                    }
                    QingResultCardBody(card = card, expanded = true, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QingResultCardSurface(card: QingCard) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = cardIcon(card),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            QingResultCardBody(card = card, expanded = false)
        }
    }
}

@Composable
private fun QingResultCardBody(card: QingCard, expanded: Boolean, modifier: Modifier = Modifier) {
    when (card) {
        is QingCard.Status -> StatusBody(card)
        is QingCard.Stat -> StatBody(card)
        is QingCard.ListCard -> ListBody(card)
        is QingCard.Chart -> ChartBody(card, expanded = expanded, modifier = modifier)
    }
}

@Composable
private fun StatusBody(card: QingCard.Status) {
    val (icon, color) = when (card.level.lowercase()) {
        "success" -> Icons.Rounded.CheckCircle to Color(0xFF2E7D32)
        "warning" -> Icons.Rounded.Warning to Color(0xFFF57C00)
        "error" -> Icons.Rounded.ErrorOutline to Color(0xFFC62828)
        else -> Icons.Rounded.Info to Color(0xFF1565C0)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = card.message.ifBlank { card.title },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StatBody(card: QingCard.Stat) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        card.items.forEach { item ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(vertical = 10.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = item.value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ListBody(card: QingCard.ListCard) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        card.rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.subtitle.isNotBlank()) {
                        Text(
                            text = row.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (row.value.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartBody(card: QingCard.Chart, expanded: Boolean, modifier: Modifier = Modifier) {
    QingChartWebView(
        optionJson = card.optionJson,
        modifier = modifier
            .fillMaxWidth()
            .height(if (expanded) 420.dp else 220.dp),
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun QingChartWebView(optionJson: String, modifier: Modifier = Modifier) {
    var pageLoaded by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val currentOption by rememberUpdatedState(optionJson)

    fun push(wv: WebView) {
        val opt = currentOption
        if (opt.isNotBlank() && wv.url?.endsWith("render.html") == true) {
            wv.evaluateJavascript("renderCard({\"option\":$opt});", null)
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageLoaded = true
                        push(this@apply)
                    }
                }
                webViewRef = this
                loadUrl("file:///android_asset/panel/render.html")
            }
        },
        modifier = modifier,
        update = { wv ->
            webViewRef = wv
            if (pageLoaded) push(wv)
        },
    )
    DisposableEffect(Unit) {
        onDispose {
            runCatching { webViewRef?.destroy() }
        }
    }
}

private fun cardIcon(card: QingCard): ImageVector = when (card) {
    is QingCard.Status -> Icons.Rounded.Info
    is QingCard.Stat -> Icons.Rounded.Speed
    is QingCard.ListCard -> Icons.Rounded.ListAlt
    is QingCard.Chart -> Icons.Rounded.PieChart
}