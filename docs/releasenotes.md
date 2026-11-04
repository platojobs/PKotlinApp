# 发布说明（Release Notes）

只记录每个 tag 上**实际发生并已提交**的变更；不含计划项。按时间从新到旧排列。

版本号规则：Android `versionCode` 递增，三端统一使用 `versionName`；tag 为 `v<versionName>`。

---

## v0.4.0

**主题：可交付验收 + 登录失效处理对齐 iOS**

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

版本：`versionCode = 5`、`versionName = 0.4.0`。

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
