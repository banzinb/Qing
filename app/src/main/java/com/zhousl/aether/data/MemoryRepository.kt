package com.zhousl.aether.data

import android.content.Context
import com.zhousl.aether.data.memory.AndroidMemoryDatabaseFactory
import com.zhousl.aether.data.memory.BillEntity
import com.zhousl.aether.data.memory.ClipEntity
import com.zhousl.aether.data.memory.FileIndexEntity
import com.zhousl.aether.data.memory.TodoEntity
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Qing local memory store. The app owns phone data; skills and the agent read
 * and write through this repository. All access is local and offline-first.
 */
class MemoryRepository(
    private val context: Context,
) {
    private val database by lazy { AndroidMemoryDatabaseFactory.getInstance(context) }
    private val dao get() = database.memoryDao()
    private companion object {
        private const val BILL_HALF_LIFE_MILLIS = 60L * 24 * 3600 * 1000
        private const val CLIP_HALF_LIFE_MILLIS = 90L * 24 * 3600 * 1000
    }

    // ---- bills ----

    suspend fun addBill(
        amountCents: Long,
        category: String,
        note: String,
        occurredAtMillis: Long,
        priority: String = "normal",
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        dao.upsertBill(
            BillEntity(
                id = id,
                amountCents = amountCents,
                category = category.ifBlank { "其他" },
                note = note,
                occurredAtMillis = occurredAtMillis,
                createdAtMillis = System.currentTimeMillis(),
                priority = priority,
            )
        )
        id
    }

    suspend fun recentBills(limit: Int = 20): List<BillEntity> = withContext(Dispatchers.IO) {
        dao.recentBills(limit)
    }

    suspend fun billsInRange(fromMillis: Long, toMillis: Long): List<BillEntity> = withContext(Dispatchers.IO) {
        dao.billsInRange(fromMillis, toMillis)
    }

    suspend fun deleteBill(id: String) = withContext(Dispatchers.IO) {
        dao.deleteBill(id)
    }

    fun observeBills(): Flow<List<BillEntity>> = dao.observeBills()

    /** Total + per-category aggregates for a millisecond range, as JSON. */
    suspend fun billStatsJson(fromMillis: Long, toMillis: Long): JSONObject = withContext(Dispatchers.IO) {
        val bills = dao.billsInRange(fromMillis, toMillis)
        val byCategory = LinkedHashMap<String, Long>()
        var total = 0L
        bills.forEach { bill ->
            total += bill.amountCents
            byCategory[bill.category] = (byCategory[bill.category] ?: 0L) + bill.amountCents
        }
        JSONObject().apply {
            put("total_cents", total)
            put("count", bills.size)
            put(
                "by_category",
                JSONObject().apply {
                    byCategory.forEach { (category, cents) -> put(category, cents) }
                },
            )
        }
    }

    // ---- todos ----

    suspend fun addTodo(title: String, dueAtMillis: Long?, priority: String = "normal"): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        dao.upsertTodo(
            TodoEntity(
                id = id,
                title = title,
                dueAtMillis = dueAtMillis,
                isDone = false,
                createdAtMillis = System.currentTimeMillis(),
                completedAtMillis = null,
                priority = priority,
            )
        )
        id
    }

    suspend fun activeTodos(): List<TodoEntity> = withContext(Dispatchers.IO) {
        dao.activeTodos()
    }

    suspend fun setTodoDone(id: String, done: Boolean) = withContext(Dispatchers.IO) {
        dao.setTodoDone(
            id = id,
            done = done,
            completedAtMillis = if (done) System.currentTimeMillis() else null,
        )
    }

    suspend fun deleteTodo(id: String) = withContext(Dispatchers.IO) {
        dao.deleteTodo(id)
    }

    fun observeTodos(): Flow<List<TodoEntity>> = dao.observeTodos()

    // ---- clips ----

    suspend fun addClip(
        title: String,
        url: String,
        content: String,
        tags: String,
        source: String,
        priority: String = "normal",
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        dao.upsertClip(
            ClipEntity(
                id = id,
                title = title.ifBlank { "未命名剪藏" },
                url = url,
                content = content,
                tags = tags,
                source = source,
                createdAtMillis = System.currentTimeMillis(),
                priority = priority,
            )
        )
        id
    }

    suspend fun searchClips(query: String, limit: Int = 20): List<ClipEntity> = withContext(Dispatchers.IO) {
        dao.searchClips(query.trim(), limit)
    }

    suspend fun deleteClip(id: String) = withContext(Dispatchers.IO) {
        dao.deleteClip(id)
    }

    fun observeClips(limit: Int = 50): Flow<List<ClipEntity>> = dao.observeClips(limit)

    // ---- file index ----

    suspend fun indexFile(
        name: String,
        path: String,
        mimeType: String,
        sizeBytes: Long,
    ): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        dao.upsertFileIndex(
            FileIndexEntity(
                id = id,
                name = name,
                path = path,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                indexedAtMillis = System.currentTimeMillis(),
            )
        )
        id
    }

    fun observeFileIndex(limit: Int = 100): Flow<List<FileIndexEntity>> = dao.observeFileIndex(limit)

    suspend fun deleteFileIndex(id: String) = withContext(Dispatchers.IO) {
        dao.deleteFileIndex(id)
    }

    // ---- user prefs ----

    suspend fun setPref(key: String, value: String) = withContext(Dispatchers.IO) {
        dao.upsertPref(key, value, System.currentTimeMillis())
    }

    suspend fun getPref(key: String): String? = withContext(Dispatchers.IO) {
        dao.getPref(key)
    }

    fun observePrefs(): Flow<List<com.zhousl.aether.data.memory.UserPrefEntity>> = dao.observePrefs()

    // ---- auto context injection ----

    /**
     * Builds a compact "what Qing remembers" block injected into the agent
     * system prompt at the start of each turn. Injection is budget-based:
     * items are scored by priority (always/normal/low) x recency decay, and
     * only the most relevant items make it into the limited context.
     */
    suspend fun buildAutoInjectionContext(maxChars: Int = 1500): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val todos = dao.activeTodos()
        val monthStart = startOfMonthMillis()
        val monthBills = dao.billsInRange(monthStart, Long.MAX_VALUE)
        val clips = dao.searchClips("", limit = 30)
        val prefs = dao.getAllPrefs()

        val sb = StringBuilder()
        sb.append("## Qing 本地记忆（自动注入，仅供参考）\n")

        // 1) 长期记住：priority=always + 用户偏好，永远优先注入
        val alwaysLines = mutableListOf<String>()
        monthBills.filter { it.priority.equals("always", ignoreCase = true) }.forEach { bill ->
            alwaysLines.add("- 记账（重要）：${formatDate(bill.occurredAtMillis)} ${bill.category} ${formatYuan(bill.amountCents)} ${bill.note}".trim())
        }
        todos.filter { it.priority.equals("always", ignoreCase = true) }.forEach { todo ->
            val due = todo.dueAtMillis?.let { "，截止 ${formatDate(it)}" } ?: ""
            alwaysLines.add("- 待办（重要）：${todo.title}$due")
        }
        clips.filter { it.priority.equals("always", ignoreCase = true) }.forEach { clip ->
            alwaysLines.add("- 剪藏（重要）：${clip.title}")
        }
        prefs.forEach { pref ->
            val label = pref.key.removePrefix("user:").removePrefix("pref:")
            alwaysLines.add("- 偏好（重要）：$label = ${pref.value}")
        }
        if (alwaysLines.isNotEmpty()) {
            sb.append("### 需要长期记住\n")
            alwaysLines.take(8).forEach { sb.append(it).append("\n") }
        }

        // 2) 未完成待办：always 优先，再按截止时间
        if (todos.isNotEmpty()) {
            sb.append("### 未完成待办（${todos.size}）\n")
            todos.sortedWith(
                compareByDescending<TodoEntity> { it.priority.equals("always", ignoreCase = true) }
                    .thenBy { it.dueAtMillis ?: Long.MAX_VALUE },
            ).take(6).forEach { todo ->
                val due = todo.dueAtMillis?.let { "，截止 ${formatDate(it)}" } ?: ""
                sb.append("- [ ] ${todo.title}$due\n")
            }
        }

        // 3) 本月账单：重要度 x 时间衰减排序
        if (monthBills.isNotEmpty()) {
            val total = monthBills.sumOf { it.amountCents }
            sb.append("### 本月账单（${monthBills.size} 笔，共 ${formatYuan(total)}）\n")
            monthBills.sortedByDescending { bill ->
                memoryScore(priority = bill.priority, ageMillis = now - bill.occurredAtMillis, halfLifeMillis = BILL_HALF_LIFE_MILLIS)
            }.take(4).forEach { bill ->
                sb.append("- ${formatDate(bill.occurredAtMillis)} ${bill.category} ${formatYuan(bill.amountCents)} ${bill.note}\n")
            }
        }

        // 4) 最近剪藏：重要度 x 时间衰减排序
        if (clips.isNotEmpty()) {
            sb.append("### 最近剪藏\n")
            clips.sortedByDescending { clip ->
                memoryScore(priority = clip.priority, ageMillis = now - clip.createdAtMillis, halfLifeMillis = CLIP_HALF_LIFE_MILLIS)
            }.take(4).forEach { clip ->
                sb.append("- ${clip.title}\n")
            }
        }

        val result = sb.toString()
        if (result.length <= "## Qing 本地记忆（自动注入，仅供参考）\n".length) {
            return@withContext ""
        }
        result.take(maxChars)
    }

    private fun memoryScore(priority: String, ageMillis: Long, halfLifeMillis: Long): Double {
        val priorityWeight = when (priority.lowercase()) {
            "always" -> 100.0
            "low" -> 1.0
            else -> 10.0
        }
        val recency = if (ageMillis <= 0L) 1.0 else Math.exp(-ageMillis.toDouble() / halfLifeMillis)
        return priorityWeight * recency
    }

    private fun startOfMonthMillis(): Long {
        val now = java.util.Calendar.getInstance()
        now.set(java.util.Calendar.DAY_OF_MONTH, 1)
        now.set(java.util.Calendar.HOUR_OF_DAY, 0)
        now.set(java.util.Calendar.MINUTE, 0)
        now.set(java.util.Calendar.SECOND, 0)
        now.set(java.util.Calendar.MILLISECOND, 0)
        return now.timeInMillis
    }

    private fun formatYuan(cents: Long): String {
        val yuan = cents / 100.0
        return String.format(java.util.Locale.CHINA, "%.2f", yuan)
    }

    private fun formatDate(millis: Long): String {
        val format = java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA)
        return format.format(java.util.Date(millis))
    }
}
