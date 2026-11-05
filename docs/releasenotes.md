# 发布说明（Release Notes）

只记录每个 tag 上**实际发生并已提交**的变更；不含计划项。按时间从新到旧排列。

版本号规则：Android `versionCode` 递增，三端统一使用 `versionName`；tag 为 `v<versionName>`。

---

## v0.4.0

**主题：设备读数（readings）全链路 —— 采集 / 存储 / 同步 / 展示**

- 采集：连接后发现 GATT 服务即订阅心率 `0x2A37`、电池 `0x2A19`、温度 `0x2A6E`、NUS TX `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` 的 notify/indicate，并写 CCCD；可读特征主动读取一次。心率按 flags 取 uint8/uint16，电池取百分比，温度先按 sint16/0.01℃（-40..125℃）解析、失败回退 IEEE-754 half，NUS 支持 `metric=value` 与纯数字文本行。解析失败只记 `BleReadings` 本地日志，静默丢弃，不写库、不崩溃。
- 存储：Room 升级到 v7，新增 `readings` 表（UUID 主键、`deviceId` 外键 CASCADE、metric/value/unit/recordedAt/syncState/rawSnapshot）与 3 个索引，`MIGRATION_6_7` 只建表建索引；指标白名单 `temperature/humidity/battery/heart_rate/co2/pressure/other`，未知指标归一为 `other` 并保留原始快照；本地保留 7 天，在启动与每次同步后清理。
- 同步：`ReadingSyncer` 复用 `/api/v1/sync/push`，≤200/批，成功标记 `synced`；幂等键按 id 排序哈希整批读数（`readings-` 前缀），失败重试复用同一幂等体；服务端按 id 去重并返回 `appliedReadings/skippedReadings`。断网继续本地累积，恢复后补传。
- 展示：设备详情新增「数据」区：指标 Chip 来自真实已有读数；最近 60 点用自绘 `ReadingChartView`（`onDraw` 画坐标轴/网格/折线/数据点/首尾时间，0/1/60 点均安全）；24h 汇总行（min/max/avg/last，远端优先失败回退本地）；最近记录最多 10 条。无读数「连接设备后开始采集」、无权限「去应用设置」、离线「离线：本地数据」，不放假数据。
- 测试：新增 `GattReadingParserTest`、`ReadingIdempotencyKeysTest`、`ReadingRepositoryTest`（含 7 天清理）、`ReadingMigrationTest`（真实 SQLite v6→v7）、`ReadingChartGeometryTest`（0/1/60 点），单测共 73 个；`E2eContractTest` 新增读数契约 6 项（推送/重复 id skipped/倒序查询/metric 过滤/summary/NaN 400）；e2e 脚本提高限流阈值以容纳新增用例。
- 文档：README 新增「6.1 数据采集（readings）」；验收清单升级到 v0.4.0 并新增 §7.6 真机读数验收（明确模拟器无法验证真实 notify）。

版本：`versionCode = 11`、`versionName = 0.4.0`。

---

## v0.3.7

**主题：修复清空别名无效 + lint 清零**

- Room 升级到 v6：`devices` 新增 `aliasDirty INTEGER NOT NULL DEFAULT 0`，`MIGRATION_5_6` 用 `ALTER TABLE` 追加；v5 历史数据逐列保留，默认视为「未显式编辑别名」。
- 别名清空语义：详情页保存时仅在别名相对初始值变化才置脏（一旦置位保持）；置脏后即使别名为空也上报 `"alias":""`，未置脏的空别名保持 absent；扫描路径永不置脏。
- 拉取/冲突写回：服务端 `alias` 为空串时覆盖本地 `name=""`，展示层回退广播名，旧别名不会被拉回复活；`aliasDirty` 保留本地值。
- 分组三态复核：对话框未修改且本地分组未知时传 `null`（不上报），显式清空传 `""`；`NULL=未上报/未知`、`""=已清空` 在「编辑 → 清空 → 同步 → 拉回」全链路不复活。
- `lintDebug` 从 62 条 warning 清理到 **0 error / 0 warning**：抽取硬编码文案、删除未使用字符串/颜色/图标、补全备份规则、KTX/无障碍与绘制分配改造；仅 `app/lint.xml` 显式忽略 4 个版本提示类检查，README 写明理由，未使用 `abortOnError=false`。
- 测试：新增 `DeviceAliasPersistenceTest`（3 项：清空别名落库、扫描不覆盖别名、脏标记保持），`DeviceMigrationTest` 新增 v5→v6，`SyncDtoMappingTest` 新增清空别名上报与拉回不复活，`SyncEngineTest` 新增清空别名幂等键区分；`E2eContractTest` 扩到 24 项（清空别名显式空串、扫描不复活），单测共 51 个。
- README 更新 Room v6 / `aliasDirty` 语义 / lint 基线；验收清单更新 v5→v6 迁移与 §7.5 清空别名验收。

版本：`versionCode = 10`、`versionName = 0.3.7`。

---

## v0.3.6

**主题：跟随 openapi 0.3.3 —— 别名/广播名拆分 + 分组三态 + 幂等键收敛**

- Room 升级到 v5：`devices` 新增 `advertisedName`（广播名）可空列，`MIGRATION_4_5` 用 `ALTER TABLE` 追加，历史行不丢。
- 本地将「用户别名 `name`」与「广播名 `advertisedName`」隔离：扫描只写广播名，详情页编辑只写别名；列表/详情展示优先级为「用户别名 → 广播名 → catalog 型号名 → 未知设备」。
- DTO 引入 `Reported<String>?` 三态（absent / `""` / 值）与 Moshi 自定义 adapter：删除分组显式上报 `"group":""`，未修改时字段完全省略；`alias`/`advertisedName` 同样按是否上报序列化。
- 幂等键只哈希实际上报字段：未上报字段的空值不再参与 SHA-256，同内容重试保持同一 key，避免 409；`group` 的 absent 与显式空串保持可区分。
- 拉取与冲突响应中被省略的可缺省字段按 absent 解析；写回 Room 时保留本地别名/广播名/分组，不会被误清空。
- 测试：`SyncDtoMappingTest` 新增三态序列化、别名/广播名隔离、absent 解析；`SyncEngineTest` 新增幂等键稳定性；`OfflineDeviceInfoTest` 覆盖展示优先级；`DeviceMigrationTest` 新增 v4→v5；`E2eContractTest` 扩到 22 项（扫描只改广播名、编辑只改别名、清空分组、不复活）。
- README 与验收清单补充三态语义、v4→v5 迁移复验与 §7.4 验收步骤。

版本：`versionCode = 9`、`versionName = 0.3.6`。

---

## v0.3.5

**主题：修复离线降级展示 + 分组落库 + 筛选无障碍**

- Room 升级到 v4：`devices` 新增 `deviceTypeName`（catalog 展示名）与 `deviceGroup`（真实分组）可空列，`MIGRATION_3_4` 用 `ALTER TABLE` 追加，历史行不丢。
- 离线型号优先展示缓存展示名；只有型号键时回退为「型号键（未识别的型号）」，两者都没有才显示「型号未知」。
- `UserDevice.group` 写入 `deviceGroup`；设备详情新增「编辑别名与分组」，保存即落库、离线即时可见，并排队同步。
- 扫描写入不再用 null 覆盖已缓存的 enrich 字段与分组。
- 设备列表每行展示真实分组，标题统计「共 N 台设备 · M 个分组」。
- 市场筛选 Chip 增加 `contentDescription`、选中态 `stateDescription`，允许 200% 字号下两行换行且不省略。
- 新增 `OfflineDeviceInfoTest`（3 个）与 `DeviceMigrationTest`（真实 SQLite 验证 v3→v4 不丢数据）。
- README 与验收清单补充离线缓存/分组验收步骤与 v3→v4 迁移复验；新增市场筛选 Chip 的 TalkBack / 200% 字号 / 对比度手工验收清单。

版本：`versionCode = 8`、`versionName = 0.3.5`。

---

## v0.3.4

**主题：接入 openapi 0.3.2 enrich 字段 + CI action 升级**

- `UserDevice` 接入 `deviceType / vendor / firmwareVersion / compatibility`（均可为 null）；设备详情优先云端 enrich，null 显示「型号未知」/「未知」，未注册型号仍展示 alias/分组/最后出现时间。
- 设备详情失败时读取 Room 本地缓存降级，并提示「网络不可用，以下为本地缓存数据」；Room 升级到 v3（新增 4 个可空列，`MIGRATION_2_3`）。
- catalog 的 `firmwares` / `accessories` 接入 `vendor` / `compatibility`；市场新增厂商与兼容性筛选，维度全部来自接口真实值。
- `PushDevice` 支持可选 `deviceType`：匹配到才上报，未匹配时不序列化，服务端沿用旧值。
- `E2eContractTest` 新增 enrich、未注册型号 null、可选 `deviceType` 保留、catalog vendor/compatibility 断言。
- CI action 升级：`checkout@v7`、`setup-java@v6`、`setup-go@v7`、`setup-gradle@v6`、`upload-artifact@v7`。

版本：`versionCode = 7`、`versionName = 0.3.4`。

---

## v0.3.3

**主题：e2e 契约联调纳入 CI**

- 新增 `e2e-contract` job，与 `verify` 并行，仅在 push / PR 触发；checkout `PJGoIoT` 到同级目录，按 `go.mod` 安装 Go，复用 `scripts/e2e-contract.sh`。
- 失败上传 e2e 测试报告与 Go 服务日志；`e2eContract` 关闭 UP-TO-DATE，避免 CI 假通过。
- README 新增「CI 里的契约联调」与本地复现说明。

版本：`versionCode = 6`、`versionName = 0.3.3`。

---

## v0.3.2

**主题：openapi 0.3.1 新端点接入 + 可交付验收 + 登录失效处理对齐 iOS**

接口变更（以 `PJGoIoT/api/openapi.yaml` 0.3.1 为准）：

- 产品、市场、设备详情改用 `GET /api/v1/device-types/{type}/catalog`，一次拿到 `deviceType + firmwares + accessories`；删除 `GET /api/products`、`GET /api/market`、`GET /api/device/{mac}` 三个历史端点的调用。
- 设备详情先按复合键 `GET /api/v1/my/devices/android/{externalId}` 校验归属，`externalId` 为规范化大写 MAC；404 视为尚未同步并继续使用公共元数据，401 按登录失效处理；旧 `/my/devices/{mac}` 不再依赖。
- 产品页固件版本取 catalog 中 `releasedAt` 最新的记录；市场页展示 catalog 中的配件（SKU、适用型号、描述）。
- 新增 `CatalogMappingTest`（7 项）：catalog → 产品/市场映射、最新固件选择、复合键大写 MAC 与 `android` platform、404 回退、401 不重登、无 token 匿名登录、无 deviceType 跳过 catalog。

新增文档：

- `docs/acceptance.md`：真机验收清单，覆盖五模块走查、BLE 扫描/RSSI/连接/断开规则、Android 16 两个广播、权限拒绝、横屏大屏、断网降级、Room 迁移、Release 与 R8。
- `docs/troubleshooting.md`：JDK、SDK、Go 服务、端口、keystore、BASE_URL 的排查步骤。
- `docs/releasenotes.md`：本文件。

代码修复：

- **401 不再自动重新匿名登录**。`RemoteSyncDataSource` 收到 401 时抛出 `AuthExpiredException`，停止同步并保留本地数据与游标；「我的」同步卡片显示「登录已失效」+「重新登录」按钮，用户确认后才切换新匿名账号（并明确提示会同步本机数据、旧账号数据不被覆盖）。重新登录时重置游标并把本机记录重新排队推送。新增 `RemoteSyncTokenTest`（401 不重登、保留 token、500 原样抛出、无 token 不算登录失效）。
- **同步错误分类**：新增 `SyncErrorKind`（网络 / 超时 / 登录 / 冲突 / 服务端 / 未知），手动同步与重新登录的失败提示改为可读文案，不再直接拼底层异常；`SyncErrorKindTest` 覆盖 7 种映射。
- **后台同步错误可见**：「我的」同步卡片新增「最近错误」，来自持久化的 `SyncStatus.lastError`（此前只写日志/状态，界面不可见）。
- **权限被拒后可恢复**：首页权限回调、设备页点击设备缺连接权限时的 Snackbar 增加「打开应用设置」动作，可直接跳到系统应用详情页恢复授权。
- **Debug 允许明文 HTTP**：新增 `app/src/debug/AndroidManifest.xml`，便于真机连接局域网 Go 服务；Release 仍禁止明文，必须 HTTPS。

版本：`versionCode = 5`、`versionName = 0.3.2`。

---

## v0.3.1

**主题：与 Go 后端做真实端到端契约联调**

- 按 `PJGoIoT/api/openapi.yaml` 修正客户端 DTO 与映射：
  - push 请求体改为 `{clientId, devices}`；
  - push 响应改为 `{applied, conflicts, nextCursor}`；
  - changes 改为 `{changes:[{seq, entity, entityId, op, payload, serverTs}], nextCursor, hasMore}`；
  - 时间统一为 RFC 3339。
- 新增 `Rfc3339`：兼容 Go 的纳秒小数位与 `+08:00` / `Z` 偏移；本地 Room 继续用 epoch millis。
- 新增 `SyncDtoMappingTest`：Android push 映射、iOS 无 MAC、tombstone、非法 id 的确定性回退。
- 新增 JVM 端到端测试 `E2eContractTest`（`./gradlew :app:e2eContract`，默认不进 CI），用 OkHttp 直连本地 Go 服务，覆盖：
  - health 200 与 `store=fixture`；
  - 匿名登录返回 UUID 用户与非空 token；
  - 未带 token 的 changes / push 返回 401；
  - android + iOS 两条设备 push 与 `applied` 校验；
  - changes 形状、RFC 3339、cursor/hasMore；
  - 同一 `Idempotency-Key` + 同一请求体重放命中幂等；
  - LWW 冲突返回 `server_newer` 且不静默覆盖；
  - 同一 key + 不同请求体返回 `409 IDEMPOTENCY_CONFLICT`；
  - 跨用户访问返回 404；
  - 生产 Retrofit API 实际调用通过。
- 新增 `scripts/e2e-contract.sh`：构建并启动 `STORE=fixture` 的 Go 服务，8080 被占用时回退 18080，轮询 health，跑测试，结束后清理并在失败时打印服务日志。
- 本机实跑 10 个用例全部通过；同时验证了 8080 占用时回退 18080。

---

## v0.3.0

**主题：多设备同步客户端**

- Room 升级到 v2：`devices` 增加 `id`（客户端 UUID）、`platform`、`externalId`、`updatedAt`、`deletedAt`、`syncState`；新增 `MIGRATION_1_2`，旧记录回填 `platform=android`、`externalId=大写 MAC`、`syncState=pending`，不丢数据。
- 设备唯一键统一为 `(platform, externalId)`：Android 使用规范化大写 MAC。
- 新增同步层：
  - `POST /api/v1/auth/anonymous` 匿名登录；
  - token 存入 `EncryptedSharedPreferences`（Keystore 保护）；
  - `GET /api/v1/sync/changes` 增量拉取；
  - `POST /api/v1/sync/push` 批量推送，携带稳定的 SHA-256 `Idempotency-Key`；
  - 冲突以服务端 LWW 版本为准，但通过 `SyncOutcome.conflicts` 上报，不静默覆盖；
  - 删除使用 tombstone（`deletedAt + pending`），推送成功后物理删除。
- `SyncEngine` 与本地存储解耦，纯 Kotlin 可单测；`SyncEngineTest` 覆盖幂等重试、冲突上报、tombstone、无服务端版本的冲突保持 pending、拉取合并。
- WorkManager：`SyncWorker` 在 `NetworkType.CONNECTED` 下执行，指数退避 30 秒；连续失败 3 次进入纯本地模式并持久化（`SyncStatusStore`）。
- 冷启动：`PKotlinApplication` 启动后先拉增量，`MainActivity` 最多等待 3 秒，超时/失败直接渲染本地数据。
- 「我的」新增同步状态卡片：上次同步、待上传、最近冲突、纯本地模式提示、立即同步。

---

## v0.2.0

**主题：从本地待办重构为 BLE 设备数据面板**

- 项目定位改为 BLE 设备数据面板：Kotlin + XML/View + BLE + Room + Retrofit，**不使用 Compose**。
- 五模块底部导航：首页 / 设备 / 产品 / 市场 / 我的，各自独立包并共用 `ui/common`；每个模块接真实数据路径与真实空状态。
- BLE：
  - `BluetoothLeScanner` + `ScanCallback` + `callbackFlow` 实时扫描，离开页面自动停止；
  - `BleConnectionManager` 使用 `connectGatt(..., TRANSPORT_LE)`，详情页每 2 秒 `readRemoteRssi`；
  - 权限：Android 12+ `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`，SDK < 31 追加 `ACCESS_FINE_LOCATION`。
- Android 16 适配：
  - 监听 `BluetoothDevice.ACTION_KEY_MISSING` 与 `ACTION_ENCRYPTION_CHANGE`（`ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`）；
  - 绑定丢失后进入 `KeyMissing`，禁止自动重连，需用户重新配对后手动重试。
- 本地规则引擎：低 RSSI、断开、绑定丢失三种规则；触发写入 Room 并在「设备」页展示，60 秒冷却。
- 网络层：Retrofit + Moshi；BASE_URL 走 `BuildConfig.API_BASE_URL`，不硬编码公网地址。
- Release：`isMinifyEnabled` + `isShrinkResources` + ProGuard 规则；签名从仓库根 `keystore.properties` 读取，密钥不入库。
- Android 16 体验：两个 Activity 启用 edge-to-edge、预测性返回；未锁定 `screenOrientation`，支持横屏/大屏。
- 双 View 约定：XML + ViewBinding 为主，另有自绘 `RssiSignalView`（完整 `onMeasure`/`onDraw`）与代码组装的 `EmptyStateView`。
- 「我的」设置：主题（跟随系统 / 浅色 / 深色）写入 `AppSettings`（SharedPreferences）并即时生效。
- 新增 GitHub Actions：push main / PR 跑 `assembleDebug lintDebug testDebugUnitTest`；`v*` tag 跑未签名 release 构建。
- 已知限制写入 README：真实 BLE、Android 16 广播投递、真机迁移等需真机自测。

---

## v0.1.0

**主题：纯 Kotlin + View 本地待办清单**

- 从零搭建单 `app` 模块工程：Kotlin DSL + Version Catalog、`ui/data/domain` 分层、MVVM + Repository + 手动 `AppContainer`。
- 功能：本地待办的列表、新增/编辑、勾选完成、删除；Room 持久化，重启保留。
- UI 全部为 XML + ViewBinding，**无 Jetpack Compose、无 Java 源文件**。
- 双 View 能力：
  - `TodoProgressView`：自绘进度视图（自定义绘制）；
  - `EmptyStateView`：Kotlin 代码组装 `LinearLayout + ImageView + TextView + Button`，支持图标/标题/提示/动作。
- 中文 README，说明技术栈、目录结构、XML 与代码构建 View 的约定及原因。
- 完成真实构建与单元测试，推送 `main` 并打 tag `v0.1.0`。
