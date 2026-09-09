package com.zhousl.aether.data.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_bills")
data class BillEntity(
    @PrimaryKey
    val id: String,
    /** Amount in cents (fen) to avoid float rounding. */
    val amountCents: Long,
    val category: String,
    val note: String,
    val occurredAtMillis: Long,
    val createdAtMillis: Long,
    @ColumnInfo(defaultValue = "normal")
    val priority: String = "normal",
)

@Entity(tableName = "memory_todos")
data class TodoEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val dueAtMillis: Long?,
    @ColumnInfo(name = "is_done")
    val isDone: Boolean,
    val createdAtMillis: Long,
    val completedAtMillis: Long?,
    @ColumnInfo(defaultValue = "normal")
    val priority: String = "normal",
)

@Entity(tableName = "memory_clips")
data class ClipEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val url: String,
    val content: String,
    val tags: String,
    val source: String,
    val createdAtMillis: Long,
    @ColumnInfo(defaultValue = "normal")
    val priority: String = "normal",
)

@Entity(tableName = "memory_file_index")
data class FileIndexEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val path: String,
    val mimeType: String,
    val sizeBytes: Long,
    val indexedAtMillis: Long,
)

@Entity(tableName = "memory_user_prefs")
data class UserPrefEntity(
    @PrimaryKey
    val key: String,
    val value: String,
    val updatedAtMillis: Long,
)
