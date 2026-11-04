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
import com.platojobs.pkotlinapp.data.repository.RuleEventRepositoryImpl
import com.platojobs.pkotlinapp.data.repository.RuleRepositoryImpl
import com.platojobs.pkotlinapp.data.repository.ScanLogRepositoryImpl
import com.platojobs.pkotlinapp.data.rule.AndroidRuleTextProvider
import com.platojobs.pkotlinapp.domain.repository.DeviceInfoRepository
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.repository.MarketRepository
import com.platojobs.pkotlinapp.domain.repository.ProductRepository
import com.platojobs.pkotlinapp.domain.repository.RuleEventRepository
import com.platojobs.pkotlinapp.domain.repository.RuleRepository
import com.platojobs.pkotlinapp.domain.repository.ScanLogRepository
import com.platojobs.pkotlinapp.domain.rule.RuleEngine

class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    private val database by lazy { AppDatabase.getInstance(appContext) }

    private val apiService by lazy { ApiClient.create() }

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

    val deviceInfoRepository: DeviceInfoRepository by lazy {
        DeviceInfoRepositoryImpl(apiService)
    }

    val productRepository: ProductRepository by lazy {
        ProductRepositoryImpl(apiService)
    }

    val marketRepository: MarketRepository by lazy {
        MarketRepositoryImpl(RemoteMarketDataSource(apiService))
    }

    val bleScanner: BleScanner by lazy { BleScanner(appContext) }

    val bleConnectionManager: BleConnectionManager by lazy {
        BleConnectionManager(appContext)
    }

    val settings: AppSettings by lazy { AppSettings(appContext) }

    val ruleEngine: RuleEngine by lazy {
        RuleEngine(
            ruleRepository = ruleRepository,
            eventRepository = ruleEventRepository,
            textProvider = AndroidRuleTextProvider(appContext),
        )
    }
}
