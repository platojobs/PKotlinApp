package com.platojobs.pkotlinapp.di

import android.content.Context
import com.platojobs.pkotlinapp.data.ble.BleConnectionManager
import com.platojobs.pkotlinapp.data.ble.BleScanner
import com.platojobs.pkotlinapp.data.local.AppDatabase
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
import com.platojobs.pkotlinapp.domain.rule.RuleEngine
import com.platojobs.pkotlinapp.domain.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** BLE notify 回调来自 binder 线程；读数落库与同步调度均切到 IO。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database by lazy { AppDatabase.getInstance(appContext) }

    private val apiService by lazy { ApiClient.create() }

    private val syncApi by lazy { ApiClient.createSync() }

    private val secureTokenStore by lazy { SecureTokenStore(appContext) }

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

    val bleConnectionManager: BleConnectionManager by lazy {
        BleConnectionManager(appContext) { deviceId, sample ->
            ioScope.launch {
                readingRepository.record(deviceId, sample)
            }
        }
    }

    val settings: AppSettings by lazy { AppSettings(appContext) }

    val ruleEngine: RuleEngine by lazy {
        RuleEngine(
            ruleRepository = ruleRepository,
            eventRepository = ruleEventRepository,
            textProvider = AndroidRuleTextProvider(appContext),
        )
    }

    val syncManager: SyncManager by lazy {
        SyncManager(
            dao = database.deviceDao(),
            engine = SyncEngine(
                local = RoomSyncLocalStore(database.deviceDao()),
                remote = remoteSyncDataSource,
                cursorStore = syncPrefs,
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
}
