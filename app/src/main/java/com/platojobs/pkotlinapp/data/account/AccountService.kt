package com.platojobs.pkotlinapp.data.account

import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.domain.account.AccountExportJson
import com.platojobs.pkotlinapp.domain.account.AccountExportPage
import com.platojobs.pkotlinapp.domain.account.AccountExportPager
import com.platojobs.pkotlinapp.domain.account.AccountExportProgress
import com.platojobs.pkotlinapp.domain.account.AccountRemote
import com.platojobs.pkotlinapp.domain.account.ClaimedAccount
import com.platojobs.pkotlinapp.domain.account.PairingCode
import com.platojobs.pkotlinapp.domain.account.PairingCodeFormat
import com.platojobs.pkotlinapp.domain.sync.SyncCursorStore
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 导出文件：只落在 cacheDir/exports，由 FileProvider 分享。 */
data class AccountExportFile(
    val file: File,
    val readings: Int,
    val pages: Int,
)

/**
 * 账号编排：
 * - 配对码生成/领取（领取后替换 token，并把本机设备与读数重新排队推送）；
 * - 账号数据导出（多页合并写盘）；
 * - 注销（远端成功后才清理本地，避免「本地清了但账号还在」）。
 */
class AccountService(
    private val remote: AccountRemote,
    private val tokenStore: TokenStore,
    private val cursorStore: SyncCursorStore,
    private val resetSyncStatus: () -> Unit,
    private val markAllPending: suspend () -> Unit,
    private val wipeLocalData: suspend () -> Unit,
    private val exportDir: File,
    private val moshi: Moshi,
    private val now: () -> Long = System::currentTimeMillis,
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
        tokenStore.saveToken(account.token)
        cursorStore.cursor = null
        markAllPending()
        resetSyncStatus()
        return account
    }

    /** 拉全账号数据并写盘；进度回调是真实累计页数与条数。 */
    suspend fun exportData(
        onProgress: (AccountExportProgress) -> Unit = {},
    ): AccountExportFile = withContext(Dispatchers.IO) {
        val pager = AccountExportPager(
            fetch = { cursor -> remote.exportPage(cursor, EXPORT_PAGE_SIZE) },
        )
        val result = pager.exportAll(onProgress)
        val json = AccountExportJson.render(moshi, result.pages)
        exportDir.mkdirs()
        val target = File(
            exportDir,
            AccountExportJson.fileName(userIdOf(result.pages), now()),
        )
        target.writeText(json, Charsets.UTF_8)
        AccountExportFile(file = target, readings = result.readings, pages = result.pageCount)
    }

    /**
     * 注销：远端 204 后才清理本地 token、游标、同步状态、EncryptedSharedPreferences
     * 与 Room 全部业务表，回到初始匿名状态。远端失败时本地数据原样保留。
     */
    suspend fun deleteAccount() {
        remote.deleteAccount()
        withContext(Dispatchers.IO) {
            tokenStore.clear()
            cursorStore.cursor = null
            resetSyncStatus()
            wipeLocalData()
        }
    }

    private fun userIdOf(pages: List<AccountExportPage>): String? =
        pages.firstOrNull()?.user?.get("id")?.toString()

    private companion object {
        const val EXPORT_PAGE_SIZE = 5000
    }
}
