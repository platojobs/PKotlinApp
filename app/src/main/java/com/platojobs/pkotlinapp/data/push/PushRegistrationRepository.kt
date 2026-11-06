package com.platojobs.pkotlinapp.data.push

import com.platojobs.pkotlinapp.domain.push.PushDeviceRecord
import com.platojobs.pkotlinapp.domain.push.PushDeviceStatus
import com.platojobs.pkotlinapp.domain.push.PushRegistrationState
import com.platojobs.pkotlinapp.domain.push.PushRegistrationStatus
import com.platojobs.pkotlinapp.domain.push.PushRemote
import com.platojobs.pkotlinapp.domain.push.PushRemoteException
import com.platojobs.pkotlinapp.domain.push.PushTokenProvider
import com.platojobs.pkotlinapp.domain.push.PushTokenResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 推送令牌注册生命周期。
 *
 * 触发点：
 * - 登录/会话就绪：[onSessionReady]；
 * - token 变化：[onTokenChanged]（真实 FCM 刷新回调接入这里）；
 * - 启动补齐：[retryPending]；
 * - 登出/删除账号前：[unregisterAll]（先 DELETE 再清本地）。
 *
 * 与 Android 通知权限完全解耦：注册成败只由 token 与服务端决定，
 * [notificationsGranted] 仅用于界面如实提示「未授权通知，推送不会展示」。
 */
class PushRegistrationRepository(
    private val store: PushDeviceStore,
    private val tokenProvider: PushTokenProvider,
    private val remote: PushRemote,
    private val appVersion: String,
    private val deviceModel: String,
    private val scope: CoroutineScope,
    private val sessionAvailable: () -> Boolean = { true },
    private val notificationsGranted: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()

    private val _state = MutableStateFlow(PushRegistrationState())

    val state: StateFlow<PushRegistrationState> = _state.asStateFlow()

    private var retryJob: Job? = null

    /** 登录/会话就绪后调用：确保当前 token 已注册。 */
    suspend fun onSessionReady() {
        mutex.withLock { registerLocked() }
    }

    /** FCM token 刷新回调：新 token 会注册并注销旧 token。 */
    suspend fun onTokenChanged() {
        mutex.withLock { registerLocked() }
    }

    /** 启动时对未注册成功的做一次补齐。 */
    suspend fun retryPending() {
        mutex.withLock { registerLocked() }
    }

    /** 从本地表恢复界面状态（不主动发网络请求）。 */
    suspend fun refresh() {
        mutex.withLock { publishSnapshotLocked() }
    }

    /**
     * 登出/删除账号前注销：逐条先 DELETE 再清本地。
     * DELETE 失败（网络/401）不阻塞本地清理，避免退出后仍显示已注册。
     */
    suspend fun unregisterAll() {
        mutex.withLock {
            cancelRetryLocked()
            val now = clock()
            val rows = buildList {
                addAll(store.registered())
                addAll(store.pendingOrRejected())
                store.unconfigured()?.let(::add)
            }.distinctBy { it.token }
            rows.forEach { row ->
                row.remoteDeviceId?.let { deviceId ->
                    runCatching { remote.unregister(deviceId) }
                }
                store.save(
                    row.copy(
                        status = PushDeviceStatus.INACTIVE,
                        remoteDeviceId = null,
                        retryCount = 0,
                        nextRetryAt = null,
                        updatedAt = now,
                    ),
                )
            }
            publish(status = PushRegistrationStatus.INACTIVE)
        }
    }

    private suspend fun registerLocked() {
        // 会话就绪前不上报，也不记录「未配置」，避免把「未登录」误显示成「未配置服务」。
        if (!sessionAvailable()) {
            cancelRetryLocked()
            publish(PushRegistrationStatus.IDLE)
            return
        }
        when (val result = tokenProvider.fetchToken()) {
            PushTokenResult.NotConfigured -> {
                val now = clock()
                store.unconfigured()?.let { existing ->
                    store.save(
                        existing.copy(
                            status = PushDeviceStatus.UNCONFIGURED,
                            lastError = UNCONFIGURED_REASON,
                            updatedAt = now,
                        ),
                    )
                } ?: store.save(
                    PushDeviceRecord(
                        token = "",
                        status = PushDeviceStatus.UNCONFIGURED,
                        lastError = UNCONFIGURED_REASON,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                cancelRetryLocked()
                publish(PushRegistrationStatus.NOT_CONFIGURED, error = UNCONFIGURED_REASON)
            }

            is PushTokenResult.Failed -> {
                handleTemporaryFailureLocked(
                    existing = store.findByToken(""),
                    reason = result.reason,
                    token = null,
                )
            }

            is PushTokenResult.Available -> {
                val token = result.token.trim()
                if (token.isEmpty()) {
                    // 占位字符串等同于未拿到 token：绝不上报服务端。
                    handleTemporaryFailureLocked(
                        existing = null,
                        reason = "推送令牌为空",
                        token = null,
                    )
                    return
                }
                deactivateRotatedTokensLocked(currentToken = token)
                val existing = store.findByToken(token)
                if (existing?.status == PushDeviceStatus.REGISTERED &&
                    existing.remoteDeviceId != null
                ) {
                    cancelRetryLocked()
                    publish(
                        PushRegistrationStatus.REGISTERED,
                        remoteDeviceId = existing.remoteDeviceId,
                    )
                    return
                }
                if (existing?.status == PushDeviceStatus.REJECTED) {
                    // 400 是终态：同一 token 的服务端参数错误不自动重试。
                    cancelRetryLocked()
                    publish(
                        PushRegistrationStatus.REJECTED,
                        remoteDeviceId = existing.remoteDeviceId,
                        error = existing.lastError,
                    )
                    return
                }
                attemptRegisterLocked(token, existing)
            }
        }
    }

    /** 服务端按 (user, token) 保留旧行；本地把轮换后的旧 token 注销，避免双份推送。 */
    private suspend fun deactivateRotatedTokensLocked(currentToken: String) {
        val now = clock()
        store.registered()
            .filter { it.token.isNotEmpty() && it.token != currentToken }
            .forEach { old ->
                val deviceId = old.remoteDeviceId
                if (deviceId == null) {
                    store.save(old.copy(status = PushDeviceStatus.INACTIVE, updatedAt = now))
                    return@forEach
                }
                try {
                    remote.unregister(deviceId)
                    store.save(
                        old.copy(
                            status = PushDeviceStatus.INACTIVE,
                            remoteDeviceId = null,
                            lastError = null,
                            retryCount = 0,
                            nextRetryAt = null,
                            updatedAt = now,
                        ),
                    )
                } catch (e: PushRemoteException) {
                    // 注销失败保留旧行，下次会话就绪继续尝试；不阻断新 token 注册。
                    store.save(
                        old.copy(
                            lastError = e.message,
                            updatedAt = now,
                        ),
                    )
                }
            }
    }

    private suspend fun attemptRegisterLocked(token: String, existing: PushDeviceRecord?) {
        val now = clock()
        try {
            val registered = remote.register(token, appVersion, deviceModel)
            cancelRetryLocked()
            store.save(
                PushDeviceRecord(
                    token = token,
                    remoteDeviceId = registered.id,
                    status = PushDeviceStatus.REGISTERED,
                    lastError = null,
                    retryCount = 0,
                    nextRetryAt = null,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
            publish(PushRegistrationStatus.REGISTERED, remoteDeviceId = registered.id)
        } catch (e: PushRemoteException.Rejected) {
            // 400 等参数错误：终态，记录可读原因，不再重试。
            cancelRetryLocked()
            store.save(
                PushDeviceRecord(
                    token = token,
                    remoteDeviceId = existing?.remoteDeviceId,
                    status = PushDeviceStatus.REJECTED,
                    lastError = e.reason,
                    retryCount = 0,
                    nextRetryAt = null,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
            publish(PushRegistrationStatus.REJECTED, error = e.reason)
        } catch (e: PushRemoteException.AuthExpired) {
            // 认证失效不盲目重试；等 SyncManager 刷新或用户重新登录后由会话就绪再次注册。
            cancelRetryLocked()
            store.save(
                PushDeviceRecord(
                    token = token,
                    remoteDeviceId = existing?.remoteDeviceId,
                    status = PushDeviceStatus.PENDING,
                    lastError = e.message,
                    retryCount = existing?.retryCount ?: 0,
                    nextRetryAt = null,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
            publish(PushRegistrationStatus.PENDING, error = e.message)
        } catch (e: Exception) {
            val reason = (e as? PushRemoteException)?.message ?: "推送注册失败，稍后重试"
            handleTemporaryFailureLocked(existing, reason, token)
        }
    }

    private suspend fun handleTemporaryFailureLocked(
        existing: PushDeviceRecord?,
        reason: String,
        token: String?,
    ) {
        val now = clock()
        val retryCount = (existing?.retryCount ?: 0) + 1
        val delayMillis = pushRetryDelayMillis(retryCount)
        val effectiveToken = token ?: existing?.token.orEmpty()
        if (effectiveToken.isNotEmpty()) {
            store.save(
                PushDeviceRecord(
                    token = effectiveToken,
                    remoteDeviceId = existing?.remoteDeviceId,
                    status = PushDeviceStatus.PENDING,
                    lastError = reason,
                    retryCount = retryCount,
                    nextRetryAt = now + delayMillis,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
        }
        publish(
            PushRegistrationStatus.PENDING,
            error = reason,
            retryCount = retryCount,
            nextRetryAt = now + delayMillis,
        )
        scheduleRetryLocked(delayMillis)
    }

    private fun scheduleRetryLocked(delayMillis: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMillis)
            mutex.withLock { registerLocked() }
        }
    }

    private fun cancelRetryLocked() {
        retryJob?.cancel()
        retryJob = null
    }

    private suspend fun publishSnapshotLocked() {
        val registered = store.registered().maxByOrNull { it.updatedAt }
        if (registered != null) {
            publish(
                PushRegistrationStatus.REGISTERED,
                remoteDeviceId = registered.remoteDeviceId,
            )
            return
        }
        if (!sessionAvailable()) {
            publish(PushRegistrationStatus.IDLE)
            return
        }
        if (store.unconfigured() != null) {
            publish(PushRegistrationStatus.NOT_CONFIGURED, error = UNCONFIGURED_REASON)
            return
        }
        val pending = store.pendingOrRejected().maxByOrNull { it.updatedAt }
        when (pending?.status) {
            PushDeviceStatus.REJECTED -> publish(
                PushRegistrationStatus.REJECTED,
                error = pending.lastError,
            )
            PushDeviceStatus.PENDING -> publish(
                PushRegistrationStatus.PENDING,
                error = pending.lastError,
                retryCount = pending.retryCount,
                nextRetryAt = pending.nextRetryAt,
            )
            else -> {
                val result = tokenProvider.fetchToken()
                if (result is PushTokenResult.NotConfigured) {
                    publish(PushRegistrationStatus.NOT_CONFIGURED, error = UNCONFIGURED_REASON)
                } else {
                    publish(PushRegistrationStatus.IDLE)
                }
            }
        }
    }

    private fun publish(
        status: PushRegistrationStatus,
        remoteDeviceId: String? = null,
        error: String? = null,
        retryCount: Int = 0,
        nextRetryAt: Long? = null,
    ) {
        _state.value = PushRegistrationState(
            status = status,
            remoteDeviceId = remoteDeviceId,
            lastError = error,
            retryCount = retryCount,
            nextRetryAt = nextRetryAt,
            notificationsGranted = notificationsGranted(),
        )
    }

    private companion object {
        const val UNCONFIGURED_REASON = "未配置 FCM（缺少 google-services.json）"

        const val BASE_RETRY_DELAY_MS = 30_000L
        const val MAX_RETRY_DELAY_MS = 6 * 60 * 60 * 1000L

        /** 30s、60s、120s… 指数退避，上限 6 小时。 */
        fun pushRetryDelayMillis(retryCount: Int): Long {
            val exponent = (retryCount - 1).coerceIn(0, 12)
            return (BASE_RETRY_DELAY_MS shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
        }
    }
}
