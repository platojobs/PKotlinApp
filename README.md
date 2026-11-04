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

## 2. 五模块能力

底部 `BottomNavigationView` 承载五个 Fragment，各自独立包，共用 `ui/common`。

### 首页 Home（`ui/home`）
- BLE 实时扫描主入口：`BluetoothLeScanner` → `ScanCallback` → `callbackFlow`。
- 列表展示名称 / MAC / RSSI，并用自绘 `RssiSignalView` 展示真实信号格数。
- 扫描结果按 MAC 合并排序，节流写入 Room；下方「最近设备」来自 Room，点击进入详情。
- 无数据时插入代码构建的 `EmptyStateView`（带「开始扫描」动作）。
- 运行时权限：Android 12+ 申请 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`；SDK < 31 追加 `ACCESS_FINE_LOCATION`。

### 设备 Devices（`ui/devices`）
- 已保存设备管理：连接状态、上次出现时间、RSSI 趋势（与上一记录比较）、删除。
- 本地规则：低 RSSI、断开、绑定丢失三类；规则存 Room，可新增 / 启停 / 删除，触发写规则事件。
- 最近触发事件列表；无数据使用真实空状态。
- 点击设备可发起连接并进入详情页。

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
- 同步状态：上次同步时间、待上传条数、最近冲突数、最近错误、纯本地模式提示、手动「立即同步」；登录失效时改为「重新登录」入口（需用户确认），数据来自真实同步状态与 Room 统计。
- 权限自检：读取真实的蓝牙扫描/连接/定位授权状态，并跳转系统应用设置。
- 扫描日志：Room 本地记录（扫描会话中每个设备首次出现写一条），可清空。
- 版本与关于：读取 `BuildConfig` 的真实版本号与构建类型。
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

### Room 与迁移（当前 v6）

数据库 `device_dashboard.db` 当前版本 **6**，共 4 张表：

| 表 | 关键字段 |
| --- | --- |
| `devices` | `mac` 主键、`name`（用户别名）、`aliasDirty`（用户是否显式编辑过别名，默认 0）、`advertisedName`（广播名）、`lastRssi`、`lastSeenTimestamp`、`id`（客户端 UUID）、`platform`、`externalId`、`updatedAt`、`deletedAt`、`syncState(pending/synced)`、`deviceType/vendor/firmwareVersion/compatibility`（enrich 缓存）、`deviceTypeName`（catalog 展示名）、`deviceGroup`（云端分组，`NULL`=未上报/未知，`""`=已显式清空） |
| `rules` | 自增 id、`type`、`threshold`、`enabled`、`createdAt`、`lastTriggeredTimestamp` |
| `rule_events` | 规则触发记录 |
| `scan_logs` | 扫描日志 |

- `MIGRATION_1_2` 使用 `ALTER TABLE ... ADD COLUMN` 扩展字段，再逐行回填客户端 UUID、`platform=android`、`externalId=规范化大写 MAC`、`updatedAt=lastSeenTimestamp`、`syncState=pending`，**不丢已有设备数据**。
- `MIGRATION_2_3` 新增 4 个 enrich 可空列；`MIGRATION_3_4` 新增 `deviceTypeName` 与 `deviceGroup` 可空列；`MIGRATION_4_5` 新增 `advertisedName` 可空列，历史行保持 null，由展示层回退到别名/型号名；`MIGRATION_5_6` 新增 `aliasDirty INTEGER NOT NULL DEFAULT 0`，历史行默认 0。
- 迁移后 schema 输出在 `app/schemas/com.platojobs.pkotlinapp.data.local.AppDatabase/6.json`。
- 别名与广播名隔离：扫描只写 `advertisedName`，详情页编辑只写 `name`（alias），两者互不覆盖；列表/详情展示优先级为「用户别名 → 广播名 → catalog 型号名 → 未知设备」。
- **别名清空语义**：`aliasDirty` 只在详情页保存且别名相对初始值发生变化时置 1（一旦置位保持），扫描路径永远不置位。置位后即使 `name` 为空，推送也会显式上报 `"alias":""`；未置位时不报空别名。服务端返回空别名时按「已清空」写回本地（`name=""`），展示层回退到广播名，旧别名不会被拉回复活。
- `DeviceDao` 使用 `@Upsert insertOrUpdate`、`suspend getAllDevices`，并提供待同步查询、待上传计数、按 externalId 标记 synced 等同步专用方法。
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
- **冷启动**：`PKotlinApplication` 启动后先拉增量，`MainActivity` 最多等待 3 秒再渲染；超时或失败直接展示本地数据，不白屏。
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
- 离线验收步骤见 [真机验收清单 §7.2](docs/acceptance.md#72-离线缓存与分组验收v035-新增)；别名/广播名与分组三态验收见 [§7.4](docs/acceptance.md#74-别名--广播名与分组三态验收v036-新增)；清空别名验收见 [§7.5](docs/acceptance.md#75-清空别名验收v037-新增)。

---

## 7. 构建与运行

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home

./gradlew assembleDebug        # Debug APK
./gradlew lintDebug            # Lint
./gradlew testDebugUnitTest    # 单元测试：51 个（含 DeviceMigration 3 + DeviceAliasPersistence 3）
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
- **release job**（仅 `v*` tag 或手动 `workflow_dispatch`）：执行 `assembleRelease`，**不注入签名密钥**，产出未签名 APK 作为构建验证；如需正式签名请在 GitHub Secrets 配置后自行补充注入步骤，仓库内不写任何密码。

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
- [ ] 冷启动 3 秒内增量拉取与超时回退本地数据的表现。
- [ ] v1 → v6 全链路 Room 迁移在已有数据的真实设备上执行（本机仅验证 schema 与构建）。

其他限制：

- **同步契约已在 fixture 后端验证**：`scripts/e2e-contract.sh` 跑通匿名登录、push、changes、幂等重放、LWW 冲突、401、跨用户 404；生产存储、正式域名与真机 WorkManager 仍需后续验证。本地没有 Go 服务时同步会失败并降级，不影响本地功能。
- 匿名 token 使用 `EncryptedSharedPreferences`（Keystore 包装）；该 API 已被 AndroidX 标记为 deprecated，但当前功能可用，后续可平滑替换为自实现 Keystore 封装。
- 未实现真实 OTA、GATT 服务发现与写入，仅展示型号库中的 GATT 元数据。
- 产品 / 市场 / 设备详情依赖同一可配置 Base URL，本地后端未启动时显示真实错误空状态。

---

## 11. 版本记录

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
