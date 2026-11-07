# 契约 0.21.0 `lastSeenAt` / `presence` 双端一致性报告

核对基线（只读，未修改任何仓库文件、未提交、未打 tag）：

- 契约：`PJGoIoT` `api/openapi.yaml` version 0.21.0，后端 `main`/`v0.26.0`（`188ad78`）。
- Android：`PKotlinApp` `v0.32.6`（`d9b0e5d`）。
- iOS：`codemagic` `v0.31.5`（`dbf8c6c`；当前 `main` 为 `ba0c31a` 文档提交）。

## 结论

两端在 `lastSeenAt` 分列持久化、`900s` 三态推导以及“本机连接优先于服务端 presence”上基本等价，但在状态档数与优先级（iOS 把 15 分钟内本机扫描计为在线、并用“本机不可连接”遮蔽 presence）、`offline` 文案（iOS 断言“已离线”、Android 只说“上报超时”）、显式 `null` 清理、`unknown` 时是否跳过云端读数、CI 覆盖与隐私申报上存在分叉或缺失。

## 差异表

| 项 | Android 现状（文件:行） | iOS 现状（文件:行） | 契约要求 | 判定 |
|---|---|---|---|---|
| presence 三态定义 | `DevicePresence` 3 值：`app/src/main/java/com/platojobs/pkotlinapp/domain/model/Models.kt:107-111` | `DevicePresence` 3 值：`codemagic/Core/Models/SavedDevice.swift:45-51` | 必须区分 unknown/online/offline：`api/openapi.yaml:3425-3428`、`:3437-3439` | 等价 |
| 900 秒窗口与边界 | `PRESENCE_WINDOW_MS = 900_000`，`now - lastSeenAt <= 900_000` 为 online：`Models.kt:114-124`；边界含 900s 有测试：`app/src/test/java/com/platojobs/pkotlinapp/domain/model/DevicePresenceTest.kt:24-41` | `window = 15 * 60`，`now - serverLastSeenAt <= 900s` 为 online：`SavedDevice.swift:51`、`:190-193`；边界测试：`codemagicTests/RoomDetailTests.swift:155-178` | `online = now - lastSeenAt <= 900s`，`offline` = 超 900s：`api/openapi.yaml:3438-3439` | 等价 |
| 状态展示档数 | 4 档：`LOCAL_CONNECTED / CLOUD_RECENT / CLOUD_STALE / CLOUD_UNKNOWN`：`Models.kt:127-139`；房间汇总 4 档 + 已标记：`app/src/main/java/com/platojobs/pkotlinapp/ui/home/RoomDetail.kt:94-101` | 首页卡片 5 档：连接 / 本机不可连接 / online / offline / unknown：`codemagic/Modules/Home/Views/HomeCells.swift:103-120`；房间汇总 5 档 + 已标记：`codemagic/Modules/Rooms/ViewModels/RoomDetailViewModel.swift:149-157`、`Resources/zh-Hans.lproj/Localizable.strings:519` | 契约只定义 presence 三态，未定义客户端展示档数 | 分叉 |
| `unknown` 是否并入离线 | 独立一档，不并入 offline：`Models.kt:136-139`；测试 `DevicePresenceTest.kt:37-41` | 独立一档，不并入 offline：`SavedDevice.swift:45-51`、`RoomDetailViewModel.swift:154-157`；测试 `RoomDetailTests.swift:151-152` | 必须区分“从未知道”与“明确离线”：`api/openapi.yaml:3425-3427` | 等价 |
| 本机 BLE 连接与 presence 的优先级 | 当前连接 > presence：`Models.kt:142-150`；`HomeViewModel.kt:307-315` | 首页：当前连接 > 本机不可连接 > presence：`HomeCells.swift:103-120`；房间详情：当前连接或“最近 15 分钟内扫描到” > 本机不可连接 > presence：`RoomDetailViewModel.swift:36-40`、`:288-300` | 契约要求不得把 BLE 本机连接与 presence 合并成同一字段语义：`api/openapi.yaml:3417-3432`、`:3439-3440` | 分叉 |
| 本机“在线”证据 | 只认当前 BLE 连接；扫描时间 `lastSeenTimestamp` 不参与状态：`Models.kt:142-150` | 首页只认当前连接（`HomeDashboardViewModel.swift:239-241`），但房间详情把 15 分钟内本机扫描也算在线（`RoomDetailViewModel.swift:288-300`）；iOS 自身首页与房间详情口径已不一致 | 服务端 presence 只来自上报；本机证据由客户端定义，但两端应统一 | 分叉 |
| 跨平台设备的状态展示 | 无“本机不可连接”分支，直接展示 presence：`app/src/main/java/com/platojobs/pkotlinapp/ui/home/HomeAdapter.kt:452-468` | 首页卡片和房间详情均先用“本机不可连接”遮蔽 presence：`HomeCells.swift:106-108`、`RoomDetailViewModel.swift:38-40` | 契约未规定；但同一设备两端展示口径应一致 | 分叉 |
| 房间汇总计数是否互斥 | 4 档互斥，另有已标记：`RoomDetail.kt:94-101` | `onlineCount/reportedCount/offlineCount/neverReportedCount` 排除 `isOnline`，但 `remoteCount` 统计全部 `!isConnectable`，与非 remote 档重叠：`RoomDetailViewModel.swift:149-157` | 契约无展示要求；计数应可解释 | 分叉 |
| 持久化字段与列 | `devices.lastSeenAt INTEGER` 可空，另存本机扫描 `lastSeenTimestamp`（非空）：`app/src/main/java/com/platojobs/pkotlinapp/data/local/Entities.kt:18`、`:59` | `saved_devices.server_last_seen_at REAL` 可空，另存本机 BLE `last_seen_at`：`codemagic/Core/Persistence/SQLiteDatabase.swift:203`、`:781` | 客户端不得写入、不参与 LWW：`api/openapi.yaml:3432`、`:3440` | 等价 |
| 增量迁移 | Room v25 新增可空列：`app/src/main/java/com/platojobs/pkotlinapp/data/local/AppDatabase.kt:363-364`、`:391`；迁移测试 `DeviceLastSeenMigrationTest.kt:16-52` | SQLite v18 新增可空列：`SQLiteDatabase.swift:771-781` | 契约未规定存储，但两端需保留“从未上报”与“本地从未扫描”的区分 | 等价 |
| 字段缺省（老后端） | `lastSeenAt=null` 一律保留本地值：`RoomSyncLocalStore.kt:50-51`、DAO CASE 保护 `data/local/Daos.kt:96-100` | 用 `hasLastSeenAtField` 区分缺省与显式 null；缺省保留本地已存值：`Core/Sync/SyncModels.swift:197-198`、`Core/Sync/SyncEngine.swift:799-803` | 老后端无该字段时应降级、不丢心跳：契约 0.21.0 之前无此字段 | 等价 |
| 显式 `null`（服务端明确“从未上报”） | 无法区分缺省与 null；`applyServerPresence` 遇 null 直接返回，永不清理：`RoomSyncLocalStore.kt:61-65` | 显式 null 会清理本地 `server_last_seen_at`：`SyncModels.swift:197-198`、`SyncEngine.swift:547-549`、`:584-588` | `null` 表示从未上报：`api/openapi.yaml:3426` | 分叉 |
| 服务端只读字段是否置脏/推进 `updatedAt` | 待上传冲突时只写列、不动 dirty/updatedAt：`RoomSyncLocalStore.kt:61-65`；`SyncEngine.kt:238-247` | 待上传冲突时只写列、不动 pending/updatedAt：`SyncEngine.swift:583-589`；测试 `codemagicTests/SyncEngineTests.swift:877-946` | 只读、不参与 LWW：`api/openapi.yaml:3432`、`:3440` | 等价 |
| 本地扫描写回是否可能误清服务端值 | 列级 CASE，null 保留旧值：`Daos.kt:96-100`；扫描保存 coalesce：`data/repository/LocalRepositories.kt:96` | `upsert` 为整行 `INSERT OR REPLACE`，SQL 含 `server_last_seen_at`：`Core/Persistence/SavedDeviceStore.swift:81-83`；当前扫描保存路径有 `!row.isSaved` 守卫，未见触发，但结构上容易误清 | 只读字段不得被本地写入清空 | 分叉 |
| 单调性保护 | 注释称“只会前进”，DAO 实际无 `max()` 守卫：`RoomSyncLocalStore.kt:50`、`Daos.kt:96-100` | `lastSeenChanged` 只比较不等，无时序守卫：`SyncEngine.swift:548-549` | 服务端单调推进：`api/openapi.yaml:3431-3432` | 等价（共同依赖服务端） |
| `presence=unknown` 时云端读数请求 | `lastSeenAt == null` 直接落 `Empty`，跳过 metrics + limit=1：`app/src/main/java/com/platojobs/pkotlinapp/ui/home/HomeViewModel.kt:426-431` | 不跳过，所有设备都发起云端读数请求：`RoomDetailViewModel.swift:238-241` | 契约未要求跳过；unknown 只说明服务端从未接受读数 | 分叉 |
| `offline` 首页文案 | 中文“云端最近上报 %1$s”，不出现“离线”：`res/values/strings.xml:632`；英文“Last reported %1$s”：`res/values-en/strings.xml:628` | 中文“已离线”，时间行“最近上报 %@”：`Resources/zh-Hans.lproj/Localizable.strings:193`、`:299`；英文“Offline”：`en.lproj/Localizable.strings:193` | `offline` 只表示超 15 分钟，没有单独“下线”事件：`api/openapi.yaml:3439` | 分叉 |
| `offline` 房间/详情文案 | 房间“云端上报已超时”：`strings.xml:868`；详情只显示“云端最近上报 X”无状态：`app/src/main/java/com/platojobs/pkotlinapp/ui/detail/DeviceDetailActivity.kt:416-418`、`strings.xml:158` | 房间“已离线 · 最近上报 %@”：`Localizable.strings:521`；详情“已离线 · 最近上报 %@”：`:155` | 同上；不应写成权威连接状态 | 分叉 |
| `online` 文案 | “云端 15 分钟内有上报”：`strings.xml:631`、`:867`；英文“Reported within 15 min”：`values-en/strings.xml:627`、`:863` | 首页“最近上报”：`Localizable.strings:192`；房间“最近上报 X”：`RoomDetailViewModel.swift:43-45` | `online = 15 分钟内`：`api/openapi.yaml:3438` | 分叉 |
| `unknown` 文案 | “云端从未收到上报” / “从未上报过读数”：`strings.xml:633`、`:869`、`:159` | “从未上报” / “从未上报读数”：`Localizable.strings:194-195` | `unknown = lastSeenAt null`：`api/openapi.yaml:3438` | 等价 |
| 本机连接文案 | “本机已连接”：`strings.xml:629`、`:866` | “本机在线”：`Localizable.strings:295`、`:519` | 不得把本机连接写成 presence 在线 | 分叉 |
| 900 秒边界的文案表达 | 明示“15 分钟内 / 超时”：`strings.xml:631-632`、`:862`、`:867-868`；英文汇总用“reported earlier”：`values-en/strings.xml:858` | 完全不提窗口，仅“最近上报 / 已离线”：`Localizable.strings:192-193`、`:519`、`:521` | 窗口 900s 固定：`api/openapi.yaml:3437-3439` | 分叉 |
| 时间显示形态 | 首页/详情相对时间；房间“相对 + `MM-dd HH:mm`”（本地时区、无年份无时区标识）：`HomeFragment.kt:450-454`、`:440-444`；详情仅相对：`DeviceDetailActivity.kt:411-414` | 首页/列表/房间均为相对时间；详情为绝对时间（模板含年、秒）：`Core/Formatting/AppDateFormat.swift:19-27`、`DeviceDetailViewController.swift:264-274` | 契约时间戳为 UTC：`api/openapi.yaml:3423` | 分叉 |
| “服务端接收时间”语义标注 | 房间与详情明示“服务端接收时间”：`strings.xml:158`、`:870` | 仅写“最近上报 / 已离线 · 最近上报”，未说明是服务端接收时间：`Localizable.strings:155`、`:521` | 推导取服务端接收时间，不是 `recordedAt`：`api/openapi.yaml:3430-3431` | 分叉 |
| 老后端无字段时的读数展示 | 视为 unknown → 跳过云端读数请求 → 显示 Empty（对 v0.25 后端会把“有历史读数”误显示为空）：`HomeViewModel.kt:426-431` | 视为 unknown，但仍请求云端读数 → 能显示读数，同时状态写“从未上报读数”：`RoomDetailViewModel.swift:238-241`、`SavedDevice.swift:190-193` | 老后端无该字段时属降级场景，应避免自相矛盾 | 分叉 |
| 网络失败/超时 | presence 只用本地已存 `lastSeenAt` 重算，云端请求失败只影响读数区：`Models.kt:120-124`、`HomeFragment.kt:426`、`HomeAdapter.kt:515` | 同上，presence 不依赖云端请求结果：`SavedDevice.swift:190-193`、`RoomDetailViewModel.swift:21-25`、`:238-241` | 无强制要求 | 等价 |
| `presence` 过期后重算方式 | 每次状态流发射/Room 重载时用本机 `System.currentTimeMillis()` 重算；无定时器：`HomeViewModel.kt:307-315`、`RoomDetail.kt:75-80` | 每次渲染/重载时用本机 `Date()` 重算；无定时器：`RoomDetailViewModel.swift:206-216`、`SavedDevice.swift:190-193` | 契约只在响应时由服务端重算：`api/openapi.yaml:3437-3440` | 等价（共同依赖本机时钟） |
| 时钟偏差 | 用本机时钟与 UTC `lastSeenAt` 比较；未来时间戳会长期判 online | 同算法、同本机时钟；`SavedDevice.swift:190-193` | 服务端按自己的时钟推导：`api/openapi.yaml:3437` | 等价（共同风险） |
| CI 后端版本 | 两个 e2e 作业 check out `platojobs/PJGoIoT` 均无 `ref`，浮动到 `main`（当前 v0.26.0）：`.github/workflows/android.yml:64-68`、`:125-129`、`:95`、`:155` | e2e 作业 pin `v0.25.0`：`.github/workflows/ios.yml:84`、`:99` | 应对 v0.26.0 验证 `lastSeenAt`/`presence` | 分叉 |
| CI 是否真的断言 `lastSeenAt`/`presence` | 只断言 `presence` 属于三值集合、never 设备 `presence=unknown` 且 `lastSeenAt=null`；未断言 reported/backfilled 为 online，也未断言其 `lastSeenAt` 非空：`app/src/test/java/com/platojobs/pkotlinapp/e2e/E2eContractTest.kt:852-876`；fixture 后端永远返回 unknown：`PJGoIoT/internal/store/fixture/fixture.go:521` | e2e runner 对 `presence`/`lastSeenAt` 断言数为 **0**（`scripts/e2e-contract.swift` 全文件 0 处匹配），且后端 pin 在无该字段的 v0.25.0 | 契约要求两字段必返：`api/openapi.yaml:3377` | 缺失 |
| 隐私申报 | `docs/data-safety.md:1` 仍为 v0.31.3，全文未提 `lastSeenAt`/`presence`；仅覆盖本机 `lastSeenTimestamp`（`docs/data-safety.md:18`） | `docs/app-privacy.md:20` 明确把 `SavedDevice.serverLastSeenAt` 归入 `NSPrivacyCollectedDataTypeOtherUsageData`（使用数据 → 其他使用数据），并在 `:27-37` 维持 8 类核对 | 不属于契约，但两端应一致申报 | 分叉（Android 缺失） |

## 需要各仓库修复的条目

### PJGoIoT

功能与契约本身无需改动。若要让 Android fixture e2e 具备真实意义，需要：

1. `internal/store/fixture/fixture.go:521` 的 fixture 存储目前不记录可用读数、`presence` 恒为 `unknown`。要么让 fixture 在 `readings` 被接受时推进 `lastSeenAt`，要么明确 fixture e2e 只做字段存在性检查、把在线/离线断言放到 Postgres e2e。

### PKotlinApp（Android）

1. **统一 `offline` 口径**，采用“上报超时”而非“已离线”，与契约 `api/openapi.yaml:3439`（没有单独下线事件）一致：
   - `res/values/strings.xml:632` 改为 `上报已超时 · 最近上报 %1$s`；`res/values-en/strings.xml:628` 改为 `Report expired · last reported %1$s`。
   - `DeviceDetailActivity.kt:416-418` 目前只显示时间、没有状态；增加 presence 判断，`offline` 使用 `上报已超时 · 最近上报 %1$s（服务端接收时间）`，`unknown` 使用现有 `从未上报过读数`。
   - `res/values-en/strings.xml:858/859` 的汇总 `reported earlier` 改为 `report expired`，与中文“超时未上报”（`strings.xml:862`）一致。
2. **补齐详情页状态**：详情页（`DeviceDetailActivity.kt:416-418`）当前不呈现 online/offline/unknown，只给时间；应与首页/房间使用同一 `DeviceLinkStatus` 派生结果，避免同一设备三处口径不同。
3. **修老后端下 unknown 跳过云端读数**：`HomeViewModel.kt:426-431` 以 `lastSeenAt == null` 直接落 `Empty`，在 v0.25 后端上会把有历史读数的设备误显示为空。改为“仅在响应显式给出 `presence=unknown` 时跳过”，或像 iOS 一样始终发起请求（`RoomDetailViewModel.swift:238-241`）。
4. **显式 `null` 与缺省区分（低优先）**：`RoomSyncLocalStore.kt:61-65`、`Daos.kt:96-100` 把两种 null 合并处理，服务端明确 `lastSeenAt: null` 时不会清理旧值。可在 DTO 侧记录键是否存在（或利用 `presence` 键存在性），`unknown + 键存在` 时调用清空；若确认服务端单调推进（`api/openapi.yaml:3431-3432`），也可在文档中声明“显式 null 不清理”为可接受行为并补测试。
5. **CI 固定后端版本**：`.github/workflows/android.yml:64-68`、`:125-129` 的两个 checkout 均无 `ref`。固定为 `v0.26.0`（或提交号），避免后端浮动导致客户端契约测试结果不可复现。
6. **加强 e2e 断言**：`E2eContractTest.kt:852-876` 的 reported/backfilled 仅检查 `presence ∈ {unknown,online,offline}`，且不检查 `lastSeenAt` 非空。应在 Postgres e2e 中至少断言：
   - 刚推送读数的设备 `presence=online` 且 `lastSeenAt` 非空；
   - 用旧 `recordedAt` 补录读数的设备仍为 `online`（服务端取接收时间，`api/openapi.yaml:3430-3431`）；
   - never 设备 `presence=unknown`、`lastSeenAt=null`（保留现有断言）。
7. **Data safety 文档更新**：`docs/data-safety.md:1` 版本从 v0.31.3 升到当前 v0.32.6，并在 `docs/data-safety.md:18` 的“应用活动 → 应用内其他操作”行补一句：`devices.lastSeenAt` / `presence` 为服务端只读派生字段，客户端不上报、不参与 LWW；与 iOS `docs/app-privacy.md:20` 的口径对齐。

### codemagic（iOS）

1. **统一 `offline` 口径**，去掉“已离线”的权威断言：
   - `Resources/zh-Hans.lproj/Localizable.strings:193` `device.presence.offline` 改为 `上报已超时`；
   - `:155`、`:521` 的 `offlineWithTime` 改为 `上报已超时 · 最近上报 %@`；
   - `:519` 汇总 `已离线 %@` 改为 `上报超时 %@`；
   - `en.lproj/Localizable.strings:193` 改为 `Report expired`，`:155`、`:521` 改为 `Report expired · last reported %@`，`:519` 改为 `report expired`。
   - 这同时消除了 `HomeCells.swift:101-102`“不写成权威的在线”与 `DeviceDetailViewController.swift:260`、`:269-270`“不写成权威在线状态”却把 `offline` 写成“已离线”的自相矛盾。
2. **统一 `online` 与窗口文案**：`:192` `device.presence.reported` 改为 `15 分钟内有上报`（en `Reported within 15 min`），与 Android `strings.xml:631`、`:867` 对齐；`room.detail.lastReported` 可保留“最近上报 X”，因为它已给出具体时间。
3. **统一本机连接文案**：`:295` `home.status.connected` 改为 `本机已连接`（en `Connected on this phone`），`:519` 汇总同步改为“本机已连接”；避免与 presence 的“在线”混淆。
4. **房间详情在线判定对齐**：`RoomDetailViewModel.swift:288-300` 把“15 分钟内本机扫描到”算作在线，而首页（`HomeDashboardViewModel.swift:239-241`）只认当前连接，Android 也只认当前连接。建议删除 `lastSeenAt >= onlineSince` 分支，仅保留 `isConnectable && isConnected`；同时修正 `RoomDetailTests.swift:174-175` 与相关 `onlineCount` 预期。若产品坚持保留“最近扫描=本机在线”，则 Android 需新增第 5 档，成本更高。
5. **跨平台设备展示优先级对齐**：`HomeCells.swift:106-108`、`RoomDetailViewModel.swift:38-40` 在 presence 之前插入“本机不可连接”，导致同一 Android 设备在 iOS 上看不到服务端状态。建议改为 presence 优先，“本机不可连接”降为 `presenceNote`（已有实现 `RoomDetailViewModel.swift:57-65`），与 Android 直接展示 presence 一致。
6. **汇总计数去重**：`RoomDetailViewModel.swift:149-157` 的 `remoteCount` 与 `neverReportedCount`/`reportedCount`/`presenceOfflineCount` 可重叠（跨平台且从未上报的设备会被计两次）。改为互斥：`remoteCount` 只统计 `!isConnectable`，其余计数排除 `!isConnectable`，并在 `Localizable.strings:519` 保持 5 档不重不漏。
7. **持久化误清防护**：`SavedDeviceStore.swift:81-83` 是整行 `INSERT OR REPLACE` 且包含 `server_last_seen_at`。当前本地扫描保存路径有 `!row.isSaved` 守卫（`Modules/Home/ViewModels/HomeViewModel.swift`），未发现实际误清，但建议给该路径补一条回归测试，或让本地扫描写回走列级更新，避免未来新增写路径把服务端只读值清空。
8. **CI 升版并补断言**：`.github/workflows/ios.yml:84` 的 `ref: v0.25.0` 在 push 凭证恢复 `workflow` scope 后改为 `v0.26.0`；`scripts/e2e-contract.swift` 增加：
   - push 读数后 `GET /my/devices` 断言该设备 `presence=online`、`lastSeenAt` 非空；
   - never 设备 `presence=unknown`、`lastSeenAt=null`；
   - offline 需要时间控制，fixture 无法回拨时应在 Postgres e2e 中预置旧 `last_seen_at` 后再断言。
9. **隐私申报**：无需改动；`docs/app-privacy.md:20` 已覆盖 `serverLastSeenAt` 且维持 8 类（`:27-37`）。

## CI 覆盖缺口与风险

1. **iOS e2e 对本特性完全空跑**：`ios.yml:84` pin 在 `v0.25.0`，该版本响应没有 `lastSeenAt`/`presence`；`scripts/e2e-contract.swift` 对这两个字段的断言数为 0。风险是解码、字段映射、三态展示任何一处回归都不会被 e2e 发现，只剩 `codemagicTests/RoomDetailTests.swift:155-178` 与 `SyncEngineTests.swift:877-946` 的合成数据单测兜底。
2. **Android fixture e2e 实际只验证“字段存在”**：`E2eContractTest.kt:858-876` 对 reported/backfilled 只检查 `presence` 是否属于三值集合，fixture 后端又恒为 `unknown`（`fixture.go:521`）。即使服务端把“刚上报”设备算成 `unknown`，断言仍通过。
3. **Android Postgres e2e 也未断言在线派生**：`android.yml:155` 跑 Postgres 时复用同一测试类，reported 设备刚推送过读数，但测试没有断言 `presence=online`；`lastSeenAt` 仅对 never 设备断言为 null，reported/backfilled 是否为非空完全未覆盖。服务端漏填 `lastSeenAt` 或错误返回全 `unknown` 时，整条 Android e2e 仍会绿。
4. **两端都没有 e2e 覆盖 `offline`**：`offline` 需要把服务端时间回拨或等待 15 分钟，fixture/现有 Postgres e2e 都做不到；当前只有 Android `DevicePresenceTest.kt:31-41`、iOS `RoomDetailTests.swift:174-175` 的单测覆盖。
5. **Android 后端版本浮动**：`android.yml:64-68`、`:125-129` 无 `ref`，CI 实际验证的是运行时的 `main`；当前是 `v0.26.0`，后续后端一旦改动契约，Android CI 无法固定复现历史结果。
6. **iOS e2e 实际验证的是“旧后端降级路径”**：由于 pin 在 `v0.25.0`，CI 只能证明“字段缺失时 iOS 不崩”，不能证明“字段存在时 iOS 正确展示/清理”；新契约的端到端行为没有进入 iOS CI。
