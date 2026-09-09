package com.zhousl.aether.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    // ---- bills ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBill(bill: BillEntity)

    @Query("SELECT * FROM memory_bills ORDER BY occurredAtMillis DESC")
    fun observeBills(): Flow<List<BillEntity>>

    @Query("SELECT * FROM memory_bills WHERE occurredAtMillis BETWEEN :from AND :to ORDER BY occurredAtMillis DESC")
    suspend fun billsInRange(from: Long, to: Long): List<BillEntity>

    @Query("SELECT * FROM memory_bills ORDER BY occurredAtMillis DESC LIMIT :limit")
    suspend fun recentBills(limit: Int): List<BillEntity>

    @Query("DELETE FROM memory_bills WHERE id = :id")
    suspend fun deleteBill(id: String)

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM memory_bills WHERE occurredAtMillis BETWEEN :from AND :to")
    suspend fun sumBillsInRange(from: Long, to: Long): Long

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM memory_bills")
    suspend fun sumAllBills(): Long

    // ---- todos ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTodo(todo: TodoEntity)

    @Query("SELECT * FROM memory_todos ORDER BY is_done ASC, dueAtMillis ASC, createdAtMillis DESC")
    fun observeTodos(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM memory_todos WHERE is_done = 0 ORDER BY dueAtMillis ASC, createdAtMillis DESC")
    suspend fun activeTodos(): List<TodoEntity>

    @Query("UPDATE memory_todos SET is_done = :done, completedAtMillis = :completedAtMillis WHERE id = :id")
    suspend fun setTodoDone(id: String, done: Boolean, completedAtMillis: Long?)

    @Query("DELETE FROM memory_todos WHERE id = :id")
    suspend fun deleteTodo(id: String)

    // ---- clips ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClip(clip: ClipEntity)

    @Query("SELECT * FROM memory_clips ORDER BY createdAtMillis DESC LIMIT :limit")
    fun observeClips(limit: Int = 50): Flow<List<ClipEntity>>

    @Query(
        "SELECT * FROM memory_clips WHERE title LIKE '%' || :query || '%' " +
            "OR content LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' " +
            "ORDER BY createdAtMillis DESC LIMIT :limit"
    )
    suspend fun searchClips(query: String, limit: Int): List<ClipEntity>

    @Query("DELETE FROM memory_clips WHERE id = :id")
    suspend fun deleteClip(id: String)

    // ---- file index ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFileIndex(file: FileIndexEntity)

    @Query("SELECT * FROM memory_file_index ORDER BY indexedAtMillis DESC LIMIT :limit")
    fun observeFileIndex(limit: Int = 100): Flow<List<FileIndexEntity>>

    @Query("DELETE FROM memory_file_index WHERE id = :id")
    suspend fun deleteFileIndex(id: String)

    // ---- user prefs ----
    @Query(
        "INSERT OR REPLACE INTO memory_user_prefs (key, value, updatedAtMillis) " +
            "VALUES (:key, :value, :updatedAtMillis)"
    )
    suspend fun upsertPref(key: String, value: String, updatedAtMillis: Long)

    @Query("SELECT value FROM memory_user_prefs WHERE key = :key")
    suspend fun getPref(key: String): String?

    @Query("SELECT * FROM memory_user_prefs")
    fun observePrefs(): Flow<List<UserPrefEntity>>

    @Query("SELECT * FROM memory_user_prefs")
    suspend fun getAllPrefs(): List<UserPrefEntity>
}
