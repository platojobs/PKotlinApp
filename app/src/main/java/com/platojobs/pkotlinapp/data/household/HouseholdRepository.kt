package com.platojobs.pkotlinapp.data.household

import com.platojobs.pkotlinapp.data.local.DeviceDao
import com.platojobs.pkotlinapp.data.local.DeviceEntity
import com.platojobs.pkotlinapp.data.local.HouseholdDao
import com.platojobs.pkotlinapp.data.local.HouseholdEntity
import com.platojobs.pkotlinapp.data.local.RoomDao
import com.platojobs.pkotlinapp.data.local.RoomEntity
import com.platojobs.pkotlinapp.data.remote.ApiErrorCodes
import com.platojobs.pkotlinapp.data.sync.SyncPrefs
import com.platojobs.pkotlinapp.domain.household.Household
import com.platojobs.pkotlinapp.domain.household.HouseholdConflictException
import com.platojobs.pkotlinapp.domain.household.HouseholdErrorKind
import com.platojobs.pkotlinapp.domain.household.HouseholdInvitation
import com.platojobs.pkotlinapp.domain.household.HouseholdInvitationInfo
import com.platojobs.pkotlinapp.domain.household.HouseholdMember
import com.platojobs.pkotlinapp.domain.household.HouseholdNotFoundException
import com.platojobs.pkotlinapp.domain.household.HouseholdRemote
import com.platojobs.pkotlinapp.domain.household.HouseholdRole
import com.platojobs.pkotlinapp.domain.household.HouseholdSnapshot
import com.platojobs.pkotlinapp.domain.household.HouseholdSyncStatus
import com.platojobs.pkotlinapp.domain.household.HouseholdSyncer
import com.platojobs.pkotlinapp.domain.household.LocalRoomSnapshot
import com.platojobs.pkotlinapp.domain.household.RoomMergePlan
import com.platojobs.pkotlinapp.domain.household.RoomSetMismatchException
import com.platojobs.pkotlinapp.domain.household.chooseCurrentHousehold
import com.platojobs.pkotlinapp.domain.household.classifyHouseholdError
import com.platojobs.pkotlinapp.domain.household.planRoomMerge
import com.platojobs.pkotlinapp.domain.sync.StructuralChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 家庭仓库：服务端家庭是权威来源，本机房间/设备作为离线缓存。
 *
 * 关键约束：
 * - 首次接入不丢本机房间：按名称认领服务端同名房间，其余按本地顺序上传（[planRoomMerge]）；
 * - 设备房间归属只在 `PATCH /my/devices` 上报，不塞进 push，避免与 LWW 冲突；
 * - 上报失败保留 pending，网络类错误下次同步重试；4xx 权限/不存在会清掉 pending 并回读服务端快照；
 * - 家庭接口不可用时只标记「未同步」，不改动本机房间与设备；
 * - 成员移除/退出家庭后清理本地共享设备与归属；整段重排遇 409 重新拉取后抛
 *   [RoomSetMismatchException]（409 即机读判定，后端 v0.13.0 暂无 ROOM_SET_MISMATCH 码）。
 */
class HouseholdRepository(
    private val householdDao: HouseholdDao,
    private val roomDao: RoomDao,
    private val deviceDao: DeviceDao,
    private val remote: HouseholdRemote,
    private val prefs: SyncPrefs,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : HouseholdSyncer {

    private val mutex = Mutex()

    private val _currentRemoteId = MutableStateFlow(prefs.currentHouseholdRemoteId)
    private val _status = MutableStateFlow(HouseholdSyncStatus())
    val status: StateFlow<HouseholdSyncStatus> = _status

    val households: Flow<List<Household>> = householdDao.observeHouseholds().map { rows ->
        rows.map { it.toDomain() }
    }

    /** 当前家庭：优先持久化选择，失效时回落到个人家庭。 */
    val current: Flow<Household?> = combine(householdDao.observeHouseholds(), _currentRemoteId) {
        rows, preferred ->
        rows.firstOrNull { it.remoteId == preferred }
            ?: rows.firstOrNull { it.isPersonal }
            ?: rows.firstOrNull()
    }.map { it?.toDomain() }

    val currentHousehold: StateFlow<Household?> = current.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    override suspend fun onSync() {
        syncAll()
    }

    /**
     * 应用 change_log 家庭/房间结构变更（后端 v0.15.0）。
     *
     * 规则：
     * - 未知/无法解析的变化不会到达这里（解析层已跳过），游标照常前进；
     * - household 本地不存在（例如刚被邀请）→ 触发一次 `GET /households` 全量基线；
     * - household delete → 整段移除房间、共享设备、归属与本地家庭行，不逐房间删除；
     * - room upsert 与本地 pending 改名/重排冲突时，本地 pending 优先，
     *   服务端值落到 [RoomEntity.serverName] / [RoomEntity.serverSortOrder] 等下次合并；
     * - room delete → 只删房间并清空设备归属，设备本身保留。
     */
    override suspend fun applyStructural(changes: List<StructuralChange>) = mutex.withLock {
        var baselineRequired = false

        changes.filterIsInstance<StructuralChange.Household>().forEach { change ->
            if (change.deleted) {
                removeHouseholdLocally(change.householdRemoteId)
                return@forEach
            }
            val row = householdDao.findByRemoteId(change.householdRemoteId)
            if (row == null) {
                baselineRequired = true
            } else {
                householdDao.updateStructural(
                    id = row.id,
                    name = change.name,
                    role = change.role.name.lowercase(),
                    memberCount = change.memberCount,
                )
            }
        }

        changes.filterIsInstance<StructuralChange.Room>().forEach { change ->
            val household = householdDao.findByRemoteId(change.householdRemoteId)
            if (household == null) {
                // 房间先于家庭变更到达或本地没有基线：交给一次全量拉取补全。
                baselineRequired = true
                return@forEach
            }
            if (change.deleted) {
                roomDao.findByRemoteId(change.roomRemoteId)?.let { local ->
                    deviceDao.clearRoomAssignments(local.id)
                    roomDao.delete(local.id)
                }
                return@forEach
            }
            val local = roomDao.findByRemoteId(change.roomRemoteId)
            if (local == null) {
                roomDao.insert(
                    RoomEntity(
                        name = change.name,
                        sortOrder = change.sortOrder,
                        createdAt = clock(),
                        remoteId = change.roomRemoteId,
                        householdId = household.id,
                    ),
                )
            } else if (local.pendingEdit) {
                roomDao.stageServerValue(local.id, change.name, change.sortOrder)
            } else {
                roomDao.updateFromServer(local.id, change.name, change.sortOrder)
            }
        }

        // 后端不回放历史结构：缺家庭/缺房间时只能拉一次基线，增量补不回来。
        if (baselineRequired) runCatching { syncAllLocked() }
    }

    private suspend fun removeHouseholdLocally(remoteId: String) {
        val row = householdDao.findByRemoteId(remoteId) ?: return
        roomDao.deleteForHousehold(row.id)
        deviceDao.deleteSharedForHousehold(row.id)
        deviceDao.clearHouseholdAssignments(row.id)
        householdDao.delete(row.id)
        if (prefs.currentHouseholdRemoteId == remoteId) {
            prefs.currentHouseholdRemoteId = null
            _currentRemoteId.value = null
        }
    }

    override suspend fun onRelogin() = mutex.withLock {
        householdDao.clear()
        roomDao.clearRemoteLinks()
        deviceDao.clearHouseholdLinks()
        deviceDao.deleteShared()
        prefs.currentHouseholdRemoteId = null
        _currentRemoteId.value = null
        _status.value = HouseholdSyncStatus()
    }

    /**
     * 登录后/后台同步：拉家庭列表 → 合并房间 → 拉设备 → 补报待处理房间变更。
     * 离线时返回 false，但保留本机数据。
     */
    suspend fun syncAll(): Boolean = mutex.withLock { syncAllLocked() }

    private suspend fun syncAllLocked(): Boolean {
        val adoptOrphans = prefs.currentHouseholdRemoteId == null
        _status.value = _status.value.copy(syncing = true, lastError = null)
        return try {
            val snapshots = remote.listHouseholds()
            persistHouseholds(snapshots)
            val chosen = chooseCurrentHousehold(snapshots, prefs.currentHouseholdRemoteId)
            setCurrentRemoteId(chosen?.remoteId)
            val row = chosen?.let { householdDao.findByRemoteId(it.remoteId) } ?: return false

            if (adoptOrphans) roomDao.adoptOrphanRooms(row.id)
            mergeRooms(row)
            flushPendingRoomEdits(row)
            pullDevices(row)
            val patchError = flushPendingPatches(row)
            _status.value = HouseholdSyncStatus(
                synced = true,
                syncing = false,
                lastError = patchError?.let { classifyHouseholdError(it).name },
                lastSyncAt = clock(),
            )
            true
        } catch (e: Exception) {
            _status.value = _status.value.copy(
                syncing = false,
                synced = false,
                lastError = classifyHouseholdError(e).name,
            )
            false
        }
    }

    /** 切换家庭：只拉该家庭的房间与设备；失败时保留已缓存内容并标记未同步。 */
    suspend fun switchHousehold(remoteId: String) {
        mutex.withLock {
            setCurrentRemoteId(remoteId)
            val row = householdDao.findByRemoteId(remoteId) ?: return@withLock
            // 切换后房间来源应以服务端为准，先合并再拉设备。
            runCatching {
                mergeRooms(row)
                pullDevices(row)
            }
        }
    }

    suspend fun refreshHouseholds(): Boolean = mutex.withLock { refreshLocked() }

    /** 不加锁的家庭列表刷新，供已持锁的操作复用。 */
    private suspend fun refreshLocked(): Boolean {
        _status.value = _status.value.copy(syncing = true, lastError = null)
        return try {
            val snapshots = remote.listHouseholds()
            persistHouseholds(snapshots)
            val chosen = chooseCurrentHousehold(snapshots, prefs.currentHouseholdRemoteId)
            setCurrentRemoteId(chosen?.remoteId)
            _status.value = _status.value.copy(syncing = false, synced = true, lastSyncAt = clock())
            true
        } catch (e: Exception) {
            _status.value = _status.value.copy(
                syncing = false,
                synced = false,
                lastError = classifyHouseholdError(e).name,
            )
            false
        }
    }

    suspend fun createHousehold(name: String): Household = mutex.withLock {
        val snapshot = remote.createHousehold(name.trim())
        val row = upsertHousehold(snapshot, sortOrder = householdDao.getAll().size)
        setCurrentRemoteId(snapshot.remoteId)
        mergeRooms(row)
        pullDevices(row)
        row.toDomain()
    }

    suspend fun claimInvitation(code: String): Household = mutex.withLock {
        val snapshot = remote.claimInvitation(code)
        upsertHousehold(snapshot, sortOrder = householdDao.getAll().size)
        setCurrentRemoteId(snapshot.remoteId)
        val row = householdDao.findByRemoteId(snapshot.remoteId)
            ?: throw HouseholdNotFoundException()
        mergeRooms(row)
        pullDevices(row)
        row.toDomain()
    }

    suspend fun members(): List<HouseholdMember> {
        val current = currentRow() ?: return emptyList()
        return remote.members(current.remoteId)
    }

    suspend fun createInvitation(): HouseholdInvitation {
        val current = currentRow() ?: throw HouseholdNotFoundException()
        return remote.createInvitation(current.remoteId)
    }

    /** 邀请码管理列表：服务端只返回哈希前缀与状态，不含明文。 */
    suspend fun invitations(): List<HouseholdInvitationInfo> {
        val current = currentRow() ?: throw HouseholdNotFoundException()
        return remote.listInvitations(current.remoteId)
    }

    suspend fun revokeInvitation(invitationId: String) {
        val current = currentRow() ?: throw HouseholdNotFoundException()
        remote.revokeInvitation(current.remoteId, invitationId)
    }

    suspend fun renameHousehold(name: String): Household = mutex.withLock {
        val row = currentRow() ?: throw HouseholdNotFoundException()
        remote.renameHousehold(row.remoteId, name.trim())
        runCatching { refreshLocked() }
        currentRow()?.toDomain() ?: row.toDomain()
    }

    /**
     * owner 移除成员：服务端把该成员设备放回其个人家庭。
     * 本地随即刷新家庭计数并重拉设备——被移除成员的设备不再返回，共享行随之清理。
     */
    suspend fun removeMember(userId: String) = mutex.withLock {
        val row = currentRow() ?: throw HouseholdNotFoundException()
        remote.removeMember(row.remoteId, userId)
        runCatching { refreshLocked() }
        val refreshed = householdDao.findByRemoteId(row.remoteId) ?: return@withLock
        runCatching { pullDevices(refreshed) }
    }

    /**
     * 转让所有权：服务端把目标成员升为 owner，调用者降为 member。
     * 成功后刷新家庭与成员列表，能力矩阵随角色变化自动收起管理入口。
     */
    suspend fun transferOwnership(userId: String): Household = mutex.withLock {
        val row = currentRow() ?: throw HouseholdNotFoundException()
        remote.transferOwnership(row.remoteId, userId)
        runCatching { refreshLocked() }
        currentRow()?.toDomain() ?: row.toDomain()
    }

    /** 服务端是否支持转让所有权（老后端为 false，UI 直接隐藏入口）。 */
    fun supportsOwnershipTransfer(): Boolean = remote.supportsOwnershipTransfer()

    /**
     * 退出家庭：服务端删除成员关系（owner 且无其他成员时家庭本身被删除）。
     * 本地清掉该家庭的房间、设备归属与共享设备，再回落到个人家庭。
     */
    suspend fun leaveHousehold(): Household? = mutex.withLock {
        val row = currentRow() ?: throw HouseholdNotFoundException()
        remote.leave(row.remoteId)
        roomDao.deleteForHousehold(row.id)
        // 先删共享设备（依赖 householdId），再清归属，否则共享行会变成无主残留。
        deviceDao.deleteSharedForHousehold(row.id)
        deviceDao.clearHouseholdAssignments(row.id)
        householdDao.delete(row.id)
        if (prefs.currentHouseholdRemoteId == row.remoteId) {
            prefs.currentHouseholdRemoteId = null
            _currentRemoteId.value = null
        }
        runCatching { refreshLocked() }
        currentRow()?.toDomain()
    }

    /**
     * 整段重排：本地先落顺序（离线也保留），未上传的房间先补传，再按集合整体提交。
     *
     * 409 表示服务端房间集合已变化：立即重新拉取覆盖本地，并抛出 [RoomSetMismatchException]
     * 让 UI 提示「房间列表已变化，已刷新」。
     */
    suspend fun reorderRooms(orderedLocalIds: List<Long>): Boolean = mutex.withLock {
        val row = currentRow() ?: throw HouseholdNotFoundException()
        orderedLocalIds.forEachIndexed { index, localId ->
            roomDao.updateSortOrder(localId, index)
            // 本地顺序先落库；服务端确认前不接受结构变更覆盖。
            roomDao.markPendingEdit(localId)
        }
        if (roomDao.getRoomsForHousehold(row.id).any { it.remoteId == null }) {
            try {
                mergeRooms(row)
            } catch (e: Exception) {
                when (classifyHouseholdError(e)) {
                    HouseholdErrorKind.OFFLINE -> {
                        _status.value = _status.value.copy(
                            synced = false,
                            lastError = HouseholdErrorKind.OFFLINE.name,
                        )
                        return@withLock false
                    }

                    else -> throw e
                }
            }
        }
        val rooms = roomDao.getRoomsForHousehold(row.id)
        if (rooms.any { it.remoteId == null }) return@withLock false
        val remoteIds = rooms.sortedBy { it.sortOrder }.mapNotNull { it.remoteId }
        val ordered = try {
            remote.reorderRooms(row.remoteId, remoteIds)
        } catch (e: HouseholdConflictException) {
            // 集合不一致：服务端是权威来源，放弃本地 pending 后重新拉取。
            roomDao.getRoomsForHousehold(row.id)
                .filter { it.pendingEdit }
                .forEach { roomDao.clearPendingEdit(it.id) }
            runCatching { mergeRooms(row) }
            // v0.14.0 起 409 带 ROOM_SET_MISMATCH；更早版本为 CONFLICT，语义相同。
            if (e.code == null ||
                e.code == ApiErrorCodes.ROOM_SET_MISMATCH ||
                e.code == ApiErrorCodes.CONFLICT
            ) {
                throw RoomSetMismatchException()
            }
            throw e
        } catch (e: java.io.IOException) {
            // 离线：保留本地顺序，等下次同步再整段提交。
            _status.value = _status.value.copy(
                synced = false,
                lastError = HouseholdErrorKind.OFFLINE.name,
            )
            return@withLock false
        }
        ordered.forEach { serverRoom ->
            roomDao.findByRemoteId(serverRoom.remoteId)?.let { local ->
                // 服务端确认整段顺序：清除 pending，并采用服务端回读的名称/顺序。
                roomDao.clearPendingEditWithServer(local.id, serverRoom.name, serverRoom.sortOrder)
            }
        }
        true
    }

    /**
     * 新建房间：本机先落库保证离线可用；在线时立即上传。
     * member 无权限时删除本机临时行并抛出可读的 403。
     */
    suspend fun createRoom(name: String): Long {
        val current = currentRow()
        val nextSort = roomDao.getRoomsForHousehold(current?.id ?: -1)
            .maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
        val localId = roomDao.insert(
            RoomEntity(
                name = name.trim(),
                sortOrder = nextSort,
                createdAt = clock(),
                remoteId = null,
                householdId = current?.id,
            ),
        )
        if (current == null) return localId
        return try {
            val created = remote.createRoom(current.remoteId, name.trim(), nextSort)
            roomDao.linkRemote(localId, created.remoteId, current.id, created.sortOrder)
            localId
        } catch (e: Exception) {
            when (classifyHouseholdError(e)) {
                HouseholdErrorKind.OFFLINE -> {
                    _status.value = _status.value.copy(synced = false, lastError = HouseholdErrorKind.OFFLINE.name)
                    localId
                }

                HouseholdErrorKind.PERMISSION_DENIED,
                HouseholdErrorKind.NOT_FOUND,
                HouseholdErrorKind.SERVER_ERROR,
                -> {
                    roomDao.delete(localId)
                    throw e
                }

                else -> {
                    roomDao.delete(localId)
                    throw e
                }
            }
        }
    }

    /**
     * 改名：本地先落库并标记 pending，在线时立即上传。
     *
     * 离线时保留本地值等下次同步补传；4xx 权限/不存在回退到改名前的本地值并抛出，
     * 避免把「服务端拒绝的改名」长期留在列表里。
     */
    suspend fun renameRoom(localId: Long, name: String) {
        val room = roomDao.find(localId) ?: return
        val trimmed = name.trim()
        val current = currentRow()
        roomDao.rename(localId, trimmed)
        if (room.remoteId == null || current == null) return
        roomDao.markPendingEdit(localId)
        try {
            val server = remote.updateRoom(current.remoteId, room.remoteId, trimmed, null)
            roomDao.clearPendingEditWithServer(localId, server.name, server.sortOrder)
        } catch (e: Exception) {
            when (classifyHouseholdError(e)) {
                HouseholdErrorKind.OFFLINE -> {
                    // 本地 pending 保留，下一次同步补传。
                    _status.value = _status.value.copy(
                        synced = false,
                        lastError = HouseholdErrorKind.OFFLINE.name,
                    )
                }

                else -> {
                    roomDao.clearPendingEdit(localId)
                    roomDao.rename(localId, room.name)
                    throw e
                }
            }
        }
    }

    suspend fun deleteRoom(localId: Long) {
        val room = roomDao.find(localId)
        val current = currentRow()
        if (room?.remoteId != null && current != null) {
            remote.deleteRoom(current.remoteId, room.remoteId)
        }
        deviceDao.clearRoomAssignments(localId)
        roomDao.delete(localId)
    }

    /**
     * 设备移入/移出房间：本地乐观更新后 PATCH 服务端。
     * 网络失败保留 pending；403/404 清除 pending 并回读服务端，避免长期重试无意义请求。
     */
    suspend fun moveDeviceToRoom(mac: String, roomLocalId: Long?) {
        val device = deviceDao.find(mac) ?: return
        if (device.shared) throw HouseholdNotFoundException()
        val current = currentRow()
        val room = if (roomLocalId != null) roomDao.find(roomLocalId) else null
        deviceDao.queueRoomAssignment(
            mac = mac,
            roomId = room?.id,
            remoteRoomId = room?.remoteId,
            pendingLocalId = room?.id,
            householdRemoteId = if (room != null) current?.remoteId else null,
        )
        val error = flushPendingPatches(current)
        if (error != null) throw error
    }

    private suspend fun mergeRooms(row: HouseholdEntity) {
        val local = roomDao.getRoomsForHousehold(row.id)
        val snapshots = local.map {
            LocalRoomSnapshot(it.id, it.name, it.sortOrder, it.remoteId)
        }
        val remoteRooms = remote.listRooms(row.remoteId)
        val plan: RoomMergePlan = planRoomMerge(snapshots, remoteRooms)

        plan.update.forEach { (localId, serverRoom) ->
            val local = roomDao.find(localId)
            if (local?.pendingEdit == true) {
                // 本地还没上传的改名/重排优先；服务端值暂存，等本地提交后再合并。
                roomDao.stageServerValue(localId, serverRoom.name, serverRoom.sortOrder)
            } else {
                roomDao.updateFromServer(localId, serverRoom.name, serverRoom.sortOrder)
            }
        }
        plan.adopt.forEach { (localId, serverRoom) ->
            val local = roomDao.find(localId)
            roomDao.linkRemote(localId, serverRoom.remoteId, row.id, serverRoom.sortOrder)
            if (local?.pendingEdit == true) {
                // linkRemote 会用服务端顺序覆盖本地；pending 房间要恢复本地顺序并暂存。
                roomDao.updateSortOrder(localId, local.sortOrder)
                roomDao.stageServerValue(localId, serverRoom.name, serverRoom.sortOrder)
            }
        }
        plan.push.sortedBy { it.sortOrder }.forEach { localRoom ->
            val created = remote.createRoom(row.remoteId, localRoom.name, localRoom.sortOrder)
            roomDao.linkRemote(localRoom.localId, created.remoteId, row.id, created.sortOrder)
            // 服务端已确认建房；本地 pending 标记与暂存值同时清掉。
            roomDao.clearPendingEditWithServer(
                localRoom.localId,
                created.name,
                created.sortOrder,
            )
        }
        plan.insert.forEach { serverRoom ->
            if (roomDao.findByRemoteId(serverRoom.remoteId) == null) {
                roomDao.insert(
                    RoomEntity(
                        name = serverRoom.name,
                        sortOrder = serverRoom.sortOrder,
                        createdAt = serverRoom.createdAt,
                        remoteId = serverRoom.remoteId,
                        householdId = row.id,
                    ),
                )
            }
        }
        // 服务端已删除的远端房间：本机同步删除，房间内设备回到未分配。
        plan.remove.forEach { localId ->
            deviceDao.clearRoomAssignments(localId)
            roomDao.delete(localId)
        }
    }

    /**
     * 补传本地 pending 的房间改名/排序。
     * 离线保留 pending；4xx 清除 pending，让下一次 merge 回读服务端真值。
     */
    private suspend fun flushPendingRoomEdits(row: HouseholdEntity) {
        roomDao.getRoomsForHousehold(row.id)
            .filter { it.pendingEdit && it.remoteId != null }
            .forEach { room ->
                try {
                    val server = remote.updateRoom(
                        remoteId = row.remoteId,
                        roomRemoteId = room.remoteId.orEmpty(),
                        name = room.name,
                        sortOrder = room.sortOrder,
                    )
                    roomDao.clearPendingEditWithServer(room.id, server.name, server.sortOrder)
                } catch (e: Exception) {
                    when (classifyHouseholdError(e)) {
                        HouseholdErrorKind.OFFLINE -> Unit
                        else -> roomDao.clearPendingEdit(room.id)
                    }
                }
            }
    }

    private suspend fun pullDevices(row: HouseholdEntity) {
        val remoteRooms = roomDao.getRoomsForHousehold(row.id)
            .mapNotNull { room -> room.remoteId?.let { it to room.id } }
            .toMap()
        val localDevices = deviceDao.getAllDevices()
        val localByKey = localDevices.associateBy { keyOf(it.platform, it.externalId) }
        val seenSharedMacs = mutableSetOf<String>()

        remote.listDevices().forEach { ref ->
            if (ref.householdRemoteId != null && ref.householdRemoteId != row.remoteId) return@forEach
            if (ref.householdRemoteId == null && !row.isPersonal) return@forEach
            val roomLocalId = ref.roomRemoteId?.let { remoteRooms[it] }
            val existing = localByKey[keyOf(ref.platform, ref.externalId)]
            if (existing == null) {
                // 其他家庭成员共享的设备：以真实服务端数据展示，只读且不参与 push。
                val mac = sharedMac(ref.platform, ref.externalId)
                seenSharedMacs += mac
                val timestamp = ref.updatedAt.takeIf { it > 0 } ?: clock()
                deviceDao.insertOrUpdate(
                    DeviceEntity(
                        mac = mac,
                        name = ref.alias ?: "共享设备",
                        lastRssi = 0,
                        lastSeenTimestamp = timestamp,
                        id = ref.externalId,
                        platform = ref.platform,
                        externalId = ref.externalId,
                        updatedAt = ref.updatedAt,
                        deletedAt = null,
                        syncState = "synced",
                        createdAt = timestamp,
                        householdId = row.id,
                        remoteRoomId = ref.roomRemoteId,
                        roomId = roomLocalId,
                        shared = true,
                    ),
                )
            } else {
                deviceDao.applyServerMeta(existing.mac, row.id, ref.roomRemoteId, roomLocalId)
            }
        }
        // 服务端已不再返回的共享设备（被移除/退出家庭）同步清理，避免列表残留。
        val staleShared = deviceDao.sharedDevicesForHousehold(row.id)
            .filter { it.mac !in seenSharedMacs }
            .map { it.mac }
        if (staleShared.isNotEmpty()) deviceDao.deleteByMacs(staleShared)
    }

    private suspend fun flushPendingPatches(row: HouseholdEntity?): Throwable? {
        var firstError: Throwable? = null
        deviceDao.pendingRoomPatches().forEach { device ->
            if (device.shared) {
                deviceDao.finishRoomPatch(device.mac)
                return@forEach
            }
            val targetLocalId = device.pendingRoomLocalId
            val targetRoom = if (targetLocalId != null) roomDao.find(targetLocalId) else null
            if (targetLocalId != null && targetRoom == null) {
                // 房间已被删除：把设备回到未分配。
            } else if (targetRoom?.remoteId == null && targetLocalId != null) {
                // 房间尚未上传成功，保留 pending 等下一轮。
                return@forEach
            }
            val roomRemoteId = if (targetLocalId == null) "" else targetRoom?.remoteId.orEmpty()
            val householdRemoteId =
                if (roomRemoteId.isEmpty()) null else device.pendingHouseholdRemoteId ?: row?.remoteId
            try {
                remote.patchDevice(
                    platform = device.platform,
                    externalId = device.externalId,
                    roomRemoteId = roomRemoteId,
                    householdRemoteId = householdRemoteId,
                )
                deviceDao.finishRoomPatch(device.mac)
            } catch (e: Exception) {
                firstError = firstError ?: e
                when (classifyHouseholdError(e)) {
                    HouseholdErrorKind.PERMISSION_DENIED,
                    HouseholdErrorKind.NOT_FOUND,
                    HouseholdErrorKind.INVALID_CODE,
                    HouseholdErrorKind.RATE_LIMITED,
                    HouseholdErrorKind.CONFLICT,
                    HouseholdErrorKind.SERVER_ERROR,
                    -> {
                        deviceDao.finishRoomPatch(device.mac)
                        // 回读服务端真值，撤销被拒绝的本地乐观更新。
                        runCatching { if (row != null) pullDevices(row) }
                    }

                    HouseholdErrorKind.OFFLINE -> Unit
                }
            }
        }
        return firstError
    }

    private suspend fun currentRow(): HouseholdEntity? {
        val preferred = prefs.currentHouseholdRemoteId
        return householdDao.findByRemoteId(preferred.orEmpty())
            ?: householdDao.getAll().firstOrNull { it.isPersonal }
            ?: householdDao.getAll().firstOrNull()
    }

    private suspend fun setCurrentRemoteId(remoteId: String?) {
        if (remoteId != null) {
            prefs.currentHouseholdRemoteId = remoteId
            _currentRemoteId.value = remoteId
        }
    }

    private suspend fun persistHouseholds(snapshots: List<HouseholdSnapshot>) {
        // 空列表通常意味着服务端异常或权限异常，保留本机数据而不是清空家庭与房间。
        if (snapshots.isEmpty()) return
        val existing = householdDao.getAll()
        val remoteIds = snapshots.map { it.remoteId }
        val removed = existing.filter { it.remoteId !in remoteIds }
        removed.forEach { row ->
            roomDao.deleteForHousehold(row.id)
            deviceDao.deleteSharedForHousehold(row.id)
            deviceDao.clearHouseholdAssignments(row.id)
            householdDao.delete(row.id)
        }
        snapshots.forEachIndexed { index, snapshot ->
            val row = householdDao.findByRemoteId(snapshot.remoteId)
            if (row == null) {
                upsertHousehold(snapshot, sortOrder = index)
            } else {
                householdDao.update(
                    id = row.id,
                    name = snapshot.name,
                    isPersonal = snapshot.isPersonal,
                    role = snapshot.role.name.lowercase(),
                    memberCount = snapshot.memberCount,
                    deviceCount = snapshot.deviceCount,
                    roomCount = snapshot.roomCount,
                    sortOrder = index,
                    createdAt = snapshot.createdAt.takeIf { it > 0 } ?: row.createdAt,
                )
            }
        }
        if (removed.isNotEmpty()) {
            val current = prefs.currentHouseholdRemoteId
            if (current != null && current !in remoteIds) _currentRemoteId.value = null
        }
    }

    private suspend fun upsertHousehold(
        snapshot: HouseholdSnapshot,
        sortOrder: Int,
    ): HouseholdEntity {
        householdDao.findByRemoteId(snapshot.remoteId)?.let { return it }
        val id = householdDao.insert(
            HouseholdEntity(
                remoteId = snapshot.remoteId,
                name = snapshot.name,
                isPersonal = snapshot.isPersonal,
                role = snapshot.role.name.lowercase(),
                memberCount = snapshot.memberCount,
                deviceCount = snapshot.deviceCount,
                roomCount = snapshot.roomCount,
                sortOrder = sortOrder,
                createdAt = snapshot.createdAt,
            ),
        )
        return householdDao.findByRemoteId(snapshot.remoteId)
            ?: HouseholdEntity(
                id = id,
                remoteId = snapshot.remoteId,
                name = snapshot.name,
                isPersonal = snapshot.isPersonal,
                role = snapshot.role.name.lowercase(),
                memberCount = snapshot.memberCount,
                deviceCount = snapshot.deviceCount,
                roomCount = snapshot.roomCount,
                sortOrder = sortOrder,
                createdAt = snapshot.createdAt,
            )
    }

    private fun HouseholdEntity.toDomain() = Household(
        localId = id,
        remoteId = remoteId,
        name = name,
        isPersonal = isPersonal,
        role = HouseholdRole.parse(role),
        memberCount = memberCount,
        deviceCount = deviceCount,
        roomCount = roomCount,
        sortOrder = sortOrder,
        createdAt = createdAt,
    )

    private fun keyOf(platform: String, externalId: String) =
        "${platform.lowercase()}|$externalId"

    private fun sharedMac(platform: String, externalId: String) =
        "shared:${platform.lowercase()}:$externalId"
}
