# BLE 设备数据面板（PKotlinApp）

[![Android CI](https://github.com/platojobs/PKotlinApp/actions/workflows/android.yml/badge.svg)](https://github.com/platojobs/PKotlinApp/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/tag/platojobs/PKotlinApp?label=release)](https://github.com/platojobs/PKotlinApp/tags)

纯 **Kotlin + XML/View** 体系的 BLE 设备数据面板示例工程：BLE 扫描/连接、Room 本地缓存、Retrofit 网络层、本地规则引擎、权限自检，以及类似米家的五模块底部导航。

**不包含 Jetpack Compose**。界面主线是 XML 布局 + ViewBinding，同时按约定保留「Kotlin 代码构建 View」能力（自绘 View 与代码组装 UI）。

---

## 1. 版本目标与技术栈

| 项目 | 取值 |
| --- | --- |
| 语言 | Kotlin 2.2.21 |
| 构建 | Gradle 8.14.3 + AGP 8.13.1 + Kotlin DSL + Version Catalog |
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 24（Android Studio 模板默认值） |
| JDK | 21（编译与 CI 均使用 JDK 21） |
| UI | XML + ViewBinding + Material 3，禁止 Compose |
| 本地存储 | Room 2.8.5（KSP） |
| 网络 | Retrofit 2.11.0 + OkHttp 4.12.0 + Moshi 1.15.2 |
| 同步 | WorkManager 2.10.5 + EncryptedSharedPreferences（Keystore 保护 token） |
| 架构 | MVVM + Repository + 手动 DI（`AppContainer`）+ StateFlow/SharedFlow |

---

## 2. 信息架构与五模块能力（v0.8.0）

底部 `BottomNavigationView` 按米家式信息架构固定五项：**首页 / 智能 / 产品 / 市场 / 我的**。设备管理（搜索 / 排序 / 房间分区 / 分组筛选）属于首页，不再保留独立「设备」Tab；规则入口整体迁移到「智能」。

### 首页 Home（`ui/home`）
- 设备卡片网格：每张卡显示名称、连接状态、最近一条读数（等宽数字）与真实 RSSI 信号条；按**房间**分段展示，房间头可折叠，「未分配」固定最后。
- 顶部保留搜索（别名 / 广播名 / 型号 / MAC）与排序（最近 / 名称 / 信号 / 添加时间）；分组 Chip 保留为辅助筛选，来自 Room 真实 `deviceGroup`。
- 首卡上方一行「今日告警摘要」：条数来自 `RuleEvent` 当日真实触发计数，点击进入「智能」。
- 下方「快捷场景」横向卡：展示本机前 3 个场景，点击即执行并 toast 汇总结果。
- 点设备卡校验蓝牙连接权限后发起连接并进入详情；长按设备卡可移动房间；「扫描添加」弹层复用同一套 `BluetoothLeScanner`。
- 三态：骨架延迟 300ms 且与真实列表同高；无房间时「还没有房间」+「新建房间」，有房间无设备时房间内引导扫描，搜索无结果时回到「没有匹配设备」。

### 智能 Intelligence（`ui/intelligence`，v0.8.0 新增）
- **自动化**：低 RSSI / 断开 / 绑定丢失三类本地规则的增删启停，以及最近触发记录；规则命中写 `RuleEvent` 并投递告警通知。
- **场景**：本机有序动作组合，支持 CRUD、一键执行、逐动作进度与结果汇总（详见 §6.8）。
- 原「设备」页的规则与触发记录入口已全部迁移到此处，首页不再出现重复入口。

### 产品 Products（`ui/products`）
- 型号库：厂商、型号、固件版本、GATT 服务表。
- 数据来自 `GET /api/v1/device-types`（类型列表）+ `GET /api/v1/device-types/{type}/catalog`（一次返回型号元数据、固件与配件）；不再拼接历史端点。失败时显示错误空状态并提供重试。
- GATT 服务行由 Kotlin 代码动态组装（非 XML）；catalog 不返回特征列表时不渲染空行。

### 市场 Market（`ui/market`）
- 配件列表：名称、SKU、厂商、兼容性、适用设备类型与描述（数据来自各设备类型的 `catalog.accessories`）。
- 0.3.2 catalog 的 `accessories.vendor` / `compatibility` 直接驱动筛选：厂商与兼容性均取接口真实值，不编造分类；无数据时筛选条隐藏。
- 数据经 `MarketDataSource` 接口抽象，`RemoteMarketDataSource` 走 Retrofit（Base URL 可配置）；未来接真实商品后端只需新增实现并替换注入，不改 UI/ViewModel。
- **不包含购物车、订单、支付等任何假交易能力**，页面有明确免责声明。

### 我的 Profile（`ui/profile`）
- 同步状态：上次同步时间、待同步条数、最近冲突数、最近错误、纯本地模式提示、手动「立即同步」；登录失效时改为「重新登录」入口（需用户确认），数据来自真实同步状态与 Room 统计。
- 权限自检：读取真实的蓝牙扫描/连接/定位授权状态，并跳转系统应用设置。
- 扫描日志：Room 本地记录（扫描会话中每个设备首次出现写一条），可清空。
- 版本与关于：读取 `BuildConfig` 的真实版本号与构建类型。
- 账号：**迁移到新设备**（8 位配对码 + 服务端真实过期倒计时）、**从旧设备接管**（用配对码换同一 `userId` 的 token 并重新标记本机数据待同步）、**导出我的数据**（按不透明 cursor 分页 JSON）、**注销账号**（二次确认后硬删除云端与本机数据）。
- 后台采集：显示当前是否正在前台服务中采集（设备名 + 已采集条数）。
- 简单设置：主题（跟随系统 / 浅色 / 深色），写入 SharedPreferences 并即时生效。

---

## 3. 工程结构

```
app/src/main/java/com/platojobs/pkotlinapp/
├── PKotlinApplication.kt          # 创建 AppContainer、应用主题
├── di/AppContainer.kt             # 手动 DI：DB、仓库、网络、BLE、规则引擎
├── domain/
│   ├── model/Models.kt            # 领域模型（含同步字段）
│   ├── repository/Repositories.kt # 仓库接口
│   ├── rule/                      # RuleEngine + 文案接口
│   └── sync/                      # SyncEngine / 同步模型 / 幂等键（纯 Kotlin）
├── data/
│   ├── ble/                       # BleScanner / BleConnectionManager
│   ├── local/                     # Room 实体、DAO、数据库、v1→v6 迁移
│   ├── repository/                # Room 仓库实现
│   ├── remote/                    # DTO、ApiService、SyncApi、ApiClient
│   ├── sync/                      # token 加密存储、远程适配、WorkManager、同步编排
│   ├── prefs/AppSettings.kt       # 主题等设置
│   └── rule/AndroidRuleTextProvider.kt
└── ui/
    ├── MainActivity.kt            # 五模块底部导航 + 预测性返回
    ├── common/                    # 权限、Insets、RSSI、空状态、时间格式化
    ├── home/                      # 首页
    ├── devices/                   # 设备与规则
    ├── products/                  # 型号库
    ├── market/                    # 市场
    ├── profile/                   # 我的
    └── detail/                    # 设备详情（独立 Activity）
```

### 如何新增一个模块

1. 在 `ui/<feature>/` 新建 `XxxFragment`、`XxxViewModel`（factory 从 `PKotlinApplication.container` 取依赖）、适配器与 XML 布局。
2. 在 `menu/bottom_nav_menu.xml` 增加 item，在 `MainActivity` 的标签映射中登记标题与 Fragment 创建逻辑。
3. 数据访问先定义 `domain/repository` 接口，再在 `data/` 写实现，并注入 `AppContainer`。
4. 空状态一律使用代码构建的 `EmptyStateView`，列表使用 ViewBinding + RecyclerView。
5. 若需要新表：在 `data/local/Entities.kt` 加实体、`Daos.kt` 加 DAO、`AppDatabase` 注册并升级版本；schema 输出到 `app/schemas/`。
6. 若新数据需要跨端同步：实现 `SyncLocalStore` 适配与对应 DTO，并扩展 `SyncEngine`；同步字段统一使用 `id / platform / externalId / updatedAt / deletedAt / syncState`。

---

## 4. 双 View 构建约定（分模块口径）

工程同时保留两条 View 构建路径，按场景选用：

| 方式 | 适用场景 | 本工程代表 |
| --- | --- | --- |
| XML 布局 + ViewBinding | 页面骨架、列表项、表单等可静态描述的结构；类型安全、易预览、易改版 | 五个 Fragment、详情页、全部 `item_*.xml` |
| Kotlin 代码构建 View | 需要按状态/数据动态生成，或需要自定义绘制与自定义属性 | `ui/common/EmptyStateView.kt`、`ui/common/RssiSignalView.kt` |

- `EmptyStateView`：完全由 Kotlin 组装 `LinearLayout + ImageView + TextView + MaterialButton`，提供 `setIcon/setTitle/setHint/setAction`，被首页、设备、产品、市场复用。这是「代码建 View」的统一入口。
- `RssiSignalView`：自绘 View，完整实现 `onMeasure` 与 `onDraw`，支持自定义属性 `rsv_activeColor`、`rsv_inactiveColor`、`rsv_barCount`，直接接收真实扫描 RSSI 并在列表/详情中绘制信号格。

约定：**能用 XML 静态描述的上层结构优先 XML；可复用、随数据变化、需要自定义绘制的控件用 Kotlin 构建**。新增 feature 模块时同样遵守，不允许只在一处堆砌两种写法。

---

## 5. BLE 与 Android 16 适配

### 权限模型

| 权限 | 声明 | 运行时申请 |
| --- | --- | --- |
| `BLUETOOTH_SCAN` | `usesPermissionFlags="neverForLocation"` | Android 12+ |
| `BLUETOOTH_CONNECT` | 普通 | Android 12+ |
| `ACCESS_FINE_LOCATION` | `maxSdkVersion="30"` | SDK_INT < 31 |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | `maxSdkVersion="30"` | 安装期授予 |

所有蓝牙调用集中在 `data/ble/`，因权限由 UI 层保证，调用处使用 `@SuppressLint("MissingPermission")`。

### Android 16 广播（已核实常量存在）

在 **compileSdk 36** 的 `android.jar` / sources 中核实：

- `BluetoothDevice.ACTION_KEY_MISSING` = `"android.bluetooth.device.action.KEY_MISSING"`，标注 `@FlaggedApi`、protected broadcast，条件权限 `BLUETOOTH_CONNECT`（部分场景还要求 `BLUETOOTH_PRIVILEGED`）。
- `BluetoothDevice.ACTION_ENCRYPTION_CHANGE` = `"android.bluetooth.device.action.ENCRYPTION_CHANGE"`，同样为 `@FlaggedApi`，需要 `BLUETOOTH_CONNECT`。

因此代码直接引用系统常量，并通过 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` 动态注册（系统广播仍可投递）。收到 `ACTION_KEY_MISSING` 时：

1. 界面提示「设备绑定信息丢失，请重新配对」；
2. 将该 MAC 加入 `blockedMacs`，主动断开并**禁止自动重连**；
3. 用户重新配对后可手动点「已重新配对，重试连接」。

> 运行时是否真正投递取决于系统 flag 与权限，**本机无 BLE 真机，未做运行时验证**，详见「已知限制」。

### 其他 Android 16 行为

- **Edge-to-edge**：两个 Activity 调用 `enableEdgeToEdge()`，布局通过 `ui/common/Insets.kt`（`ViewCompat.setOnApplyWindowInsetsListener`）处理状态栏/导航栏/IME 遮挡；工程中不存在 `windowOptOutEdgeToEdgeEnforcement`。
- **预测性返回**：`MainActivity` 与 `DeviceDetailActivity` 均使用 `OnBackPressedCallback`；未重写 `onBackPressed()`，Manifest 也未设置 `enableOnBackInvokedCallback=false`。
- **大屏/横屏**：Manifest 未锁定 `screenOrientation`；列表使用自适应宽度与 RecyclerView，横屏/大屏不会因固定高度崩溃。

---

## 6. 数据层与多设备同步

### Room 与迁移（当前 v11）

数据库 `device_dashboard.db` 当前版本 **11**，共含设备、读数、规则、规则事件、扫描日志、场景、场景动作与房间等表：

| 表 | 关键字段 |
| --- | --- |
| `devices` | `mac` 主键、`name`（用户别名）、`aliasDirty`（用户是否显式编辑过别名，默认 0）、`advertisedName`（广播名）、`lastRssi`、`lastSeenTimestamp`、`id`（客户端 UUID）、`platform`、`externalId`、`updatedAt`、`deletedAt`、`syncState(pending/synced)`、`deviceType/vendor/firmwareVersion/compatibility`（enrich 缓存）、`deviceTypeName`（catalog 展示名）、`deviceGroup`（云端分组，`NULL`=未上报/未知，`""`=已显式清空）、`roomId`（本机房间归属，`NULL`=未分配） |
| `rooms` | 自增 id、`name`、`sortOrder`、`createdAt`；仅本机维护，不参与云端同步 |
| `rules` | 自增 id、`type`、`threshold`（RSSI 规则）、`enabled`、`createdAt`、`lastTriggeredTimestamp`；v10 新增 `metric`、`comparator`、`thresholdValue`、`consecutive`（指标规则；旧规则为 NULL/默认值） |
| `rule_events` | 规则触发记录；v10 新增 `metric`、`value`、`thresholdValue`、`unit`，保存指标命中的触发值与阈值证据 |
| `scan_logs` | 扫描日志 |

- `MIGRATION_1_2` 使用 `ALTER TABLE ... ADD COLUMN` 扩展字段，再逐行回填客户端 UUID、`platform=android`、`externalId=规范化大写 MAC`、`updatedAt=lastSeenTimestamp`、`syncState=pending`，**不丢已有设备数据**。
- `MIGRATION_2_3` 新增 4 个 enrich 可空列；`MIGRATION_3_4` 新增 `deviceTypeName` 与 `deviceGroup` 可空列；`MIGRATION_4_5` 新增 `advertisedName` 可空列，历史行保持 null，由展示层回退到别名/型号名；`MIGRATION_5_6` 新增 `aliasDirty INTEGER NOT NULL DEFAULT 0`，历史行默认 0；`MIGRATION_6_7` 新增 `readings`；`MIGRATION_7_8` 新增 `devices.createdAt`；`MIGRATION_8_9` 新增本机场景表。
- `MIGRATION_9_10` 为 `rules` 增加 `metric/comparator/thresholdValue/consecutive`，为 `rule_events` 增加 `metric/value/thresholdValue/unit`。旧 RSSI 规则的 `type` 与 `threshold` **原样保留**（仍走 `rssi < threshold`），只回填 `comparator='<'` 与 `thresholdValue=threshold` 作为语义副本；其它旧规则保持默认值。`RuleMigrationTest` 用真实 SQLite 验证列、回填与旧规则字段不变。
- `MIGRATION_10_11` 新增本机 `rooms` 表，并给 `devices` 增加可空 `roomId`。旧设备全部为 `NULL`（未分配），`deviceGroup` 原样保留；`RoomMigrationTest` 用真实 SQLite 验证设备数量/字段不变与 `roomId` 为 null。
- 迁移后 schema 输出在 `app/schemas/com.platojobs.pkotlinapp.data.local.AppDatabase/11.json`。
- 别名与广播名隔离：扫描只写 `advertisedName`，详情页编辑只写 `name`（alias），两者互不覆盖；列表/详情展示优先级为「用户别名 → 广播名 → catalog 型号名 → 未知设备」。
- **别名清空语义**：`aliasDirty` 只在详情页保存且别名相对初始值发生变化时置 1（一旦置位保持），扫描路径永远不置位。置位后即使 `name` 为空，推送也会显式上报 `"alias":""`；未置位时不报空别名。服务端返回空别名时按「已清空」写回本地（`name=""`），展示层回退到广播名，旧别名不会被拉回复活。
- `DeviceDao` 使用 `@Upsert insertOrUpdate`、`suspend getAllDevices`，并提供待同步查询、待同步计数、按 externalId 标记 synced 等同步专用方法。
- 删除设备改为**墓碑（tombstone）**：写 `deletedAt + syncState=pending`，UI 通过 `deletedAt IS NULL` 过滤，推送成功后再物理删除。

### 多设备同步

后端设备唯一键为 `(user_id, platform, external_id)`。Android 端固定：

- `platform = "android"`
- `externalId = 规范化大写 MAC`（`AA:BB:CC:DD:EE:FF`）
- iOS 端使用 `CBPeripheral.identifier` 作为 `externalId`，两端通过该唯一键对齐。

同步 API（`SyncApi`，字段与 `PJGoIoT/api/openapi.yaml` 对齐，Base URL 见下节）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `api/v1/auth/anonymous` | 匿名登录，请求体可为空，`201` 返回 `{ userId, token }` |
| `GET` | `api/v1/sync/changes?cursor=<整数>&limit=` | 增量拉取，返回 `{ changes, nextCursor, hasMore }` |
| `POST` | `api/v1/sync/push` | 批量推送，Header 带 `Authorization` 与 `Idempotency-Key` |

契约与本地模型的映射：

- push 请求体为 `{ clientId, devices: [...] }`；`clientId` 使用安装时生成的 installId。
- 设备字段：`alias` ↔ 本地 `name`，`advertisedName` ↔ 本地同名列（与别名独立）；
  `scanRecords[{rssi, at}]` ↔ `lastRssi` / `lastSeenTimestamp`；
  `updatedAt` / `deletedAt` 为 RFC 3339 字符串，本地 Room 存 epoch millis。
- Android 的 `mac` 元数据为小写 MAC，`externalId` 为规范化大写 MAC；iOS 无 `mac`（`externalId` 为 CBPeripheral.identifier）。
- 增量 cursor 是服务端整数序列号，本地以字符串透传。
- push 响应为 `{ applied, conflicts, nextCursor }`；`applied[].status` 为 `applied` / `unchanged`。

实现要点：

- **Token 安全**：匿名 token 仅写入 `EncryptedSharedPreferences`（MasterKey 由 Android Keystore 保护），不落明文数据库、不打印日志。**401 不自动重新登录**：停止同步、保留本地数据与游标，在「我的」页显示「登录已失效」+「重新登录」，由用户确认后才换新匿名账号（旧账号数据不会被删除或覆盖）。
- **重新登录**：清空失效 token、重置游标、把本机记录重新排队推送到新账号；确认框会明确提示这一行为。
- **可缺省字段三态（openapi 0.3.3）**：DTO 用 `Reported<String>?`（`data/remote/SyncApi.kt`）区分 absent / 空串 / 值，Moshi 自定义 adapter 保证 `null` 完全省略、`Reported("")` 序列化为 `"group":""`。
  - `group` 为 `""` 时显式清除；为 `null`（未上报）时完全省略，服务端保持原值；
  - 拉取/冲突响应中被省略的字段解析为 absent，写回 Room 时保留本地值，不会误清空；
  - 扫描只上报 `advertisedName`，用户编辑只上报 `alias`（本地已有别名时再次上报同值，不会覆盖服务端广播名）；
  - `alias` 与前两者不同：它由本地 `aliasDirty` 区分「未编辑」与「显式清空」。未编辑且为空时省略；显式清空时上报 `"alias":""`；拉回空别名会清空本地旧值并按广播名展示。
- **幂等推送**：`IdempotencyKeys.forBatch` 只对**实际会上报**的字段（含 alias/广播名/型号/分组、RSSI、时间）生成稳定的 SHA-256 key；未上报字段不参与哈希，避免同内容重试被算成不同请求而 409。同一 key + 同一请求体重放会命中服务端幂等缓存（`Idempotent-Replayed: true`），同一 key + 不同请求体返回 `409 IDEMPOTENCY_CONFLICT`。
- **冲突处理**：服务端按 LWW 返回 `conflicts`（如 `server_newer`）时以服务端版本覆盖本地，但结果会通过 `SyncOutcome.conflicts` 上报，并显示在「我的」页，**不静默覆盖**。
- **拉取合并**：若本地记录为 `pending` 且 `updatedAt` 更新，则不被较旧的服务端版本覆盖；服务端 tombstone 直接删除本地记录。
- **WorkManager**：`SyncWorker` 在 `NetworkType.CONNECTED` 约束下批量 push，成功后置 `synced`；失败按指数退避重试。
- **冷启动**：`PKotlinApplication.onCreate` 只做容器装配与主题恢复；`MainActivity` 不再等待网络，直接渲染首页骨架/空态；同步、保留清理与周期调度在首帧之后由 `startBackgroundWork()` 投递到 IO 线程（详见 §12 的真实测量数字）。
- **降级**：连续失败 3 次进入纯本地模式并持久化，主界面顶部横幅与「我的」页同步卡片都会提示；本地 Room 功能不受影响，用户可手动同步恢复。
- **测试**：`SyncEngineTest` 覆盖幂等重试、未上报字段不参与幂等键、冲突落败上报、tombstone 删除与拉取合并；`Rfc3339Test` 覆盖 Go 纳秒时间与带时区偏移的解析；`SyncDtoMappingTest` 覆盖三态序列化（absent/空串/值）、别名与广播名隔离、absent 解析、push DTO 字段归一化、iOS 无 MAC、tombstone 与非法 id 回退；`SyncErrorKindTest` 覆盖网络/超时/401/403/409/500/未知分类；`RemoteSyncTokenTest` 覆盖「401 不自动重登、保留 token、其它错误原样抛出」；`OfflineDeviceInfoTest` 覆盖展示优先级与离线分组；`DeviceAliasPersistenceTest` 覆盖显式清空别名落库、扫描不覆盖别名/不置脏；`DeviceMigrationTest` 用真实 SQLite 验证 v3→v4、v4→v5、v5→v6 不丢数据。

同步往返样例（与 `openapi.yaml` 一致）：

```json
// POST /api/v1/auth/anonymous   -> 201
{ "userId": "2a1b...", "token": "<anonymous-token>" }

// POST /api/v1/sync/push
{
  "clientId": "install-uuid",
  "devices": [
    {
      "id": "client-uuid",
      "platform": "android",
      "externalId": "AA:BB:CC:DD:EE:FF",
      "mac": "aa:bb:cc:dd:ee:ff",
      "deviceType": "nordic_uart",
      "alias": "BLE Tracker",
      "group": "",
      "rules": {},
      "scanRecords": [{ "rssi": -55, "at": "2026-11-05T01:00:00Z" }],
      "updatedAt": "2026-11-05T01:00:00Z",
      "deleted": false
    }
  ]
}
// -> 200
{
  "applied": [
    { "id": "client-uuid", "platform": "android", "externalId": "AA:BB:CC:DD:EE:FF",
      "mac": "aa:bb:cc:dd:ee:ff", "status": "applied", "serverSeq": 1 }
  ],
  "conflicts": [],
  "nextCursor": 1
}

// GET /api/v1/sync/changes?cursor=0
{
  "changes": [
    {
      "seq": 1,
      "entity": "user_device",
      "entityId": "<user-id>:android:AA:BB:CC:DD:EE:FF",
      "op": "upsert",
      "payload": {
        "id": "client-uuid",
        "platform": "android",
        "externalId": "AA:BB:CC:DD:EE:FF",
        "mac": "aa:bb:cc:dd:ee:ff",
        "alias": "BLE Tracker",
        "scanRecords": [{ "rssi": -55, "at": "2026-11-05T01:00:00Z" }],
        "updatedAt": "2026-11-05T01:00:00Z",
        "deletedAt": null,
        "deviceType": "nordic_uart",
        "vendor": "Nordic Semiconductor",
        "firmwareVersion": "nRF Connect SDK v2.9.0",
        "compatibility": {
          "platforms": ["android", "ios"],
          "protocol": "ble-gatt"
        }
      },
      "serverTs": "2026-11-05T01:00:00.123456789Z"
    }
  ],
  "nextCursor": 1,
  "hasMore": false
}
```

### 设备 enrich 字段（openapi 0.3.2）

`GET /my/devices/android/{externalId}` 的 `UserDevice` 从 0.3.2 起返回：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `deviceType` | string \| null | 型号键（如 `nordic_uart`）；未注册型号为 null |
| `vendor` | string \| null | 厂商，优先公共设备库，其次型号库 |
| `firmwareVersion` | string \| null | 固件版本，无关联时为 null |
| `compatibility` | object \| null | `platforms` / `minAppVersion` / `protocol` / `features` |

客户端约定：

- 设备详情**优先使用云端 enrich**；字段为 null 时展示「未知」，不显示 `null` 或空串。型号展示顺序：catalog 中文名 → 型号键 +「（未识别的型号）」→ 「型号未知」；未注册型号仍展示 alias/广播名、分组与最后出现时间。
- 名称展示顺序：用户别名（`name`）→ 广播名（`advertisedName`）→ catalog 型号名；扫描只写广播名，用户编辑只写别名。
- 成功结果写入 Room（v3 迁移缓存 `deviceType/vendor/firmwareVersion/compatibility`，v4 迁移追加 `deviceTypeName/deviceGroup`，v5 迁移追加 `advertisedName`）；断网或网络失败时读取本地缓存降级，并标注「网络不可用，以下为本地缓存数据」。
- 离线时型号优先显示缓存的 catalog 展示名；只有型号键时回退为「`nordic_uart`（未识别的型号）」，两者都没有才显示「型号未知」。
- 分组随 `UserDevice.group` 落库到 `deviceGroup`；设备详情「编辑别名与分组」在本地即时生效，离线展示与设备列表分组统计都读取该值。扫描写入不会覆盖已缓存的分组/enrich/别名。
- push 的 `PushDevice` 新增**可选** `deviceType`：只有本地缓存或 catalog 能匹配到型号时才填写；未匹配时字段不序列化（Moshi 默认省略 null），服务端沿用旧值，不会把型号清空。`alias/advertisedName/group` 同理：未上报即省略，`group=""` 才是显式清除。
- `catalog.firmwares` / `catalog.accessories` 的 `vendor` / `compatibility` 用于产品展示与市场真实维度筛选。

### 契约端到端联调（e2eContract）

本地手动执行方式如下；GitHub Actions 也会在 push / PR 时自动跑同一脚本（见「CI 里的契约联调」）。前置条件：

- Go 1.22+（本机实测 Go 1.27.3）；
- JDK 21；
- 与本仓库同级的 `PJGoIoT` 目录（也可用 `PJGOIOT_DIR=/path/to/PJGoIoT` 指定）。

```bash
./scripts/e2e-contract.sh
```

脚本行为：

1. 从 `${PJGOIOT_DIR:-../PJGoIoT}` 构建并启动 `STORE=fixture` 的 Go 服务，默认端口 `8080`；
   若 8080 已被占用则自动改用 `18080` 并打印说明；
2. 轮询 `/api/v1/health` 直到 200（最多 30 秒）；
3. 执行 `./gradlew :app:e2eContract -Pe2eBaseUrl=http://127.0.0.1:<port>`；
4. 结束后关闭服务，失败时打印 Go 服务日志最后 60 行并返回非零退出码。

JVM 测试用 OkHttp 直连本地服务，不依赖模拟器，覆盖：

- `/api/v1/health` 200 且 `store=fixture`；
- 匿名登录返回 UUID `userId` 与非空 token；
- 未带 token 访问 changes / push 返回 `401 UNAUTHORIZED`；
- 一次 push 两条设备（android + ios 同型号），校验 `applied` 与 `platform/externalId`；
- changes 校验 `seq/entity/entityId/op/payload/serverTs` 形状、RFC 3339 时间、`nextCursor/hasMore`；
- 同一 `Idempotency-Key` + 同一请求体重放：`Idempotent-Replayed: true`，且不产生新 change；
- LWW 冲突：旧 `updatedAt` 推送返回 `server_newer` 冲突，服务端数据不被覆盖、不产生新 change；
- 同一 key + 不同请求体返回 `409 IDEMPOTENCY_CONFLICT`；
- 跨用户访问他人设备返回 `404 NOT_FOUND`，且 changes 按用户隔离；
- UserDevice enrich：`deviceType/vendor/firmwareVersion/compatibility` 均返回且可解析；
- 未注册型号：4 个 enrich 字段为 null、接口仍 200，alias/group 保留，不返回 500；
- 可选 `deviceType`：匹配到才序列化，未匹配时字段被省略；再次推送不带该字段时服务端保留旧值；
- 别名/广播名隔离：只推 `advertisedName` 不改 alias，只推 `alias` 不改广播名；
- 分组三态：`group=""` 显式清除；后续不带 `group` 的同步不会把已清空分组复活；
- 冲突响应缺省字段解析为 absent（不会回填空串清掉本地数据）；
- catalog：`firmwares` / `accessories` 均带 `vendor` 与 `compatibility`，未注册型号 catalog 返回 404；
- 生产 Retrofit `SyncApi` 的匿名登录、changes 解析与 push（`toPushDto` 映射）实际调用通过。

本机实跑结果（2026-11-05，PJGoIoT v0.3.3）：上述 22 项全部通过；另验证了 8080 被占用时自动回退 18080。

### CI 里的契约联调

GitHub Actions 的 `e2e-contract` job 与 `verify` job **并行**执行，不会阻塞 debug / lint / 单测；仅在 `push` 与 `pull_request` 触发。

job 做了这些事：

1. checkout 本仓库，并用 `actions/checkout@v7` 的 `path` 把 `platojobs/PJGoIoT` 放到 `PJGoIoT/`；
2. `actions/setup-go@v7` 按 `PJGoIoT/go.mod` 安装 Go 1.22+，`actions/setup-java@v6` 安装 JDK 21；
3. 以 `PJGOIOT_DIR=${{ github.workspace }}/PJGoIoT` 执行 `./scripts/e2e-contract.sh`（脚本自带 8080 → 18080 端口回退与 health 轮询）；
4. 无论成败上传 `app/build/reports/tests/e2eContract` 与 `app/build/test-results/e2eContract`；
5. 失败时额外上传 `e2e-logs/go-service.log`（CI 使用 `E2E_LOG_LEVEL=info`，本地默认 `warn`）。

这个 job 专门防「契约漂移当场变红」，覆盖：

- **字段漂移**：push 请求体 / `applied` / `conflicts` / changes 元素字段与 openapi 不一致时，生产 Retrofit DTO 解析会失败；
- **幂等契约**：同 key + 同体重放不产生新 change，同 key + 异体必须 409；
- **游标类型**：`nextCursor` 按整数游标解析与回传，字符串游标会直接失败；
- **时间格式**：`serverTs` 必须是 RFC 3339，Go 纳秒精度也要能解析；
- **复合键**：`platform + externalId`（Android 大写 MAC）查询与 changes 回放；
- **enrich 字段**：`UserDevice.deviceType/vendor/firmwareVersion/compatibility` 与 catalog 的 `vendor/compatibility` 缺失或改类型会红；
- **可选字段语义**：未注册型号字段必须为 null（不能 `500` 或假值），未上报 `deviceType/group/alias/advertisedName` 时服务端不得清空旧值；`group=""` 必须显式清除；
- **别名/广播名**：`advertisedName` 与 `alias` 独立，推广播名不改别名，反之亦然；
- **鉴权**：缺 token 返回 401、跨用户访问返回 404。

CI action 已升级到当前推荐 major：`checkout@v7`、`setup-java@v6`、`setup-go@v7`、`setup-gradle@v6`、`upload-artifact@v7`，用于消除 Node 20 / 弃用告警。

本地复现失败：

```bash
./scripts/e2e-contract.sh
```

若想主动验证「漂移会红」，可在 `../PJGoIoT` 临时修改某个响应字段（例如把 `nextCursor` 改成字符串），再执行上面的命令；测试失败后脚本会打印 Go 服务日志最后 60 行并以非零码退出。恢复后端改动后重跑即可。
`e2eContract` task 设置了 `outputs.upToDateWhen { false }`，因此不会因为 UP-TO-DATE 而假通过。

CI run 链接（`main` @ `cb8d046`，2026-11-05，action 升级后）：

- 整轮运行：<https://github.com/platojobs/PKotlinApp/actions/runs/41902523671>（`verify` 与 `e2e-contract` 均 success）
- `e2e-contract` job：<https://github.com/platojobs/PKotlinApp/actions/runs/41902523671/job/126828424666>
- `verify` job：<https://github.com/platojobs/PKotlinApp/actions/runs/41902523671/job/126828424829>

### 网络配置（BASE_URL）

不硬编码公网地址，`BuildConfig.API_BASE_URL` 按以下优先级生成：

1. Gradle 属性：`./gradlew assembleDebug -PapiBaseUrl=http://192.168.1.10:8080/`
2. `local.properties`：`apiBaseUrl=http://10.0.2.2:8080/`
3. 默认值：`http://10.0.2.2:8080/`（Android 模拟器访问宿主机的本地 Go 服务）

Debug / Release 都可通过 `apiBaseUrl` 切换；发布前请传入正式域名并确认 HTTPS。Debug 构建通过 `app/src/debug/AndroidManifest.xml` 允许明文 HTTP（便于连接局域网 Go 服务），Release 不放开明文，必须使用 `https://` 地址。产品、市场、设备详情与同步接口均使用该 Base URL，本地没有后端时这些网络页面会显示错误空状态并提供重试，不会崩溃或展示假数据。

### 断网降级

- 产品 / 市场：请求失败显示错误文案 + `EmptyStateView` 重试，不展示假数据。
- 设备详情：设备信息失败时读取 Room 缓存并提示「网络不可用，以下为本地缓存数据」；名称按「别名 → 广播名 → 型号名」展示；型号优先显示缓存的 catalog 展示名，缺失时回退型号键并标注「未识别的型号」；分组按 `deviceGroup` 真实值展示，不再一律显示「未分组」。连接状态与 RSSI 仍来自真实 BLE。
- 设备编辑：详情页「编辑别名与分组」直接写 Room 并排队同步；断网时保存也会立即在详情与设备列表生效，重连后推送。清空分组会存为 `""` 并在下次同步显式上报，扫描不会把它复活；清空别名会置 `aliasDirty` 并上报 `"alias":""`，扫描/拉回都不会复活旧别名。
- 设备列表：每行展示「分组：X / 未分组」，标题旁统计「共 N 台设备 · M 个分组」，数据全部来自 Room，离线一致。
- 同步：连续失败 3 次进入纯本地模式，主界面横幅 +「我的」同步卡片提示；Room 数据（首页、设备、日志）不依赖网络。
- 首页 / 设备 / 我的：本地 Room 数据不依赖网络，断网仍可用。
- 离线验收步骤见 [真机验收清单 §7.2](docs/acceptance.md#72-离线缓存与分组验收v035-新增)；别名/广播名与分组三态验收见 [§7.4](docs/acceptance.md#74-别名--广播名与分组三态验收v036-新增)；清空别名验收见 [§7.5](docs/acceptance.md#75-清空别名验收v037-新增)；读数采集验收见 [§7.6](docs/acceptance.md#76-设备读数readings验收v040-新增)；长周期图表与区间验收见 [§7.7](docs/acceptance.md#77-长周期图表与区间验收v050-新增)；告警通知见 [§7.8](docs/acceptance.md#78-告警通知验收v060-新增)、CSV 导出见 [§7.9](docs/acceptance.md#79-读数-csv-导出验收v060-新增)、深色模式见 [§7.10](docs/acceptance.md#710-深色模式验收v060-新增)。

### 6.1 数据采集与长周期图表（readings，v0.5.0）

设备连接并发现 GATT 服务后，App 会订阅以下 notify/indicate 特征，并把解析成功的读数写入 Room、随后随同步批次推送到服务端：

| 特征 UUID | 指标 | 统一解析规则（与 iOS 对齐） | 单位 |
| --- | --- | --- | --- |
| `0x2A37` 心率测量 | `heart_rate` | 首字节 flags；bit0=0 取第 2 字节 uint8；bit0=1 取第 2-3 字节 uint16 **小端**（低位在前）；只接受 1..300 | `bpm` |
| `0x2A19` 电池电量 | `battery` | 单字节百分比，**0 与 100 均合法**；101..255 丢弃 | `%` |
| `0x2A6E` 温度 | `temperature` | **2 字节**：优先 sint16 × 0.01℃，落在 −50..150℃ 时采用；否则回退 IEEE-754 半精度。**4 字节**：float32（小端）。所有路径 NaN/Inf 或超出 −50..150℃ 都丢弃 | `°C` |
| `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` NUS TX | 由文本决定 | 按 `\n` **逐行解析**，trim 后每行独立成一条读数；支持 `metric=value` 与纯数字；未知指标记 `other` 并保留原文快照。整包 ≤256 字节，**单行 ≤64 字符**，单行失败只丢该行 | 按指标 |

**黄金字节表**（同一输入必须得到唯一输出，已固化在 `GattReadingParserTest`）：

| 特征 | 输入 hex | 输出 |
| --- | --- | --- |
| 心率 | `00 48` | `heart_rate` 72 bpm |
| 心率 | `00 00` | 丢弃（0 不合法） |
| 心率 | `01 2C 01` | `heart_rate` 300 bpm（小端 uint16） |
| 心率 | `01 2D 01` | 丢弃（301 超范围） |
| 电池 | `64` / `00` | `battery` 100% / 0% |
| 电池 | `65` | 丢弃 |
| 温度 | `2A 08` | `temperature` 20.90℃（sint16 × 0.01） |
| 温度 | `70 FE` | `temperature` −4.00℃ |
| 温度 | `00 00 C0 3F` | `temperature` 1.5℃（float32） |
| 温度 | `FF FF` | `temperature` −0.01℃（优先 sint16，不按 half NaN 丢弃） |
| 温度 | `00 4A` / `00 4D` | **两端实测已确认**：12.0℃ / 20.0℃（iOS 用 `Float16(bitPattern:)` 真解码，Android 同口径半精度回退） |
| NUS | `temperature=23.5\nbattery=88\nxx` | 2 条读数（第 3 行丢弃） |
| 任意 | 空字节 | 丢弃，不崩溃 |

解析失败（空字节、长度不足、超长、非数字、NaN/Inf、超出合理范围）**静默丢弃**：只写一条 `Log.w("BleReadings", …)` 本地日志（含特征 UUID、长度和十六进制快照），不写 Room、不影响连接状态、不崩溃；NUS 部分行失败时其余合法行照常入库。可读特征在订阅后还会主动 `readCharacteristic` 一次，获取电池/温度初始值。

**本地存储（Room v7）**

- 新增 `readings` 表：`id`（UUID 主键）、`deviceId`（外键指向 `devices.mac`，`ON DELETE CASCADE`）、`metric`、`value`、`unit`、`recordedAt`、`syncState`（`pending`/`synced`）、`rawSnapshot`。
- 索引：`deviceId`、`syncState`、`(deviceId, metric, recordedAt)`；`MIGRATION_6_7` 只 `CREATE TABLE` + 索引，不重建既有表。
- 指标白名单：`temperature` / `humidity` / `battery` / `heart_rate` / `co2` / `pressure` / `other`；未知指标入库前归一为 `other`，并保留原始字节/文本摘要。
- 保留期：客户端本地 **7 天**，在 `Application.onCreate` 与每次读数同步完成后执行清理；服务端保留 **90 天**。清理条件为 `recordedAt < cutoff AND syncState = 'synced'`，**pending 记录无论多旧都保留**，避免离线超过 7 天的数据被误删；同步成功后再按保留期清理。

**同步策略**

- 读数使用独立的 `ReadingSyncer`，复用同一个 `POST /api/v1/sync/push`：每批最多 **200** 条，成功后将对应行标为 `synced`，循环直到不足一批。
- 幂等：批次 `Idempotency-Key` 由按 `id` 排序后的 `id/deviceId/metric/value/unit/recordedAt` 做 SHA-256（前缀 `readings-`）；失败重试复用同一幂等体，服务端再按 `id` 去重，重复 id 计入 `skippedReadings` 而不报错。
- 断网时读数继续本地累积（`pending`），网络恢复后由常规同步/重试路径补传；设备与读数可以同批推送，服务端响应新增 `appliedReadings` / `skippedReadings`。
- 远端查询：`GET /api/v1/my/devices/{platform}/{externalId}/readings`（倒序游标分页、可按 metric/时间过滤）、`/readings/summary?metric=&hours=24`（min/max/avg/last）、`/readings/series?metric=&from=&to=&bucket=`（长周期分桶）与 `/readings/metrics`（指标清单）。详情页 24h 汇总优先远端，失败或为空时回退本地并显示「离线：本地数据」。

**详情页「数据」区**

- 指标 Chip 来源为服务端 `GET .../readings/metrics`，展示「指标名 · N 条」并按 `lastAt` 倒序；`unit` 取该指标最新一条读数。网络失败时才回退本地聚合，无指标时显示「连接设备后开始采集」，没有蓝牙连接权限时显示提示并提供「去应用设置」。
- 区间 Chip：**1 小时 / 24 小时 / 7 天 / 30 天**，默认 24 小时；切换后立即重新查询并重绘。长周期使用自绘 `ReadingChartView`：按桶 `start` 的真实时间比例定位 X 轴，桶间缺口断线，每个桶画 avg 折线并用浅色带标出 min–max；几何计算全部在纯 Kotlin `ReadingChartGeometry`，不依赖 Android 运行时。
- **图表点选（v0.11.0）**：点按或长按图表选中最近桶，图表下方展示该桶区间（本地时区）、min/max/avg/last 与真实条数；点空白取消并恢复提示。选中态只复用现有图表语义色（`chart_point` / `chart_line`），不改 Y 轴、不引入新色值（`ChartColorAuditTest` 继续守护）。整图 `contentDescription` 默认播报「指标，区间，最低 X，最高 Y，共 N 个数据点」，选中后更新为当前桶摘要。命中判定为纯函数 `ReadingChartGeometry.hitTest`（最近桶 / 阈值内命中 / 空白返回 null），单测覆盖。
- 汇总行固定展示**最近 24 小时**的最小/最大/平均/最新，不随区间切换；单位与当前选中指标一致。「最近记录」展示最新 10 条（值 + 相对时间）。所有数值均来自真实 Room/远端数据，不放演示假数据。

**区间与分桶（v0.5.0）**

| 区间 | bucket | 理论桶数（≤500） | 说明 |
| --- | --- | --- | --- |
| 1 小时 | `1m` | 60 | 分钟级细节 |
| 24 小时 | `1h` | 24 | 默认区间 |
| 7 天 | `1h` | 168 | 小时级趋势 |
| 30 天 | `1d` | 30 | 日级趋势 |

- 请求：`GET /api/v1/my/devices/{platform}/{externalId}/readings/series?metric=&from=&to=&bucket=`，`from`/`to` 为 **UTC RFC3339**。若首选粒度会超过 500 桶，`ReadingRange.chooseBucket` 会自动退到更粗的粒度（`1m → 1h → 1d`）。
- **UTC 网格**：`1m` 对齐整分钟、`1h` 对齐整点、`1d` 对齐 UTC 零点，与服务端 `BucketStart` 完全一致，因此离线本地分桶与在线图表落在同一时间轴。
- **服务端只返回有数据的桶**（空桶不返回、不补零）。客户端必须按 `start` 的真实时间比例定位，绝不能按数组下标等距绘制；当 `bucket.start > previous.end` 时视为缺口，折线**断开**，不得跨越空桶连线。这是长周期图最常见的画错点，`ReadingChartGeometryTest` 有专门用例固化。
- 网络失败时回退本地（Room 仅保留 7 天）：本地读数按同一 UTC 网格聚合，30 天区间标注「离线：仅本地 7 天数据」，数据集中在时间轴右侧；其余区间标注「离线：本地数据」。
- Y 轴按区间内真实 min/max 自适应；单桶或同值时自动展开固定范围，标签带当前指标单位。

### 6.2 告警通知（v0.6.0）

- 规则引擎命中 `LOW_RSSI` / `DISCONNECTED` / `BOND_LOST` 后，通过 `RuleAlertSink` 投递本地通知；Android 8+ 使用单独渠道 **「设备告警」**（`device_alerts`），通知点击直接打开对应设备详情。
- **权限**：Android 13+ 动态申请 `POST_NOTIFICATIONS`（新增规则或打开「我的」告警开关时触发）；拒绝后「我的」显示「系统通知权限未授予」与「去设置授予通知权限」按钮，不阻断规则记录、扫描、同步等功能。
- **节流**：同一设备 + 同一规则在窗口内只通知一次，默认 **5 分钟**，可在「我的」选择 **1 / 5 / 30 分钟**。窗口与「恢复待发」状态落 `SharedPreferences`（`alert_notification_state`），进程重启后仍去重。
- **恢复**：信号回落到阈值内时发一条「告警已恢复」，每次告警只发一次；关闭开关或权限缺失时不投递，但规则事件仍写入 Room。
- 通知正文只含设备名、指标、当前值、阈值与触发时间（例如「客厅温度计 · 信号强度 -85 dBm（阈值 -70 dBm）· 10:32」），**不放 token / 账号**。

### 6.3 读数 CSV 导出（v0.6.0）

- 详情「数据」区新增「导出 CSV」：导出 **当前设备 + 当前指标 + 当前区间**。区间跨度 **≤7 天**直接读 Room；**>7 天**走服务端 `GET .../readings?limit=100&cursor=...` 分页直到 `hasMore=false`，最多 **5 万条**，超限提示「请缩短区间后重试」。
- 文件：UTF-8 **BOM**，表头固定 `recordedAt,metric,value,unit`，时间为 **ISO8601 UTC**（`2026-01-01T04:00:00Z`），逗号/引号/换行按 RFC 4180 转义；空数据仍输出表头。
- 路径：只写 `cacheDir/exports/`，文件名 `readings_<MAC>_<metric>_<range>_<UTC时间>.csv`；通过 `FileProvider`（`${applicationId}.fileprovider`）生成 `content://` URI，用 `ACTION_SEND` 系统分享，不落应用私有目录之外。
- UI：导出期间显示**真实累计条数**（「正在导出… N 条」）；失败给出明确原因与「重试」操作，不静默失败。

### 6.4 设备列表检索与排序（v0.6.0）

- 搜索框即时过滤：别名、广播名、型号展示名、MAC（大小写不敏感；MAC 可忽略冒号，如 `aabbcc112233`）。
- 排序 Chip：**最近出现（默认） / 名称 / 信号强度 / 添加时间**，选择写入 `AppSettings`，重启后保持；「添加时间」来自 Room 新增的 `devices.createdAt`（只写一次，升级时用 `lastSeenTimestamp` 回填）。
- 分组 Chip 来自真实 `deviceGroup` 值（去重升序），存在空分组时追加「未分组」；与搜索可叠加。
- 空结果分两种：**没有设备** → 引导「去扫描」（切到首页扫描 Tab）；**筛选无结果** → 「没有匹配的设备」+ 一键「清除筛选」。

### 6.5 深色模式（v0.6.0）

- 「我的」主题三态保持不变：**跟随系统 / 浅色 / 深色**，切换通过 `AppCompatDelegate` 立即重建界面。
- 图表折线、数据点、网格、坐标轴、min–max 区间带，以及 Chip、卡片、次要文字的颜色全部来自主题资源；深色值集中在 `values-night/colors.xml`，代码中**不出现** `#FFF` / `#000` / `Color.rgb(...)` 等硬编码。
- `ChartColorAuditTest` 扫描 `ui/chart` 源码与布局，断言无颜色字面量，并校验日间/夜间资源成对存在；深色下网格 `#3A4152`、坐标轴 `#8B93A7`、折线 `#A8B4FF`，对比度满足可读性。

**限制**：模拟器无法验证真实 BLE notify 投递与厂商固件字节格式；解析规则已用单测覆盖，但真实心率带/温湿度计/NUS 设备需按 [§7.6](docs/acceptance.md#76-设备读数readings验收v040-新增) 真机验收。

### 6.6 账号迁移与注销（v0.7.0）

对齐 PJGoIoT openapi 0.6.0。

- **迁移到新设备**：`POST /auth/pairing-code`（需 Bearer）返回 8 位码与 `expiresAt`。客户端只以服务端 `expiresAt` 驱动倒计时（每秒刷新），**不自行推算 10 分钟**；到 0 显示「已过期，请重新生成」。支持复制到剪贴板；「重新生成」会调用同一接口，**新码立即使旧码失效**，界面先弹确认并提示这一点。
- **从旧设备接管**：输入 8 位码 → `POST /auth/pairing-code/claim`（无需鉴权）→ 用返回的 `{userId, token}` 替换本机 token。接管成功后**保留本地设备与读数**，把 Room 中 `sync_state` 全量重置为 `pending`（`deviceDao.markAllPending()` + `readingDao.markAllPending()`），清空同步游标并重置同步状态，让新账号拿到本机已有数据。
- **输入约束**：`PairingCodeFormat` 统一 `ALPHABET = ABCDEFGHJKLMNPQRSTUVWXYZ23456789`（剔除易混的 `0/O/1/I`），输入自动大写、过滤非法字符、截断到 8 位；不足 8 位时「接管」按钮不可用，不做「任意输入」的模糊校验。
- **错误处理**：`404` 统一文案「码无效或已过期」，**不区分**过期/已用/不存在/格式错误；`429` 显示「尝试过于频繁，请稍后再试」并在可取得 `Retry-After` 时展示剩余冷却秒数；网络错误给可重试提示；`401` 提示重新登录。
- **导出我的数据**：`GET /me/export` 按 `hasMore` / `nextCursor` 循环拉全，**cursor 只透传、不解析**（`AccountExportPager` 仅在 `hasMore=false`、空游标或游标重复时停止）；多页 `devices` 按 `id`/`externalId` 去重，`readings` 顺序拼接；结果为带缩进的 JSON，写 `cacheDir/exports/account_export_<userId>_<UTC时间>.json`，经 `FileProvider` + `ACTION_SEND` 分享。导出期间显示真实「已拉 N 条 / 第 M 页」，失败可重试。
- **注销账号**：二次确认对话框写明「设备与读数将永久删除且不可恢复」；确认后 `DELETE /api/v1/me`（成功 204）。成功后依次清 token（`EncryptedSharedPreferences` 整块清空）、游标、同步状态、`Room.clearAllTables()`，回到初始匿名状态并提示；`401` 与网络错误分别提示，**不静默失败**。

### 6.7 后台采集（前台服务，v0.7.0）

- 设备详情连接成功后显示「后台采集」开关；开启后由**前台服务**保持 BLE notify 采集，状态在 `CollectionSession`（`StateFlow`），通知常驻。
- 通知渠道 **「数据采集」**（`reading_collection`，ID `2001`），标题「正在后台采集」，正文「设备名 · 已采集 N 条」，带「停止」动作；点击通知进入该设备详情。通知为 `ongoing` + `onlyAlertOnce`，不会重复打扰。
- **权限与 manifest**：manifest 声明 `FOREGROUND_SERVICE` 与 `FOREGROUND_SERVICE_CONNECTED_DEVICE`，服务 `foregroundServiceType="connectedDevice"`；Android 14+（API 34+）必须有 `CONNECTED_DEVICE` 类型权限，否则启动失败并回滚开关 + 提示，不崩溃。Android 13+ 需要 `POST_NOTIFICATIONS`：**拒绝通知权限不阻断采集**，只在界面上提示「通知不可见」。
- **启动限制**：Android 12+ 禁止从后台静默启动前台服务，本功能只从用户点击开关（界面可见时）触发；开关状态写入 `AppSettings.backgroundCollectionEnabled`，重启后保持。
- **停止**：手动关闭开关、断开连接、退出详情页、连接变为 `Idle/Disconnected/KeyMissing`、或切换到另一台设备时，服务与通知都会被正确停止/清理。

### 6.8 场景（仅本机，v0.8.0）

**场景仅保存在本机 Room，不会同步到云端**（界面以「仅本机」badge 明示）。账号切换/注销不会上传场景；迁移到新设备时场景只在原设备可用。

- 数据模型：`scenes(id, name, createdAt)` + `scene_actions(id, sceneId, position, type, deviceMac, muteMinutes)`，`sceneId` 外键 `CASCADE`，Room v9（`MIGRATION_8_9`）。
- 动作集合只包含真实可执行能力：连接指定设备、断开指定设备、开始后台采集、停止后台采集、静音告警 10/30/60 分钟、刷新同步。界面下拉的设备来自本机已保存设备；静音时长限定三档，杜绝「播放音乐」这类假动作。
- 执行语义：按 `position` 顺序逐个执行；**任一动作失败不阻断后续动作**，全部执行完汇总为成功/失败计数。每个动作都反馈成功或失败原因，例如「缺少蓝牙连接权限」「设备未连接，无需断开」「请先连接设备」「已在后台采集中，未重复执行」「当前未在后台采集」。
- 进度：执行中当前场景行显示「正在执行 动作名（i/n）」并禁用重复点击；首页快捷场景卡同样禁用重复执行。
- 结果：全部成功 toast「「场景名」执行成功（N 个动作）」；存在失败时在智能页弹窗逐条列出成功/失败与原因；部分成功与全部失败的口径见 `SceneRunResult.summary()`。
- 真实执行：`SceneRuntimeImpl` 直接调用 `BleConnectionManager.connect`、`CollectionLauncher.start/stop`（前台服务 + `CollectionSession`）、`AppSettings.muteAlertsFor`（告警投递实时读取静音截止时间）与 `SyncScheduler.scheduleNow`。

### 6.9 指标阈值规则与推荐模板（v0.11.0）

- **指标阈值规则**：条件为「已有指标（`temperature` / `humidity` / `battery` / `heart_rate` / `co2` 等）+ 比较符（`>` / `<` / `>=` / `<=`）+ 阈值 + 连续 N 条（1–5，默认 1）」。规则与读数入库链路共用现有 `RuleEngine`：`BleConnectionManager` 每写入一条读数后调用 `onReading`，后台采集同样生效。
- **防抖**：同一 `ruleId + mac` 维护连续计数；任一条读数不满足即清零并进入恢复分支，达到 N 条才触发。通知沿用既有节流窗口（同一 `ruleId + mac`），1 分钟冷却内重复命中只记录一次事件。
- **恢复**：只有该规则此前确实触发过（`activeAlerts`）才调用 `onMetricRecovered`；`AlertNotifier` 再按「触发通知是否真的投递」过滤，关闭通知或权限缺失时不会补发恢复。
- **命中记录**：指标事件带 `metric/value/thresholdValue/unit/timestamp/mac`，消息文案展示 `℃` / `%` / `bpm`（不显示裸数字），例如「客厅温度计 · 温度 31.2℃ > 阈值 30℃」。事件进「智能 → 最近触发」，与 RSSI/断开规则共用同一列表。
- **推荐规则**：智能页新增「推荐规则」入口，按每台设备**真实出现过的本地指标**生成模板（温度 > 30℃、湿度 > 70%、电量 < 20%、心率 > 120、CO₂ > 1000 ppm），只列真实存在的指标；已存在同指标 + 比较符 + 阈值的规则不再重复推荐。点击条目即写入 Room、刷新规则列表并立即生效，不做只展示不落库的假入口。无任何真实读数时显示「连接到设备并采集到读数后可创建推荐规则」；有读数但模板都已创建时显示「当前指标都已创建规则」。
- **手动新增**：新增规则弹窗增加「指标阈值」类型，指标下拉只列已采集到的真实指标；比较符四档、阈值支持小数、连续条数限定 1–5，校验失败给出明确错误。
- **迁移**：Room v10（`MIGRATION_9_10`），旧规则 `type/threshold` 不变并回填指标字段副本，`RuleMigrationTest` + `RuleEngineTest` 覆盖「升级后旧规则仍能触发」。

### 6.10 中英双语、200% 字号与无障碍（v0.12.0）

- **双语资源**：默认 `res/values/strings.xml` 为中文，`res/values-en/strings.xml` 为英文；复数走 `<plurals>`（时间、动作数、读数条数、分桶数、连续条数），所有格式化占位符统一命名参数（`%1$s` / `%2$d`），不存在裸 `%s`。`BilingualAuditTest` 解析两份 XML，断言 key 集合完全一致、占位符个数与类型一致、无空值，并扫描 `ui/` 下 Kotlin 源码不允许出现中文字面量（注释与日志除外）。
- **术语**：英文统一 Device / Reading / Sync / Scan / Group / Alert / **Transfer Code** / Bucket，8 位接管码绝不写作 “pairing code”，完整对照表见 §13.2。
- **格式化**：界面上的日期与数字使用 `Locale.getDefault()`（如设备详情读数、图表 Y 轴）；CSV 导出仍固定 `Locale.US`，保证文件小数点与表头跨语言稳定。
- **200% 字号**：`fontScale ≥ 1.5` 时首页设备卡片由 2 列降为 1 列，卡片文字取消 `maxLines=1`/省略，随内容换行；设备详情标签改为 `wrap_content + minWidth 100dp`；规则新增/编辑弹窗放入 `NestedScrollView`；图表高度 160dp → 220dp 且 Y 轴刻度从 5 段缩为 3 个（上下界 + 中位），左侧标签区宽度按最大标签实测宽度自适应；市场筛选 Chip 从横向滚动改为可换行 `ChipGroup`（`maxLines=2`、不省略）。
- **无障碍**：扫描、添加设备、规则开关、导出、注销的触控区 ≥48dp；规则开关补 `stateDescription`（「已启用 / 已停用」）；图标补 `contentDescription`；图表整图播报「温度，最近 24 小时，最低 X 最高 Y，共 N 个数据点」，点选后更新为当前桶摘要。文字配色按 WCAG 计算全部 ≥4.5:1（`text_tertiary` 由 `#667085` 调深到 `#5F6A7D` 以覆盖 `surface_sunken` 组合），`BilingualAuditTest` 同时审计浅色/深色配色与触控尺寸。
- **验证**：`FontScaleLayoutTest` 使用 Robolectric 在 `fontScale=2.0` 实测设备卡片、规则行与规则弹窗（高度 >0、不超父宽、弹窗可滚动），并断言图表刻度数为 3。

### 6.11 房间（v0.13.0）

- **数据模型**：`rooms(id, name, sortOrder, createdAt)` + `devices.roomId`（可空，`NULL` = 未分配），Room v11（`MIGRATION_10_11`）。`deviceGroup` 作为兼容字段保留，继续服务云同步与分组 Chip，但不再承担首页分区。
- **迁移**：旧设备数量与字段原样保留，`roomId` 全部为 `NULL`，`deviceGroup` 不丢失；`RoomMigrationTest` 用真实 SQLite 覆盖 v10 → v11。
- **首页分区**：房间按 `sortOrder → createdAt → id` 顺序渲染；每段一个可折叠房间头（房间名 + `共 N 台设备`），房间内设备继续使用当前排序；「未分配」固定最后，没有未分配设备时不渲染空分区。
- **交互取舍**（避免搜索 / 排序 / 分组 Chip 与房间分区打架）：
  - **搜索**：输入非空时平铺设备结果并隐藏房间头，折叠状态不参与过滤；清空搜索后恢复房间分区。
  - **排序**：排序只在每个房间内部生效，不跨房间重排；房间顺序由 `sortOrder` 决定。
  - **分组 Chip**：仍按 `deviceGroup` 过滤设备，激活时隐藏无匹配设备的房间；不改变设备所属房间，可与搜索叠加。
- **房间管理**：首页设备区标题右侧「房间管理」；支持新建、点击行重命名、删除（二次确认文案写明「房间内设备会回到未分配」）。删除时先把设备 `roomId` 清空再删房间，设备落点为「未分配」。
- **设备移入移出**：列表长按设备打开「移动到房间」（首项「未分配」即移出）；详情页「房间」行同样可改；本机还没有房间时，详情页可直接新建并归入当前设备。
- **空态**：无房间且无设备 →「还没有房间」+「新建房间」；有房间但无设备 → 保留房间分区，房间头显示「该房间暂无设备」，点标题进入扫描；搜索无结果 → 沿用「没有匹配设备」空态。
- **同步边界**：房间仅本机维护，不参与云端同步；扫描、编辑与云端拉取都用 `existing?.roomId` 保留本机归属，不会被同步覆盖。

### 6.12 家庭、邀请码与房间共享（v0.14.0）

- **数据模型**：Room v12（`MIGRATION_11_12`）新增 `households(id, remoteId 唯一, name, isPersonal, role, memberCount, deviceCount, sortOrder, createdAt)`；`rooms` 增加可空 `remoteId`、`householdId`；`devices` 增加可空 `householdId`、`remoteRoomId`、`pendingRoomPatch`、`pendingRoomLocalId`、`pendingHouseholdRemoteId`、`shared`。
- **设备归属映射取舍**：选择反规范化 `devices.remoteRoomId`（+ 本机 `roomId` 外键）而不是独立映射表。理由：房间归属是设备上的单一可选属性，服务端 `PATCH /my/devices` 本来就按设备粒度更新；映射表会让同一事实存在两份来源，需要额外一致性与级联清理，而收益只是未来可能的多房间归属——当前契约没有这个需求。
- **拉取时机**：登录后 `HouseholdSyncer` 挂在 `SyncManager` 的冷启动、手动同步与切换账号三条路径；`GET /households` 落库并持久化当前家庭（`SyncPrefs.current_household`），失效时回落到个人家庭。切换家庭只拉该家庭的房间与设备，不影响其他家庭缓存。
- **迁移合并策略（不静默丢弃本机房间）**：首次接入时把没有 `remoteId` 的本机房间按 `sortOrder` 处理——先按规范化名称（`trim` + 小写 + 合并连续空白）认领尚未被占用的服务端同名房间，认领后保留本机 `id`，因此设备归属原样保留；仍匹配不到的房间**按本地顺序逐条上传**到服务端，绝不删除；剩余服务端房间插入本机；只有「曾映射过 remoteId 而服务端已删除」的房间才在本机移除，房间内设备回到未分配。该策略是幂等的，`planRoomMerge` 单测覆盖认领、上传、插入、移除与大小写/空白不重复认领；`HouseholdMigrationTest` 用真实 SQLite 验证 v11 → v12 房间与设备归属不丢。
- **上报方式**：设备房间归属只走 `PATCH /my/devices/{platform}/{externalId}`（`roomId` 三态：缺失不改、`""` 清除、非空覆盖；householdId 同理），**不塞进 `POST /sync/push`**，避免与 LWW 的 `updatedAt` 版本比较互相覆盖。本机先乐观更新并写 `pendingRoomPatch`，网络失败保留 pending 下次同步重试；403/404 等 4xx 清除 pending 并回读服务端快照撤销乐观更新。冷启动拉取时 `applyServerMeta` 对 `pending` 设备只用 `CASE` 更新房间元数据、保留 `syncState`，不会把未上传的本地编辑洗掉。
- **共享设备**：家庭成员拥有的设备以 `shared:<platform>:<externalId>` 合成一条只读记录，展示真实别名/房间数据，不参与 push 与房间修改；服务端不再返回该设备时（被移除或退出家庭）自动清理，避免列表残留。
- **家庭管理（我的）**：展示当前家庭与角色（所有者/成员）与成员列表；创建家庭；生成邀请码展示 8 位码与服务端 `expiresAt` 倒计时、复制、过期后重新生成；加入家庭输入框复用配对码那套规范化（`ABCDEFGHJKLMNPQRSTUVWXYZ23456789`、自动大写、过滤、截断 8 位）与 404/429 文案。
- **首页切换**：仅当家庭数 > 1 时在搜索框上方显示可见的横向家庭 Chip（不做隐藏长按）；单家庭不渲染切换控件。切换只改变当前家庭，不写死列表数据。
- **房间来源**：服务端房间为权威来源，本机 `rooms` 作为离线缓存；线下新增房间先落本机并在联网时上传，未获得 `remoteId` 的房间头标记「未同步」；成员设备的真实房间同样按 `remoteRoomId → roomId` 映射展示。
- **权限降级**：403 映射「你没有修改该家庭或房间的权限」，404「家庭或房间不存在，可能已被移除」，429「尝试次数过多」，网络错误「网络不可用，已保留本机数据」；不展示原始错误码、不整页报错，操作失败后列表保持可用。
- **离线**：家庭接口不可用时保留本机房间与设备，状态卡显示「未同步：当前显示本机缓存，联网后自动更新」，不白屏、不清空家庭列表（服务端返回空列表时也保留本机数据）。

### 6.13 家庭管理能力与整段房间排序（v0.15.0）

- **数据模型**：Room v13（`MIGRATION_12_13`）为 `households` 增加 `roomCount INTEGER NOT NULL DEFAULT 0`；该字段只存服务端 `GET /households` 返回的真实计数，UI 不数本地缓存。
- **新增接口接法**（后端 PJGoIoT v0.13.0 / openapi 0.9.0）：
  - `DELETE /households/{id}/members/{userId}`：owner 移除成员；成功后本地刷新家庭计数并重拉设备，被移除成员的共享设备因不再返回而清理。
  - `POST /households/{id}/leave`：成员退出；owner 还有其他成员时服务端返回 409。成功后本地删除该家庭的房间、共享设备与设备归属，清除当前家庭偏好，回落到个人家庭。
  - `GET /households/{id}/invitations` / `DELETE /households/{id}/invitations/{invitationId}`：列表只消费 `codeHashPrefix`、`status`、`attempts`、`expiresAt`、`createdAt`，**不请求也不展示明文**；撤销只对未使用且未过期的码有效。
  - `PUT /households/{id}/rooms/order`：整段集合顺序提交（`roomIds` 为完整列表），服务端按数组下标重写 `sortOrder`。
- **错误判定**：HTTP 409 映射为 `HouseholdConflictException`（保留 `error.code`），重排集合不一致再转换为 `RoomSetMismatchException`；分类统一为 `HouseholdErrorKind.CONFLICT`。后端 v0.13.0 未提供 `ROOM_SET_MISMATCH`，因此绝不解析 `message`，只依据 HTTP 状态与 `error.code` 做机读判断，未来后端补码时可平滑替换。
- **409 重试策略**：本地先按用户操作乐观落顺序（离线也保留）；若存在未上传房间先补传，再整段 PUT。收到 409 时立即重新拉取服务端房间集合覆盖本地并抛 `RoomSetMismatchException`，UI 提示「房间列表已变化，已刷新」，用户看到的即为最新顺序，不需要盲目重试；网络错误则保留本地顺序并标记「未同步」。
- **权限与可见性**：`householdCapabilities()` 统一计算入口——owner 可见重命名、签发/撤销邀请码、移除成员；member 全部隐藏，仅保留「退出家庭」；owner 且 `memberCount > 1` 时退出按钮被拦截，展示「请先转让所有权或移除其他成员」。成员行仅 owner 可长按移除，二次确认文案说明对方设备会回到其个人家庭。
- **计数**：首页家庭 Chip 使用服务端 `deviceCount`，家庭卡片使用 `memberCount/deviceCount/roomCount`；服务端数字是唯一来源。

---

## 7. 构建与运行

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home

./gradlew assembleDebug        # Debug APK
./gradlew lintDebug            # Lint
./gradlew testDebugUnitTest    # 单元测试：245 个（含规则防抖/恢复/迁移、图表命中、推荐模板、双语/无障碍/200% 字号、家庭移除/退出/邀请码/重排 409）
./gradlew assembleRelease      # 签名 Release APK
./gradlew bundleRelease        # 签名 Release AAB

./scripts/e2e-contract.sh      # 契约联调（需本地 PJGoIoT；CI 的 e2e-contract job 自动执行）
```

Debug 包路径：`app/build/outputs/apk/debug/app-debug.apk`

### Lint 基线

`lintDebug` 当前为 **0 error / 0 warning**，未使用 `abortOnError=false` 之类的全局屏蔽。历史 62 条 warning 已按类型直接修复：硬编码文案抽到 `strings.xml`、未使用字符串/颜色/图标删除、`contentDescription` 与 KTX 改造、`RectF` 预分配、移除重复根背景、补全备份规则等。

仅 `app/lint.xml` 显式忽略 4 个「版本提示」类检查，原因如下：

| 检查项 | 保留原因 |
| --- | --- |
| `GradleDependency` / `NewerVersionAvailable` | 依赖升级需单独回归（Material、Retrofit、OkHttp 等），不在 bugfix 版本里批量升级 |
| `AndroidGradlePluginVersion` | AGP 升级需同步验证 KSP/Compose 无关插件链，单独排期 |
| `OldTargetApi` | `targetSdk 36` 是本轮验收基线；37 的行为变更需专项验证后再升 |

其余检查（含 `UnusedResources`、`InlinedApi`、`DataExtractionRules`）均按 lint 建议在代码/资源中处理，不使用配置抑制。

### 验收与排障文档

- [真机验收清单](docs/acceptance.md)：五模块走查、BLE、Android 16 广播、权限、横竖屏、断网降级、Room 迁移、Release/R8，逐项写预期与失败判定。
- [常见问题排查](docs/troubleshooting.md)：JDK、SDK、Go 服务、端口占用、keystore、BASE_URL 更换。
- [发布说明](docs/releasenotes.md)：v0.1.0 → 当前版本的实际变更。

---

## 8. Release 签名与混淆

### 签名配置（密码不入库）

`app/build.gradle.kts` 从**仓库根目录的 `keystore.properties`** 读取签名信息；该文件与密钥库均被 `.gitignore` 覆盖，不会提交。

`keystore.properties` 示例（放在仓库根目录，**不要提交**）：

```properties
storeFile=../.pkotlinapp-secrets/pkotlinapp-release.jks
storePassword=<你的密码>
keyAlias=pkotlinapp
keyPassword=<你的密码>
```

`storeFile` 相对仓库根目录解析；密钥库放在仓库外（如 `../.pkotlinapp-secrets/`）。

生成密钥库：

```bash
mkdir -p ../.pkotlinapp-secrets
keytool -genkeypair -v \
  -keystore ../.pkotlinapp-secrets/pkotlinapp-release.jks \
  -alias pkotlinapp -keyalg RSA -keysize 2048 -validity 9125
```

若 `keystore.properties` 不存在（例如 CI 环境），`signingConfig` 不会被创建，`assembleRelease` 仍可产出未签名 APK，适合做编译验证。

### Release 三件套

```kotlin
release {
    isDebuggable = false
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
}
```

### ProGuard / R8 规则覆盖

`app/proguard-rules.pro` 已覆盖：

- 泛型签名、内部类、运行时注解（Retrofit / Moshi 依赖）；
- Retrofit `ApiService`、`Response`、`Call`、`Continuation`；
- `data.remote.**` DTO 与 Moshi 注解方法、`@JsonQualifier`；
- `kotlin.Metadata`、`kotlin.reflect.**`、`kotlin.jvm.internal.**`；
- Room `data.local.**` 实体与列名；
- OkHttp / Okio 的 `-dontwarn`。

验证命令：

```bash
/Users/<user>/Library/Android/sdk/build-tools/36.0.0/apksigner \
  verify --print-certs app/build/outputs/apk/release/app-release.apk
```

### 上架说明

- 版本目标：`compileSdk/targetSdk = 36`，满足 Google Play 新应用的 targetSdk 要求；`minSdk = 24`。
- 权限模型：`BLUETOOTH_SCAN` 标注 `neverForLocation`；低版本才声明定位权限，避免新版过度申请。
- Android 16 适配：edge-to-edge、预测性返回、大屏方向、BLE 新广播监听均已按上文处理。
- 发布前请替换为自己的正式 keystore，并在 Play Console 配置 App Signing；密码与密钥文件不得入库。

---

## 9. 持续集成

`.github/workflows/android.yml`：

- **verify job**（push main / pull_request）：JDK 21（Temurin）、`./gradlew assembleDebug lintDebug testDebugUnitTest`，要求 lint 0 error / 0 warning；上传 Lint 与单测报告。
- **e2e-contract job**（与 verify 并行）：checkout 本仓库与 `platojobs/PJGoIoT`，启动 `STORE=fixture` 的 Go 后端，跑 `:app:e2eContract`；失败上传测试报告与 Go 服务日志。
- **e2e-postgres job**（与 fixture e2e 并行，不阻塞主流程）：Docker 起真 Postgres + API，跑 `scripts/e2e-postgres.sh`（含 410 分支），失败上传 Postgres 服务日志，见 §15。
- **release job**（仅 `v*` tag 或手动 `workflow_dispatch`）：执行 `assembleRelease`，**不注入签名密钥**，产出未签名 APK 作为构建验证；如需正式签名请在 GitHub Secrets 配置后自行补充注入步骤，仓库内不写任何密码。

### 9.1 排障：没有 `workflow` scope 时如何推送 workflow 变更

OAuth 凭据缺少 `workflow` scope 时，GitHub 会拒绝任何包含 `.github/workflows/` 改动的推送，报错：

```text
! [remote rejected] main -> main (refusing to allow an OAuth App to create or update
workflow `.github/workflows/android.yml` without `workflow` scope)
```

首选补充授权：`gh auth refresh -s workflow`（需要能访问 `github.com/login/device/code`，在浏览器完成设备授权）。

如果设备授权端点不可达（例如代理下 `dial tcp ...:443: i/o timeout`），改用 **SSH over 443** 推送，不修改任何全局 git config，也不改 remote：

```bash
GIT_SSH_COMMAND="ssh -i ~/.ssh/id_ed25519_github_platojobs_platojobsdemacbook-air \
  -o IdentitiesOnly=yes -o ConnectTimeout=15" \
  git push ssh://git@ssh.github.com:443/platojobs/PKotlinApp.git main v0.10.0
```

说明：SSH key 已登记在 GitHub 账号时，SSH 推送不受 OAuth App 的 `workflow` scope 限制；`ssh.github.com:443` 用于绕过 22 端口封锁。推送后可用 `git fetch origin main && git show origin/main:.github/workflows/android.yml | head` 确认远端 workflow 已更新。

---

## 10. 已知限制与真机自测项

以下 BLE/系统行为仍需在真实 Android 设备上自测；同步契约已在 JVM 层对 `STORE=fixture` 的 Go 后端跑通。

- [ ] BLE 扫描能否发现真实设备、RSSI 是否随距离变化。
- [ ] 点击设备 `connectGatt()` 后的连接状态流转与 `readRemoteRssi` 轮询。
- [ ] Android 16 `ACTION_KEY_MISSING` / `ACTION_ENCRYPTION_CHANGE` 是否实际投递（受系统 flag 与权限影响）。
- [ ] 绑定丢失后禁止自动重连、重新配对后手动重试的完整流程。
- [ ] 各机型上的权限弹窗、拒绝后跳系统设置的行为。
- [ ] 横屏、大屏、折叠屏的 Edge-to-edge 与列表布局表现。
- [ ] WorkManager 在网络恢复后自动推送、断网连续失败进入纯本地模式。
- [ ] 首启三步引导在真机上的系统权限弹窗顺序（Android 11 / 12 / 13+），以及拒绝后「仅本机可用」降级页与设置回跳。
- [ ] 冷启动首帧与首页内容出现时间（`am start -W` + `Displayed`），对比 §12 的模拟器数字。
- [ ] v1 → v6 全链路 Room 迁移在已有数据的真实设备上执行（本机仅验证 schema 与构建）。

其他限制：

- **同步契约已在 fixture 后端验证**：`scripts/e2e-contract.sh` 跑通匿名登录、push、changes、幂等重放、LWW 冲突、401、跨用户 404，以及配对码生成/重新生成失效/claim/404 文案/导出分页/注销后 token 失效；生产存储、正式域名与真机 WorkManager 仍需后续验证。本地没有 Go 服务时同步会失败并降级，不影响本地功能。
- 匿名 token 使用 `EncryptedSharedPreferences`（Keystore 包装）；该 API 已被 AndroidX 标记为 deprecated，但当前功能可用，后续可平滑替换为自实现 Keystore 封装。
- 未实现真实 OTA、GATT 服务发现与写入，仅展示型号库中的 GATT 元数据。
- 产品 / 市场 / 设备详情依赖同一可配置 Base URL，本地后端未启动时显示真实错误空状态。

---

## 11. 首次使用与权限前置（v0.9.0）

首启引导只在**首次安装**或**用户尚未走完**时出现；跳过也会持久化完成状态，中途返回则不写，下次启动继续展示。

- 入口：`MainActivity.onCreate` 读取 `AppSettings.onboardingCompleted`，未完成且非 Activity 重建时启动 `OnboardingActivity`（`ui/onboarding/OnboardingActivity.kt`）。
- 三步说明（均为真实用途，不是套话）：
  1. **蓝牙 / 附近的设备**：扫描发现设备广播；连接后读取心率、电量、温度等读数。Android 12+ 只申请「附近的设备」，扫描不用于定位。
  2. **通知**：规则命中（信号低于阈值、设备断开、绑定丢失）时发送本地告警；后台采集时显示常驻通知。拒绝后规则仍记录，但不提醒。
  3. **位置**：仅 Android 11 及以下因系统限制必须授权才能扫描 BLE；App 不调用定位 API、不读取/上传位置。Android 12+ 明确显示「当前系统不需要位置权限」，不申请。
- 先说明后申请：前两步的「继续」只翻页，最后一步「继续」才调用 `RequestMultiplePermissions`；扫描/连接前的补授权也先弹 `PermissionRationale` 说明框（`ui/common/PermissionRationale.kt`），点「继续」才弹系统框。
- 拒绝后不是死路：结果页展示三项真实授权状态；缺少扫描/连接时显示「仅本机可用：无法扫描或连接设备，不能从设备读取实时读数」，并给出「设置 → 应用 → BLE 设备面板 → 权限」路径与「打开应用设置」按钮；返回后自动刷新状态。仅缺通知时只提示告警不提醒，不判定为仅本机。
- 首页空态统一：无设备时只有「还没有设备」+「开始扫描」一套文案（`home_devices_empty_*`），扫描弹窗复用同一句提示；已删除旧的 `home_empty_*` / `devices_empty_*` 重复文案。
- 测试：`OnboardingPolicyTest`（扫描缺失→仅本机、仅通知缺失→非仅本机、全授权→能力完整）；`AppSettingsTest` 覆盖 `onboardingCompleted` 默认值与持久化；`TerminologyAuditTest` 校验高频文案。真机走查见 `docs/acceptance.md` §A0 与 §4 P1/P3。

## 12. 启动性能（真实测量）

> 测量环境：本机 Android Emulator `Test_API35`（android-35 google_apis_playstore / arm64，headless + swiftshader 软件渲染，关闭系统动画，Debug APK），测量前执行 `cmd package compile -m speed -f` 做 AOT 编译以降低 JIT 噪声。改前/改后使用同一模拟器、同一协议。模拟器进程启动与软件渲染开销明显高于中端真机，因此同时记录 `am start -W` 的 `Displayed` 与 App 侧拆分耗时，不把模拟器数字当成真机结论。

### 测量方法

```bash
adb shell cmd package compile -m speed -f com.platojobs.pkotlinapp
adb shell am force-stop com.platojobs.pkotlinapp
adb shell am start -W -n com.platojobs.pkotlinapp/.ui.MainActivity
adb logcat -d -s ActivityTaskManager:I | grep "Displayed com.platojobs"
```

每次测量前 `force-stop`，保证进程不在后台；改前 v0.8.0 用 `/tmp/pk080` worktree 安装同签名的降级包（`adb install -r -d`），改后 v0.9.0 用当前构建，各连续测量取中位数并记录最大值。App 侧拆分通过临时 `ColdStartTrace` 日志记录 `Application.onCreate`、`MainActivity.onCreate`、首次 `onPreDraw`，**发布版已移除该日志**。

### 改前（v0.8.0，10 次）

| 指标 | 数值 |
| --- | --- |
| `am start -W` TotalTime | 765 / 1489 / 981 / 959 / 1443 / 1094 / 1340 / 1103 / 1414 / 1082 ms，**中位数 1098.5 ms**，最大 1489 ms |
| `Application.onCreate` | 9–13 ms |
| Activity 实例化 → 首次 onPreDraw | 282–345 ms（首帧只有应用壳与 `syncLoading` 转圈，**首页内容要等网络**） |
| 进程 fork → `Application.onCreate` | 约 1.0 s（模拟器进程启动/类加载，非 App 代码） |

### 定位到的原因

1. `MainActivity` 在 `lifecycleScope` 中 `awaitInitialSync()`，最多等 3 秒才挂载首页 Fragment；首帧只显示空容器与 `syncLoading` 转圈，用户看到的是“壳 + 转圈”，不是本地内容。
2. `PKotlinApplication.onCreate` 同时启动冷启动拉取、7 天清理与 WorkManager 调度；虽在 IO 线程，但与首帧布局/渲染争抢 CPU。
3. `Application.onCreate` 本身耗时 9–13 ms（仅容器装配 + 主题 SharedPreferences 读取），没有主线程数据库查询或网络请求；模拟器 TotalTime 的主要构成是进程启动（约 1.0 s）与软件渲染，不是应用初始化。

### 改后（v0.9.0，20 次）

| 指标 | 数值 |
| --- | --- |
| `am start -W` TotalTime（第一批 10 次） | 929 / 1183 / 1482 / 1920 / 1004 / 749 / 816 / 797 / 772 / 788 ms |
| `am start -W` TotalTime（第二批 10 次，间隔 3 s） | 1635 / 537 / 820 / 777 / 812 / 943 / 801 / 832 / 683 / 913 ms |
| 合并统计 | **中位数 818 ms**，最小 537 ms，最大 1920 ms，20 次中 8 次低于 800 ms |
| `Application.onCreate` | 4–35 ms（仅容器装配 + 主题读取；同步/清理/调度不在 onCreate 执行） |
| `MainActivity.onCreate` | 138–230 ms（`activity_main` 装配、Insets、底部导航） |
| 首次 `onPreDraw` | 256–374 ms（只含应用壳 + `shellSkeleton`；首页 Fragment 下一帧挂载） |
| 后台工作启动 | `binding.root.postOnAnimation { app.startBackgroundWork() }`，首帧绘制后投递到 `Dispatchers.IO` |

结论：同环境、同协议下 `TotalTime` 中位数从 **1098.5 ms 降到 818 ms（−25.5%）**，App 侧首帧耗时 256–374 ms，剩余约 450–600 ms 来自模拟器进程 fork、类加载与软件渲染；波动（537–1920 ms）来自模拟器调度。期间发现首页三态容器层级缺陷（内容被整体 `GONE`）导致首页空白，该构建的 742.5 ms 数据不参与对比，修复布局后按上表重测。

### 改动内容

- 删除 `awaitInitialSync()` 与 `syncLoading`，首页立即渲染本地 Room 数据；网络同步不再决定首屏。
- 首帧只装配应用壳 + `SkeletonView`，`showTab()` 延后到 `postOnAnimation` 的下一帧，避免首帧承担首页列表构建。
- `PKotlinApplication` 新增 `startBackgroundWork()`：冷启动拉取、7 天保留清理、WorkManager 周期调度统一在首帧之后、IO 线程执行，且只执行一次。
- `onCreate` 保持只做 `AppContainer` 装配与主题恢复，不打开数据库、不发网络请求。

## 13. 上架材料与术语统一

| 材料 | 路径 | 覆盖内容 |
| --- | --- | --- |
| 商店文案 | `docs/store-listing.md` | 中文短描述（≤80 字）、长描述（仅已实现功能）、关键词、内容分级、不含交易/广告声明、v0.9.0 版本说明 |
| 隐私政策 | `docs/privacy-policy.md` | 逐项真实数据（BLE MAC/名称/广播名、读数与时间戳、匿名账号 ID/token、本地日志）、用途、保留期（服务端读数 90 天 / 本机 7 天）、注销即删、无广告 SDK、位置不采集不上传、系统备份关闭、导出文件边界 |
| Data safety 对照 | `docs/data-safety.md` | 按 Play 表单逐项：收集/共享/加密传输/可删除/可选性，每项给出代码或接口证据；未实现能力不申报 |

版本说明一致性：`docs/releasenotes.md` 与 README 本节的 v0.9.0 条目必须一致（版本 `0.9.0 / 17`，tag `v0.9.0`）。

### 13.1 术语统一（设计规范第 8 节）

| 统一使用 | 禁止/避免 | 说明 |
| --- | --- | --- |
| 设备 | 装置、机器 | 所有卡片、空态、错误与设置文案 |
| 读数 | 数值、测量数据（指单条读数时） | 集合仍称「数据」，单条/指标值统一「读数」 |
| 同步 | 上传 | 待同步条数、同步完成、重新同步；「推送/拉取」仅用于同步方向说明 |
| 扫描 | 搜索（指发现 BLE 设备时） | 首页“搜索已保存设备”属于列表过滤，可保留「搜索」 |
| 分组 | 分类 | 设备分组与规则分组统一「分组」 |
| 绑定 / 解绑 | 配对（系统蓝牙配对除外） | 账号接管入口的「配对码」为既有产品名，保留 |
| 最后在线 | 最后在线时间/最后活跃 | 统一「最后在线」 |

落地方式：`TerminologyAuditTest` 直接解析 `strings.xml`，对 9 条高频文案（扫描按钮、首页空态、同步状态、读数标题/空态、首启降级文案等）断言不含「装置/机器/上传/策略/参数/最后在线/数值」；另断言扫描按钮含「扫描」、待同步以「待同步」开头、读数标题为「读数」。

### 13.2 中英术语对照（v0.12.0）

| 中文 | English | 说明 |
| --- | --- | --- |
| 设备 | Device | 卡片、空态、错误与设置 |
| 读数 | Reading | 单条读数；集合用 Readings |
| 同步 | Sync | 不使用 Upload 表示整条同步链路 |
| 扫描 | Scan | 发现 BLE 设备；列表过滤仍用 Search |
| 分组 | Group | 设备分组与规则分组统一 |
| 房间 | Room | 本机房间分区；与云端 Group 不是同一概念 |
| 告警 | Alert | 通知渠道、今日告警、最近触发 |
| 接管码 | **Transfer Code** | 8 位账号迁移码；**英文禁用 pairing code**；蓝牙重新配对用 re-pair |
| 分桶 | Bucket | 图表聚合单元 |

`BilingualAuditTest` 额外断言英文资源中不出现 `pairing code`，且包含 `Transfer Code`。

## 14. 游标失效与全量重同步（v0.10.0）

### 14.1 契约

`GET /sync/changes` 的游标指向已被清理的变更行时，服务端返回：

```http
HTTP/1.1 410 Gone
Content-Type: application/json

{"error":{"code":"RESYNC_REQUIRED","message":"cursor too old"}}
```

- 判定条件：`cursor > 0` 且该用户当前最小保留 `seq` 比 `cursor + 1` 还大；
- `cursor=0` 表示从未同步，**永不返回 410**，直接从头拉剩余变更。

### 14.2 客户端处理（不是重试）

| 分支 | 触发 | 客户端动作 | 是否指数退避 |
| --- | --- | --- | --- |
| `RESYNC` | 410 + `RESYNC_REQUIRED` | 清空本地游标 → 立刻用 `cursor=0` 全量重拉**一次**；`pending` 设备/读数不删除，随后重新上传；状态记录 `resyncedAt`，界面提示「云端变更记录已过期，已重新全量同步」 | 否（终态分支，只处理一次） |
| `AUTH` | 401 / 403 / `UNAUTHORIZED` | 暂停同步，等用户确认重登，文案「登录已失效」 | 否 |
| `BUSY` | 503 / `TIMEOUT`（查询超时） | 保留游标与 `pending`，文案「服务端繁忙，稍后重试」 | 是（WorkManager 指数退避） |
| `CONFLICT` | 409 / `IDEMPOTENCY_CONFLICT` | 记录冲突并展示 | 否 |

实现位置：

- [`ApiErrors.kt`](file:///Users/platojobs/Desktop/GitHub/platojobs/PKotlinApp/app/src/main/java/com/platojobs/pkotlinapp/data/remote/ApiErrors.kt)：错误码常量、读取 410/503 响应体、HTTP 异常 → 领域异常；
- [`SyncEngine.kt`](file:///Users/platojobs/Desktop/GitHub/platojobs/PKotlinApp/app/src/main/java/com/platojobs/pkotlinapp/domain/sync/SyncEngine.kt)：捕获 `CursorExpiredException` → 游标置空 → `pull("0")` 一次，并回调 `onCursorExpired`；
- [`SyncManager.kt`](file:///Users/platojobs/Desktop/GitHub/platojobs/PKotlinApp/app/src/main/java/com/platojobs/pkotlinapp/data/sync/SyncManager.kt)：冷启动重同步后立即补传 pending 读数（失败不影响首屏）；
- [`SyncPrefs.kt`](file:///Users/platojobs/Desktop/GitHub/platojobs/PKotlinApp/app/src/main/java/com/platojobs/pkotlinapp/data/sync/SyncPrefs.kt)：`recordResync()` 持久化 `resyncedAt`，「我的」展示提示，不静默。

合并保护：全量重拉应用服务端墓碑前，如果本地同 `externalId` 记录是 `PENDING` 且 `updatedAt` 更新，则保留本地版本；`pending` 读数全程不参与删除。

### 14.3 其它新增契约

- **会话到期（180 天 TTL）**：`/auth/anonymous` 与 `/auth/pairing-code/claim` 返回 `expiresAt`；登录/接管时随 token 保存，「我的」只读展示「会话到期：YYYY-MM-DD（只读）」。401 仍走既有重登流程，文案「登录已失效」。
- **列表上限**：readings 500、export 5000、changes 500。客户端读数分页固定 100、导出按 `EXPORT_MAX=5000` 分片、changes 显式传 `limit=500`，均在超限前收敛（`ApiLimits`）。
- **错误码映射补齐**：`RESYNC_REQUIRED` → `RESYNC`、`TIMEOUT` → `BUSY`、`IDEMPOTENCY_CONFLICT` → `CONFLICT`，见 `SyncErrorMappingTest`。

### 14.4 测试

- `SyncEngineTest`：410 → 游标归零、第二次请求用 `cursor=0`、`pending` 保留且不被较旧服务端数据覆盖、服务端墓碑不删较新 pending、连续两次 410 只处理一次；
- `RemoteSyncTokenTest`：fake transport 下 410 → `CursorExpiredException`、503 `TIMEOUT` → `ServerBusyException`（不重登、不清游标）、changes `limit=500`、登录保存 `expiresAt`；
- `SyncErrorMappingTest`：HTTP 状态与三个错误码到 `SyncErrorKind` 的映射；
- `TerminologyAuditTest`：410/401/503 三条 UI 文案分别包含「已重新全量同步」「登录已失效」「服务端繁忙」。

## 15. 真存储契约联调（v0.10.0）

`scripts/e2e-postgres.sh` 一条命令完成：起栈 → seed → 跑契约套件 → 410 分支 → 停栈。

```bash
./scripts/e2e-postgres.sh
```

流程：

1. 调 `PJGoIoT/scripts/stack-for-e2e.sh`：Docker 起 `postgres:16-alpine`（自动选空闲端口）、迁移到最新版本、Go 进程以 `STORE=postgres` 启动 API，输出 `BASE_URL`；
2. 调 `scripts/seed-for-e2e.sh`：写入真实迁移 seed 关联的 ESP32-C3-DevKitM-1 与 24 条读数，并从 API 读回校验；
3. 跑 `:app:e2ePostgres`：与 fixture 套件共用用例，另加 postgres 专属断言——匿名登录/claim 的 `expiresAt`（180 天）、push 设备+读数、changes、readings/changes/export 的 limit 边界（500/500/5000 通过，501/501/5001 返回 400）、series/metrics、注销 204 后 401；家庭链路在两种存储下一致覆盖：服务端计数、邀请码列表无明文、撤销后 claim 404、重排缺项 409、owner 带成员退出 409、移除成员后对方 404、owner 退出 204；
4. 410 分支：匿名登录 → push 2 台设备 → SQL 把该用户最老 `seq` 的 `server_ts` 改到 100 天前 → 跑 `cmd/cleanup`（change_log 保留 90 天）→ `cursor=最老seq-1` 断言 `410 RESYNC_REQUIRED` → `cursor=0` 断言 200 且返回剩余变更；
5. 无论成功失败都停栈；失败时把 `api.log` 复制到 `E2E_LOG_DIR`，供 CI artifact 上传。

本机真实执行输出（2026-11-05，Docker 29.5.2，`cursor` 为该次实际 seq）：

```text
[E2E:pg] 用户 b63dc736-001e-4a7f-bb75-46e9ebfeee3f 最老 seq=18，把 server_ts 改到 100 天前
[E2E:pg] 运行 cleanup（change_log 保留 90 天）
清理完成 readings_deleted=0 readings_cutoff=2016-11-07T09:58:54Z retention_days=3650
change_log_deleted=1 change_log_cutoff=2026-08-07T09:58:54Z change_log_retention_days=90
idempotency_keys_deleted=0 idempotency_cutoff=2026-10-29T09:58:54Z idempotency_retention_days=7
tokens_deleted=0 pairing_codes_deleted=0
[E2E:pg] 410 校验通过：cursor=17 → RESYNC_REQUIRED
cursor=0 changes=1 nextCursor=19
[E2E:pg] cursor=0 从未返回 410，剩余变更已拉回
[E2E:pg] 通过：postgres @ http://127.0.0.1:50911
```

CI：GitHub Actions 的 `e2e-postgres` job 与该次运行同一脚本，日志与测试报告见
<https://github.com/platojobs/PKotlinApp/actions/workflows/android.yml>。

## 16. 版本记录

- `v0.15.0`：接入后端 v0.13.0 的家庭管理能力。Room v13（`MIGRATION_12_13`）为 `households` 增加 `roomCount`，统一保存服务端 `memberCount/deviceCount/roomCount` 真实数字，首页 Chip 与家庭卡片不再数本地缓存。新增 `DELETE /households/{id}/members/{userId}`（owner 移除成员，本地刷新计数并重拉设备清理共享行）、`POST /households/{id}/leave`（成员退出，owner 有其他成员时 409；本地删除该家庭房间/共享设备/归属并回落个人家庭）、`GET /households/{id}/invitations`（只取 `codeHashPrefix`/状态/过期时间，绝无明文）与 `DELETE .../invitations/{invitationId}`、`PUT /households/{id}/rooms/order`（整段集合排序）。409 机读判定为 HTTP 409 + `error.code`（后端 v0.13.0 无 `ROOM_SET_MISMATCH`），重排冲突自动重拉并提示「房间列表已变化，已刷新」，离线保留本地顺序标「未同步」。`householdCapabilities()` 统一 owner/member 可见性：member 看不到重命名、邀请码签发/撤销与成员移除，owner 带成员退出时提示「请先转让所有权或移除其他成员」。新增 `HouseholdRepositoryTest`（成员移除清理、退出回落、邀请码列表/撤销、重排 409 重试、离线排序）、`HouseholdMigrationTest` v12→v13 与 owner/member 能力单测；fixture 与真 Postgres e2e 补「服务端计数 → 邀请码列表无明文 → 撤销后 claim 404 → 重排缺项 409 → owner 带成员退出 409 → 移除成员后对方 404 → owner 退出 204」。单测 245 全绿、`lintDebug` 0 issue、`assembleDebug`、两套 e2e 全绿，tag `v0.15.0`。
- `v0.14.0`：家庭接上后端，房间真正归属家庭并支持成员共享。Room v12（`MIGRATION_11_12`）新增 `households` 表，`rooms` 增加 `remoteId/householdId`，`devices` 增加 `householdId/remoteRoomId/pendingRoomPatch/pendingRoomLocalId/pendingHouseholdRemoteId/shared`；设备归属选择反规范化 `devices.remoteRoomId` 而不是映射表（单一可选属性、PATCH 按设备粒度，理由见 §6.12）。登录/手动同步/重登三条路径拉 `GET /households` 并持久化当前家庭，切换家庭按家庭拉房间与设备。首次接入按规范化名称认领服务端同名房间、其余按本地顺序上传，绝不静默丢弃本机房间，`planRoomMerge` + `HouseholdMigrationTest` 覆盖。设备房间归属只走 `PATCH /my/devices`（不塞 push，避免 LWW），失败保留 pending 重试，4xx 清 pending 并回读服务端。「我的」新增家庭管理（角色/成员、创建、8 位邀请码 + 服务端倒计时 + 复制 + 重新生成、加入家庭复用配对码规范化与 404/429 文案）；首页仅在多家庭时显示可见切换 Chip；服务端房间为权威来源，未上传房间与离线状态明确标注「未同步」；403/404/429/离线全部降级为可读提示。fixture 与真 Postgres e2e 均跑通「创建家庭 → 邀请码 → 第二账号领取 → 成员可见设备 → member 改房间 403 → 跨家庭 404」；单测 235 全绿、`lintDebug` 0 issue、`assembleDebug` 全绿，tag `v0.14.0`。
- `v0.13.0`：本机房间与首页分区。Room v11 新增 `rooms(id, name, sortOrder, createdAt)` 与可空 `devices.roomId`（`MIGRATION_10_11`），旧设备保持未分配、`deviceGroup` 原样保留；`RoomMigrationTest` 用真实 SQLite 验证升级。首页按房间分段、房间头可折叠、「未分配」固定最后；搜索时平铺结果并忽略折叠，排序只在房间内生效，分组 Chip 仅按 `deviceGroup` 过滤并隐藏空房间（决策见 §6.11）。房间支持新建/重命名/删除（二次确认，删除后设备回到未分配），设备可长按或详情页移入移出；空态区分「还没有房间」与「房间内无设备引导扫描」。房间仅本机、不参与同步，扫描/编辑/拉取均保留 `roomId`。`RoomRepositoryTest` 覆盖排序、重命名与删除落点；单测 227 全绿、`lintDebug` 0 issue、`assembleDebug`、fixture 与真 Postgres e2e 全绿，tag `v0.13.0`。
- `v0.12.0`：与 iOS 配平的双语、超大字号与无障碍。全部文案拆为默认中文 `values/strings.xml` 与 `values-en/strings.xml`（457 条字符串 + 8 组 `plurals`，占位符全部命名参数），英文统一 Device/Reading/Sync/Scan/Group/Alert/**Transfer Code**/Bucket，禁止 pairing code；新增 `BilingualAuditTest` 校验两份资源 key/占位符/空值一致性、UI 层 Kotlin 无中文字面量、英文术语、配色对比度与 48dp 触控区。界面日期/数字改用 `Locale.getDefault()`，CSV 仍固定 `Locale.US`。200% 字号下首页降为单列、卡片与详情行自适应换行、规则弹窗可滚动、图表 Y 轴缩为 3 刻度且标签宽度自适应、市场 Chip 换行不省略；`FontScaleLayoutTest` 用 Robolectric 实测高度与父宽。无障碍补规则开关 `stateDescription`、图标 `contentDescription`、图表整图/选中摘要，`text_tertiary` 调深满足 `surface_sunken` 上 ≥4.5:1。单测 222 全绿、`lintDebug` 0 issue、`assembleDebug`、fixture 与真 Postgres e2e 全绿，tag `v0.12.0`。
- `v0.11.0`：与 iOS 对齐的「有厚度」功能，Android 以规则模板替代 CSV 入口。**指标阈值规则**：`rules` 支持指标 + 比较符（`>`/`<`/`>=`/`<=`）+ 阈值 + 连续 N 条（1–5，默认 1）防抖，接入现有 `RuleEngine` 与读数入库链路（含后台采集），恢复通知只在确实触发过且通知已投递时发一条，节流沿用 `ruleId + mac`；命中事件落库并携带 `metric/value/thresholdValue/unit`，展示 `℃`/`%`/`bpm`。Room v10（`MIGRATION_9_10`）旧规则 `type/threshold` 不变并回填指标字段，旧规则升级后仍触发。**图表点选**：点按/长按选中最近桶，展示区间（本地时区）、min/max/avg/last/条数，点空白取消，不改 Y 轴、只用现有语义色；整图与选中态更新 `contentDescription`；命中几何为纯函数并补单测。**推荐规则**：智能页按设备真实出现过的指标生成模板（温度>30℃/湿度>70%/电量<20%/心率>120/CO₂>1000），一键写入 Room 立即生效；无读数显示真实空态。单测 211 全绿、`lintDebug` 0 issue、`assembleDebug`、fixture 与真 Postgres e2e 全绿，tag `v0.11.0`。
- `v0.10.0`：跟随后端新增契约并升级真存储联调。`GET /sync/changes` 游标早于最老保留行时返回 `410 RESYNC_REQUIRED`（`cursor=0` 永不 410）；客户端收到后清空游标、只用 `cursor=0` 全量重拉一次，`pending` 设备/读数不丢弃并重新上传，服务端墓碑不覆盖更新的本地 pending；「我的」展示「云端变更记录已过期，已重新全量同步」，该分支与 401（重登）、503 `TIMEOUT`（指数退避）独立。`/auth/anonymous` 与 claim 返回 `expiresAt`（180 天 TTL），「我的」只读展示会话到期日；错误码映射补齐 `RESYNC_REQUIRED`/`TIMEOUT`/`IDEMPOTENCY_CONFLICT`；readings/changes/export 的 limit 在客户端收敛到 500/500/5000。新增 `scripts/e2e-postgres.sh` 与 `:app:e2ePostgres`，用 `stack-for-e2e.sh` + `seed-for-e2e.sh` 起真 Postgres，覆盖登录/push/changes/410（SQL 改 server_ts + cleanup）/series/metrics/export/注销 401；CI 新增并行 `e2e-postgres` job。单测新增 410/503/错误码/limit/expiresAt 用例；`testDebugUnitTest`、`lintDebug` 0 issue、`assembleDebug`、两套 e2e 全绿，tag `v0.10.0`。
- `v0.9.0`：首次使用路径 + 冷启动性能 + 上架材料。新增 `OnboardingActivity` 三步首启引导（蓝牙/通知/位置的真实用途，可跳过，状态持久化在 `AppSettings.onboardingCompleted`，中途返回不写），权限改为「先说明后申请」（最后一步「继续」才弹系统框，扫描/连接补授权先弹 `PermissionRationale`），拒绝后展示逐项授权状态、「仅本机可用」能力边界与「打开应用设置」入口，返回自动刷新；仅缺通知不算仅本机。首页无设备时只保留「还没有设备」+「开始扫描」一套文案，删除重复空态串，网络不可用只由顶部横幅说明；修复首页三态容器层级缺陷（根布局被整体 `GONE`）。冷启动移除 `awaitInitialSync()`/`syncLoading`，首帧只出应用壳 + 骨架、首页下一帧挂载，同步/清理/调度在首帧后投递 IO，同环境实测 TotalTime 中位数 1098.5 ms → 818 ms（20 次，见 §12）。新增 `docs/store-listing.md`、`docs/privacy-policy.md`、`docs/data-safety.md`；按设计规范第 8 节统一术语并在 §13.1 列表，新增 `TerminologyAuditTest` 检查 9 条高频文案。新增 `OnboardingPolicyTest`、`TerminologyAuditTest`，`AppSettingsTest` 补 `onboardingCompleted`；单测 182 全绿，`assembleDebug`、`lintDebug` 0 issue。
- `v0.8.0`：设计令牌与信息架构升级。新增 `colors.xml` / `values-night/colors.xml` / `dimens.xml` / 10 级 `TextAppearance` / `res/color` 状态选择器与 `Widget.App.Card`（1dp 描边、零阴影、12dp 圆角），删除布局与代码中的硬编码色值与字号，`windowBackground`/系统栏跟随语义色；自绘大标题 96→56（12dp 阈值、200ms 插值）、行高 56/72/88、页边距 16；统一浅色 6% / 深色 8% 按压遮罩，按钮只变色、卡片按下 0.98；读数/阈值/计数改用 `tnum` 等宽数字；`ChartColorAuditTest` 扩展为同时禁止硬编码字号。底部导航按米家重组为**首页 / 智能 / 产品 / 市场 / 我的**：首页改为设备卡片网格 + 分组分段 + 今日告警摘要 + 快捷场景；新增智能页承载自动化规则与场景；原「设备」Tab 与其规则入口删除。场景为仅本机的有序动作组合（连接/断开设备、开始/停止后台采集、静音告警 10/30/60 分钟、刷新同步），Room v9（`MIGRATION_8_9`）、`SceneExecutor` 顺序执行且失败不阻断、逐动作反馈与汇总；首页展示前 3 个场景。五模块统一骨架（延迟 300ms）/真实空态/可重试错误块。新增场景执行器、持久化、v8→v9 迁移与汇总口径单测；`assembleDebug`、`testDebugUnitTest`、`lintDebug` 全绿。
- `v0.7.0`：账号迁移与注销 + 后台采集。「我的」新增**迁移到新设备**（`POST /auth/pairing-code`，8 位码 + 服务端 `expiresAt` 真实倒计时、复制、重新生成使旧码立即失效）与**从旧设备接管**（`POST /auth/pairing-code/claim` 换同 `userId` 新 token，保留本地设备/读数并全量重置 `pending` 后重新上传）；输入框按 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` 自动大写/过滤/截断 8 位；404 统一「码无效或已过期」，429 显示剩余冷却秒数。新增**导出我的数据**（`GET /me/export` 按 `hasMore`/`nextCursor` 循环，cursor 只透传不解析，`devices` 去重、`readings` 拼接，写 `cacheDir/exports/` 后经 FileProvider 分享，显示真实进度与可重试）与**注销账号**（二次确认写明「设备与读数将永久删除且不可恢复」，`DELETE /me` 成功后清 token/游标/同步状态/`clearAllTables()`，401 与网络错误分别提示）。新增**后台采集**前台服务：连接后开关控制，`foregroundServiceType=connectedDevice` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`（Android 14+），通知渠道「数据采集」常驻（设备名 + 已采集条数 + 停止动作），Android 13+ 拒绝通知权限不阻断采集，断开/退出自动停止，开关持久化。单测 168（配对码/导出分页/注销顺序/服务会话/通知构建/Robolectric），e2e 新增账号契约 12 项；lint 0 issue。Room 无 schema 变更（仍为 v8）。
- `v0.6.0`：四项真实可用功能。**告警通知**：独立渠道「设备告警」、Android 13+ `POST_NOTIFICATIONS` 动态申请与「我的」拒绝提示、真实开关、1/5/30 分钟节流（SharedPreferences 持久化）、信号恢复通知只发一次、点击通知进设备详情、正文不含敏感信息。**CSV 导出**：详情按当前设备+指标+区间导出，≤7 天读 Room、>7 天服务端 cursor 分页（上限 5 万条），UTF-8 BOM / `recordedAt,metric,value,unit` / ISO8601 UTC / RFC 4180 转义，写 `cacheDir/exports/` 并用 `FileProvider` + `ACTION_SEND` 分享，真实进度与可重试错误。**设备列表**：别名/广播名/型号/MAC 即时搜索、四种排序持久化、真实分组 Chip（含未分组）、无设备与筛选无结果两种空状态。**深色模式**：`values-night/colors.xml` + 图表颜色全部走主题资源，`ChartColorAuditTest` 防硬编码回归。Room v8 新增 `devices.createdAt`（`MIGRATION_7_8`）；单测 139，lint 0 issue。
- `v0.5.0`：详情页数据区升级为产品级长周期图表。新增区间切换（1 小时 / 24 小时 / 7 天 / 30 天，默认 24 小时）与 `1m/1h/1d` 分桶映射、500 桶自动降级；接入 `GET .../readings/series`（UTC RFC3339 网格）与 `GET .../readings/metrics`（count/lastAt/unit，倒序）。图表按桶 `start` 的真实时间比例定位，空桶不返回时折线断开不连线，每个桶画 avg 折线 + min–max 区间带，Y 轴与单位自适应；网络失败回退本地分桶，30 天提示「离线：仅本地 7 天数据」。指标 Chip 改为服务端真值并展示「指标名 · N 条」，24h 汇总固定窗口、单位随指标。单测 96 个（新增区间映射/分桶/缺口几何/Y 轴/离线降级/指标排序），e2e 新增 series/metrics/空区间/非法 bucket 4 项。
- `v0.4.1`：与 iOS 统一读数解析口径：NUS 改为按 `\n` 逐行解析（整包 ≤256 字节、单行 ≤64 字符，单行失败只丢该行）；温度 2 字节优先 sint16 × 0.01℃、否则回退 IEEE-754 half，4 字节走 float32，范围统一 −50..150℃；心率 uint16 明确小端；电池 0% 视为合法。清理逻辑修正为只删除 `syncState = 'synced'` 且超过 7 天的记录，pending 无论多旧都保留。新增黄金字节表单测（总单测 79 个），lint 0 issue。
- `v0.4.0`：接入设备读数全链路：GATT 订阅心率 `0x2A37`、电池 `0x2A19`、温度 `0x2A6E`、NUS TX `6E400003-…` 并按特征解析（失败静默丢弃 + 本地日志）；Room v7 新增 `readings` 表（`MIGRATION_6_7`），指标白名单 + 未知指标 `other` + 原始快照，本地保留 7 天（服务端 90 天）；读数以 ≤200/批复用 `/sync/push` 按 id 幂等同步，断网累积、恢复补传；详情页新增「数据」区（真实指标 Chip、自绘 60 点折线图、24h min/max/avg/last、最近记录、离线/空状态/权限引导）。单测 73 个、e2e 新增读数契约 6 项（推送/重复 skipped/倒序查询/summary/NaN 400）。
- `v0.3.7`：修复「清空别名无效」：Room v6 新增 `aliasDirty`（`MIGRATION_5_6`），详情页显式清空别名时上报 `"alias":""`，扫描路径永不置脏，服务端空别名拉回后清空本地并按广播名展示；复核分组 `NULL`/`""` 三态链路不复活；`lintDebug` 从 62 条 warning 清理到 0（硬编码文案抽 strings、未用资源删除、KTX/无障碍/备份规则修复，仅 4 个版本提示类检查在 `lint.xml` 显式抑制并写明理由）；新增 `DeviceAliasPersistenceTest`、v5→v6 迁移测试与 e2e 清空别名断言（e2e 扩到 24 项），单测 51 个。
- `v0.3.6`：跟随 PJGoIoT openapi 0.3.3：Room v5 新增 `advertisedName`（广播名）并与用户别名 `name` 隔离 + `MIGRATION_4_5`，列表/详情按「别名 → 广播名 → 型号名」展示；扫描只写广播名、编辑只写别名；DTO 引入 `Reported<String>?` 三态（absent / `""` / 值）与 Moshi adapter，删除分组显式上报 `"group":""`、未修改则省略；幂等键只哈希实际上报字段；冲突/拉取缺省字段按 absent 处理并保留本地值；新增三态序列化、别名隔离、清空不复活、幂等键稳定性测试，e2e 扩到 22 项。
- `v0.3.5`：离线降级修复：Room v4 新增 `deviceTypeName`（catalog 展示名）与 `deviceGroup`（真实分组）可空列 + `MIGRATION_3_4`；离线型号优先展示缓存名，缺失时回退型号键并标注「未识别的型号」；设备编辑（别名/分组）落库并即时离线可见，设备列表展示分组与分组统计；扫描写入不再覆盖已缓存 enrich/分组；市场筛选 Chip 补 contentDescription、选中态 stateDescription 与 200% 字号换行；新增离线展示名/分组与 v3→v4 迁移测试。
- `v0.3.4`：接入 PJGoIoT openapi 0.3.2 enrich 字段（`UserDevice.deviceType/vendor/firmwareVersion/compatibility`、catalog 配件/固件 `vendor/compatibility`）；设备详情优先云端并支持本地缓存降级，市场新增厂商/兼容性筛选，push 支持可选 `deviceType` 且不上报时不覆盖旧值；Room v2→v3 迁移；扩展 e2e 断言；CI action 升级到 checkout@v7 / setup-java@v6 / setup-go@v7 / setup-gradle@v6 / upload-artifact@v7。
- `v0.3.3`：把 `e2e-contract` 纳入 GitHub Actions（与 verify 并行，push/PR 触发），失败上传测试报告与 Go 服务日志；`e2eContract` 强制每次真实执行；README 新增「CI 里的契约联调」。
- `v0.3.2`：产品 / 市场 / 设备详情改用 `GET /device-types/{type}/catalog`（一次返回 firmware + accessories），设备详情复合键查询改用 `GET /my/devices/android/{externalId}`；新增 `CatalogMappingTest`。同时新增验收 / 排障 / 发布说明文档，401 改为「停止同步 + 用户确认重新登录」（与 iOS 对齐），同步错误分类展示，权限被拒后提供「打开应用设置」，Debug 构建允许明文 HTTP。
- `v0.3.1`：按 `PJGoIoT/api/openapi.yaml` 修正同步 DTO（`{clientId,devices}` / `{applied,conflicts,nextCursor}` / `changes[].payload`），时间统一 RFC 3339，新增 `scripts/e2e-contract.sh` 与本机 Go 后端端到端契约联调（含幂等重放、LWW 冲突、401、跨用户 404）。
- `v0.3.0`：接入多设备同步客户端（匿名登录、加密 token、增量拉取、幂等批量推送、冲突上报、tombstone），Room v1→v2 迁移，WorkManager 后台同步与纯本地降级，「我的」同步状态卡片，同步单测。
- `v0.2.0`：项目定位改为 BLE 设备数据面板；五模块底部导航、BLE 扫描/连接、Room、Retrofit、规则引擎、Android 16 适配、签名 Release、GitHub Actions CI。
- `v0.1.0`：初始纯 Kotlin + View 待办 Demo（已被本版本覆盖）。
