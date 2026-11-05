# 发布说明（Release Notes）

只记录每个 tag 上**实际发生并已提交**的变更；不含计划项。按时间从新到旧排列。

版本号规则：Android `versionCode` 递增，三端统一使用 `versionName`；tag 为 `v<versionName>`。

---

## v0.8.0

**主题：设计令牌落地 / 米家式信息架构 / 本机场景**

- 设计令牌：新建完整语义色（昼/夜）与 `dimens`、10 级 `TextAppearance`（Display 34 / Title 28 / Headline 22 / Body 17 / Callout 15 / Caption 13 / Footnote 11 / NumericXL 40 / NumericL 28 / NumericM 20）、`res/color` 状态选择器与 `Widget.App.Card`（1dp 描边、零阴影、12dp 圆角、16dp 内边距）；`windowBackground`、状态栏/导航栏与图标明暗跟随语义色；清除布局与代码中的硬编码色值和字号。
- 交互与几何：统一按压遮罩（浅色 6% / 深色 8%），按钮只变色不缩放，卡片按下 0.98；列表卡片零阴影、仅浮层使用 2/4/8dp elevation；自绘大标题 96→56（滚动 12dp 触发、200ms 插值）；行高 56/72/88、页边距 16；读数、阈值、计数统一 `tnum` 等宽数字。
- 信息架构：底部五项改为 **首页 / 智能 / 产品 / 市场 / 我的**。首页为设备卡片网格（名称、连接状态、最近读数、信号条）+ 分组分段 + 搜索/排序/分组 + 今日告警摘要（真实 `RuleEvent` 计数）+ 快捷场景；新增「智能」页承载自动化规则（增删启停）与最近触发记录，原「设备」页入口删除；产品/市场/我的保持能力，仅做视觉与几何收口。
- 场景（仅本机）：`scenes` / `scene_actions`（Room v9，`MIGRATION_8_9`，外键 CASCADE），动作只允许连接/断开指定设备、开始/停止后台采集、静音告警 10/30/60 分钟、刷新同步；一键执行按顺序逐个执行，任一失败不阻断后续，逐动作给出成功/失败原因并汇总；执行中显示进度、禁止重复点击；首页快捷场景展示前 3 个并 toast 结果。界面明示「仅本机」，不参与云端同步。
- 三态：统一 `ThreeStateHost`——骨架延迟 300ms 且与真实内容同高、空态使用真实文案 + 主行动按钮、错误块可重试；首页/智能/产品/市场覆盖页级三态，「我的」为本地即时数据，区块空态使用真实文案，不再出现白屏或通用「暂无数据」。
- 测试：新增 `SceneExecutorTest`（真实调用断言、权限缺失、设备未连接、重复采集、失败不阻断、汇总口径）、`SceneRepositoryTest`（顺序持久化/编辑替换/删除/未知类型过滤）、`SceneMigrationTest`（v8→v9 建表与外键级联）；`ChartColorAuditTest` 扩展为禁止布局与源码硬编码字号。单测 168 → 176，`assembleDebug`、`testDebugUnitTest`、`lintDebug` 全绿（0 error）。
- 文档：README 新增「信息架构」「场景（仅本机）」章节；`docs/acceptance.md` 升级 v0.8.0 并新增 §7.13 首页卡片/告警摘要与 §7.14 场景 CRUD/执行走查。

版本：`versionCode = 16`、`versionName = 0.8.0`。

---

## v0.7.0

**主题：账号迁移与注销 / 后台采集（对齐 PJGoIoT v0.7.0，openapi 0.6.0）**

- 跨设备接管（配对码）：「我的」新增「迁移到新设备」，调用 `POST /api/v1/auth/pairing-code`（Bearer），展示 8 位码并以服务端 `expiresAt` 每秒刷新倒计时（不自行推算 10 分钟）；支持复制到剪贴板；「重新生成」确认后调用同一接口，新码使旧码立即失效并在界面提示。新增「从旧设备接管」：输入 8 位码 → `POST /auth/pairing-code/claim` → 用 `{userId, token}` 替换本机 token，保留本地设备与读数并把 `devices`/`readings` 的 `sync_state` 全量重置为 `pending`、清空游标与同步状态，使新账号接管本机数据。输入框自动大写、过滤 `0/O/1/I` 等易混字符并截断 8 位，不足 8 位不可提交。错误处理：404 统一「码无效或已过期」，429「尝试过于频繁，请稍后再试」并显示 `Retry-After` 冷却秒数，网络错误可重试，401 提示重新登录。
- 账号导出与注销：`GET /api/v1/me/export` 按 `hasMore`/`nextCursor` 循环拉全（cursor 只透传、不解析；空游标或重复游标即停止），多页 `devices` 按 `id`/`externalId` 去重、`readings` 顺序拼接，缩进 JSON 写 `cacheDir/exports/` 后经 `FileProvider` + `ACTION_SEND` 分享；界面显示真实「已拉 N 条 / 第 M 页」，失败可重试。注销账号二次确认写明「设备与读数将永久删除且不可恢复」，确认后 `DELETE /api/v1/me`；204 后依次清 token（`EncryptedSharedPreferences` 整块清空）、游标、同步状态与 `Room.clearAllTables()`，回到匿名初始态并提示；401 与网络错误分别处理，不静默。
- 后台采集（前台服务）：详情页连接成功后出现「后台采集」开关，开启即以 `foregroundServiceType=connectedDevice` 的前台服务维持采集；通知渠道「数据采集」（`reading_collection`），常驻标题「正在后台采集」、正文「设备名 · 已采集 N 条」、带「停止」动作，点击进详情；`ongoing` + `onlyAlertOnce`。manifest 声明 `FOREGROUND_SERVICE` 与 `FOREGROUND_SERVICE_CONNECTED_DEVICE`；Android 14+ 缺权限时启动失败回滚开关并提示，Android 13+ 拒绝通知权限仍可采集（提示通知不可见）。只在界面操作时启动，符合 Android 12+ 启动限制；断开/退出/切换设备自动停止服务与通知；开关状态持久化，「我的」展示当前采集状态。
- 测试：新增 `PairingCodeFormatTest`、`AccountExportPagerTest`、`AccountExportJsonTest`、`AccountServiceTest`、`CollectionSessionTest`、`CollectionNotificationFactoryTest`（Robolectric 断言通知标题/正文/渠道/停止动作/点击意图），`AppSettingsTest` 补后台采集持久化；单测 139 → 168。e2e 新增 `account contract`（配对码字符集与 10 分钟 TTL、重新生成使旧码 404、claim 同 userId 新 token 且旧 token 仍有效、单次使用、未知码统一文案、导出需要鉴权、不透明游标分 2 页拉全并按 `recordedAt` 升序、Retrofit 生产映射、注销 204 后全部 token 401 且重复注销 401）。
- 文档：README 新增 §6.6「账号迁移与注销」、§6.7「后台采集」；验收清单升级 v0.7.0 并新增 §7.11/§7.12（含 Android 12/13/14 前台服务与通知权限差异）。
- Room 无 schema 变更（仍为 v8）；`ReadingDao.markAllPending()` 仅为新增查询，不涉及迁移。

版本：`versionCode = 15`、`versionName = 0.7.0`。

---

## v0.6.0

**主题：告警通知 / CSV 导出 / 设备列表检索 / 深色模式**

- 告警通知：规则命中后经 `RuleAlertSink` 投递本地通知，Android 8+ 独立渠道「设备告警」；Android 13+ 动态申请 `POST_NOTIFICATIONS`，拒绝时「我的」给出跳设置的提示且不阻断功能。「我的」新增真实告警开关与 1/5/30 分钟节流窗口；同一设备同一规则窗口内只通知一次，信号回落阈值内发一条「已恢复」，状态落 SharedPreferences 抗进程重启。通知正文只含设备名、指标、当前值、阈值与触发时间，点击打开设备详情，不含 token/账号。
- CSV 导出：详情「数据」区按当前设备 + 指标 + 区间导出。≤7 天读 Room；>7 天按 `limit=100` + cursor 分页直到 `hasMore=false`，上限 5 万条。CSV 带 UTF-8 BOM、表头 `recordedAt,metric,value,unit`、ISO8601 UTC 时间与 RFC 4180 转义；文件仅写 `cacheDir/exports/`，经 `FileProvider` + `ACTION_SEND` 系统分享；导出显示真实累计条数，失败给可重试错误。
- 设备列表：搜索按别名/广播名/型号名/MAC 模糊匹配（大小写不敏感，MAC 忽略冒号）；排序支持最近出现（默认）/名称/信号强度/添加时间并持久化；分组 Chip 来自真实分组值并含「未分组」；空结果区分「无设备（去扫描）」与「筛选无结果（清除筛选）」。
- 深色模式：新增 `values-night/colors.xml`，图表折线/数据点/网格/坐标轴/min–max 区间带、Chip、卡片、次要文字全部走主题资源；Room v8 新增 `devices.createdAt`（`MIGRATION_7_8`，历史数据用 `lastSeenTimestamp` 回填）支撑「按添加时间」排序。
- 测试：新增 `AlertThrottleTest`、`AlertNotifierTest`、`AlertNotificationFactoryTest`（Robolectric 断言 NotificationCompat 构建对象）、`ReadingCsvTest`、`ReadingPagerTest`、`DeviceListFilterTest`、`AppSettingsTest`、`ChartColorAuditTest`；v7→v8 迁移用例。单测 96 → 139。
- 文档：README 删除「待 iOS 确认」，`00 4A / 00 4D` 明确为两端实测 12.0℃ / 20.0℃；新增 §6.2–§6.5；验收清单升级 v0.6.0 并新增 §7.8–§7.10。

版本：`versionCode = 14`、`versionName = 0.6.0`。

---

## v0.5.0

**主题：详情页数据区升级为可切换长周期的产品级图表（对齐 PJGoIoT v0.5.0）**

- 区间切换：详情「数据」区新增 **1 小时 / 24 小时 / 7 天 / 30 天** Chip，默认 24 小时；映射 `1m / 1h / 1h / 1d`，任一区间理论桶数 ≤500，超限时 `chooseBucket` 自动加粗到下一档。
- 接入 `GET /api/v1/my/devices/{platform}/{externalId}/readings/series?metric=&from=&to=&bucket=`：`from/to` 使用 UTC RFC3339，桶起点与服务端 UTC 网格一致（整分钟 / 整点 / UTC 零点）。
- 图表几何重写：X 轴按桶 `start` 的真实时间比例定位（非数组下标）；服务端空桶不返回时，`bucket.start > previous.end` 即断线，不跨越缺口连线；每个桶绘制 avg 折线 + min–max 浅色区间带；Y 轴按区间真实 min/max 自适应并带当前单位；NaN/Inf 桶过滤，min/max 缺失回退 avg。
- 指标清单改为服务端真值：`GET .../readings/metrics` 提供 `count / lastAt / unit`，Chip 展示「指标名 · N 条」并按 `lastAt` 倒序；单位随所选指标更新 Y 轴与汇总行。网络失败才用本地聚合兜底。
- 离线降级：series 请求失败时把 Room 读数按同一 UTC 网格聚合；30 天区间显示「离线：仅本地 7 天数据」，数据集中在时间轴右侧；其余区间显示「离线：本地数据」。
- 24h 汇总保留固定窗口（不受区间切换影响），文案明确「最近 24 小时」。
- 测试：新增 `ReadingRangeTest`（映射/500 桶/自动降桶/UTC 对齐）、`ReadingSeriesBucketingTest`（聚合/排序/异常值/30 天仅本地 7 天）、重写 `ReadingChartGeometryTest`（空数据/单桶/等距/缺口断线/时间比例/30 天 24 桶/Y 轴/异常值），仓储新增本地指标与 `readingsSince` 用例；单测 79 → 96。
- e2e：真实后端新增 series 升序与桶内 count、空区间 `[]`、非法 bucket `400`、metrics 倒序/计数/单位 4 项。
- 文档：README §6.1 新增「区间与分桶」章节（UTC 网格与缺口不连线原因）；验收清单升级 v0.5.0（versionCode 13）并新增 §7.7。

版本：`versionCode = 13`、`versionName = 0.5.0`。

---

## v0.4.1

**主题：读数解析口径与 iOS 对齐（黄金字节表）+ 修复 pending 读数被 7 天清理误删**

- NUS 文本改为逐行解析：按 `\n` 分行、逐行 trim，一行一条读数；单行失败只丢该行（此前只取首行会漏数据）。整包上限 256 字节、单行上限 64 字符；未知 metric 归一 `other` 并保留该行原文快照。
- 温度统一口径：2 字节优先 sint16 × 0.01℃；若结果不在 −50…150℃ 或非有限值，回退 IEEE-754 半精度；4 字节按 IEEE-754 float32（小端）。任何路径结果超出 −50…150℃、NaN/Inf 一律丢弃。
- 心率：flags bit0=0 取 uint8；bit0=1 取 uint16 **小端**（第 2 字节低位、第 3 字节高位），范围 1..300，超范围丢弃。
- 电池：`0` 与 `100` 都是真实值（0% 不能再判为失败），仅 >100 丢弃。
- 清理逻辑修正：本地 7 天清理条件改为 `recordedAt < cutoff AND syncState='synced'`，**pending 记录无论多旧都保留**，离线超过 7 天的数据不会被清掉。
- 测试：`GattReadingParserTest` 按黄金表重写（心率 4 例、电池 3 例、温度 5 例、NUS 6 例、空字节/未知 UUID），`ReadingRepositoryTest` 新增「pending 无论多旧都保留」「仅删已同步」两项；单测 73 → 79。
- 文档：README §6.1 更新统一解析口径表与「黄金字节表」；验收清单升级到 v0.4.1（versionCode 12）并补充 §7.6 本地保留与 pending 保护。

版本：`versionCode = 12`、`versionName = 0.4.1`。

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
