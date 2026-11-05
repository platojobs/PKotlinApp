package com.platojobs.pkotlinapp.domain.sync

/** 同步状态（持久化在本地，非敏感信息）。 */
data class SyncStatus(
    val lastSyncAt: Long? = null,
    val pendingCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastConflictCount: Int = 0,
    val localOnly: Boolean = false,
    /** 401：登录失效，同步已暂停，等待用户确认后重新登录。 */
    val authExpired: Boolean = false,
    val lastError: String? = null,
    /**
     * 最近一次 410 `RESYNC_REQUIRED` 触发全量重同步的时间。
     * 非空时「我的」展示「云端变更记录已过期，已重新全量同步」，不静默处理。
     */
    val resyncedAt: Long? = null,
)
