package com.platojobs.pkotlinapp.di

import android.content.Context
import com.platojobs.pkotlinapp.data.account.AccountService
import com.platojobs.pkotlinapp.data.account.RemoteAccountDataSource
import com.platojobs.pkotlinapp.data.ble.BleConnectionManager
import com.platojobs.pkotlinapp.data.ble.BleScanner
import com.platojobs.pkotlinapp.data.collection.CollectionSession
import com.platojobs.pkotlinapp.data.local.AppDatabase
import com.platojobs.pkotlinapp.data.notification.AlertNotifier
import com.platojobs.pkotlinapp.data.notification.AlertStatePrefs
import com.platojobs.pkotlinapp.data.notification.AndroidAlertContentBuilder
import com.platojobs.pkotlinapp.data.notification.AndroidAlertPoster
import com.platojobs.pkotlinapp.data.prefs.AppSettings
import com.platojobs.pkotlinapp.data.remote.ApiClient
import com.platojobs.pkotlinapp.data.remote.DeviceInfoRepositoryImpl
import com.platojobs.pkotlinapp.data.remote.MarketRepositoryImpl
import com.platojobs.pkotlinapp.data.remote.ProductRepositoryImpl
import com.platojobs.pkotlinapp.data.remote.RemoteMarketDataSource
import com.platojobs.pkotlinapp.data.repository.DeviceRepositoryImpl
import com.platojobs.pkotlinapp.data.repository.ReadingRepositoryImpl
import com.platojobs.pkotlinapp.data.repository.RuleEventRepositoryImpl
import com.platojobs.pkotlinapp.data.repository.RuleRepositoryImpl
import com.platojobs.pkotlinapp.data.repository.ScanLogRepositoryImpl
import com.platojobs.pkotlinapp.data.repository.SceneRepositoryImpl
import com.platojobs.pkotlinapp.data.scene.SceneRuntimeImpl
import com.platojobs.pkotlinapp.data.rule.AndroidRuleTextProvider
import com.platojobs.pkotlinapp.data.sync.ReadingSyncer
import com.platojobs.pkotlinapp.data.sync.RemoteSyncDataSource
import com.platojobs.pkotlinapp.data.sync.RoomSyncLocalStore
import com.platojobs.pkotlinapp.data.sync.SecureTokenStore
import com.platojobs.pkotlinapp.data.sync.SyncManager
import com.platojobs.pkotlinapp.data.sync.SyncPrefs
import com.platojobs.pkotlinapp.data.sync.SyncScheduler
import com.platojobs.pkotlinapp.data.sync.SyncStatusStore
import com.platojobs.pkotlinapp.domain.repository.DeviceInfoRepository
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.repository.MarketRepository
import com.platojobs.pkotlinapp.domain.reading.ReadingRemote
import com.platojobs.pkotlinapp.domain.repository.ProductRepository
import com.platojobs.pkotlinapp.domain.repository.ReadingRepository
import com.platojobs.pkotlinapp.domain.repository.RuleEventRepository
import com.platojobs.pkotlinapp.domain.repository.RuleRepository
import com.platojobs.pkotlinapp.domain.repository.ScanLogRepository
import com.platojobs.pkotlinapp.domain.repository.SceneRepository
import com.platojobs.pkotlinapp.domain.rule.AlertThrottle
import com.platojobs.pkotlinapp.domain.rule.RuleEngine
import com.platojobs.pkotlinapp.domain.scene.SceneExecutor
import com.platojobs.pkotlinapp.domain.sync.SyncEngine
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.NotificationPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** BLE notify 回调来自 binder 线程；读数落库与同步调度均切到 IO。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database by lazy { AppDatabase.getInstance(appContext) }

    private val apiService by lazy { ApiClient.create() }

    private val syncApi by lazy { ApiClient.createSync() }

    private val secureTokenStore by lazy { SecureTokenStore(appContext) }

    /** 「我的」只读展示会话到期日。 */
    val tokenStore: com.platojobs.pkotlinapp.data.sync.TokenStore
        get() = secureTokenStore

    private val syncPrefs by lazy { SyncPrefs(appContext) }

    private val syncStatusStore by lazy { SyncStatusStore(appContext) }

    val deviceRepository: DeviceRepository by lazy {
        DeviceRepositoryImpl(database.deviceDao())
    }

    val ruleRepository: RuleRepository by lazy {
        RuleRepositoryImpl(database.ruleDao())
    }

    val ruleEventRepository: RuleEventRepository by lazy {
        RuleEventRepositoryImpl(database.ruleEventDao())
    }

    val scanLogRepository: ScanLogRepository by lazy {
        ScanLogRepositoryImpl(database.scanLogDao())
    }

    val readingRepository: ReadingRepository by lazy {
        ReadingRepositoryImpl(database.readingDao())
    }

    /** 本地场景仓库：仅本机，不参与云端同步。 */
    val sceneRepository: SceneRepository by lazy {
        SceneRepositoryImpl(database.sceneDao())
    }

    private val remoteSyncDataSource by lazy {
        RemoteSyncDataSource(
            api = syncApi,
            tokenStore = secureTokenStore,
            installId = syncPrefs.installId,
        )
    }

    /** 详情页可选的远端 24h 汇总；失败自动回退本地。 */
    val readingRemote: ReadingRemote by lazy { remoteSyncDataSource }

    val deviceInfoRepository: DeviceInfoRepository by lazy {
        DeviceInfoRepositoryImpl(
            api = apiService,
            syncApi = syncApi,
            tokenStore = secureTokenStore,
            deviceDao = database.deviceDao(),
        )
    }

    val productRepository: ProductRepository by lazy {
        ProductRepositoryImpl(apiService)
    }

    val marketRepository: MarketRepository by lazy {
        MarketRepositoryImpl(RemoteMarketDataSource(apiService))
    }

    val bleScanner: BleScanner by lazy { BleScanner(appContext) }

    /** 后台采集会话：前台服务与「我的」状态共用同一事实源。 */
    val collectionSession: CollectionSession = CollectionSession()

    val bleConnectionManager: BleConnectionManager by lazy {
        BleConnectionManager(appContext) { deviceId, sample ->
            ioScope.launch {
                readingRepository.record(deviceId, sample)
                collectionSession.record(deviceId)
            }
        }
    }

    /** 账号迁移 / 导出 / 注销；远端失败时本地数据不会被清理。 */
    val accountService: AccountService by lazy {
        AccountService(
            remote = RemoteAccountDataSource(api = syncApi, tokenStore = secureTokenStore),
            tokenStore = secureTokenStore,
            cursorStore = syncPrefs,
            resetSyncStatus = { syncStatusStore.resetForRelogin() },
            markAllPending = {
                database.deviceDao().markAllPending()
                database.readingDao().markAllPending()
            },
            wipeLocalData = { database.clearAllTables() },
            exportDir = File(appContext.cacheDir, "exports"),
            moshi = ApiClient.moshi(),
        )
    }

    val settings: AppSettings by lazy { AppSettings(appContext) }

    private val alertNotifier by lazy {
        AlertNotifier(
            throttle = AlertThrottle(
                store = AlertStatePrefs(appContext),
                windowMillis = { settings.alertThrottleMinutes * 60_000L },
            ),
            isEnabled = { settings.alertsEnabled && !settings.alertsMuted() },
            canNotify = { NotificationPermission.isGranted(appContext) },
            contentBuilder = AndroidAlertContentBuilder(appContext),
            poster = AndroidAlertPoster(appContext),
        )
    }

    val ruleEngine: RuleEngine by lazy {
        RuleEngine(
            ruleRepository = ruleRepository,
            eventRepository = ruleEventRepository,
            textProvider = AndroidRuleTextProvider(appContext),
            alertSink = alertNotifier,
        )
    }

    val syncManager: SyncManager by lazy {
        SyncManager(
            dao = database.deviceDao(),
            engine = SyncEngine(
                local = RoomSyncLocalStore(database.deviceDao()),
                remote = remoteSyncDataSource,
                cursorStore = syncPrefs,
                // 410 只处理一次，记录状态供「我的」展示，不做重试。
                onCursorExpired = { syncStatusStore.recordResync() },
            ),
            statusStore = syncStatusStore,
            tokenStore = secureTokenStore,
            cursorStore = syncPrefs,
            readingSyncer = ReadingSyncer(
                dao = database.readingDao(),
                remote = remoteSyncDataSource,
                repository = readingRepository,
            ),
        )
    }

    val syncScheduler: SyncScheduler by lazy { SyncScheduler(appContext) }

    /** 场景动作执行器：真实调用 BLE / 前台服务 / 偏好 / 同步。 */
    val sceneExecutor: SceneExecutor by lazy {
        SceneExecutor(
            runtime = SceneRuntimeImpl(
                context = appContext,
                ble = bleConnectionManager,
                collectionSession = collectionSession,
                settings = settings,
                syncScheduler = syncScheduler,
                devices = deviceRepository,
            ),
            canConnect = { BlePermissions.hasConnect(appContext) },
        )
    }
}
