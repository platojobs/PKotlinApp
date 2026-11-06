package com.platojobs.pkotlinapp.data.account

import com.platojobs.pkotlinapp.data.remote.ExportTaskResponse
import com.platojobs.pkotlinapp.data.remote.PairingClaimRequest
import com.platojobs.pkotlinapp.data.remote.SyncApi
import com.platojobs.pkotlinapp.data.remote.toSyncException
import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.domain.account.AccountDeletionBlockedException
import com.platojobs.pkotlinapp.domain.account.AccountExportTask
import com.platojobs.pkotlinapp.domain.account.AccountRemote
import com.platojobs.pkotlinapp.domain.account.ClaimedAccount
import com.platojobs.pkotlinapp.domain.account.DeletionRequestToken
import com.platojobs.pkotlinapp.domain.account.DeletionTokenExpiredException
import com.platojobs.pkotlinapp.domain.account.DeletionTokenInvalidException
import com.platojobs.pkotlinapp.domain.account.ExportDownloadExpiredException
import com.platojobs.pkotlinapp.domain.account.ExportDownloadInvalidException
import com.platojobs.pkotlinapp.domain.account.ExportTaskStatus
import com.platojobs.pkotlinapp.domain.account.PairingCode
import com.platojobs.pkotlinapp.domain.account.PairingCodeInvalidException
import com.platojobs.pkotlinapp.domain.account.PairingRateLimitedException
import com.platojobs.pkotlinapp.domain.sync.Rfc3339
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException

/**
 * 账号相关 Retrofit 适配（openapi 0.15.0）：
 * - `POST /auth/pairing-code`（Bearer）→ `{code, expiresAt}`
 * - `POST /auth/pairing-code/claim`（无鉴权）→ `{userId, token, expiresAt}`；404 统一为无效/过期
 * - `POST /me/deletion-request`（Bearer）→ 一次性删除令牌
 * - `DELETE /me`（仅 `X-Deletion-Token`，不含 Bearer）→ 204；409 表示家庭还有成员
 * - `POST /me/export`（Bearer）→ 202 异步任务；`GET /me/export/{id}` 轮询并取 10 分钟一次性下载链接
 * - `GET /me/export/{id}/download?token=...`（链接本身即凭据，无 Bearer）
 *
 * 错误分类：
 * - 404 → [PairingCodeInvalidException]（仅配对码）；
 * - 429 → [PairingRateLimitedException]，带 Retry-After 秒数（可能缺失）；
 * - 401 → [AuthExpiredException]（与同步一致，不自动重新匿名登录）。
 */
class RemoteAccountDataSource(
    private val api: SyncApi,
    private val tokenStore: TokenStore,
    private val baseUrl: String,
    private val downloadClient: OkHttpClient = OkHttpClient(),
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

    override suspend fun requestDeletionToken(): DeletionRequestToken = withToken { authorization ->
        val response = api.createDeletionRequest(authorization)
        DeletionRequestToken(
            token = response.token?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("删除令牌响应缺少 token"),
            expiresAt = Rfc3339.parse(response.expiresAt) ?: 0L,
        )
    }

    override suspend fun deleteAccount(deletionToken: String) {
        try {
            // 删除接口不带 Bearer：请求只凭一次性令牌。
            api.deleteMyAccount(deletionToken)
        } catch (e: HttpException) {
            when (e.code()) {
                409 -> throw AccountDeletionBlockedException()
                403 -> throw DeletionTokenInvalidException()
                410 -> throw DeletionTokenExpiredException()
                404 -> throw IllegalStateException("账号已不存在（删除请求可能已被处理）")
                else -> throw e.toSyncException() ?: e
            }
        }
    }

    override suspend fun createExport(): AccountExportTask = withToken { authorization ->
        toDomain(api.createMyDataExport(authorization))
    }

    override suspend fun exportTask(id: String): AccountExportTask = withToken { authorization ->
        toDomain(api.myDataExport(authorization, id))
    }

    override suspend fun downloadExport(downloadUrl: String): String {
        val url = baseUrl.toHttpUrl().resolve(downloadUrl)
            ?: throw IllegalStateException("导出下载地址无效")
        val request = Request.Builder().url(url).get().build()
        downloadClient.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> return response.body?.string().orEmpty()
                410 -> throw ExportDownloadExpiredException()
                403 -> throw ExportDownloadInvalidException()
                else -> throw IllegalStateException("下载导出失败（HTTP ${response.code}）")
            }
        }
    }

    /** 解析任务状态；未知状态按 failed 处理，不猜测。 */
    private fun toDomain(dto: ExportTaskResponse): AccountExportTask {
        val id = dto.id?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("导出任务响应缺少 id")
        val status = when (dto.status) {
            "pending" -> ExportTaskStatus.PENDING
            "ready" -> ExportTaskStatus.READY
            else -> ExportTaskStatus.FAILED
        }
        return AccountExportTask(
            id = id,
            status = status,
            downloadUrl = dto.downloadUrl,
            downloadExpiresAt = Rfc3339.parse(dto.downloadExpiresAt),
            expiresAt = Rfc3339.parse(dto.expiresAt),
        )
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
