package com.platojobs.pkotlinapp.data.account

import com.platojobs.pkotlinapp.data.remote.ApiLimits
import com.platojobs.pkotlinapp.data.remote.PairingClaimRequest
import com.platojobs.pkotlinapp.data.remote.SyncApi
import com.platojobs.pkotlinapp.data.remote.toSyncException
import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.domain.account.AccountExportPage
import com.platojobs.pkotlinapp.domain.account.AccountRemote
import com.platojobs.pkotlinapp.domain.account.ClaimedAccount
import com.platojobs.pkotlinapp.domain.account.PairingCode
import com.platojobs.pkotlinapp.domain.account.PairingCodeInvalidException
import com.platojobs.pkotlinapp.domain.account.PairingRateLimitedException
import com.platojobs.pkotlinapp.domain.sync.Rfc3339
import retrofit2.HttpException

/**
 * 账号相关 Retrofit 适配（openapi 0.6.0）：
 * - `POST /auth/pairing-code`（Bearer）→ `{code, expiresAt}`
 * - `POST /auth/pairing-code/claim`（无鉴权）→ `{userId, token, expiresAt}`；404 统一为无效/过期
 * - `GET /me/export`（Bearer，cursor/limit）→ 一页导出数据
 * - `DELETE /me`（Bearer）→ 204
 *
 * 错误分类：
 * - 404 → [PairingCodeInvalidException]；
 * - 429 → [PairingRateLimitedException]，带 Retry-After 秒数（可能缺失）；
 * - 401 → [AuthExpiredException]（与同步一致，不自动重新匿名登录）。
 */
class RemoteAccountDataSource(
    private val api: SyncApi,
    private val tokenStore: TokenStore,
) : AccountRemote {

    override suspend fun createPairingCode(): PairingCode = withToken { authorization ->
        val response = api.createPairingCode(authorization)
        val code = response.code?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("配对码响应缺少 code")
        PairingCode(
            code = code,
            // expiresAt 是服务端真值；解析失败时由 UI 按「未知过期时间」处理。
            expiresAt = Rfc3339.parse(response.expiresAt) ?: 0L,
        )
    }

    override suspend fun claim(code: String): ClaimedAccount = try {
        val response = api.claimPairingCode(PairingClaimRequest(code))
        val userId = response.userId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("配对响应缺少 userId")
        val token = response.token?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("配对响应缺少 token")
        ClaimedAccount(
            userId = userId,
            token = token,
            expiresAt = Rfc3339.parse(response.expiresAt) ?: 0L,
        )
    } catch (e: HttpException) {
        when (e.code()) {
            404 -> throw PairingCodeInvalidException()
            429 -> throw PairingRateLimitedException(e.retryAfterSeconds())
            else -> throw e
        }
    }

    override suspend fun exportPage(cursor: String?, limit: Int): AccountExportPage =
        withToken { authorization ->
            val response = api.exportMyData(
                authorization = authorization,
                cursor = cursor,
                limit = limit.coerceIn(1, ApiLimits.EXPORT_MAX),
            )
            AccountExportPage(
                user = response.user.orEmpty(),
                devices = response.devices.orEmpty(),
                readings = response.readings.orEmpty(),
                exportedAt = Rfc3339.parse(response.exportedAt),
                exportedAtRaw = response.exportedAt,
                nextCursor = response.nextCursor,
                hasMore = response.hasMore == true,
            )
        }

    override suspend fun deleteAccount() = withToken { authorization ->
        api.deleteMyAccount(authorization)
    }

    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        val token = tokenStore.token()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("尚未登录，无法执行账号操作")
        return try {
            block("Bearer $token")
        } catch (e: HttpException) {
            // 与同步一致：401→重登，503 TIMEOUT→繁忙可重试。
            throw e.toSyncException() ?: e
        }
    }

    private fun HttpException.retryAfterSeconds(): Int? =
        response()?.headers()?.get("Retry-After")?.trim()?.toIntOrNull()
}
