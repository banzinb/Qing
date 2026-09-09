package com.zhousl.aether.data.memory

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [
        BillEntity::class,
        TodoEntity::class,
        ClipEntity::class,
        FileIndexEntity::class,
        UserPrefEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@ConstructedBy(MemoryDatabaseConstructor::class)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object MemoryDatabaseConstructor : RoomDatabaseConstructor<MemoryDatabase> {
    override fun initialize(): MemoryDatabase
}
