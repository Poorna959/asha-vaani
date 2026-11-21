package com.ashavaani.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CustomRegisterEntity::class,
        PatientEntity::class,
        VisitEntity::class,
        FollowUpEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AshaDatabase : RoomDatabase() {
    abstract fun ashaDao(): AshaDao

    companion object {
        private const val DB_NAME = "asha_vaani_local_cache.db"

        @Volatile
        private var INSTANCE: AshaDatabase? = null

        fun getInstance(context: Context): AshaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AshaDatabase::class.java,
                    DB_NAME,
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
