# 契约 0.21.0 `lastSeenAt` / `presence` 第二次对账报告

核对基线（只读，未修改任何仓库代码、未提交、未打 tag）：

- 契约：`PJGoIoT` `api/openapi.yaml` version 0.21.0，后端 `v0.26.0`。
- Android：`PKotlinApp` `v0.32.7`（`9cb069a`）。
- iOS：`codemagic` `v0.31.7`（`72afcb3`；工作区 HEAD `f404ebc` 仅文档提交）。`v0.31.6`（`9100aca`）引入共享四档，`v0.31.7` 的代码差异仅 `DeviceLinkStatus.swift` 分行文案与 `SavedDeviceCell.swift` 调用、以及 e2e 的显式 `[SKIP]`，presence 语义未再改。

## 结论

六项中，**显式 `null`、`unknown` 跳过云端读数、iOS 共享四档三处已真正闭合**；`offline` 的「已离线 vs 上报超时」主分叉也已消除，但 **Android 首页卡片与设备详情仍未给出「超时」判定**，且 **Android 完全没有 iOS 的「本机不可连接」维度**，两端信息量不等价；**Android 的 Postgres CI 已真实断言 online/offline/unknown，iOS CI 仍 pin `v0.25.0` 并显式跳过**，新字段在 iOS CI 中依然零覆盖。

## 六项逐条核实

| 核查项 | 声称 | 代码与日志证据 | 判定 |
|---|---|---|---|
| 1. 状态档数统一 | iOS 新增 `DeviceLinkStatus`，首页 / 房间 / 列表 / 详情共用；`isOnline` 彻底消失 | 共享实现 `codemagic/Core/Models/DeviceLinkStatus.swift:8-30`，四档 `:9-14`，排序 `:34-42`；首页 `Modules/Home/ViewModels/HomeDashboardViewModel.swift:250`、`Modules/Home/Views/HomeCells.swift:102`；房间 `Modules/Rooms/ViewModels/RoomDetailViewModel.swift:199-203`；列表 `Modules/Devices/Views/SavedDeviceCell.swift:33`；详情 `Modules/Devices/Scenes/DeviceDetailViewController.swift:262`。全仓 `isOnline` 仅剩测试替身 `codemagicTests/SceneSyncTests.swift` 的网络开关，业务代码 0 处；`room` 的 15 分钟内本机扫描回退已删除，`RoomDetailViewModel.swift:288-300` 不再存在 | **主体闭合**；但列表与详情把 `isConnected` 写死为 `false`（`SavedDeviceCell.swift:33`、`DeviceDetailViewController.swift:262`），这两页永远不会出现 `localConnected`，与「四页同一答案」的声称不完全一致 |
| 2. `offline` 文案 | iOS「本机已连接 / 云端 15 分钟内有上报 / 云端上报已超时 / 云端从未收到上报」；Android 四档对照 | iOS 原文 `Resources/zh-Hans.lproj/Localizable.strings:189-195`、英文 `en.lproj/Localizable.strings:189-195`；Android 原文 `app/src/main/res/values/strings.xml:629-633`、英文 `values-en/strings.xml:627-629`。Android 房间行已改为「云端上报已超时」（`strings.xml:868`），首页卡片仍是「云端最近上报 %1$s」（`strings.xml:632`），详情页无状态、只显示时间（`ui/detail/DeviceDetailActivity.kt:416-418`） | **主分叉闭合**（不再出现「已离线」）；**页面级未完全收敛**：Android 首页卡片与详情不给出超时结论，iOS 四页均给出 |
| 3. 显式 `null` | Android `NullableReported?` 三态；iOS 原本清空；`/my/devices` 与 `/sync/changes` 均区分缺省与 null | Android：`data/remote/SyncApi.kt:51-59`（适配器）、`:129-133`（三态声明）；`/sync/changes` 映射 `data/sync/RemoteSyncDataSource.kt:411-412`；`/my/devices` 映射 `data/household/RemoteHouseholdDataSource.kt:281-282`、落库 `data/household/HouseholdRepository.kt:645-648`；保留/清空 `data/sync/RoomSyncLocalStore.kt:50-51`、`:61-70`。iOS：`Core/Sync/SyncModels.swift:196-200`（`container.contains` 判键）、`Core/Sync/SyncEngine.swift:548-549`、`:585-589`、`:800-803`、`Core/Persistence/SavedDeviceStore.swift:123-131` | **闭合（状态承载路径）**；注意 iOS 的 `GET /my/devices/{platform}/{externalId}` 所用 `UserDeviceInfo` 完全不解析新字段（`Core/Models/DeviceCatalog.swift:193-208`），该端点只作型号资料，不是状态路径 |
| 4. `unknown` 跳过云端读数 | 两端都跳过，且界面为「空」不是「加载中 / 失败」 | Android `ui/home/HomeViewModel.kt:426-431` 直接落 `CloudReadingState.Empty`；iOS 首页 `Modules/Home/ViewModels/HomeDashboardViewModel.swift:425`，房间 `Modules/Rooms/ViewModels/RoomDetailViewModel.swift:209-213` 落 `.empty`；两端空态文案分别由 `home_device_no_reading` / `room.detail.noReading` 呈现 | **闭合** |
| 5. 隐私申报 | iOS「使用数据 → 其他使用数据」、Android「应用活动 → 应用内其他操作」，属分类体系差异 | Android `docs/data-safety.md:1` 已升到 v0.32.7，`:18` 与 `:24-27` 明确 `lastSeenAt` / `presence` 归入「应用活动 → 应用内其他操作」，只读不上报；iOS `docs/app-privacy.md:20` 将 `SavedDevice.serverLastSeenAt` 归入 `NSPrivacyCollectedDataTypeOtherUsageData`，`codemagic/Resources/PrivacyInfo.xcprivacy` 仍为 8 类。两端代码路径一致：只读落地、不参与 push | **闭合**；分类名称差异来自 Play 与 Apple 两套表，不是归类错误或漏报。仅 iOS 文档版本头仍写 `v0.31.5`（`docs/app-privacy.md:1`、`:46`），属文档版本滞后 |
| 6. CI 是否仍空跑 | Android 真 Postgres 断 online/offline/unknown；iOS 仍 pin v0.25.0、`[SKIP]` 460/1；本地 v0.26 硬断言并做过反向验证 | Android：`.github/workflows/android.yml:64-68`、`:125-129` 仍无 `ref`（浮动 `main`）；`:149-155` 真跑 `scripts/e2e-postgres.sh`；`scripts/e2e-postgres.sh:75-158` 推读数、断言 `online/unknown`、`psql` 回拨 `last_seen_at` 20 分钟后再断言 `offline`；`app/src/test/java/com/platojobs/pkotlinapp/e2e/E2eContractTest.kt:863-869` 在 `store=postgres` 下硬断言 `online` 且 `lastSeenAt` 非空。iOS：`.github/workflows/ios.yml:84` pin `v0.25.0`、`:99` 跑 `scripts/e2e-contract.sh`；`scripts/e2e-contract.swift:916-932` 无字段时打印 `[SKIP]`；本机 `gh run view 42364832366` 日志为 **460 通过 / 1 跳过 / 0 失败**，SKIP 原因与代码一致。iOS 本地 v0.26 的硬断言只有 `presence=unknown / lastSeenAt=null`（`scripts/e2e-contract.swift:918-925`），反向验证 459/1 仅在 `docs/e2e-postgres.md:127`、`docs/releasenotes.md:5` 有记录 | **部分闭合**：Android Postgres 链真实覆盖三态；Android fixture 链仍只验枚举；iOS CI 对两个新字段仍为零覆盖 |

## 仍然存在的差异

| 项 | Android 现状（文件:行） | iOS 现状（文件:行） | 影响 | 判定 |
|---|---|---|---|---|
| 首页 `offline` 卡片文案 | 「云端最近上报 %1$s」，不出现「超时」：`strings.xml:632`；仅顶部汇总提示说明「超时未上报」：`ui/home/HomeAdapter.kt:256-262`、`HomeFragment` 的 `home_status_presence_hint` | 卡片主状态「云端上报已超时」+ 次行「云端最近上报 X」：`Core/Models/DeviceLinkStatus.swift:45-52`、`:111-136`，文案 `Localizable.strings:191`、`:193` | Android 用户单看卡片只能自行推断离线；iOS 直接断言超时 | 分叉（文案层） |
| 设备详情是否展示 presence 档位 | 只显示「云端最近上报 X（服务端接收读数时间）」或「从未上报过读数」，无 online/offline/unknown 状态：`ui/detail/DeviceDetailActivity.kt:416-418`、`strings.xml:158-159` | 有独立「最近上报」行，值为四档状态 + 绝对时间：`Modules/Devices/Scenes/DeviceDetailViewController.swift:201`、`:260-274` | 同一台超时设备，Android 详情看不出「超时」，iOS 能看出 | 分叉（覆盖缺失） |
| 「本机不可连接」维度 | 不存在。`Device` 有 `platform`（`data/local/Entities.kt:20`），但首页 / 房间 / 详情均未展示平台或「本机不可连接」；全仓 `grep` 无该文案 | 作为附加说明与云端状态并列：`DeviceLinkStatus.swift:63-64`、`:99-107`、`:122-126`；首页 `HomeCells.swift:103-120`、房间 `RoomDetailViewModel.swift:36-40`、列表 `SavedDeviceCell.swift`、详情 `DeviceDetailViewController.swift:260-274` | iOS 能看到「来自其他平台、本机不可连接」；Android 会把它当普通云端设备，信息量不对等。两端的组织方式并非「Android 有该维度、iOS 并列」——实际是 Android 缺失该维度 | 分叉（信息缺失） |
| 列表 / 详情的本机连接态 | 状态只在首页与房间计算，且两处都传入真实 `connectedMac`（`domain/model/Models.kt:142-150`、`ui/home/HomeViewModel.kt:307-315`） | 共享实现存在，但列表 / 详情硬编码 `isConnected: false`（`SavedDeviceCell.swift:33`、`DeviceDetailViewController.swift:262`），连接中的设备在这两页显示云端档 | 同一设备在 iOS 首页显示「本机已连接」，列表 / 详情显示「云端 15 分钟内有上报」 | 分叉（实现统一、数据源未统一） |
| 本机已连接时的云端状态 | 卡片只显示「本机已连接」，不显示云端超时（`HomeAdapter.kt:666-668`、`HomeFragment.kt:758-763`） | 本机已连接但云端超时时，次行仍显示「云端上报已超时」：`DeviceLinkStatus.swift:127-132` | iOS 信息更完整；Android 隐藏了「本机连着、云端读数早已超时」这一事实 | 分叉（信息量） |
| 时间显示形态 | 首页 / 详情相对时间；房间相对 + `MM-dd HH:mm`（本地时区、无年份无时区标识）：`ui/home/HomeFragment.kt:766-768`、`strings.xml:409`；详情相对：`DeviceDetailActivity.kt:411-414` | 首页 / 列表 / 房间相对时间；详情绝对时间（模板含年、秒）：`Core/Formatting/AppDateFormat.swift:19-28`、`DeviceDetailViewController.swift:264-274` | 不改变 presence 语义，但同一设备详情两端可读性不同 | 分叉（展示层） |
| iOS 单设备 `/my/devices` 不解析新字段 | Android 的 `/my/devices` 列表参与状态（`RemoteHouseholdDataSource.kt:281-282`） | `UserDeviceInfo` 无 `lastSeenAt` / `presence`（`Core/Models/DeviceCatalog.swift:193-208`），只作型号资料 | 不影响当前保存设备的 presence（状态只走 `/sync/changes`），但若未来用单设备端点刷新状态会丢字段 | 可接受但需记录 |
| iOS 显式 `null` 无测试 | Android 有完整单测：`data/sync/RoomSyncLocalStorePresenceTest.kt:61-101`、`data/sync/SyncDtoMappingTest.kt:295-329` | iOS 只有「缺省字段不清空」测试 `codemagicTests/SyncEngineTests.swift:953-983`；没有任何显式 `lastSeenAt: null` 的清空测试 | 代码正确但无回归保护，后续重构可能退回 | 缺失（测试） |
| Android CI 后端未 pin | 浮动 `main`：`.github/workflows/android.yml:64-68`、`:125-129` | iOS pin `v0.25.0`：`.github/workflows/ios.yml:84` | Android 断言有效但不可复现；后端 `main` 前进可能改变行为 | 风险 |
| iOS 隐私文档版本滞后 | Android 文档已到 v0.32.7：`docs/data-safety.md:1` | iOS 文档头仍 `v0.31.5`、二进制写 `0.31.5/build 45`：`docs/app-privacy.md:1`、`:46`，但 `:20` 已包含 `serverLastSeenAt` | 申报内容正确，版本证据与 v0.31.7 不符 | 文档滞后 |

## 需要各仓库修复的条目

### PJGoIoT

契约与服务端实现无需改动。若要让 Android fixture e2e 也具备在线/离线意义，可让 `internal/store/fixture/fixture.go` 在接受 `readings` 时推进 `last_seen_at`；否则应在 fixture 用例中明确标注「只做字段存在性」。

### PKotlinApp（Android）

1. **首页 `offline` 卡片补「超时」判定**：`res/values/strings.xml:632` 改为 `云端上报已超时 · 最近上报 %1$s`（英文 `res/values-en/strings.xml:628` 改为 `Cloud report timed out · last reported %1$s`），与房间 `strings.xml:868`、iOS `device.link.cloudStale` 对齐；否则保留现状但应在产品口径上接受「首页只报时间」。
2. **详情页补 presence 档位**：`ui/detail/DeviceDetailActivity.kt:416-418` 现在只有时间，建议按 `deviceLinkStatus` 输出「本机已连接 / 云端 15 分钟内有上报 / 云端上报已超时 / 云端从未收到上报」，再附时间，避免首页、房间、详情三处结论不同。
3. **补齐跨平台说明（可选但信息量需要）**：Android 能同步非 Android 设备（`Entities.kt:20` 的 `platform`），但 UI 无「来自 X、本机不可连接」。若产品要求与 iOS 信息对等，需在首页 / 房间 / 详情加该附加说明；若明确不做，应在报告层面承认为合理产品差异。
4. **CI pin 后端版本**：`.github/workflows/android.yml:64-68`、`:125-129` 增加 `ref: v0.26.0`，避免浮动 `main` 让 Postgres 断言不可复现。

### codemagic（iOS）

1. **列表 / 详情接入真实连接态，或明确不展示本机档**：`SavedDeviceCell.swift:33`、`DeviceDetailViewController.swift:262` 固定 `isConnected: false`，导致同一设备在首页是「本机已连接」、列表 / 详情是云端档。若列表 / 详情拿不到 BLE 状态，应在产品上明确「这两页只展示云端状态」，而不是声称四页完全同源同答。
2. **补显式 `null` 回归测试**：在 `codemagicTests/SyncEngineTests.swift` 增加 `hasLastSeenAtField=true`、`serverLastSeenAt=nil` 的用例，断言已有值被清空；当前只有「缺省字段不清空」用例（`:953-983`）。
3. **隐私文档版本头更新**：`docs/app-privacy.md:1`、`:46` 从 `v0.31.5` 升到 `v0.31.7`；申报内容本身正确，无需改分类。
4. **CI 至少让 presence 进入一条真实后端链路**：`ios.yml:84` 仍 pin `v0.25.0`（受 `workflow` scope 限制无法升 `v0.26.0`），可先把本地 `scripts/e2e-postgres.sh` 的 online/offline/unknown 断言接入 CI（不依赖改 pin），或单独建立一个 pin `v0.26.0` 的 e2e 作业；否则当前 `[SKIP]` 会长期存在。

## CI 覆盖缺口与空跑断言

- **iOS `e2e-contract`（CI）**：pin `v0.25.0`，`presence` / `lastSeenAt` 项显式 `[SKIP]`（`scripts/e2e-contract.swift:916-932`）。日志核对为 460 通过 / 1 跳过 / 0 失败，跳过项不计通过。也就是说，**iOS CI 的 460 项通过并不证明新字段被消费**。
- **iOS 本地 `v0.26.0` 的硬断言**只覆盖 `GET /my/devices/{mac}` 上「从未上报设备」的 `presence=unknown`、`lastSeenAt=null`（`scripts/e2e-contract.swift:918-925`）；**online / offline 在 `e2e-contract` 中没有任何断言**，它们只在本地 `scripts/e2e-postgres.sh` 的真 Postgres 步骤覆盖，而该脚本**不在 CI**（`ios.yml` 只有 `build-test` 与 `e2e-contract` 两个作业）。
- **反向验证**（`v0.26.0` 下把期望改成 `online` → 459 通过 / 1 失败）在 `docs/e2e-postgres.md:127`、`docs/releasenotes.md:5` 有记录；代码路径上字段存在时确实是硬断言，但没有仓库内的可复现日志，只能视为「有记录、未独立复现」。
- **Android `e2e-contract`（fixture）**：`E2eContractTest.kt:870-879` 只断言 `presence` 属于 `{unknown,online,offline}` 集合，fixture 后端恒为 `unknown`；**没有 online / offline 的真实推导**，属于弱断言。
- **Android `e2e-postgres`**：真实覆盖 `online`（刚接受读数、旧 `recordedAt` 补录）、`offline`（`psql` 回拨 20 分钟）、`unknown`（从未上报），但走的是 `/my/devices`；**`/sync/changes` 的 `lastSeenAt` / `presence` 映射没有任何真实后端断言**。
- **只在单测 / UI 测试覆盖、没有任何真实后端用例的分支**：
  - 两端 `/sync/changes` 的 `lastSeenAt` / `presence` 解析与三态（Android `SyncDtoMappingTest.kt:295-329`，iOS `SyncEngineTests.swift:877-983`）。
  - Android pending 冲突下 `applyServerPresence` 只写列、不置脏（`RoomSyncLocalStorePresenceTest.kt:84-101`）。
  - iOS pending 冲突下 `applyServerLastSeenAt` 只写列（`SyncEngineTests.swift:877-951`）。
  - iOS 显式 `null` 清空（**无单测、无 e2e**）。
  - 两端 `unknown` 跳过云端读数、空态呈现（`HomeViewModel.kt:426-431`、`RoomDetailViewModel.swift:209-213` 仅有单测/UI 测试）。
  - iOS 列表 / 详情 `isConnected=false` 与「本机不可连接 + 云端状态」组合（只有 UI 测试与截图）。
  - Android 首页卡片 stale 文案、详情页无状态（只有字符串/UI 测试）。

## 两端仍然一致的部分

- **presence 重算**：两端都不使用响应里的 `presence` 字段做展示，而是用已落地的 `lastSeenAt` 在本机重算；窗口同为 900 秒，边界同为 `<=`。Android `domain/model/Models.kt:113-124`，iOS `Core/Models/SavedDevice.swift:51`、`:190-193`。
- **持久化分列**：服务端最近上报时间与本机 BLE 扫描时间分列，均只在服务端字段存在时覆盖；本地扫描保存不会推进 `updatedAt` 或置脏。Android `data/local/Entities.kt:18`、`:59`、`data/local/Daos.kt:96-97`；iOS `Core/Persistence/SQLiteDatabase.swift:203`、`:781`、`Core/Persistence/SavedDeviceStore.swift:121-131`。
- **共同的时钟偏差风险**：两端都用本机时钟与 UTC `lastSeenAt` 比较，未来时间戳会长期判 online；这不是两端分叉，但都偏离服务端「按自己的时钟推导」的口径（`api/openapi.yaml:3437）。

## 合理平台差异（不需要统一）

- **隐私分类表**：Play「应用活动 → 应用内其他操作」与 Apple「使用数据 → 其他使用数据」是两套商店分类体系，指向同一批「读数 / 上报时间戳」使用数据；两端都声明为采集、只读、不上报，未发现一端申报一端漏报。
- **iOS 房间汇总多一列「本机不可连接」**：这是附加维度，不占四档，不造成状态重复计数；Android 没有该维度属于信息缺失，不是合理差异。

## 未闭合 / 需接受的真实差异

1. Android 首页卡片与详情页没有「上报超时」结论（文案与覆盖缺失）。
2. Android 没有「本机不可连接」维度，iOS 有；两端信息量不等价。
3. iOS 列表 / 详情固定 `isConnected=false`，四页并未真正做到「同一设备同一答案」。
4. iOS CI 对 `presence` / `lastSeenAt` 仍为零覆盖；Android fixture 链仍为弱断言，且后端版本未 pin。
5. iOS 显式 `null` 清空无回归测试。

以上不属于「已闭合」；其余（状态枚举、900 秒边界、分列持久化、只读不置脏、显式 null 语义、unknown 跳过云端读数、隐私申报）两端一致或已按契约对齐。
