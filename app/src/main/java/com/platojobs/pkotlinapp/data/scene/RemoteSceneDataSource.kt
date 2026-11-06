package com.platojobs.pkotlinapp.data.scene

import com.platojobs.pkotlinapp.data.remote.SceneApi
import com.platojobs.pkotlinapp.data.remote.SceneInputDto
import com.platojobs.pkotlinapp.data.remote.ScenePatchDto
import com.platojobs.pkotlinapp.data.remote.apiErrorDetails
import com.platojobs.pkotlinapp.data.remote.toSyncException
import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.data.sync.toRemoteScene
import com.platojobs.pkotlinapp.domain.scene.RemoteScene
import com.platojobs.pkotlinapp.domain.scene.SceneRejectedException
import com.platojobs.pkotlinapp.domain.scene.SceneRetryableException
import retrofit2.HttpException

/** 场景远端的最小接口，便于 JVM 单测用内存假实现覆盖首次上传/离线补传/软删。 */
interface SceneRemote {
    suspend fun list(): List<RemoteScene>

    suspend fun create(input: SceneInputDto): RemoteScene

    suspend fun update(remoteId: String, patch: ScenePatchDto): RemoteScene

    suspend fun delete(remoteId: String)
}

/**
 * Retrofit 适配：`GET/POST/PUT/DELETE api/v1/scenes`。
 * 404 在删除时视为「已经不存在」（跨用户/已软删统一 404），其它状态按同步异常上抛。
 */
class RemoteSceneDataSource(
    private val api: SceneApi,
    private val tokenStore: TokenStore,
) : SceneRemote {

    override suspend fun list(): List<RemoteScene> = withToken { authorization ->
        api.listScenes(authorization).items.orEmpty().mapNotNull { it.toRemoteScene() }
    }

    override suspend fun create(input: SceneInputDto): RemoteScene =
        withToken { authorization ->
            api.createScene(authorization, input).toRemoteScene()
                ?: error("场景创建响应缺少 id/updatedAt")
        }

    override suspend fun update(remoteId: String, patch: ScenePatchDto): RemoteScene =
        withToken { authorization ->
            api.updateScene(authorization, remoteId, patch).toRemoteScene()
                ?: error("场景更新响应缺少 id/updatedAt")
        }

    override suspend fun delete(remoteId: String) {
        withToken { authorization -> api.deleteScene(authorization, remoteId) }
    }

    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        // 场景同步只在设备同步之后执行；此时 token 一定存在，缺失就当作可重试失败。
        val token = tokenStore.token()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("未登录，场景保留 pending")
        return try {
            block("Bearer $token")
        } catch (e: HttpException) {
            val details = e.apiErrorDetails()
            when {
                // 跨用户/已软删统一 404：删除幂等，更新/读取按「已不存在」处理。
                e.code() == 404 -> throw SceneGoneException()
                // 408/429 明确可重试；429 单独按 Retry-After 延后。
                e.code() == 408 -> throw SceneRetryableException(cause = e)
                e.code() == 429 -> throw SceneRetryableException(
                    retryAfterSeconds = e.retryAfterSecondsOrNull(),
                    cause = e,
                )
                // 其它 4xx（400/409/413/422…）终态拒绝：不重试，等用户改内容。
                e.code() in 400..499 && e.code() != 401 && e.code() != 403 ->
                    throw SceneRejectedException(details?.code, details?.message)
                else -> throw e.toSyncException() ?: e
            }
        }
    }

    /** `Retry-After` 按秒解析；HTTP-date 形式换算不了时返回 null（退化为立即重试）。 */
    private fun HttpException.retryAfterSecondsOrNull(): Long? =
        response()?.headers()?.get("Retry-After")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it in 0..86_400 }
}

/** DELETE 遇到 404：服务端已软删或不属于本人，等同删除完成。 */
class SceneGoneException : RuntimeException("scene already deleted")
