package com.platojobs.pkotlinapp.domain.sync

/**
 * 服务端变更日志已过保留期（410 `RESYNC_REQUIRED`）。
 *
 * 处理原则：
 * - 清空本地游标，改用 `cursor=0` 全量重拉一次；
 * - 本地 pending 记录不删除，合并后继续上传；
 * - 这是终态分支，不进指数退避，也不等同于 401（重登）或 503（重试）。
 */
class CursorExpiredException(
    message: String = "云端变更记录已过期",
    cause: Throwable? = null,
) : Exception(message, cause)
