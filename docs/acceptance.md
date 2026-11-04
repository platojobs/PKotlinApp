# 真机验收清单（v0.3.5）

本清单用于「执行人不在开发现场」时也能照着做完验收。每项都写明**操作步骤、预期现象、失败判定**。
所有结果请记录到文末的「验收记录表」；未实际执行的项不要填 PASS。

- 适用版本：`versionName = 0.3.5`（`versionCode = 8`）
- 应用包名：`com.platojobs.pkotlinapp`
- 主界面：`com.platojobs.pkotlinapp.ui.MainActivity`
- 设备详情：`com.platojobs.pkotlinapp.ui.detail.DeviceDetailActivity`

---

## 0. 验收前准备

### 0.1 硬件与系统

| 项 | 要求 | 说明 |
| --- | --- | --- |
| Android 真机 | Android 12+（API 31+） | 需要验证运行时蓝牙权限 |
| Android 16 设备 | API 36 | 仅「Android 16 两个广播」一组需要 |
| BLE 外设 | 至少 1 个可扫描、可连接的 BLE 设备 | 心率带、手环、BLE 温湿度计均可 |
| 可配对外设 | 支持系统蓝牙配对/加密 | 验证 `ACTION_KEY_MISSING` / `ACTION_ENCRYPTION_CHANGE` 需要 |
| 网络 | 手机与 Go 服务同一局域网 | 产品 / 市场 / 同步需要 |

### 0.2 构建与安装

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

预期：安装成功，桌面出现「BLE 设备面板」。
失败判定：`INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不同，先卸载旧包）；或构建失败（见 `docs/troubleshooting.md`）。

### 0.3 后端准备（产品 / 市场 / 同步）

二选一：

1. 本机 Go 服务（推荐）：
   ```bash
   ./scripts/e2e-contract.sh   # 会构建并启动 STORE=fixture 的 PJGoIoT
   ```
   模拟器使用默认 `http://10.0.2.2:8080/`；真机需要把 BASE_URL 改成电脑局域网 IP：
   ```bash
   ./gradlew assembleDebug -PapiBaseUrl=http://192.168.1.10:8080/
   ```
2. 已有 HTTPS 测试环境：`-PapiBaseUrl=https://your-domain/`。

> Debug 构建已允许明文 HTTP（`app/src/debug/AndroidManifest.xml`）；Release 构建不允许明文，必须 HTTPS。

### 0.4 记录基线

```bash
adb shell dumpsys package com.platojobs.pkotlinapp | grep -E "versionName|versionCode"
```

预期：`versionName=0.3.5`、`versionCode=8`。

---

## 1. 五模块逐页走查

底部导航固定五项：**首页 / 设备 / 产品 / 市场 / 我的**。每页都要检查：能进入、不崩溃、无空白页、无假数据。

### A1 首页 Home

- 步骤：冷启动 App，停留在「首页」。
- 预期：
  - 顶部状态文案为「未在扫描」或「正在扫描附近 BLE 设备…」；
  - 未授权时显示权限提示，不崩溃；
  - 无扫描结果时显示空状态「还没有发现设备」，带「开始扫描」按钮；
  - 「最近设备」为空时显示「暂无历史设备，扫描后会自动保存」。
- 失败判定：白屏、崩溃、显示写死的设备列表、空状态按钮点了无反应。

### A2 设备 Devices

- 步骤：切到「设备」。
- 预期：
  - 显示「共 N 台设备」，N 与首页扫描后保存的数量一致；
  - 每项显示名称 / MAC / RSSI 信号格 / 上次出现时间 / 趋势；
  - 点击设备进入详情；
  - 长按或删除入口弹出「移除设备」确认框，确认后列表减少；
  - 「本地规则」「最近触发」两个区块有真实空状态。
- 失败判定：数量与列表不一致、删除无确认、空状态缺失、点击无反应。

### A3 产品 Products

- 步骤：切到「产品」；先确保后端可用。
- 预期：
  - 展示型号库（来自 `GET /device-types` + `GET /device-types/{type}/catalog`）：厂商、型号、最新固件版本、GATT 服务；
  - GATT 服务行由代码动态组装（不是静态占位）；catalog 无特征时不出现空的「特征：」行；
  - 后端不可用时显示「加载失败：…」+「重试加载型号库」。
- 失败判定：展示固定假数据、错误时白屏、重试按钮无效。

### A4 市场 Market

- 步骤：切到「市场」。
- 预期：
  - 展示各设备类型的配件（名称、SKU、适用型号、描述）；
  - 页面有「仅展示配件与固件信息，不含交易能力」说明；
  - 无购物车、订单、支付入口；
  - 后端不可用时显示错误空状态 +「重试加载市场数据」。
- 失败判定：出现交易类假功能、错误时崩溃、展示硬编码占位数据。

### A5 我的 Profile

- 步骤：切到「我的」。
- 预期：
  - 同步卡片显示上次同步时间、待上传条数、最近冲突数；
  - 权限自检三项（扫描 / 连接 / 定位）与系统授权状态一致；
  - 扫描日志有真实记录，可清空；
  - 主题切换立即生效并持久化（杀进程重开仍生效）；
  - 版本信息显示「版本 0.3.5」「构建类型：debug」；
  - 项目仓库链接可打开 GitHub。
- 失败判定：同步卡片空白、权限状态与实际不一致、版本号不是 0.3.5、主题重启后丢失。

---

## 2. BLE 扫描 / RSSI / 连接 / 断开规则

### B1 扫描启动与停止

- 步骤：首页点「开始扫描」。
- 预期：
  - 状态变为「正在扫描附近 BLE 设备…」；
  - 列表出现周围 BLE 设备（MAC + RSSI）；
  - 再点「停止扫描」立即停止，状态回到「未在扫描」。
- 失败判定：权限已授予但列表一直为空（确认周围确有 BLE 设备）、按钮文案不切换、停止后仍持续刷新。

### B2 离开页面自动停止

- 步骤：扫描中切到「设备」或其他模块，再回到首页。
- 预期：离开后扫描停止（日志不再增加），回到首页后按权限自动恢复扫描。
- 失败判定：后台一直扫描耗电、回首页后不恢复。

### B3 RSSI 实时性

- 步骤：扫描列表观察某一设备 RSSI；进入详情后用手遮住设备或拉远。
- 预期：
  - 列表 RSSI 随距离变化（例如 -45 dBm → -80 dBm）；
  - 详情页连接后每 2 秒读取一次远端 RSSI（`readRemoteRssi`）；
  - 自绘信号格数随 RSSI 变化（强 / 良好 / 一般 / 较弱）。
- 失败判定：RSSI 永远不变、详情页 RSSI 为 `-- dBm` 且一直不刷新。

### B4 连接与手动断开

- 步骤：首页或设备页点击设备 → 进入详情。
- 预期：
  - 状态依次「正在连接…」→「已连接」；
  - 已连接后显示设备信息（后端可用时）与 RSSI；
  - 点「断开连接」后状态变为「未连接」，RSSI 清空；
  - **不会自动重连**（等待 30 秒仍是「未连接」）。
- 失败判定：一直停在「正在连接…」、断开后自动重连、详情页崩溃。

### B5 断开规则（DISCONNECTED）

- 步骤：设备页「新增规则」→ 类型「设备断开」→ 保存并启用；连接设备后手动断开。
- 预期：「最近触发」新增一条断开事件；60 秒内同一设备重复断开不会刷屏。
- 失败判定：无事件、同一分钟大量重复事件。

### B6 低 RSSI 规则（LOW_RSSI）

- 步骤：新增规则，阈值填 `-40`，启用；首页扫描，把设备移远到 RSSI < -40。
- 预期：「最近触发」出现「RSSI 低于阈值」事件；阈值输入非法（如 `-120`、`abc`）时保存被拦截并提示。
- 失败判定：事件不产生、非法阈值能保存。

### B7 绑定丢失规则（BOND_LOST）

- 步骤：新增「绑定信息丢失」规则并启用；按第 3 节 G1 触发 `ACTION_KEY_MISSING`。
- 预期：除详情页提示外，「最近触发」记录一条绑定丢失事件。
- 失败判定：广播收到但无规则事件（或广播未投递，按 G1 记为 BLOCKED）。

---

## 3. Android 16 两个广播

> 前提：Android 16（API 36）真机 + 可配对 BLE 外设。
> 这两个广播都是 `@FlaggedApi` 的受保护系统广播，实际投递取决于系统 flag；**未投递时记 BLOCKED，不要记 PASS**。

### G1 `ACTION_KEY_MISSING`（绑定信息丢失）

- 步骤：
  1. 系统蓝牙设置中完成设备配对；
  2. App 内连接该设备并停留在详情页；
  3. 在系统蓝牙设置中「取消配对 / 忘记设备」（保持连接或触发密钥失效）；
  4. 回到 App 观察详情页。
- 预期：
  - 详情页出现「设备绑定信息丢失，请重新配对」提示；
  - 连接状态进入「绑定丢失」，RSSI 清空；
  - 自动重连被禁止（点击连接无效，必须有「已重新配对，重试连接」）；
  - 重新在系统设置配对后，点「已重新配对，重试连接」可再次连接。
- 失败判定：收到广播但未禁止重连、重新配对后无法恢复、App 崩溃。
- 无法投递时：记录 BLOCKED，并注明机型、系统版本、是否开启相关 flag。

### G2 `ACTION_ENCRYPTION_CHANGE`（加密状态变化）

- 步骤：连接一个支持加密的已配对设备，观察详情页「Android 16 安全事件」区块。
- 预期：
  - 初始显示「尚未收到加密状态广播」；
  - 设备建立加密链路后显示「已加密」+「最近一次加密广播：…」；
  - 断开/取消加密后显示「未加密」。
- 失败判定：收到广播但文案不变、时间戳不更新。

---

## 4. 权限拒绝与受限路径

### P1 首次启动拒绝扫描权限

- 步骤：首次启动，点「开始扫描」，在系统弹窗点「拒绝」。
- 预期：
  - 状态栏显示「缺少蓝牙或附近设备权限」；
  - 底部 Snackbar 出现，并带「打开应用设置」按钮；
  - 点按钮直达本应用的系统设置页。
- 失败判定：只有一句 Toast、无设置入口、点击设置无反应。

### P2 从设置恢复权限

- 步骤：在 P1 的设置页手动开启「附近的设备 / 蓝牙」权限，返回 App，再点「开始扫描」。
- 预期：扫描直接开始，不再弹权限框。
- 失败判定：返回后仍提示缺权限、必须杀进程才恢复。

### P3 拒绝连接权限后点击设备

- 步骤：只授予扫描权限、拒绝连接权限；进入「设备」页点击已保存设备。
- 预期：Snackbar「缺少蓝牙连接权限，请在系统设置中授权后重试」+「打开应用设置」。
- 失败判定：直接崩溃、无提示、无设置入口。

### P4 「不再询问」后拒绝

- 步骤：连续拒绝两次（或选择「不再询问」），再点扫描。
- 预期：不再弹系统框，仍显示缺权限提示与设置入口。
- 失败判定：系统框无限弹出、无提示。

### P5 Android 11 及以下的定位权限

- 步骤：Android 11（API 30）设备，拒绝定位权限后扫描。
- 预期：「我的 → 权限自检」显示定位权限「未授权」；扫描提示缺权限。
- 失败判定：低版本不申请定位权限、权限自检状态错误。

### P6 蓝牙关闭 / 不支持 BLE

- 步骤：关闭系统蓝牙后点扫描；或在无 BLE 的设备上安装。
- 预期：提示「蓝牙未开启，请先开启蓝牙」或「当前设备不支持 BLE」，不崩溃。
- 失败判定：崩溃、无提示、一直转圈。

---

## 5. 横屏与大屏

### R1 横屏走查

- 步骤：开启自动旋转，依次旋转五个模块与设备详情。
- 预期：页面重排正常；列表可滚动；无控件被状态栏/导航栏遮挡；无崩溃；输入框（新增规则）不被键盘完全遮挡。
- 失败判定：内容被裁切、白屏、Activity 重建崩溃。

### R2 分屏 / 折叠屏 / 平板

- 步骤：进入分屏或折叠屏展开态；或使用平板。
- 预期：布局随窗口尺寸变化，列表宽度自适应；底部导航仍可用；无固定高度导致的重叠。
- 失败判定：底部导航被挤出屏幕、内容重叠、必须退出分屏才能操作。

### R3 Edge-to-edge

- 步骤：检查首页顶部与底部导航。
- 预期：内容从状态栏下方开始，底部导航不被手势条覆盖。
- 失败判定：文字被状态栏压住、底部按钮点不到。

---

## 6. 断网与同步降级

### N1 网络页面降级

- 步骤：开启飞行模式，进入「产品」「市场」，再进入任一设备详情。
- 预期：
  - 产品 / 市场显示加载失败 + 重试按钮；
  - 详情页设备信息显示失败提示 +「重试读取设备信息」；
  - 首页 / 设备 / 我的 的本地数据仍正常展示。
- 失败判定：崩溃、白屏、展示假数据。

### N2 同步失败与纯本地模式

- 步骤：断网状态下，在「我的」连续点 3 次「立即同步」。
- 预期：
  - 每次提示「同步失败：网络不可用，请检查网络后重试」；
  - 第 3 次后出现「已连续失败，切换为纯本地模式…」，主界面顶部出现「网络同步不可用…」横幅；
  - 同步卡片显示「最近错误：…」；
  - 本地设备与日志仍可读写。
- 失败判定：无分类文案（直接暴露异常类名）、横幅不出现、断网后本地功能不可用。

### N3 恢复网络后自动重试

- 步骤：保持 App 在前台，关闭飞行模式，等待后台任务重试（指数退避 30 秒起）。
- 预期：同步成功后横幅消失；「上次同步」更新；待上传数下降。
- 失败判定：恢复网络后永远不再同步、必须重装应用。

### N4 登录失效（401）与重新登录

- 步骤：
  1. 后端可用时先完成一次成功同步；
  2. 让服务端 token 失效（重启 `STORE=fixture` 的 Go 服务即可，fixture 数据在内存中）；
  3. 在「我的」点「立即同步」。
- 预期：
  - 同步卡片显示「登录已失效，同步已暂停；本地数据与同步游标仍保留」；
  - 「立即同步」按钮消失，出现「重新登录」按钮；
  - **不会自动换账号重试**；本地设备与日志不受影响；
  - 点「重新登录」弹出确认框，明确提示会以新匿名账号同步、旧账号数据不会被删除或覆盖；
  - 确认后同步成功，同步卡片恢复正常，本机设备被推送到新账号。
- 失败判定：401 后自动重新匿名登录、游标被清空且无提示、本地数据丢失、确认框文案缺失。

---

## 7. Room v1 → v2 → v3 → v4 迁移

> 需要 v0.3.1 的 Debug APK 作为「旧版本」。若手头没有，可从 tag 构建：
>
> ```bash
> git worktree add /tmp/pkotlin-v031 v0.3.1
> cd /tmp/pkotlin-v031 && ./gradlew assembleDebug
> ```

### M1 旧数据准备

- 步骤：安装 v0.3.1 Debug 包，扫描并保存至少 1 台设备；新增 1 条规则；产生 1 条扫描日志。
- 预期：设备 / 规则 / 日志均能在界面上看到。
- 失败判定：旧版本本身无法产生数据（先排除环境问题）。

### M2 覆盖安装 v0.3.5

- 步骤：
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
  启动 App，进入「设备」与「我的」。
- 预期：
  - 安装成功，无需卸载；
  - 旧设备、规则、扫描日志全部保留；
  - 设备数量与 v0.3.1 一致；
  - 无 `IllegalStateException: Room cannot verify the data integrity`。
- 失败判定：要求卸载重装、旧数据丢失、启动即崩溃。

### M3 校验数据库版本与回填字段

- 步骤（Debug 包可 `run-as`）：
  ```bash
  adb exec-out run-as com.platojobs.pkotlinapp cat databases/device_dashboard.db > /tmp/device_dashboard.db
  sqlite3 /tmp/device_dashboard.db "PRAGMA user_version;"
  sqlite3 /tmp/device_dashboard.db \
    "SELECT mac, platform, externalId, syncState, substr(id,1,8), deviceType, deviceTypeName, deviceGroup FROM devices;"
  ```
- 预期：`user_version = 4`；每条设备 `platform = android`、`externalId` 为规范化大写 MAC、`id` 非空 UUID、`syncState` 为 `pending`/`synced`；旧数据的 `deviceTypeName` 与 `deviceGroup` 为 NULL（列存在且历史行不丢）。
- 失败判定：版本不是 4、旧字段为空、`externalId` 为小写或带空格、行数比升级前少。

### M4 v3 → v4 迁移不丢数据（可选复验）

- 步骤：
  1. 安装 v0.3.4 Debug 包（Room v3），联网打开一次设备详情，让 enrich 字段落库；
  2. 覆盖安装 v0.3.5，再导出数据库并查询：
     ```bash
     sqlite3 /tmp/device_dashboard.db "PRAGMA user_version;"
     sqlite3 /tmp/device_dashboard.db \
       "SELECT mac, deviceType, vendor, firmwareVersion, compatibility FROM devices;"
     ```
- 预期：`user_version = 4`；设备行数与 v0.3.4 一致，原有 `deviceType/vendor/firmwareVersion/compatibility` 值逐列保留，`deviceTypeName/deviceGroup` 为 NULL。
- 失败判定：行数减少、旧 enrich 值变 NULL、启动时 `Room cannot verify the data integrity`。

### 7.2 离线缓存与分组验收（v0.3.5 新增）

前置：后端可用、目标 BLE 设备已在「设备」列表；准备一台**已注册型号**和一台**未注册型号**（后者可人为把 `deviceType` 写成不存在的键）。

- 步骤：
  1. 联网打开已注册型号的设备详情，确认厂商/型号/固件/兼容性正常；退出详情；
  2. 关闭手机网络（或停止 Go 服务），从设备列表再次进入同一设备；
  3. 观察详情页型号、厂商、分组、最后出现时间与顶部提示；
  4. 点「编辑别名与分组」，把分组改为「客厅」，保存；返回设备列表查看该行分组与顶部统计；
  5. 恢复网络并执行一次同步，检查后端 `GET /api/v1/my/devices/android/{externalId}` 的 `group`。
- 预期：
  - 离线详情显示「网络不可用，以下为本地缓存数据」；
  - 型号显示 catalog 缓存中文名（如「Nordic UART 温湿度计」），而不是 `nordic_uart`；
  - 未注册型号离线显示 `unknown_type（未识别的型号）`；若连型号键都没有则显示「型号未知」；
  - 分组显示真实值（如「客厅」），不再是「未分组」；列表行显示「分组：客厅」，标题统计「共 N 台设备 · M 个分组」；
  - 断网保存后立即在详情与列表生效，重开 App 仍在；
  - 同步成功后服务端 `group = 客厅`。
- 失败判定：离线仍显示型号键且无「未识别的型号」标注、分组一律「未分组」、保存后杀进程丢失、扫描一次后缓存展示名/分组被清空。

### 7.3 市场筛选 Chip 无障碍与大字号验收

- 前置：进入「市场」，等待厂商/兼容性筛选 Chip 渲染完成。
- 步骤与预期：
  1. **读屏语义**：开启 TalkBack，逐个聚焦 Chip；应听到「<筛选值>，已选中/未选中」，聚焦 ChipGroup 时先听到「厂商筛选」或「兼容性筛选」；不得只读「全部」而丢失筛选维度。
  2. **选中态可读**：点击任一 Chip，读屏应立即播报新的选中态，视觉上有明确的选中底色/描边，不能只靠颜色深浅区分。
  3. **200% 字号**：系统设置 → 显示 → 字体大小调到最大（或开发者选项 font_scale=2.0），回到市场；所有 Chip 文本应最多两行完整显示，无 `…` 截断、无上下裁切；超出屏宽时可横向滚动，不遮挡相邻 Chip。
  4. **颜色对比度**：用 Accessibility Scanner 扫描市场页，或在浅色/深色主题下目测 Chip 文字与背景；正文对比度需 ≥ 4.5:1，选中态不得仅用低对比色差表达。
  5. **触摸目标**：Chip 可点区域不小于 48dp（Material Chip 默认 `ensureMinTouchTargetSize`）。
- 失败判定：TalkBack 不播报选中态、Chip 文本被省略号截断、200% 字号下文字裁切、扫描器报告对比度不足、点击区域明显小于 48dp。
- 记录：本次仅完成代码侧改造与静态核对；真机 TalkBack / 200% 字号 / Accessibility Scanner 需按上述步骤执行后填 PASS。

---

## 8. Release 包安装与 R8 生效验证

### L1 构建

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew assembleRelease bundleRelease
```

预期：
- `app/build/outputs/apk/release/app-release.apk` 存在；
- `app/build/outputs/bundle/release/app-release.aab` 存在；
- `app/build/outputs/mapping/release/mapping.txt` 存在；
- Release APK 明显小于 Debug APK（资源收缩 + R8）。

失败判定：构建失败；有 `keystore.properties` 却产物未签名（见 troubleshooting）。

### L2 安装与启动

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell dumpsys package com.platojobs.pkotlinapp | grep -E "versionName|versionCode"
```

预期：安装成功；版本 `0.3.5 / 8`；能启动；五模块可进入；BLE 扫描/连接可用。
失败判定：`INSTALL_FAILED_NO_MATCHING_ABIS`、安装后闪退（多为 R8 裁剪过度或签名问题）。

### L3 签名验证

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

路径示例：`/Users/<user>/Library/Android/sdk/build-tools/36.0.0/apksigner`。

预期：输出 `Verifies` 与证书指纹，且 `CN` 为你的发布证书。
失败判定：`DOES NOT VERIFY`、`Missing APK Signature Scheme v2`、证书为 Android Debug。

### L4 R8 生效验证

- 步骤：
  ```bash
  grep -E "com.platojobs.pkotlinapp.ui.home.HomeViewModel ->|com.platojobs.pkotlinapp.ui.devices.DevicesViewModel ->" \
    app/build/outputs/mapping/release/mapping.txt
  ```
- 预期：能看到类似 `com.platojobs.pkotlinapp.ui.home.HomeViewModel -> a5.w:` 的重命名映射。
  说明：`data.remote.**`、`data.local.**`、`domain.sync.**` 以及 Manifest 中声明的组件被 ProGuard 规则显式保留，**名字不变是预期行为**；用 ViewModel 验证混淆更准确。
- 辅助检查：
  ```bash
  unzip -l app/build/outputs/apk/release/app-release.apk | grep -E "classes|resources.arsc"
  ```
  预期只有 `classes.dex` 等少量条目，且 `res/` 被资源收缩。
- 业务验证：在 Release 包中实际完成「匿名登录 → 立即同步 → 产品/市场加载」，证明 Retrofit / Moshi / Room 未被 R8 裁掉。
- 失败判定：`mapping.txt` 为空、ViewModel 仍为完整类名（说明未混淆）、Release 网络请求报 `ClassNotFoundException` / `JsonDataException`、登录或同步崩溃。

> Release 构建不允许明文 HTTP。用本地 Go 服务验收 Release 网络功能时，请把 BASE_URL 指向 HTTPS 测试环境；否则只能验收本地功能，并记为 BLOCKED。

---

## 9. 验收记录表

复制下表填写。`PASS` 必须有实际观察结果；未执行填 `NOT RUN`，环境不满足填 `BLOCKED`。

| ID | 项目 | 结果（PASS / FAIL / BLOCKED / NOT RUN） | 证据（截图/日志/sqlite 输出） | 备注 |
| --- | --- | --- | --- | --- |
| A1 | 首页 |  |  |  |
| A2 | 设备 |  |  |  |
| A3 | 产品 |  |  |  |
| A4 | 市场 |  |  |  |
| A5 | 我的 |  |  |  |
| B1 | 扫描启停 |  |  |  |
| B2 | 离开页面停止 |  |  |  |
| B3 | RSSI 实时 |  |  |  |
| B4 | 连接/断开 |  |  |  |
| B5 | 断开规则 |  |  |  |
| B6 | 低 RSSI 规则 |  |  |  |
| B7 | 绑定丢失规则 |  |  |  |
| G1 | KEY_MISSING |  |  |  |
| G2 | ENCRYPTION_CHANGE |  |  |  |
| P1 | 拒绝扫描权限 |  |  |  |
| P2 | 设置恢复权限 |  |  |  |
| P3 | 拒绝连接权限 |  |  |  |
| P4 | 不再询问 |  |  |  |
| P5 | 低版本定位 |  |  |  |
| P6 | 蓝牙关闭/不支持 |  |  |  |
| R1 | 横屏 |  |  |  |
| R2 | 分屏/大屏 |  |  |  |
| R3 | Edge-to-edge |  |  |  |
| N1 | 断网降级 |  |  |  |
| N2 | 纯本地模式 |  |  |  |
| N3 | 恢复网络重试 |  |  |  |
| N4 | 401 重新登录 |  |  |  |
| M1 | 旧数据准备 |  |  |  |
| M2 | 覆盖安装 |  |  |  |
| M3 | 数据库校验 |  |  |  |
| L1 | Release 构建 |  |  |  |
| L2 | Release 安装 |  |  |  |
| L3 | 签名验证 |  |  |  |
| L4 | R8 生效 |  |  |  |

## 10. 已知无法在本仓库内自证的部分

以下结论必须来自真机/真实账号，代码仓库与 CI 只能证明「已实现、能构建、单测通过」：

- 真实 BLE 扫描、RSSI 变化、GATT 连接与断开时序；
- Android 16 两个受保护广播是否实际投递；
- 各厂商 ROM 的权限弹窗行为与设置页跳转；
- Room v1→v2→v3 在真实旧数据上的迁移（本仓库已导出 schema，但未跑真机迁移）；
- WorkManager 在真实网络切换下的调度与退避；
- 生产后端域名、真实账号体系与跨设备同步。
