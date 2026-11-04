package com.platojobs.pkotlinapp.domain.sync

/** 同步状态（持久化在本地，非敏感信息）。 */
data class SyncStatus(
    val lastSyncAt: Long? = null,
    val pendingCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastConflictCount: Int = 0,
    val localOnly: Boolean = false,
    val lastError: String? = null,
)
