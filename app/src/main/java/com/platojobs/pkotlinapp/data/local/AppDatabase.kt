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
    val V7_TO_V8 = listOf(
        // 首次添加时间；历史数据用最后出现时间回填，避免所有旧设备集中在 1970。
        "ALTER TABLE devices ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0",
        "UPDATE devices SET createdAt = lastSeenTimestamp WHERE createdAt = 0",
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

/** v9 -> v10 的指标阈值规则；旧规则保留 RSSI 语义并回填比较符/阈值副本。 */
internal object RuleMigrationSql {
    val V9_TO_V10 = listOf(
        "ALTER TABLE rules ADD COLUMN metric TEXT",
        "ALTER TABLE rules ADD COLUMN comparator TEXT NOT NULL DEFAULT '>'",
        "ALTER TABLE rules ADD COLUMN thresholdValue REAL NOT NULL DEFAULT 0",
        "ALTER TABLE rules ADD COLUMN consecutive INTEGER NOT NULL DEFAULT 1",
        // 旧规则仍是 LOW_RSSI（type 不变），这里只把语义字段回填成 `< 阈值`，不放宽触发条件。
        "UPDATE rules SET comparator = '<', thresholdValue = threshold WHERE type = 'LOW_RSSI'",
        "ALTER TABLE rule_events ADD COLUMN metric TEXT",
        "ALTER TABLE rule_events ADD COLUMN value REAL",
        "ALTER TABLE rule_events ADD COLUMN thresholdValue REAL",
        "ALTER TABLE rule_events ADD COLUMN unit TEXT",
    )
}

/** v8 -> v9 的本地场景表；SQL 与 Room 导出的 9.json 保持一致。 */
internal object SceneMigrationSql {
    val V8_TO_V9 = listOf(
        """
        CREATE TABLE IF NOT EXISTS `scenes` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `scene_actions` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `sceneId` INTEGER NOT NULL,
            `position` INTEGER NOT NULL,
            `type` TEXT NOT NULL,
            `deviceMac` TEXT,
            `muteMinutes` INTEGER,
            FOREIGN KEY(`sceneId`) REFERENCES `scenes`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_scene_actions_sceneId` " +
            "ON `scene_actions` (`sceneId`)",
    )
}

/** v10 -> v11 的本地房间；devices 增加可空 roomId，旧设备保持未分配。 */
internal object RoomMigrationSql {
    val V10_TO_V11 = listOf(
        """
        CREATE TABLE IF NOT EXISTS `rooms` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `sortOrder` INTEGER NOT NULL DEFAULT 0,
            `createdAt` INTEGER NOT NULL
        )
        """.trimIndent(),
        "ALTER TABLE devices ADD COLUMN roomId INTEGER",
    )
}

/** v11 -> v12 的家庭接入：新增 households 表，房间/设备增加远端家庭与房间字段。 */
internal object HouseholdMigrationSql {
    val V11_TO_V12 = listOf(
        """
        CREATE TABLE IF NOT EXISTS `households` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `remoteId` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `isPersonal` INTEGER NOT NULL DEFAULT 0,
            `role` TEXT NOT NULL,
            `memberCount` INTEGER NOT NULL DEFAULT 0,
            `deviceCount` INTEGER NOT NULL DEFAULT 0,
            `sortOrder` INTEGER NOT NULL DEFAULT 0,
            `createdAt` INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_households_remoteId` " +
            "ON `households` (`remoteId`)",
        // 房间：remoteId 为空表示仅本机、待上传；householdId 为空表示旧数据，首次家庭同步归入当前家庭。
        "ALTER TABLE rooms ADD COLUMN remoteId TEXT",
        "ALTER TABLE rooms ADD COLUMN householdId INTEGER",
        // 设备：家庭/远端房间归属，以及房间变更待上报标记。
        "ALTER TABLE devices ADD COLUMN householdId INTEGER",
        "ALTER TABLE devices ADD COLUMN remoteRoomId TEXT",
        "ALTER TABLE devices ADD COLUMN pendingRoomPatch INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE devices ADD COLUMN pendingRoomLocalId INTEGER",
        "ALTER TABLE devices ADD COLUMN pendingHouseholdRemoteId TEXT",
        "ALTER TABLE devices ADD COLUMN shared INTEGER NOT NULL DEFAULT 0",
    )
}

/** v12 -> v13：家庭卡片使用服务端统计的房间数。 */
internal object HouseholdCountsMigrationSql {
    val V12_TO_V13 = listOf(
        "ALTER TABLE households ADD COLUMN roomCount INTEGER NOT NULL DEFAULT 0",
    )
}

/** v13 -> v14：本地反馈草稿队列。 */
internal object FeedbackMigrationSql {
    val V13_TO_V14 = listOf(
        """
        CREATE TABLE IF NOT EXISTS `feedback_drafts` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `category` TEXT NOT NULL,
            `description` TEXT NOT NULL,
            `contact` TEXT,
            `createdAt` INTEGER NOT NULL,
            `state` TEXT NOT NULL DEFAULT 'pending'
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_feedback_drafts_state` " +
            "ON `feedback_drafts` (`state`)",
    )
}

/** v14 -> v15：房间接入 change_log 结构增量同步，新增本地 pending/服务端暂存列。 */
internal object RoomStructuralSyncMigrationSql {
    val V14_TO_V15 = listOf(
        "ALTER TABLE rooms ADD COLUMN pendingEdit INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE rooms ADD COLUMN serverName TEXT",
        "ALTER TABLE rooms ADD COLUMN serverSortOrder INTEGER",
    )
}

/** v15 -> v16：场景接入规则引擎，新增触发规则绑定、定时间隔、启停与执行记录表。 */
internal object SceneEngineMigrationSql {
    val V15_TO_V16 = listOf(
        // 场景触发规则写入 rules 表，由 RuleEngine 判定；sceneId 为空仍是独立自动化规则。
        "ALTER TABLE rules ADD COLUMN sceneId INTEGER",
        // 定时规则间隔（分钟）。
        "ALTER TABLE rules ADD COLUMN intervalMinutes INTEGER",
        // 场景停用后自动触发不再执行，历史场景默认启用。
        "ALTER TABLE scenes ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1",
        // 场景「标记设备」动作的真实落点。
        "ALTER TABLE devices ADD COLUMN markedAt INTEGER",
        """
        CREATE TABLE IF NOT EXISTS `scene_runs` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `sceneId` INTEGER NOT NULL,
            `sceneName` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `triggerType` TEXT NOT NULL,
            `triggerValue` TEXT NOT NULL,
            `successCount` INTEGER NOT NULL,
            `failureCount` INTEGER NOT NULL,
            `failureReason` TEXT,
            `detail` TEXT NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_scene_runs_timestamp` " +
            "ON `scene_runs` (`timestamp`)",
    )
}

@Database(
    entities = [
        DeviceEntity::class,
        RuleEntity::class,
        RuleEventEntity::class,
        ScanLogEntity::class,
        ReadingEntity::class,
        SceneEntity::class,
        SceneActionEntity::class,
        SceneRunEntity::class,
        RoomEntity::class,
        HouseholdEntity::class,
        FeedbackDraftEntity::class,
    ],
    version = 16,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun deviceDao(): DeviceDao

    abstract fun ruleDao(): RuleDao

    abstract fun ruleEventDao(): RuleEventDao

    abstract fun scanLogDao(): ScanLogDao

    abstract fun readingDao(): ReadingDao

    abstract fun sceneDao(): SceneDao

    abstract fun sceneRunDao(): SceneRunDao

    abstract fun roomDao(): RoomDao

    abstract fun householdDao(): HouseholdDao

    abstract fun feedbackDao(): FeedbackDao

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

        /** v7 -> v8：新增首次添加时间，历史设备用最近出现时间回填。 */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                DeviceMigrationSql.V7_TO_V8.forEach(db::execSQL)
            }
        }

        /** v8 -> v9：新增仅本机的场景与场景动作表（不参与云端同步）。 */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SceneMigrationSql.V8_TO_V9.forEach(db::execSQL)
            }
        }

        /**
         * v9 -> v10：规则增加指标阈值字段与命中证据列。
         * 旧 RSSI 规则的 type/threshold 原样保留，只回填比较符与阈值副本，升级后仍能触发。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                RuleMigrationSql.V9_TO_V10.forEach(db::execSQL)
            }
        }

        /**
         * v10 -> v11：新增本机房间表与设备归属列。
         * 旧设备 roomId 为 null（未分配），deviceGroup 原样保留作为兼容字段。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                RoomMigrationSql.V10_TO_V11.forEach(db::execSQL)
            }
        }

        /**
         * v11 -> v12：接入服务端家庭。已有房间与设备归属原样保留（roomId 不动），
         * 新列默认为空，等待首次 GET /households 后把本机房间合并进当前家庭。
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                HouseholdMigrationSql.V11_TO_V12.forEach(db::execSQL)
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                HouseholdCountsMigrationSql.V12_TO_V13.forEach(db::execSQL)
            }
        }

        /** v13 -> v14：新增本地反馈草稿表，不动任何既有业务表。 */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                FeedbackMigrationSql.V13_TO_V14.forEach(db::execSQL)
            }
        }

        /** v14 -> v15：房间 pending/暂存列；既有房间默认无 pending、无暂存值。 */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                RoomStructuralSyncMigrationSql.V14_TO_V15.forEach(db::execSQL)
            }
        }

        /** v15 -> v16：场景触发规则、定时间隔、启停与执行记录；既有场景默认启用且无触发规则。 */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SceneEngineMigrationSql.V15_TO_V16.forEach(db::execSQL)
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
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                )
                    .build()
                    .also { instance = it }
            }
    }
}
