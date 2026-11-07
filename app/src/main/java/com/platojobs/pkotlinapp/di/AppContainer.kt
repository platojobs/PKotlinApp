package com.platojobs.pkotlinapp.di

import android.content.Context
import com.platojobs.pkotlinapp.BuildConfig
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
import com.platojobs.pkotlinapp.data.reading.LatestReadingCache
import com.platojobs.pkotlinapp.data.remote.ApiClient
import com.platojobs.pkotlinapp.data.remote.DeviceInfoRepositoryImpl
import com.platojobs.pkotlinapp.data.remote.MarketRepositoryImpl
import com.platojobs.pkotlinapp.data.remote.RemoteMarketDataSource
import com.platojobs.pkotlinapp.data.repository.DeviceRepositoryImpl
import com.platojobs.pkotlinapp.data.repository.ReadingRepositoryImpl
import com.platojobs.pkotlinapp.data.repository.RoomRepositoryImpl
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
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.domain.repository.DeviceInfoRepository
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.repository.MarketRepository
import com.platojobs.pkotlinapp.domain.reading.ReadingRemote
import com.platojobs.pkotlinapp.domain.repository.ReadingRepository
import com.platojobs.pkotlinapp.domain.repository.RoomRepository
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

    private val secureTokenStore by lazy { SecureTokenStore(appContext) }

    /** 刷新失败（服务端明确 401/403）时进入重新登录态；本地数据与游标保持不变。 */
    private val onReloginRequired: () -> Unit = { syncStatusStore.recordAuthExpired() }

    private val apiService by lazy { ApiClient.create(tokenStore = secureTokenStore, onReloginRequired = onReloginRequired) }

    private val syncApi by lazy { ApiClient.createSync(tokenStore = secureTokenStore, onReloginRequired = onReloginRequired) }

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

    /** 本机房间仓库：作为离线缓存；服务端房间由 householdRepository 合并进来。 */
    val roomRepository: RoomRepository by lazy {
        RoomRepositoryImpl(database.roomDao(), database.deviceDao())
    }

    /** 家庭仓库：家庭/成员/邀请码/房间与设备房间上报。 */
    val householdRepository: com.platojobs.pkotlinapp.data.household.HouseholdRepository by lazy {
        com.platojobs.pkotlinapp.data.household.HouseholdRepository(
            householdDao = database.householdDao(),
            roomDao = database.roomDao(),
            deviceDao = database.deviceDao(),
            remote = com.platojobs.pkotlinapp.data.household.RemoteHouseholdDataSource(
                api = syncApi,
                tokenStore = secureTokenStore,
            ),
            prefs = syncPrefs,
            scope = ioScope,
        )
    }

    /** 意见反馈：真实提交到后端，失败时本地草稿保留并按状态机重试。 */
    val feedbackStore: com.platojobs.pkotlinapp.data.feedback.FeedbackStore by lazy {
        com.platojobs.pkotlinapp.data.feedback.FeedbackStore(
            dao = database.feedbackDao(),
            remote = com.platojobs.pkotlinapp.data.feedback.RemoteFeedbackDataSource(
                api = syncApi,
                tokenStore = secureTokenStore,
            ),
            contextProvider = { com.platojobs.pkotlinapp.data.feedback.androidFeedbackContext() },
            scope = ioScope,
        ).also { store ->
            // 进程被杀时可能停在 sending，启动后回到 pending 等待用户手动重试。
            ioScope.launch { store.recoverInFlight() }
        }
    }

    /** 场景仓库：本地执行投影 + 服务端规范化 JSON 的双向映射。 */
    val sceneRepository: SceneRepository by lazy {
        SceneRepositoryImpl(
            database.sceneDao(),
            com.platojobs.pkotlinapp.data.repository.RoomSceneDeviceLookup(database.deviceDao()),
        )
    }

    private val remoteSceneDataSource by lazy {
        com.platojobs.pkotlinapp.data.scene.RemoteSceneDataSource(
            com.platojobs.pkotlinapp.data.remote.ApiClient.createScenes(
                tokenStore = secureTokenStore,
                onReloginRequired = onReloginRequired,
            ),
            secureTokenStore,
        )
    }

    /** 场景同步：GET /scenes 基线 + change_log scene + 离线补传。 */
    private val sceneSyncer by lazy {
        com.platojobs.pkotlinapp.data.scene.SceneSyncer(
            dao = database.sceneDao(),
            deviceDao = database.deviceDao(),
            remote = remoteSceneDataSource,
            onScenesChanged = { ruleEngine.refresh() },
        )
    }

    /** 场景执行记录：由引擎真实写入，界面只读。 */
    val sceneRunRepository: com.platojobs.pkotlinapp.domain.repository.SceneRunRepository by lazy {
        com.platojobs.pkotlinapp.data.repository.SceneRunRepositoryImpl(database.sceneRunDao())
    }

    private val remoteSyncDataSource by lazy {
        RemoteSyncDataSource(
            api = syncApi,
            tokenStore = secureTokenStore,
            installId = syncPrefs.installId,
            // 匿名登录/会话就绪后异步注册推送；不阻塞同步主流程。
            onSessionReady = {
                ioScope.launch { runCatching { pushRegistrationRepository.onSessionReady() } }
            },
        )
    }

    /**
     * 默认无 FCM：返回 NotConfigured，不虚构 token。
     * 放入 google-services.json 并接入 firebase-messaging 后，
     * 在这里切换为 FirebasePushTokenProvider 即可，其余生命周期无需改动。
     */
    private fun createPushTokenProvider(): com.platojobs.pkotlinapp.domain.push.PushTokenProvider =
        com.platojobs.pkotlinapp.data.push.UnavailablePushTokenProvider()

    /** 推送令牌生命周期：登录注册、token 轮换重注册、登出注销、启动补齐。 */
    val pushRegistrationRepository: com.platojobs.pkotlinapp.data.push.PushRegistrationRepository by lazy {
        com.platojobs.pkotlinapp.data.push.PushRegistrationRepository(
            store = com.platojobs.pkotlinapp.data.push.RoomPushDeviceStore(database.pushDeviceDao()),
            tokenProvider = createPushTokenProvider(),
            remote = com.platojobs.pkotlinapp.data.push.RemotePushDataSource(
                api = syncApi,
                tokenStore = secureTokenStore,
            ),
            appVersion = BuildConfig.VERSION_NAME,
            deviceModel = android.os.Build.MODEL.orEmpty(),
            scope = ioScope,
            sessionAvailable = { secureTokenStore.token()?.isNotBlank() == true },
            notificationsGranted = { NotificationPermission.isGranted(appContext) },
        )
    }

    /** 详情页可选的远端 24h 汇总；失败自动回退本地。 */
    val readingRemote: ReadingRemote by lazy { remoteSyncDataSource }

    /** 首页卡片/房间详情的云端最近读数：按设备缓存并合并并发请求。 */
    val latestReadingCache: LatestReadingCache by lazy {
        LatestReadingCache(remote = remoteSyncDataSource, scope = ioScope)
    }

    val deviceInfoRepository: DeviceInfoRepository by lazy {
        DeviceInfoRepositoryImpl(
            api = apiService,
            syncApi = syncApi,
            tokenStore = secureTokenStore,
            deviceDao = database.deviceDao(),
        )
    }

    /** 设备类型目录：服务端唯一真值 + Room 缓存；首屏读缓存，后台条件刷新。 */
    val deviceCatalogStore: com.platojobs.pkotlinapp.data.catalog.DeviceCatalogStore by lazy {
        com.platojobs.pkotlinapp.data.catalog.DeviceCatalogStore(
            dao = database.catalogDao(),
            remote = com.platojobs.pkotlinapp.data.catalog.RetrofitCatalogRemote(apiService),
        )
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
                // 入库后立即评估指标阈值规则，保证后台采集也生效。
                val displayName = deviceRepository.getDevice(deviceId)?.displayName().orEmpty()
                ruleEngine.onReading(deviceId, displayName.ifBlank { deviceId }, sample)
            }
        }
    }

    /** 账号迁移 / 导出 / 注销；远端失败时本地数据不会被清理。 */
    val accountService: AccountService by lazy {
        AccountService(
            remote = RemoteAccountDataSource(
                api = syncApi,
                tokenStore = secureTokenStore,
                baseUrl = BuildConfig.API_BASE_URL,
            ),
            tokenStore = secureTokenStore,
            cursorStore = syncPrefs,
            resetSyncStatus = { syncStatusStore.resetForRelogin() },
            markAllPending = {
                database.deviceDao().markAllPending()
                database.readingDao().markAllPending()
            },
            wipeLocalData = { database.clearAllTables() },
            exportDir = File(appContext.cacheDir, "exports"),
            onPushUnregister = { pushRegistrationRepository.unregisterAll() },
            onSessionReady = { pushRegistrationRepository.onSessionReady() },
        )
    }

    val settings: AppSettings by lazy { AppSettings(appContext) }

    /**
     * 在 IO 线程预热 Room：开户、迁移与 schema 校验是首页首条数据的主要等待项。
     * 只做打开，不发查询，调用方负责放到后台线程。
     */
    fun preWarmDatabase() {
        database.openHelper.writableDatabase
    }

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
            triggerObserver = sceneTriggerRunner,
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
            householdSyncer = householdRepository,
            sceneSyncer = sceneSyncer,
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

    /** 引擎触发规则的场景执行入口；手动运行复用同一执行器与记录写入。 */
    val sceneTriggerRunner: com.platojobs.pkotlinapp.data.scene.SceneTriggerRunner by lazy {
        com.platojobs.pkotlinapp.data.scene.SceneTriggerRunner(
            sceneRepository = sceneRepository,
            sceneRunRepository = sceneRunRepository,
            executor = sceneExecutor,
        )
    }
}
