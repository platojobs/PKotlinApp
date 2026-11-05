package com.platojobs.pkotlinapp.domain.account

/** 服务端签发的一次性配对码；expiresAt 为服务端真值（epoch millis）。 */
data class PairingCode(
    val code: String,
    val expiresAt: Long,
)

/** 领取配对码后拿到的同一账号新 token。 */
data class ClaimedAccount(
    val userId: String,
    val token: String,
)

/**
 * 账号导出的一页。user/devices/readings 保留服务端原始结构（不做字段级重写），
 * nextCursor 为不透明串，客户端只透传不解析。
 */
data class AccountExportPage(
    val user: Map<String, Any?>,
    val devices: List<Map<String, Any?>>,
    val readings: List<Map<String, Any?>>,
    val exportedAt: Long?,
    val exportedAtRaw: String?,
    val nextCursor: String?,
    val hasMore: Boolean,
)

/** 配对码/导出/注销的远端能力；实现负责鉴权头与错误分类。 */
interface AccountRemote {
    suspend fun createPairingCode(): PairingCode

    /** @throws PairingCodeInvalidException 404（不区分无效/过期/已用） */
    suspend fun claim(code: String): ClaimedAccount

    suspend fun exportPage(cursor: String?, limit: Int): AccountExportPage

    /** 401 由实现抛 AuthExpiredException。 */
    suspend fun deleteAccount()
}

/** 404 统一语义：码无效或已过期，不区分具体原因，避免枚举。 */
class PairingCodeInvalidException : Exception("配对码无效或已过期")

/** 429 限流；retryAfterSeconds 来自 Retry-After 头，缺失时为 null。 */
class PairingRateLimitedException(
    val retryAfterSeconds: Int?,
) : Exception("尝试过于频繁，请稍后再试")

/** 导出页数或条数超过客户端保护上限。 */
class AccountExportTooLargeException(
    val maxReadings: Int,
) : Exception("导出数据超过上限")
