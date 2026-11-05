package com.platojobs.pkotlinapp.domain.sync

import com.platojobs.pkotlinapp.domain.household.HouseholdRole

/**
 * `change_log` 中非设备实体的结构变更（后端 v0.15.0 / openapi 0.11.0）。
 *
 * 只有显式识别的 entity 才会变成这里的类型；未知 entity 由解析层丢弃，
 * 游标照常按 `nextCursor` 前进，不会因为老客户端不认识的类型卡住同步。
 */
sealed interface StructuralChange {

    /** `entity=household`：家庭改名、本人角色变化、成员计数变化或整段删除。 */
    data class Household(
        val householdRemoteId: String,
        val name: String,
        val ownerUserId: String,
        /** 接收者（当前账号）在该家庭中的角色。 */
        val role: HouseholdRole,
        val memberCount: Int,
        val deleted: Boolean,
    ) : StructuralChange

    /** `entity=room`：房间新增/改名/重排或删除。 */
    data class Room(
        val roomRemoteId: String,
        val householdRemoteId: String,
        val name: String,
        val sortOrder: Int,
        val deleted: Boolean,
    ) : StructuralChange
}
