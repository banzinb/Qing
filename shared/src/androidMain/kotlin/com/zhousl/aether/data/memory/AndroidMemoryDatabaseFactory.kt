package com.zhousl.aether.data.memory

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

object AndroidMemoryDatabaseFactory {
    @Volatile
    private var instance: MemoryDatabase? = null

    /** v1 -> v2: add priority column (always/normal/low) to bills, todos, clips. */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE memory_bills ADD COLUMN priority TEXT NOT NULL DEFAULT 'normal'")
            connection.execSQL("ALTER TABLE memory_todos ADD COLUMN priority TEXT NOT NULL DEFAULT 'normal'")
            connection.execSQL("ALTER TABLE memory_clips ADD COLUMN priority TEXT NOT NULL DEFAULT 'normal'")
        }
    }

    fun getInstance(context: Context): MemoryDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            MemoryDatabase::class.java,
            "aether_memory.db",
        ).setDriver(BundledSQLiteDriver())
            .addMigrations(MIGRATION_1_2)
            .build()
            .also { instance = it }
    }
}