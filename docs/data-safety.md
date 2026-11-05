# Google Play Data safety 对照（v0.9.0）

按 Play Console「Data safety」表单逐项对照本项目**实际实现**。每一行都给出代码/接口证据；未实现的能力不申报。

## 1. 数据收集与共享总表

| Play 数据类别 | 具体数据 | 是否收集 | 是否共享给第三方 | 用途 | 是否可选 | 传输加密 | 可删除 | 证据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Health and fitness → Health info | 心率读数（仅当设备上报 `0x2A37`） | 是 | 否 | 应用功能（展示、历史曲线、同步） | 设备支持才产生 | 是（生产 HTTPS） | 是（注销/清数据） | `data/local/Entities.kt`、`domain/reading/ReadingModels.kt`、`data/remote/SyncApi.kt` |
| Device or other IDs | 蓝牙 MAC、广播名、安装 UUID（`clientId`）、匿名 `userId` | 是 | 否 | 应用功能（设备管理、账号同步、鉴权） | 同步功能需要；纯本地模式可不用网络 | 是 | 是 | `SyncApi.kt`、`SyncPrefs.kt`、`RemoteSyncDataSource.kt` |
| App activity → Other actions（映射说明见 §3） | BLE 传感器读数（温度/湿度/电量/CO2/气压/其他）、数值、单位、记录时间戳、RSSI 扫描记录 | 是 | 否 | 应用功能（实时读数、历史曲线、汇总、规则判断、同步） | 设备连接后才产生 | 是 | 是 | `Entities.kt`、`ReadingRepositoryImpl.kt`、`SyncApi.kt` |
| App info and performance → Crash logs / Diagnostics | 无 | 否 | 否 | — | — | — | — | 无 Crashlytics/Sentry/Firebase；错误仅 `Log.w/i` 与 `SyncPrefs.lastError` |
| Location → Approximate/Precise location | 无 | 否 | 否 | — | — | — | — | 无 `LocationManager`/FusedLocation；`ACCESS_FINE_LOCATION` 仅 `maxSdkVersion=30` 用于 BLE 扫描限制 |
| Personal info | 无邮箱、手机号、姓名、密码 | 否 | 否 | — | — | — | — | 匿名 `POST /auth/anonymous`，无登录表单 |
| Financial info | 无 | 否 | 否 | — | — | — | — | 市场页无购物车/订单/支付 |
| Messages / Contacts / Calendar / Photos / Videos / Audio / Files / Web browsing | 无 | 否 | 否 | — | — | — | — | Manifest 无对应权限；无相关 API 调用 |
| Advertising ID | 无 | 否 | 否 | — | — | — | — | 依赖列表无广告 SDK；无 `AdvertisingIdClient` |

**共享结论**：数据只发送到本项目自建后端，用于匿名账号同步与恢复；无第三方广告、分析、推送或社交接收方。用户主动导出并分享文件时，接收方由用户在系统分享面板中选择，属于用户主动行为。

## 2. 安全实践

| 表单问题 | 回答 | 证据/说明 |
| --- | --- | --- |
| 数据在传输中是否加密？ | 是（生产环境） | Release Manifest 未开启 `usesCleartextTraffic`，`targetSdk 36` 默认禁止明文；需通过 `apiBaseUrl` 注入 HTTPS 地址。Debug 仅本地联调允许明文（`app/src/debug/AndroidManifest.xml`）。 |
| 是否提供删除数据的方式？ | 是 | 应用内「我的 → 注销账号」调用 `DELETE /api/v1/me`，成功后清 token、游标、同步状态与全部 Room 表；设备移除与清空扫描日志也可删除部分数据。 |
| 是否已独立安全审查？ | 否 | 项目未做第三方安全审计。 |
| 是否遵循 Families Policy？ | 不适用/否 | 不专门面向儿童，无 UGC、社交、广告或交易。 |
| 是否包含广告？ | 否 | 无广告 SDK、无广告位、无广告 ID。 |
| 是否包含应用内购买？ | 否 | 无内购、订阅、购物车、订单或支付。 |

## 3. 类别映射说明与已知边界

1. **传感器读数归类**：Play 表单没有独立的「BLE 传感器读数」类别。温度/湿度/电量/CO2/气压属于设备产生的应用内数据，本文件按最接近的 **App activity → Other actions** 申报；心率单独按 **Health and fitness → Health info** 申报。实际填写时如 Play Console 提供更贴切的类别，以控制台选项为准。
2. **MAC 地址**：BLE 设备 MAC 属于设备标识符，按 **Device or other IDs** 申报；`BLUETOOTH_SCAN` 已声明 `neverForLocation`，不用于推断位置。
3. **匿名账号 ID**：安装 UUID 与匿名 `userId` 用于同步归属与鉴权；token 存于 `EncryptedSharedPreferences`（Keystore），不写入日志。
4. **服务端 90 天保留**：属于后端契约（README「数据保留」与同步文档），客户端无法强制执行；本仓库无后端代码，上架前需由后端确认。
5. **未同步读数**：本机清理只删除 `syncState = synced` 且超过 7 天的读数；未同步读数会保留到同步成功，属于防止数据丢失的实现细节，隐私政策已如实说明。
6. **位置数据**：应用不调用定位 API，不读取经纬度，不上传位置；Android 11 及以下的 `ACCESS_FINE_LOCATION` 仅用于满足系统 BLE 扫描限制。
7. **崩溃数据**：无崩溃上报 SDK；`lastError` 与 Logcat 仅在本机，不上传，因此 Data safety 的 Crash logs / Diagnostics 填「不收集」。

## 4. 表单填写速查

- **Does your app collect or share any of the required user data types?** 是。
- **Is all of the user data collected by your app encrypted in transit?** 是（生产 HTTPS）。
- **Do you provide a way for users to request that their data is deleted?** 是（应用内注销账号；另可移除设备/清空日志）。
- **Data types**：Health info（心率）、Device or other IDs（MAC/安装 UUID/匿名账号 ID）、App activity → Other actions（传感器读数与 RSSI 记录）。
- **Data not collected**：位置、个人身份信息、财务信息、消息、通讯录、日历、照片/视频、音频、文件、网页浏览、广告 ID、崩溃日志、诊断数据。
- **Data shared with third parties**：无。
- **Data collection optional**：同步需要匿名账号；不联网时应用以纯本地模式运行（设备与读数仍可在本机查看）。
- **Ads / IAP**：均无。

## 5. 代码证据索引

| 证据 | 位置 |
| --- | --- |
| 权限声明与 `neverForLocation`、`maxSdkVersion=30` | `app/src/main/AndroidManifest.xml` |
| 运行时权限集合 | `app/src/main/java/com/platojobs/pkotlinapp/ui/common/BlePermissions.kt` |
| 同步请求/响应字段 | `app/src/main/java/com/platojobs/pkotlinapp/data/remote/SyncApi.kt` |
| 同步映射（MAC/设备/读数） | `app/src/main/java/com/platojobs/pkotlinapp/data/sync/RemoteSyncDataSource.kt` |
| Room 实体字段 | `app/src/main/java/com/platojobs/pkotlinapp/data/local/Entities.kt` |
| 7 天保留、仅清 synced | `domain/reading/ReadingModels.kt`、`data/repository/ReadingRepositoryImpl.kt`、`data/local/Daos.kt` |
| token 加密 | `data/sync/SecureTokenStore.kt` |
| 安装 UUID | `data/sync/SyncPrefs.kt` |
| 注销流程 | `domain/account/AccountService.kt` |
| 导出字段与分享 | `domain/export/ReadingCsv.kt`、`domain/account/AccountExportJson.kt`、`ui/common/FileSharing.kt` |
| 无广告/崩溃/分析 SDK | `app/build.gradle.kts`、`gradle/libs.versions.toml` |
| Release 禁止明文 | `app/src/main/AndroidManifest.xml`（无 `usesCleartextTraffic`）；`app/src/debug/AndroidManifest.xml`（仅 Debug 放开） |
