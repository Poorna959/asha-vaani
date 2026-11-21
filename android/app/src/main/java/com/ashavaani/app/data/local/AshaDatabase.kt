package com.ashavaani.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CustomRegisterEntity::class,
        PatientEntity::class,
        VisitEntity::class,
        FollowUpEntity::class,
        PendingSyncEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AshaDatabase : RoomDatabase() {
    abstract fun ashaDao(): AshaDao

    companion object {
        private const val DB_NAME = "asha_vaani_local_cache.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_sync_outbox` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `opType` TEXT NOT NULL,
                        `targetId` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_sync_outbox_createdAt` ON `pending_sync_outbox` (`createdAt`)"
                )
            }
        }

        @Volatile
        private var INSTANCE: AshaDatabase? = null

        fun getInstance(context: Context): AshaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AshaDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
