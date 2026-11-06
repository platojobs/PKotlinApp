package com.platojobs.pkotlinapp.domain.account

/** 服务端签发的一次性配对码；expiresAt 为服务端真值（epoch millis）。 */
data class PairingCode(
    val code: String,
    val expiresAt: Long,
)

/** 领取配对码后拿到的同一账号新 token；expiresAt 为服务端签发时间（epoch millis，缺失为 0）。 */
data class ClaimedAccount(
    val userId: String,
    val token: String,
    val expiresAt: Long = 0L,
)

/** POST /me/deletion-request 签发的一次性删除令牌。 */
data class DeletionRequestToken(
    val token: String,
    /** 令牌过期时间（epoch millis，解析失败为 0）。 */
    val expiresAt: Long,
)

/** 异步导出任务状态；对应服务端 pending / ready / failed。 */
enum class ExportTaskStatus { PENDING, READY, FAILED }

/**
 * 服务端异步导出任务。
 * downloadUrl 为相对路径，含一次性令牌；downloadExpiresAt 是链接的失效时间。
 */
data class AccountExportTask(
    val id: String,
    val status: ExportTaskStatus,
    val downloadUrl: String? = null,
    val downloadExpiresAt: Long? = null,
    val expiresAt: Long? = null,
)

/** 配对码/导出/注销的远端能力；实现负责鉴权头与错误分类。 */
interface AccountRemote {
    suspend fun createPairingCode(): PairingCode

    /** @throws PairingCodeInvalidException 404（不区分无效/过期/已用） */
    suspend fun claim(code: String): ClaimedAccount

    /** 申请一次性删除令牌（Bearer）。 */
    suspend fun requestDeletionToken(): DeletionRequestToken

    /**
     * 用一次性令牌删除账号。
     * @throws AccountDeletionBlockedException 409（owner 的家庭还有成员）
     * @throws DeletionTokenInvalidException 403
     * @throws DeletionTokenExpiredException 410
     */
    suspend fun deleteAccount(deletionToken: String)

    /** 创建异步导出任务（202，状态 pending）。 */
    suspend fun createExport(): AccountExportTask

    /** 查询任务；ready 时带新的下载链接。 */
    suspend fun exportTask(id: String): AccountExportTask

    /**
     * 下载导出 JSON 原文。
     * @throws ExportDownloadExpiredException 410（链接过期或已被使用，需重新查询任务）
     */
    suspend fun downloadExport(downloadUrl: String): String
}

/** 404 统一语义：码无效或已过期，不区分具体原因，避免枚举。 */
class PairingCodeInvalidException : Exception("配对码无效或已过期")

/** 429 限流；retryAfterSeconds 来自 Retry-After 头，缺失时为 null。 */
class PairingRateLimitedException(
    val retryAfterSeconds: Int?,
) : Exception("尝试过于频繁，请稍后再试")

/** 409：owner 的家庭还有其他成员，需先转让所有权或移除成员。 */
class AccountDeletionBlockedException : Exception("家庭还有其他成员，请先转让所有权或移除成员")

/** 403：删除令牌无效。 */
class DeletionTokenInvalidException : Exception("删除令牌无效，请重新申请")

/** 410：删除令牌已过期。 */
class DeletionTokenExpiredException : Exception("删除令牌已过期，请重新申请")

/** 410：下载链接已过期或已被使用，应重新查询任务获取新链接。 */
class ExportDownloadExpiredException : Exception("下载链接已过期或已被使用")

/** 403：下载链接无效或任务不可下载。 */
class ExportDownloadInvalidException : Exception("下载链接无效或导出任务不可下载")
