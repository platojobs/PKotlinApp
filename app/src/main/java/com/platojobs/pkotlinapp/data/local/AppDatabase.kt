package com.platojobs.pkotlinapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DeviceEntity::class,
        RuleEntity::class,
        RuleEventEntity::class,
        ScanLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun deviceDao(): DeviceDao

    abstract fun ruleDao(): RuleDao

    abstract fun ruleEventDao(): RuleEventDao

    abstract fun scanLogDao(): ScanLogDao

    companion object {
        private const val DB_NAME = "device_dashboard.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).build().also { instance = it }
            }
    }
}
