# Google Play Data safety 对照（v0.32.7）

按 Play Console「Data safety」表单逐项对照本项目**实际实现**。每一行都给出客户端与服务端代码证据；
未实现的能力不申报，实际不存在的数据类型也不申报。

> **口径**：Play 的「收集」指数据离开用户设备。只写在本机 Room / SharedPreferences、从未上传的数据
> 不算收集，统一列在 §1.2。服务端为本项目自建后端（`../PJGoIoT`），不属于第三方接收方；
> 同一家庭的成员通过该后端读取共享设备与场景，属于应用功能内的用户间共享，不是向第三方共享。

## 1. 数据收集与共享

### 1.1 已收集的数据类型

| Play 类别 | 具体字段 | 本地表 | 上报接口 | 收集 | 共享 | 传输加密 | 可要求删除 | 用途 | 是否可选 | 代码证据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 健康与健身 → 健康信息 | 心率指标值、单位、记录时间戳（`metric = heart_rate`） | `readings` | `POST /sync/push`（`readings`） | 是（设备上报 `0x2A37` 且启用同步） | 否 | 是（生产 HTTPS） | 是（注销账号；服务端 90 天清理） | 应用功能 | 设备支持才产生；不联网可只留本机 | `domain/reading/ReadingModels.kt`、`data/sync/RemoteSyncDataSource.kt` |
| 设备或其他 ID | 蓝牙 MAC、规范化 `externalId`、广播名 `advertisedName`、安装 UUID `clientId`、匿名 `userId`、匹配到的 `deviceType`（型号标识） | `devices`、`SyncPrefs` | `POST /sync/push`（`devices`）、`POST /auth/anonymous` | 是（联网同步时） | 否 | 是 | 是（注销账号硬删；应用内移除设备只写 `deleted_at` tombstone） | 应用功能（设备管理、匿名账号同步、鉴权） | 同步需要匿名账号；不联网可纯本地使用 | `data/remote/SyncApi.kt`（`PushDeviceDto`）、`RemoteSyncDataSource.toPushDto()`、`data/sync/SyncPrefs.kt` |
| 应用活动 → 应用内其他操作（读数与扫描信号） | 温度/湿度/电量/CO2/气压/其他指标值、单位、记录时间戳、RSSI、扫描记录时间；服务端派生的 `lastSeenAt`（最后一次接受读数上报的接收时间） | `readings`、`devices.lastRssi/lastSeenTimestamp`、`devices.lastSeenAt` | `POST /sync/push`（`readings`、`scanRecords`）；`lastSeenAt` 只由服务端返回，不上报 | 是 | 否 | 是 | 是（注销账号；服务端读数 90 天清理，本机 synced 记录 7 天清理） | 应用功能（实时展示、历史曲线、规则判断、同步） | 连接设备后才产生 | `data/remote/SyncApi.kt`（`PushReadingDto`）、`domain/reading/ReadingModels.kt`、`RoomSyncLocalStore.kt`（`lastSeenAt` 只写列） |
| 应用活动 → 应用内其他操作（家庭与场景元数据） | 家庭名称、成员关系与角色、房间名称/排序、设备别名/分组/房间归属、场景名称/启用状态/冷却秒数/触发条件/动作列表（含目标设备 MAC、静音时长、通知文案） | `households`、`rooms`、`devices`、`scenes`、`scene_actions` | `GET/POST/PATCH/DELETE /households…`、`/households/{id}/members`、`/rooms`、`PATCH /my/devices/{platform}/{externalId}`、`/scenes` | 是（使用家庭/场景并联网时） | 否（同一家庭成员可读取共享数据，属应用功能内共享） | 是 | 是（删除家庭硬删；删除场景为 tombstone；注销账号硬删） | 应用功能 | 不创建家庭/场景则不产生 | `data/household/HouseholdRepository.kt`、`data/scene/RemoteSceneDataSource.kt`、`data/remote/SyncApi.kt` |
| 用户内容 → 其他用户生成内容 | 反馈类型 `kind`、反馈正文 `body`、提交时间与处理状态 | `feedback_drafts`（本地草稿） | `POST /feedback` | 是（用户主动提交） | 否 | 是 | 是（注销账号；列表/详情接口不返回 contact/diagnostics） | 开发者通讯（处理用户反馈） | 用户主动提交才产生 | `data/feedback/RemoteFeedbackDataSource.kt`、`domain/feedback/FeedbackModels.kt`、`data/remote/SyncApi.kt`（`FeedbackCreateRequest`） |
| 个人信息 → 电子邮件地址 / 电话号码 | 可选联系方式 `contact`（界面明确提示「邮箱或手机号」） | `feedback_drafts.contact` | `POST /feedback`（`contact`） | 是（仅用户填写时） | 否 | 是 | 是（注销账号；反馈列表/详情不回传该字段） | 开发者通讯（进一步沟通） | 选填 | `res/values/strings.xml`（`feedback_contact_hint`）、`RemoteFeedbackDataSource.submit()` |
| 应用信息与性能 → 诊断信息 | 反馈上下文 `platform`、`appVersion`、`buildType`、`osVersion`、`deviceModel` | 不落库，随请求即时生成 | `POST /feedback`（`context`） | 是（仅提交反馈时） | 否 | 是 | 是（注销账号） | 开发者通讯（定位反馈问题） | 随反馈提交 | `data/remote/SyncApi.kt`（`FeedbackContextDto`）、`domain/feedback/FeedbackModels.kt`（`FeedbackContext`） |

**`presence` / `lastSeenAt` 归类依据（按代码路径）**：`lastSeenAt` 是服务端对**已上报读数**记录的最后接收时间
（`user_devices.last_seen_at`），随 `/my/devices` 与 `/sync/changes` 返回并镜像到本机 `devices.lastSeenAt`，
不参与任何上传，属于上表「读数与扫描信号」同一批数据的派生属性，因此归入「应用活动 → 应用内其他操作」而非新增类别。
`presence` 是服务端每次响应时按 `now() - last_seen_at <= 900s` 实时推导的 `unknown/online/offline`，不落库、不上报，
不单独构成一项采集数据。iOS 将该字段归入「使用数据 → 其他使用数据」；Play 侧对应同一「应用活动」大类，
这里的归类来自本仓库的上报与持久化代码路径，不是照抄 iOS 结论。

**共享结论**：不向第三方出售或共享数据。同步与反馈只发送到本项目自建后端（`../PJGoIoT`）；
无广告、分析、第三方推送或社交 SDK。用户主动导出并分享文件时，接收方由用户在系统分享面板中选择，
属于用户主动行为，不在本表申报范围内。

### 1.2 未收集的数据类型

| Play 类别 | 数据/字段 | 是否收集 | 依据 |
| --- | --- | --- | --- |
| 设备或其他 ID → 推送通知令牌 | 平台、token、appVersion、deviceModel | **否**（本版本不产生 token、不调用 `POST /push/devices`） | `di/AppContainer.createPushTokenProvider()` 固定返回 `UnavailablePushTokenProvider`；`PushRegistrationRepository` 在 `NotConfigured` 时只写本地 `push_devices` 的 `unconfigured` 行（`token = ""`），不上报。字段与接口为未来接入保留 |
| 位置 | 精确/粗略位置、经纬度 | 否 | 无定位 API 调用；`ACCESS_FINE_LOCATION` 仅 `maxSdkVersion=30` 满足 BLE 扫描限制，`BLUETOOTH_SCAN` 声明 `neverForLocation` |
| 个人信息（除可选联系方式） | 姓名、地址、账号密码、生日等 | 否 | 仅匿名 `POST /auth/anonymous`，无登录表单 |
| 财务信息 | 支付、订单、订阅 | 否 | 市场页无购物车/订单/支付 |
| 消息、通讯录、日历、照片/视频、音频、文件、网页浏览 | — | 否 | Manifest 无对应权限；无相关 API 调用 |
| 广告标识符 | Advertising ID | 否 | 依赖列表无广告 SDK；无 `AdvertisingIdClient` |
| 应用信息与性能 → 崩溃日志 | Crash logs | 否 | 无 Crashlytics/Sentry/Firebase；错误只写本机 Logcat 与 `SyncPrefs.lastError` |
| —（仅本机，不离开设备） | `scan_logs` 扫描明细（名称/MAC/RSSI/时间）、`rules` 自动化规则、`rule_events` 规则触发记录、`scene_runs` 场景执行记录、`device_type_catalog` 目录缓存、`readings.rawSnapshot`、`lastError`/Logcat | 否 | 上述表只在本机 Room/SharedPreferences 使用；同步 payload 中 `PushDeviceDto.rules` 恒为空映射，场景规则只经 `/scenes` 上传，读数只含 `id/platform/externalId/metric/value/unit/recordedAt` |

## 2. 保留期与删除路径

| 数据 | 本地存储 | 服务端存储 | 保留期 | 删除路径 | 依据 |
| --- | --- | --- | --- | --- | --- |
| 设备唯一键与元数据 | `devices`（主键 `mac`，另有 `externalId`、服务端只读的 `lastSeenAt`） | `user_devices`（含 `last_seen_at`） | 移除设备只写 `deleted_at` tombstone，行内别名/分组/扫描记录/`last_seen_at` 保留至注销账号；服务端无定时 TTL | 应用内移除设备（软删除）；注销账号硬删除 `user_devices` 并本机 `clearAllTables()` | `Entities.kt`、`AccountService.deleteAccount()`、`../PJGoIoT/internal/store/postgres/postgres.go`（upsert 写 `deleted_at`）、`../PJGoIoT/db/migrations/000020_device_presence.up.sql`、`account.go` |
| 读数 | `readings` | `readings` | 本机：已同步读数 7 天，未同步读数保留到同步成功；服务端：默认 90 天 | 本机定时清理 + 注销账号；服务端 `cmd/cleanup -retention-days 90` + 注销账号 | `Reading.RETENTION_DAYS = 7`、`Daos.deleteOlderThan`、`../PJGoIoT/cmd/cleanup/main.go` |
| 家庭 / 成员关系 / 房间 / 设备归属 | `households`、`rooms`、`devices.roomId` | `households`、`household_members`、`household_rooms` | 直到删除家庭、退出家庭、移除成员或注销账号；服务端无定时 TTL | 家庭删除/退出接口；注销账号。**所有者**的家庭还有其他成员时必须先转让所有权或移除成员，否则注销被拒绝 | `HouseholdRepository.kt`、`../PJGoIoT/internal/store/postgres/account.go` |
| 场景规则与动作 | `scenes`、`scene_actions` | `scenes`（`trigger`/`actions` 为 JSONB） | 删除场景只写 `deleted_at` tombstone，触发/动作 JSONB 保留至注销账号；服务端无定时 TTL | `DELETE /scenes/{sceneId}`（软删除）；注销账号硬删 `scenes` | `data/local/Entities.kt`、`../PJGoIoT/db/migrations/000017_scenes.up.sql`、`../PJGoIoT/internal/store/postgres/scene.go`（`SET deleted_at`）、`account.go` |
| 反馈正文与可选联系方式 | `feedback_drafts` | `feedback`、`feedback_status_events` | 直到注销账号；服务端无定时 TTL（关闭反馈只改状态，不删除正文） | 注销账号按 `user_id` 删除反馈与状态事件 | `../PJGoIoT/db/migrations/000013_feedback.up.sql`、`account.go` |
| 反馈诊断上下文 | 不落库 | `feedback`（`platform/app_version/build_type/os_version/device_model`） | 同上 | 同上 | `000013_feedback.up.sql`、`account.go` |
| 推送令牌（未产生） | `push_devices`（仅 `unconfigured` 状态，`token = ""`） | `push_devices`（本版本不会写入行） | 若未来接入：服务端 inactive 设备默认 30 天清理、推送投递记录 7 天 | 注销时 `DELETE /push/devices/{deviceId}`；无 token 时无实际调用 | `../PJGoIoT/cmd/cleanup/main.go`（`PUSH_DEVICE_RETENTION_DAYS=30`、`PUSH_DELIVERY_RETENTION_DAYS=7`） |
| 同步变更日志 / 幂等键 | — | `change_log` / `idempotency_keys` | 默认 90 天 / 7 天 | `cmd/cleanup` | `../PJGoIoT/cmd/cleanup/main.go` |

**账号删除的完整动作**：应用内「我的 → 注销账号」先请求删除令牌，再调用 `DELETE /api/v1/me`；
服务端在单个事务内删除反馈、设备、读数、场景、家庭房间/成员/家庭、令牌与用户行，
本地清 token、游标、同步状态并执行 `database.clearAllTables()`（`AccountService.deleteAccount()`、`AppContainer.wipeLocalData`）。

## 3. 安全实践

| 表单问题 | 回答 | 证据/说明 |
| --- | --- | --- |
| 数据在传输中是否加密？ | 是（生产环境） | Release Manifest 未开启 `usesCleartextTraffic`，`targetSdk 36` 默认禁止明文；需通过 `apiBaseUrl` 注入 HTTPS 地址。Debug 仅本地联调允许明文（`app/src/debug/AndroidManifest.xml`）。 |
| 是否提供删除数据的方式？ | 是 | 应用内注销账号（服务端硬删 + 本机全量删除）；家庭/房间为硬删除；移除设备与删除场景先写 `deleted_at` tombstone，完整擦除需注销账号；另可清空扫描日志。家庭所有者在有其他成员时需先转让/移除成员。 |
| 是否已独立安全审查？ | 否 | 项目未做第三方安全审计。 |
| 是否遵循 Families Policy？ | 不适用/否 | 不专门面向儿童；无广告、支付或公开社交。 |
| 是否包含广告？ | 否 | 无广告 SDK、无广告位、无广告 ID。 |
| 是否包含应用内购买？ | 否 | 无内购、订阅、购物车、订单或支付。 |

## 4. 表单填写速查

- **Does your app collect or share any of the required user data types?** 是。
- **Is all of the user data collected by your app encrypted in transit?** 是（生产 HTTPS）。
- **Do you provide a way for users to request that their data is deleted?** 是（应用内注销账号；移除设备/删除场景为 tombstone，注销时硬删）。
- **Data types（收集）**：
  - 健康与健身 → 健康信息（心率）
  - 设备或其他 ID（蓝牙 MAC/广播名、安装 UUID、匿名账号 ID、匹配到的 `deviceType` 型号标识）
  - 应用活动 → 应用内其他操作（传感器读数与 RSSI、家庭/成员/房间/设备别名分组、场景规则与动作）
  - 用户内容 → 其他用户生成内容（反馈正文）
  - 个人信息 → 电子邮件地址 / 电话号码（反馈选填联系方式）
  - 应用信息与性能 → 诊断信息（反馈上下文 appVersion/buildType/osVersion/deviceModel）
- **Data not collected**：位置、姓名/地址/密码、财务信息、消息、通讯录、日历、照片/视频、音频、文件、网页浏览、广告 ID、崩溃日志、推送令牌（本版本未接入 FCM）。
- **Data shared with third parties**：无。
- **Data collection optional**：同步需要匿名账号；不联网时以纯本地模式运行（设备与读数仍可在本机查看）；反馈与联系方式由用户主动提交。
- **Purposes**：应用功能（同步与展示）、开发者通讯（反馈）。本应用无分析 SDK，不申报「分析」用途。
- **Ads / IAP**：均无。

## 5. 申报与代码核对记录（本轮修正）

| 旧申报/旧文档 | 代码事实 | 处理 |
| --- | --- | --- |
| `store-listing.md` §5 写「仅健康信息、设备标识与传感器读数」 | 家庭/成员/房间/场景/设备别名与分组均经 `/households…`、`/scenes` 上传；反馈正文、联系方式与诊断上下文经 `/feedback` 上传 | 已补齐三类数据，见 §1.1 |
| 「个人信息：无」 | 反馈联系方式提示为「邮箱或手机号」，`contact` 随 `POST /feedback` 上传 | 改为申报「个人信息 → 电子邮件地址 / 电话号码」，标注选填、开发者通讯 |
| 未列反馈正文与诊断信息 | `FeedbackCreateRequest.body` 与 `context{appVersion,buildType,osVersion,deviceModel}` 均上传；`diagnostics` 字段存在但客户端未赋值 | 申报正文（用户内容）与上下文（诊断信息）；不申报未赋值的 `diagnostics` 自由文本 |
| 「推送令牌：否」但未解释字段 | 本地 `push_devices`、服务端 `push_devices` 与 `/push/devices` 端点存在，但 `UnavailablePushTokenProvider` 始终 `NotConfigured`，无 `google-services.json` | 维持「不收集」，并在 §1.2 列明平台/token/appVersion/deviceModel 仅在未接入时为空值，不产生上传 |
| §3「本仓库无后端代码」 | 后端代码位于 `../PJGoIoT`，`cmd/cleanup` 与 `account.go` 可核验保留期/删除 | 替换为真实路径，见 §2、§6 |
| 未区分仅本机数据 | `scan_logs`、`rules`、`rule_events`、`scene_runs`、目录缓存、`rawSnapshot` 均不上传 | 在 §1.2 明确列为「不收集」 |
| 旧文档按硬删除描述设备/场景 | 服务端 `user_devices` 与 `scenes` 通过 `deleted_at` 软删除，行内别名/扫描记录/触发与动作 JSONB 保留到注销账号 | 在 §2 标明 tombstone 语义；「可要求删除」以注销账号硬删为准 |
| 文档停在 v0.31.3，未覆盖契约 0.21.0 新增的 `lastSeenAt` / `presence` | `SyncDeviceDto.lastSeenAt`、`UserDeviceDto.lastSeenAt` 均从 `/sync/changes`、`/my/devices` 读取；Room v25 新增 `devices.lastSeenAt`；`RoomSyncLocalStore.applyServerPresence` 只写列，不上报；`presence` 仅在响应中读取 | 按代码路径归入「应用活动 → 应用内其他操作」，`presence` 不落库不单列；§2 补保留期，§6 补证据。本轮仅新增这两个字段，无其它漏报数据 |

## 6. 代码证据索引

| 证据 | 位置 |
| --- | --- |
| 权限声明与 `neverForLocation`、`maxSdkVersion=30` | `app/src/main/AndroidManifest.xml` |
| 运行时权限集合 | `app/src/main/java/com/platojobs/pkotlinapp/ui/common/BlePermissions.kt` |
| 同步与反馈请求/响应字段 | `app/src/main/java/com/platojobs/pkotlinapp/data/remote/SyncApi.kt` |
| 设备/读数/家庭/场景同步映射 | `app/src/main/java/com/platojobs/pkotlinapp/data/sync/RemoteSyncDataSource.kt`、`data/household/HouseholdRepository.kt`、`data/scene/RemoteSceneDataSource.kt` |
| presence / lastSeenAt 契约字段（只读、不上报） | `data/remote/SyncApi.kt`（`SyncDeviceDto.lastSeenAt`）、`data/remote/Dtos.kt`、`data/sync/RoomSyncLocalStore.kt`、`../PJGoIoT/db/migrations/000020_device_presence.up.sql` |
| 反馈提交字段 | `app/src/main/java/com/platojobs/pkotlinapp/data/feedback/RemoteFeedbackDataSource.kt` |
| Room 实体与保留字段 | `app/src/main/java/com/platojobs/pkotlinapp/data/local/Entities.kt` |
| 本机读数 7 天保留、仅清 synced | `domain/reading/ReadingModels.kt`、`data/repository/ReadingRepositoryImpl.kt`、`data/local/Daos.kt` |
| token 加密 | `data/sync/SecureTokenStore.kt` |
| 安装 UUID | `data/sync/SyncPrefs.kt` |
| 注销流程（服务端 + 本机清表） | `domain/account/AccountService.kt`、`di/AppContainer.kt`（`wipeLocalData`） |
| 推送未配置（不产生 token） | `di/AppContainer.kt`（`createPushTokenProvider`）、`domain/push/PushTokenProvider.kt`、`data/push/PushRegistrationRepository.kt` |
| 无广告/崩溃/分析 SDK | `app/build.gradle.kts`、`gradle/libs.versions.toml` |
| 服务端表结构 | `../PJGoIoT/db/migrations/000011_households.up.sql`、`000013_feedback.up.sql`、`000017_scenes.up.sql`、`000018_push_and_alerts.up.sql` |
| 服务端保留期 | `../PJGoIoT/cmd/cleanup/main.go`（读数 90 天、change_log 90 天、幂等键 7 天、推送投递 7 天、inactive 推送设备 30 天） |
| 服务端账号删除 | `../PJGoIoT/internal/store/postgres/account.go` |
