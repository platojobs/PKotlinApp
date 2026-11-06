package com.platojobs.pkotlinapp.data.account

import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.domain.account.AccountRemote
import com.platojobs.pkotlinapp.domain.account.ClaimedAccount
import com.platojobs.pkotlinapp.domain.account.ExportDownloadExpiredException
import com.platojobs.pkotlinapp.domain.account.ExportTaskStatus
import com.platojobs.pkotlinapp.domain.account.PairingCode
import com.platojobs.pkotlinapp.domain.account.PairingCodeFormat
import com.platojobs.pkotlinapp.domain.sync.SyncCursorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 导出文件：只落在 cacheDir/exports，由 FileProvider 分享。 */
data class AccountExportFile(val file: File)

/** 导出任务的客户端阶段；服务端 pending 时展示「处理中」，ready 后才下载。 */
enum class ExportStage { PENDING, READY }

/**
 * 账号编排：
 * - 配对码生成/领取（领取后替换 token，并把本机设备与读数重新排队推送）；
 * - 账号数据导出（服务端异步任务：创建 → 轮询 → 取一次性链接下载，过期则重新申请）；
 * - 注销（先用 Bearer 申请一次性令牌，再仅凭令牌删除；远端成功后才清理本地）。
 */
class AccountService(
    private val remote: AccountRemote,
    private val tokenStore: TokenStore,
    private val cursorStore: SyncCursorStore,
    private val resetSyncStatus: () -> Unit,
    private val markAllPending: suspend () -> Unit,
    private val wipeLocalData: suspend () -> Unit,
    private val exportDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    /** 轮询间隔；测试可注入 0 以跳过等待。 */
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    /** 轮询上限，避免任务长期挂起时无限等待。 */
    private val maxPolls: Int = DEFAULT_MAX_POLLS,
) {

    suspend fun createPairingCode(): PairingCode = remote.createPairingCode()

    /**
     * 领取配对码：校验格式 → 换取新 token → 替换本机 token → 清空同步游标
     * → 本机设备与读数全部标记 pending，让新账号拿到本机数据。
     */
    suspend fun claim(rawCode: String): ClaimedAccount {
        val code = PairingCodeFormat.sanitize(rawCode)
        require(PairingCodeFormat.isValid(code)) { "配对码必须是 8 位有效字符" }
        val account = remote.claim(code)
        tokenStore.saveSession(account.token, account.expiresAt.takeIf { it > 0 })
        cursorStore.cursor = null
        markAllPending()
        resetSyncStatus()
        return account
    }

    /**
     * 服务端异步导出：创建任务 → 轮询到 ready → 用一次性链接下载并写盘。
     * 下载链接过期（410）时重新查询任务拿新链接，不沿用失效地址。
     */
    suspend fun exportData(
        onStage: (ExportStage) -> Unit = {},
    ): AccountExportFile = withContext(Dispatchers.IO) {
        val created = remote.createExport()
        var task = created
        var polls = 0
        while (task.status == ExportTaskStatus.PENDING) {
            if (polls >= maxPolls) {
                throw IllegalStateException("导出任务长时间未完成，请稍后重试")
            }
            onStage(ExportStage.PENDING)
            delay(pollIntervalMillis)
            task = remote.exportTask(created.id)
            polls += 1
        }
        if (task.status != ExportTaskStatus.READY) {
            throw IllegalStateException("服务端未能生成导出文件，请稍后重试")
        }
        onStage(ExportStage.READY)
        val json = download(task.downloadUrl, task.id)
        exportDir.mkdirs()
        val target = File(exportDir, exportFileName(task.id, now()))
        target.writeText(json, Charsets.UTF_8)
        AccountExportFile(file = target)
    }

    /** 下载一次性链接；410 时重新查询任务获取新链接后再试一次。 */
    private suspend fun download(url: String?, taskId: String): String {
        val first = url?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("导出任务缺少下载地址")
        return try {
            remote.downloadExport(first)
        } catch (e: ExportDownloadExpiredException) {
            val refreshed = remote.exportTask(taskId)
            if (refreshed.status != ExportTaskStatus.READY) {
                throw IllegalStateException("导出链接已失效，且任务当前不可下载")
            }
            val next = refreshed.downloadUrl?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("导出任务缺少下载地址")
            // 第二次仍过期就如实失败，由用户重试整个导出。
            remote.downloadExport(next)
        }
    }

    /**
     * 注销：先 Bearer 申请一次性令牌，再仅凭令牌删除；远端 204 后才清理本地
     * token、游标、同步状态与 Room 全部业务表，回到无账号状态。
     * 远端失败（含 409 家庭还有成员）时本地数据原样保留。
     */
    suspend fun deleteAccount() {
        val token = remote.requestDeletionToken()
        remote.deleteAccount(token.token)
        withContext(Dispatchers.IO) {
            tokenStore.clear()
            cursorStore.cursor = null
            resetSyncStatus()
            wipeLocalData()
        }
    }

    /** `account_export_<taskId>_<UTC 秒>.json`，只落在 cacheDir/exports。 */
    private fun exportFileName(taskId: String, timestamp: Long): String {
        val formatter = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "account_export_${taskId}_${formatter.format(Date(timestamp))}.json"
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS = 2_000L
        const val DEFAULT_MAX_POLLS = 60
    }
}
