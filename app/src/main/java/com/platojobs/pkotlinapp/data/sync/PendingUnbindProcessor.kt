package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.local.DeviceDao
import com.platojobs.pkotlinapp.domain.repository.DeviceUnbindRepository

/**
 * 待解绑队列：同步前先补做未完成的解绑。
 *
 * - 成功（服务端 204，或 404 视为已不存在）后物理删除本地行；
 * - 失败保留 `pendingDelete`，交给 WorkManager 在联网后按退避重试；
 * - 待解绑行不进入 `/sync/push`（由 [DeviceDao.pendingDevices] 过滤）。
 *
 * 任一设备失败都会把首个异常抛给上层，让本次同步标记失败并重试；
 * 已成功的行不会被回滚，下次只会重试剩下的。
 */
class PendingUnbindProcessor(
    private val dao: DeviceDao,
    private val unbind: DeviceUnbindRepository,
) {

    /** @return 本次成功解绑并删除的行数。失败抛异常，交上层决定重试。 */
    suspend fun process(): Int {
        val rows = dao.pendingDeletes()
        if (rows.isEmpty()) return 0
        var removed = 0
        var failure: Throwable? = null
        for (row in rows) {
            try {
                unbind.deleteOwnedDevice(
                    platform = row.platform,
                    externalId = row.externalId.ifBlank { row.mac },
                )
                dao.delete(row.mac)
                removed += 1
            } catch (e: Throwable) {
                // 单台失败不阻断其它设备；保留 pendingDelete 等下次重试。
                if (failure == null) failure = e
            }
        }
        failure?.let { throw it }
        return removed
    }
}
