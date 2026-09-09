package com.zhousl.aether.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhousl.aether.aetherRuntime
import com.zhousl.aether.data.MemoryRepository
import com.zhousl.aether.data.memory.BillEntity
import com.zhousl.aether.data.memory.ClipEntity
import com.zhousl.aether.data.memory.FileIndexEntity
import com.zhousl.aether.data.memory.TodoEntity
import com.zhousl.aether.ui.theme.AetherBackground
import com.zhousl.aether.ui.theme.AetherOnSurface
import com.zhousl.aether.ui.theme.AetherOnSurfaceVariant
import com.zhousl.aether.ui.theme.AetherPrimary
import com.zhousl.aether.ui.theme.AetherSurface
import com.zhousl.aether.ui.theme.AetherSurfaceHigh
import com.zhousl.aether.ui.theme.AetherSurfaceHigher
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun DataOverviewScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val memory = remember { context.aetherRuntime.memoryRepository }
    val scope = rememberCoroutineScope()

    val bills by memory.observeBills().collectAsState(initial = emptyList())
    val todos by memory.observeTodos().collectAsState(initial = emptyList())
    val clips by memory.observeClips(limit = 50).collectAsState(initial = emptyList())
    val files by memory.observeFileIndex(limit = 100).collectAsState(initial = emptyList())

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AetherBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        AetherGlassCapsule(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            cornerRadius = 20.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = AetherOnSurface,
                    )
                }
                Text(
                    text = "我的数据",
                    style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.6.sp),
                    color = AetherOnSurface,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        val monthBills = bills.filter { sameMonth(it.occurredAtMillis, System.currentTimeMillis()) }
        val monthTotalCents = monthBills.sumOf { it.amountCents }
        val activeTodoCount = todos.count { !it.isDone }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SummaryRow(
                    totalCents = monthTotalCents,
                    billCount = monthBills.size,
                    activeTodoCount = activeTodoCount,
                    clipCount = clips.size,
                )
            }
            item {
                SectionHeader(icon = Icons.Rounded.ReceiptLong, title = "账单（本月 ${formatYuan(monthTotalCents)} · ${monthBills.size} 笔）")
            }
            if (bills.isEmpty()) {
                item { EmptyHint("还没有账单记录，跟青说「记一笔账」试试") }
            } else {
                items(bills.take(8), key = { it.id }) { bill ->
                    BillRow(bill = bill, onDelete = { scope.launch { memory.deleteBill(bill.id) } })
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
            item {
                SectionHeader(icon = Icons.Rounded.TaskAlt, title = "待办（未完成 $activeTodoCount）")
            }
            if (todos.isEmpty()) {
                item { EmptyHint("还没有待办，跟青说「记一下明天要…」") }
            } else {
                items(todos.take(10), key = { it.id }) { todo ->
                    TodoRow(
                        todo = todo,
                        onToggle = { scope.launch { memory.setTodoDone(todo.id, !todo.isDone) } },
                        onDelete = { scope.launch { memory.deleteTodo(todo.id) } },
                    )
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
            item {
                SectionHeader(icon = Icons.Rounded.Bookmark, title = "剪藏（${clips.size}）")
            }
            if (clips.isEmpty()) {
                item { EmptyHint("还没有剪藏，把链接甩给青帮你存") }
            } else {
                items(clips.take(8), key = { it.id }) { clip ->
                    ClipRow(clip = clip, onDelete = { scope.launch { memory.deleteClip(clip.id) } })
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
            item {
                SectionHeader(icon = Icons.Rounded.FolderOpen, title = "文件索引（${files.size}）")
            }
            if (files.isEmpty()) {
                item { EmptyHint("还没有文件索引，技能处理过的文件会出现在这里") }
            } else {
                items(files.take(8), key = { it.id }) { file ->
                    FileRow(file = file, onDelete = { scope.launch { memory.deleteFileIndex(file.id) } })
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(
    totalCents: Long,
    billCount: Int,
    activeTodoCount: Int,
    clipCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AetherSurfaceHigh)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SummaryCell(icon = Icons.Rounded.BarChart, label = "本月支出", value = formatYuan(totalCents), tint = AetherPrimary)
        SummaryCell(icon = Icons.Rounded.ReceiptLong, label = "账单", value = "$billCount 笔")
        SummaryCell(icon = Icons.Rounded.TaskAlt, label = "待办", value = "$activeTodoCount 项")
        SummaryCell(icon = Icons.Rounded.Bookmark, label = "剪藏", value = "$clipCount 条")
    }
}

@Composable
private fun SummaryCell(icon: ImageVector, label: String, value: String, tint: Color = AetherOnSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = AetherOnSurface,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AetherOnSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = AetherOnSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = AetherOnSurface,
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AetherOnSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurface)
            .padding(16.dp),
    )
}

@Composable
private fun BillRow(bill: BillEntity, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurfaceHigh)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bill.note.ifBlank { bill.category },
                style = MaterialTheme.typography.bodyLarge,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${bill.category} · ${formatDate(bill.occurredAtMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = formatYuan(bill.amountCents),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = AetherOnSurface,
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = "删除",
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TodoRow(todo: TodoEntity, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurfaceHigh)
            .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = todo.isDone,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = AetherPrimary,
                uncheckedColor = AetherOnSurfaceVariant,
            ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = todo.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (todo.isDone) AetherOnSurfaceVariant else AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            todo.dueAtMillis?.let {
                Text(
                    text = "截止 ${formatDate(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherOnSurfaceVariant,
                )
            }
        }
        if (todo.isDone) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = AetherPrimary, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = "删除",
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ClipRow(clip: ClipEntity, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurfaceHigh)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = clip.title,
                style = MaterialTheme.typography.bodyLarge,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(clip.source.ifBlank { null }, clip.tags.ifBlank { null })
                    .filterNotNull()
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = "删除",
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun FileRow(file: FileIndexEntity, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherSurfaceHigh)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${file.mimeType.ifBlank { "未知类型" }} · ${formatDate(file.indexedAtMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = AetherOnSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = "删除",
                tint = AetherOnSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun sameMonth(millis: Long, now: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = millis }
    val b = Calendar.getInstance().apply { timeInMillis = now }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.MONTH) == b.get(Calendar.MONTH)
}

private fun formatYuan(cents: Long): String =
    String.format(Locale.CHINA, "%.2f", cents / 100.0)

private fun formatDate(millis: Long): String {
    val format = java.text.SimpleDateFormat("M月d日", Locale.CHINA)
    return format.format(java.util.Date(millis))
}