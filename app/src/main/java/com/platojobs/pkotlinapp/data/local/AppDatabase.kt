package com.platojobs.pkotlinapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/** 迁移 SQL 单独暴露，便于 JVM 用真实 SQLite 验证迁移不丢数据。 */
internal object DeviceMigrationSql {
    val V3_TO_V4 = listOf(
        "ALTER TABLE devices ADD COLUMN deviceTypeName TEXT",
        // group 是 SQL 关键字，列名使用 deviceGroup。
        "ALTER TABLE devices ADD COLUMN deviceGroup TEXT",
    )
    val V4_TO_V5 = listOf(
        // 广播名与用户别名 name 独立：扫描只写 advertisedName。
        "ALTER TABLE devices ADD COLUMN advertisedName TEXT",
    )
    val V5_TO_V6 = listOf(
        // 用户显式编辑别名的标记：扫描不置位，清空别名也能上报。
        "ALTER TABLE devices ADD COLUMN aliasDirty INTEGER NOT NULL DEFAULT 0",
    )
}

/** v6 -> v7 的读数表；SQL 与 Room 导出的 7.json 保持一致。 */
internal object ReadingMigrationSql {
    val V6_TO_V7 = listOf(
        """
        CREATE TABLE IF NOT EXISTS `readings` (
            `id` TEXT NOT NULL,
            `deviceId` TEXT NOT NULL,
            `metric` TEXT NOT NULL,
            `value` REAL NOT NULL,
            `unit` TEXT NOT NULL,
            `recordedAt` INTEGER NOT NULL,
            `syncState` TEXT NOT NULL DEFAULT 'pending',
            `rawSnapshot` TEXT,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`deviceId`) REFERENCES `devices`(`mac`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_readings_deviceId` ON `readings` (`deviceId`)",
        "CREATE INDEX IF NOT EXISTS `index_readings_syncState` ON `readings` (`syncState`)",
        "CREATE INDEX IF NOT EXISTS `index_readings_deviceId_metric_recordedAt` " +
            "ON `readings` (`deviceId`, `metric`, `recordedAt`)",
    )
}

@Database(
    entities = [
        DeviceEntity::class,
        RuleEntity::class,
        RuleEventEntity::class,
        ScanLogEntity::class,
        ReadingEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun deviceDao(): DeviceDao

    abstract fun ruleDao(): RuleDao

    abstract fun ruleEventDao(): RuleEventDao

    abstract fun scanLogDao(): ScanLogDao

    abstract fun readingDao(): ReadingDao

    companion object {
        private const val DB_NAME = "device_dashboard.db"

        /**
         * v1 -> v2：为设备增加多端同步字段，并保留全部历史数据。
         * 已有记录回填 client UUID、platform=android、externalId=规范化大写 MAC，
         * 标记为 pending，等待首次同步。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE devices ADD COLUMN platform TEXT NOT NULL DEFAULT 'android'")
                db.execSQL("ALTER TABLE devices ADD COLUMN externalId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE devices ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE devices ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE devices ADD COLUMN syncState TEXT NOT NULL DEFAULT 'pending'")

                val macs = mutableListOf<String>()
                db.query("SELECT mac FROM devices").use { cursor ->
                    while (cursor.moveToNext()) {
                        macs += cursor.getString(0)
                    }
                }
                macs.forEach { mac ->
                    db.execSQL(
                        "UPDATE devices SET id = ?, externalId = UPPER(?), " +
                            "updatedAt = lastSeenTimestamp, platform = 'android', " +
                            "syncState = 'pending', deletedAt = NULL WHERE mac = ?",
                        arrayOf(UUID.randomUUID().toString(), mac, mac),
                    )
                }
            }
        }

        /**
         * v2 -> v3：缓存后端 0.3.2 的设备 enrich 字段，用于断网降级展示。
         * 全部为可空列，历史数据保持 null（表示未注册型号）。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN deviceType TEXT")
                db.execSQL("ALTER TABLE devices ADD COLUMN vendor TEXT")
                db.execSQL("ALTER TABLE devices ADD COLUMN firmwareVersion TEXT")
                db.execSQL("ALTER TABLE devices ADD COLUMN compatibility TEXT")
            }
        }

        /**
         * v3 -> v4：缓存 catalog 展示名与云端分组，供离线降级展示。
         * 全部为可空列，历史数据保持 null（表示只有型号键 / 尚未拿到分组）。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                DeviceMigrationSql.V3_TO_V4.forEach(db::execSQL)
            }
        }

        /**
         * v4 -> v5：新增广播名缓存，与用户别名 `name` 独立。
         * 历史数据保持 null（表示尚未扫描到广播名），由展示层回退别名/型号。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                DeviceMigrationSql.V4_TO_V5.forEach(db::execSQL)
            }
        }

        /**
         * v5 -> v6：新增「用户显式编辑过别名」标记。
         * 历史行默认 0（扫描/服务端来源），首次在详情页保存后置 1。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                DeviceMigrationSql.V5_TO_V6.forEach(db::execSQL)
            }
        }

        /** v6 -> v7：新增读数表（外键 devices.mac，7 天本地保留由仓库层清理）。 */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ReadingMigrationSql.V6_TO_V7.forEach(db::execSQL)
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                    .build()
                    .also { instance = it }
            }
    }
}
