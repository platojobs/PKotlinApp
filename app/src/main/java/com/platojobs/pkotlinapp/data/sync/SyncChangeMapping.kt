package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.remote.SyncChangeDto
import com.platojobs.pkotlinapp.domain.household.HouseholdRole
import com.platojobs.pkotlinapp.domain.sync.StructuralChange
import com.platojobs.pkotlinapp.domain.sync.SyncRecord

/**
 * 一条 `change_log` 行解析后的结果：设备变更与结构变更二选一。
 * 未知 entity 返回 null，调用方直接跳过但继续推进游标。
 */
internal sealed interface SyncChangeEntry {
    data class Device(val record: SyncRecord) : SyncChangeEntry

    data class Structure(val change: StructuralChange) : SyncChangeEntry
}

internal const val ENTITY_DEVICE = "user_device"
internal const val ENTITY_HOUSEHOLD = "household"
internal const val ENTITY_ROOM = "room"

/**
 * 按 entity 显式分派，而不是「能解析成设备就当设备」：
 * household/room payload 与设备字段有重叠，误判会写入假的设备墓碑。
 */
internal fun SyncChangeDto.toSyncChangeEntry(): SyncChangeEntry? = when (entity?.trim()?.lowercase()) {
    ENTITY_DEVICE -> toDomain()?.let { SyncChangeEntry.Device(it) }

    ENTITY_HOUSEHOLD -> payload?.let { body ->
        val id = entityId?.takeIf { it.isNotBlank() } ?: body.id?.takeIf { it.isNotBlank() }
            ?: return null
        SyncChangeEntry.Structure(
            StructuralChange.Household(
                householdRemoteId = id,
                name = body.name.orEmpty(),
                ownerUserId = body.ownerUserId.orEmpty(),
                role = HouseholdRole.parse(body.role),
                memberCount = body.memberCount ?: 0,
                deleted = body.deleted == true || op == "delete",
            ),
        )
    }

    ENTITY_ROOM -> payload?.let { body ->
        val id = entityId?.takeIf { it.isNotBlank() } ?: body.id?.takeIf { it.isNotBlank() }
            ?: return null
        val householdId = body.householdId?.takeIf { it.isNotBlank() } ?: return null
        SyncChangeEntry.Structure(
            StructuralChange.Room(
                roomRemoteId = id,
                householdRemoteId = householdId,
                name = body.name.orEmpty(),
                sortOrder = body.sortOrder ?: 0,
                deleted = body.deleted == true || op == "delete",
            ),
        )
    }

    // 未知 entity：忽略并继续推进游标（硬约束）。
    else -> null
}
