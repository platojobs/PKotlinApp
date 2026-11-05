# 真机验收清单（v0.7.0）

本清单用于「执行人不在开发现场」时也能照着做完验收。每项都写明**操作步骤、预期现象、失败判定**。
所有结果请记录到文末的「验收记录表」；未实际执行的项不要填 PASS。

- 适用版本：`versionName = 0.7.0`（`versionCode = 15`）
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

预期：`versionName=0.6.0`、`versionCode=14`。

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
  - 版本信息显示「版本 0.6.0」「构建类型：debug」；
  - 项目仓库链接可打开 GitHub。
- 失败判定：同步卡片空白、权限状态与实际不一致、版本号不是 0.6.0、主题重启后丢失。

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

## 7. Room v1 → v2 → v3 → v4 → v5 → v6 → v7 → v8 迁移

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

### M2 覆盖安装 v0.6.0

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
    "SELECT mac, platform, externalId, syncState, substr(id,1,8), deviceType, deviceTypeName, deviceGroup, advertisedName, aliasDirty, createdAt FROM devices;"
  ```
- 预期：`user_version = 8`；每条设备 `platform = android`、`externalId` 为规范化大写 MAC、`id` 非空 UUID、`syncState` 为 `pending`/`synced`；旧数据的 `deviceTypeName`、`deviceGroup`、`advertisedName` 为 NULL，`aliasDirty = 0`，`createdAt` 已用 `lastSeenTimestamp` 回填（不为 0）；`readings` 表存在且为空或仅含本次会话读数。
- 失败判定：版本不是 8、旧字段为空、`externalId` 为小写或带空格、行数比升级前少、`aliasDirty` 或 `createdAt` 列缺失、`readings` 表或索引缺失。

### M4 v5 → v6 迁移不丢数据（可选复验）

- 步骤：
  1. 安装 v0.3.6 Debug 包（Room v5），扫描一台设备并编辑别名/分组，让 `name/deviceGroup/advertisedName` 落库；
  2. 覆盖安装 v0.6.0，再导出数据库并查询：
     ```bash
     sqlite3 /tmp/device_dashboard.db "PRAGMA user_version;"
     sqlite3 /tmp/device_dashboard.db \
       "SELECT mac, name, deviceType, vendor, firmwareVersion, compatibility, deviceTypeName, deviceGroup, advertisedName, aliasDirty, createdAt FROM devices;"
     ```
- 预期：`user_version = 8`；设备行数与 v0.3.6 一致，原有字段逐列保留，新增 `aliasDirty = 0`、`createdAt` 已回填；`readings` 表已创建。
- 失败判定：行数减少、旧字段变 NULL、启动时 `Room cannot verify the data integrity`。

### M5 v6 → v7 迁移不丢数据（可选复验）

- 步骤：
  1. 安装 v0.3.7 Debug 包（Room v6），扫描一台设备并编辑别名/分组；
  2. 覆盖安装 v0.6.0，导出数据库查询：
     ```bash
     sqlite3 /tmp/device_dashboard.db "PRAGMA user_version;"
     sqlite3 /tmp/device_dashboard.db "SELECT COUNT(*) FROM devices;"
     sqlite3 /tmp/device_dashboard.db "SELECT COUNT(*) FROM readings;"
     ```
  3. 连接设备产生读数，进入设备详情「数据」区。
- 预期：`user_version = 8`；设备行数与升级前一致，`createdAt` 已回填；升级瞬间 `readings` 为空（旧版本没有该表），连接后出现真实读数；详情页不崩溃。
- 失败判定：启动崩溃、设备丢失、`readings` 表缺失、详情页图表崩溃。

### M6 v7 → v8 迁移与添加时间排序（v0.6.0 新增）

- 步骤：
  1. 安装 v0.5.0 Debug 包（Room v7），扫描至少 2 台设备并记录各自「最近出现」时间；
  2. 覆盖安装 v0.6.0，导出数据库并查询：
     ```bash
     sqlite3 /tmp/device_dashboard.db "PRAGMA user_version;"
     sqlite3 /tmp/device_dashboard.db "SELECT mac, lastSeenTimestamp, createdAt FROM devices;"
     ```
- 预期：`user_version = 8`；每行 `createdAt >= 0` 且历史设备 `createdAt` 等于 `lastSeenTimestamp`；设备列表选择「添加时间」时顺序按 `createdAt` 倒序，切换其它排序后重启仍保持所选排序。
- 失败判定：`createdAt` 缺失、全部为 0、升级崩溃。

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

### 7.4 别名 / 广播名与分组三态验收（v0.3.6 新增）

前置：后端为 PJGoIoT v0.3.3（fixture 或真实存储）；准备一台可广播名称的 BLE 设备。

- 步骤与预期：
  1. **扫描只写广播名**：全新安装后扫描设备并保存；列表/详情应显示广播名（如 `PJ-Sensor`），Room 中 `name`（用户别名）为空、`advertisedName` 有值。
  2. **别名不覆盖广播名**：详情页「编辑别名与分组」把别名改为「客厅开发板」；列表/详情优先显示「客厅开发板」；Room 中 `advertisedName` 仍保留原广播名。
  3. **再次扫描不覆盖别名**：返回首页重新扫描同一设备；别名仍为「客厅开发板」，广播名只在设备真实改名时变化。
  4. **未修改分组不上报**：仅编辑别名并保存（分组不动），用抓包或后端日志确认 push JSON **没有** `"group"` 字段；服务端分组保持原值。
  5. **删除分组显式清除**：把分组清空并保存，确认 push JSON 含 `"group":""`；后端 `GET /api/v1/my/devices/android/{externalId}` 的 `group` 为 `""`。
  6. **清空后不复活**：连续同步或再次扫描后，后端 `group` 仍为 `""`，不会变回旧分组。
  7. **冲突缺省字段**：用旧 `updatedAt` 触发 LWW 冲突，冲突响应中未上报的 `group/advertisedName` 解析为 absent，本地不会被清空。
- 失败判定：扫描把用户别名改回广播名、编辑别名后广播名丢失、删除分组后服务端仍保留旧分组、未修改分组却发送空串、同内容重试出现 409。

### 7.5 清空别名验收（v0.3.7 新增）

前置：后端为 PJGoIoT v0.3.3；设备已有用户别名（如「客厅开发板」）且已同步。

- 步骤与预期：
  1. **清空别名生效**：详情页「编辑别名与分组」把别名输入框清空并保存；界面名称应立即回退为广播名（如 `PJ-Sensor`），Room 中 `name = ''`、`aliasDirty = 1`。
  2. **推送显式空串**：执行「立即同步」，抓包或后端日志确认 push JSON 含 `"alias":""`（不是省略）；`GET /api/v1/my/devices/android/{externalId}` 的 `alias` 为 `""`。
  3. **扫描不复活**：返回首页重新扫描同一设备，再同步；服务端 `alias` 仍为 `""`，广播名只按真实广播变化，旧别名不会回来。
  4. **拉回不复活**：清空后等待一次增量拉取或重启 App，详情/列表仍显示广播名，Room `name` 仍为空。
  5. **未编辑别名不误清空**：只修改分组并保存，push JSON 不应出现 `"alias"` 字段（若本地别名非空则以实际值上报，不发送空串）。
  6. **分组同样不复活**：清空分组 → 同步 → 再次扫描/同步，服务端 `group` 持续为 `""`（与 §7.4 第 5/6 步同口径）。
- 失败判定：清空保存后仍显示旧别名、push 中 `"alias"` 被省略导致服务端保留旧值、扫描或拉取后旧别名复活、仅改分组却把别名清空。

### 7.6 设备读数（readings）验收（v0.4.0 新增）

前置：真机 + 至少一台支持以下任一 notify 特征的 BLE 设备（心率 `0x2A37`、电池 `0x2A19`、温度 `0x2A6E`、NUS TX `6E400003-…`）；后端为 PJGoIoT v0.4.0（含 readings 端点）；应用已授予「附近的设备」权限。

- 步骤与预期：
  1. **订阅与采集**：进入设备详情并连接；用 `adb logcat -s BleReadings` 观察，设备真实推送 notify 后应出现解析成功路径的读数，详情「数据」区出现对应指标 Chip；无数据时显示「连接设备后开始采集」，不得出现演示数据。
  2. **解析正确性**：心率带读数与设备屏幕/官方 App 对比误差 ≤1 bpm；电池百分比与系统蓝牙设置中的电量一致；温度计读数与环境温度量级一致；NUS 设备按文本 `metric=value` 解析出正确指标。若无对应特征，该指标 Chip 不应凭空出现。
  3. **非法报文不写脏数据**：用 nRF Connect 发送非数字/超长 NUS 文本或长度不足的报文；`logcat` 出现 `discard invalid reading`，详情页无新增异常值，App 不崩溃、连接不断开。
  4. **本地保留与 pending 保护**：联网同步后把系统时间调快 8 天重开 App，已同步且超过 7 天的读数应被清理、最近 7 天保留；断网产生读数（`syncState=pending`）后同样把时间调快 8 天重开，`sqlite3 ... "SELECT COUNT(*) FROM readings WHERE syncState='pending';"` 必须保持不变，pending 不得被清理。
  5. **同步与幂等**：联网执行「立即同步」，服务端 `GET /api/v1/my/devices/android/{externalId}/readings?limit=100` 返回倒序列表且 id 与本地一致；重复同步同一批不报 409，服务端 `skippedReadings` 增加、列表无重复。
  6. **断网补传**：断网连接设备产生读数（`readings` 表 `syncState=pending`）；恢复网络后同步，`pending` 清零且服务端出现对应读数。
  7. **汇总与离线标注**：`GET .../readings/summary?metric=&hours=24` 的 min/max/avg/last 与详情页汇总行一致；停掉网络/后端后重进详情，仍显示本地图表与最近记录，并出现「离线：本地数据」。
  8. **图表边界**：0 条读数（新设备）显示空状态不崩溃；1 条读数时只画一个桶的区间带与数据点；长周期分桶后折线、坐标轴、首尾时间正常，无越界或闪烁（区间与缺口见 §7.7）。
  9. **权限空状态**：在系统设置撤销「附近的设备」权限后重进详情，「数据」区显示权限提示与「去应用设置」按钮，点击可跳到本应用设置页；恢复权限后读数恢复展示。
- 失败判定：notify 已到达但无读数、读数与真实值明显不符、非法报文导致崩溃或写入脏数据、本地超过 7 天仍保留、重复同步报错/重复插入、断网读数丢失、离线仍显示远端数据、0/1/60 点图表崩溃、无权限时展示假数据或按钮无响应。
- 说明：模拟器无法接收真实 BLE notify，本组必须真机执行；解析器的合法/非法/超长/非数字路径已由 `GattReadingParserTest` 覆盖，同步与查询契约由 e2e 覆盖。

### 7.7 长周期图表与区间验收（v0.5.0 新增）

前置：后端为 PJGoIoT **v0.5.0**（含 `/readings/series` 与 `/readings/metrics`）；设备已产生跨小时/跨天的真实读数。

- 步骤与预期：
  1. **区间切换**：进入详情「数据」区，区间 Chip 为「1 小时 / 24 小时 / 7 天 / 30 天」，默认选中 24 小时；逐一切换，图表立即重新请求并重绘，`logcat` 可见 `metric=…&bucket=1m/1h/1d`（1h→1m、24h→1h、7d→1h、30d→1d）。
  2. **指标真值**：指标 Chip 文案为「指标名 · N 条」，顺序与 `GET .../readings/metrics` 的 `lastAt` 倒序一致；切换指标后 Y 轴单位与汇总行单位同步变化。
  3. **时间定位与缺口**：采集一段时间后让设备停止推送若干小时（制造空桶），再恢复；图表在缺口处折线断开，不得跨越空桶连线；X 轴位置与真实时间成比例，不能等距排列。
  4. **波动区间**：每个桶内多次读数时，可见 avg 折线外的浅色 min–max 区间带；Y 轴上下界随所选区间自适应。
  5. **固定 24h 汇总**：切换 1h/7d/30d 时，汇总行文案仍为「最近 24 小时汇总」，数值不随区间变化；单位与当前指标一致。
  6. **离线降级**：停掉后端后重进详情并切换区间，图表回退本地数据并显示「离线：本地数据」；选择 30 天时显示「离线：仅本地 7 天数据」，数据集中在时间轴右侧（本地仅保留 7 天）。
  7. **空区间**：选择没有数据的指标/区间，显示「该区间暂无数据」，不出现假折线、不崩溃。
  8. **无指标**：新设备无任何读数时显示「连接设备后开始采集」，不发起 series 请求。
- 失败判定：切换区间不重新查询、bucket 映射错误、缺口仍连线、X 轴等距、Y 轴不随数据变化、单位不更新、30 天离线提示缺失、空区间显示假数据或崩溃。
- 说明：图表几何（空数据/单桶/等距/缺口/30 天 24 桶/异常值/Y 轴）由 `ReadingChartGeometryTest`、`ReadingRangeTest`、`ReadingSeriesBucketingTest` 覆盖；series 与 metrics 契约由 e2e 覆盖。

### 7.8 告警通知验收（v0.6.0 新增）

前置：Android 13+ 真机；已添加低 RSSI 或断开规则；准备能制造信号衰减/断开的外设。

- 步骤与预期：
  1. **首次授权**：首次新增规则或打开「我的」告警开关时弹出系统通知权限；允许后 `adb shell dumpsys notification --noredact | grep device_alerts` 能看到渠道「设备告警」。
  2. **告警投递**：让设备信号低于阈值（或断开），下拉通知栏出现「设备告警」，正文含设备名、信号强度当前值、阈值与触发时间（HH:mm）；点击通知直接进入该设备详情。
  3. **节流**：在「我的」选择 1 分钟窗口，连续制造多次同规则命中；1 分钟内只出现一条通知；切换 5/30 分钟重复验证（规则事件仍持续记录，通知不刷屏）。
  4. **恢复**：让信号回到阈值内，出现一条「告警已恢复」；再次恢复不会重复出现；恢复后立即再次触发可再次通知。
  5. **开关关闭**：关闭「接收告警通知」，再次制造命中；通知栏无新增，但设备页规则事件仍增加；重新打开开关后新的命中可正常投递。
  6. **拒绝权限**：Android 13+ 拒绝 `POST_NOTIFICATIONS` 后，「我的」出现「系统通知权限未授予」与「去设置授予通知权限」；点击跳应用设置；扫描、连接、同步、图表均不受影响。
  7. **隐私**：通知正文与展开内容不含 token、账号、安装 ID 等敏感字段。
- 失败判定：无渠道、拒绝权限后崩溃或其它功能被阻断、窗口内重复通知、恢复通知重复/缺失、关闭开关仍投递、点击通知进错设备。

### 7.9 读数 CSV 导出验收（v0.6.0 新增）

前置：后端为 PJGoIoT v0.5.0+；详情页已有指标与读数；准备一台可导出 7 天前后数据的设备。

- 步骤与预期：
  1. **≤7 天本地导出**：详情选择 1 小时/24 小时/7 天与某指标，点「导出 CSV」；进度显示真实条数；系统分享面板出现，选择「保存到文件」后能用 Excel/Numbers 打开。
  2. **>7 天服务端分页**：选择 30 天导出，`logcat` 可见多次 `GET .../readings?limit=100&cursor=...`，直到 `hasMore=false`；进度条数字持续增长。
  3. **文件格式**：用文本编辑器确认首字节为 UTF-8 BOM，表头为 `recordedAt,metric,value,unit`，时间为 `2026-…Z`（UTC）；英文逗号/引号/换行字段被正确加引号转义；无数据指标仍生成仅表头的文件。
  4. **路径与分享**：`adb shell run-as com.platojobs.pkotlinapp ls cache/exports` 能看到文件；分享 URI 为 `content://com.platojobs.pkotlinapp.fileprovider/exports/...`；应用私有目录之外无副本。
  5. **超限**：制造/构造超过 5 万条的区间，导出提示「超过 50000 条，请缩短区间后重试」，不产生超大文件、不卡死。
  6. **失败重试**：停掉后端后导出 30 天，出现明确失败提示与「重试」；恢复后端后点重试成功。
- 失败判定：无进度、静默失败、CSV 乱码/无 BOM、时间非 UTC、超过 5 万条仍继续、文件出现在应用私有目录之外。

### 7.10 深色模式验收（v0.6.0 新增）

前置：至少一台设备有读数与分组；准备好浅色/深色截图对比。

- 步骤与预期：
  1. **三态切换**：「我的」主题为「跟随系统 / 浅色 / 深色」；切换后界面立即重建，状态持久化，重启保持。
  2. **图表**：深色下进入详情「数据」区，折线、数据点、网格、坐标轴、min–max 区间带清晰可辨，不出现白底白线/黑底黑线；切换区间与指标后仍正确。
  3. **控件**：排序/分组/指标/区间 Chip、卡片、次要文字、空状态在深色下对比度足够，无硬编码白色/黑色块。
  4. **回归**：浅色与深色下导出、告警通知按钮、搜索框均可用；无文字不可见。
- 失败判定：存在硬编码颜色（白底白字/黑底黑字）、切换主题不生效、图表元素在深色不可辨。

### 7.11 账号迁移与注销验收（v0.7.0 新增）

前置：PJGoIoT v0.7.0+（openapi 0.6.0）已启动；准备**两台** Android 设备（旧机 A 与新机 B），两台都安装本版本。

- 步骤与预期：
  1. **生成配对码**：A「我的」→「迁移到新设备」→「生成配对码」；出现 8 位大写码，字符只含 `A-H/J-N/P-Z/2-9`（不会出现 `0/O/1/I`）；倒计时从服务端的 `expiresAt` 开始递减（对照服务端时间，与本地时钟无关）。
  2. **复制**：点「复制」，剪贴板内容为 8 位码，粘贴可原样得到。
  3. **重新生成**：点「重新生成」先弹确认并提示「旧码立即失效」；确认后出现新码。用 B 输入**旧码** → 提示「码无效或已过期」。
  4. **接管**：B「我的」→「从旧设备接管」输入新码。输入框只能输入 8 位、自动大写、输入 `0/O/1/I` 时被过滤、不足 8 位时按钮不可用。提交后提示接管成功并展示 userId；A 上原有数据（设备、读数）在 B 上保留且随后续同步上传到同一账号（服务端按该 userId 可查到）。
  5. **错误处理**：输入过期或已用的码 → 只显示「码无效或已过期」，不出现具体原因；1 分钟内连续错误输入触发限流 → 出现「尝试过于频繁，请稍后再试」，若服务端返回 `Retry-After` 则显示剩余秒数；飞行模式下提交 → 出现网络可重试提示。
  6. **导出我的数据**：A/B 任一「我的」→「导出我的数据」；进度显示「已拉 N 条 / 第 M 页」且 M 随分页递增；`adb shell run-as com.platojobs.pkotlinapp ls cache/exports` 出现 `account_export_<userId>_<UTC>.json`；系统分享面板可打开，JSON 内 `user/devices/readings` 齐全、`readings` 按 `recordedAt` 升序且无重复 id。
  7. **导出失败重试**：停后端后导出 → 出现明确失败提示与「重试」；恢复后端后重试成功。
  8. **注销**：确认对话框必须出现「设备与读数将永久删除且不可恢复」；确认后提示注销成功并回到匿名初始状态（「我的」同步卡片回到初始、设备/读数列表清空）。
  9. **注销后校验**：`GET /api/v1/sync/changes`（旧 token）返回 401；`adb shell run-as com.platojobs.pkotlinapp ls databases` 业务表为空（可 `sqlite3` 检查 `readings`/`devices` 行数，或重启后确认无数据）；再次注销（原 token 已失效）返回 401。
- 失败判定：倒计时用本地时间瞎算、旧码仍可接管、接管后本地数据被清空或仍标记 synced（导致数据不重传）、404 暴露具体原因、429 不显示冷却、导出解析/拼接 cursor 导致漏页、注销后本地仍有业务数据或 token、401/网络错误静默。

### 7.12 后台采集（前台服务）验收（v0.7.0 新增）

前置：至少一台可连接并持续 notify 的 BLE 设备；分别在 **Android 12（API 31/32）**、**Android 13（API 33）**、**Android 14+（API 34/35/36）** 各跑一遍（差异见下）。

- 步骤与预期：
  1. **启动**：连接设备成功 → 详情页出现「后台采集」开关，默认关闭；打开后通知栏出现常驻通知，渠道为 **「数据采集」**（`adb shell dumpsys notification --noredact | grep reading_collection`），标题「正在后台采集」，正文含设备名与「已采集 N 条」，N 随 notify 递增。
  2. **通知动作**：点「停止」→ 服务停止、通知消失、开关自动回到关闭；从最近任务划掉 App 后通知不应长期残留（重新打开「我的」显示未采集）。
  3. **点击通知**：点击通知正文 → 进入该设备详情页。
  4. **持久化**：开关打开后返回列表再进详情、或杀进程后重启 App，开关状态保持（「我的」显示当前是否在后台采集中，含设备名与条数）。
  5. **停止条件**：手动关闭开关 / 断开连接 / 返回退出详情 / 设备进入 `KeyMissing`，四类操作后服务与通知均停止；切到另一台设备并连接时，服务切换到新设备而不是同时采集两台。
  6. **Android 14+ 权限**：检查 `adb shell dumpsys package com.platojobs.pkotlinapp | grep FOREGROUND_SERVICE_CONNECTED_DEVICE` 已授予；手动撤销该权限（`adb shell appops`/设置页）后再打开开关 → 不崩溃，开关回滚并提示启动失败。
  7. **Android 13+ 通知权限**：首次打开开关时请求 `POST_NOTIFICATIONS`。拒绝 → 采集仍启动（「我的」状态显示采集中），界面提示「通知不可见」；允许 → 通知正常常驻。
  8. **Android 12 启动限制**：确认只在详情页（界面可见）点击开关时启动服务；从后台/广播路径不会静默启动（无 `ForegroundServiceStartNotAllowedException`，`logcat | grep BackgroundService` 无相关报错）。
  9. **Android 12 及以下通知渠道**：API 26+ 才有渠道，API 24/25（如可测）不因 `NotificationChannel` 崩溃，通知仍可展示。
- 失败判定：进程被杀即丢通知且状态未持久化、断开后服务/通知残留、Android 12+ 后台启动触发系统异常、Android 14+ 缺权限时崩溃、拒绝通知权限导致功能不可用或崩溃。

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

预期：安装成功；版本 `0.7.0 / 15`；能启动；五模块可进入；BLE 扫描/连接可用。
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
| S1 | 真实 notify 采集 |  |  |  |
| S2 | 非法报文丢弃 |  |  |  |
| S3 | 读数同步/离线补传 |  |  |  |
| S4 | 0/1/60 点图表 |  |  |  |
| M6 | v7→v8 添加时间 |  |  |  |
| T1 | 告警通知渠道/投递 |  |  |  |
| T2 | 通知节流与恢复 |  |  |  |
| T3 | 通知权限拒绝提示 |  |  |  |
| T4 | 告警开关关闭不投递 |  |  |  |
| E1 | ≤7 天 CSV 导出/分享 |  |  |  |
| E2 | >7 天分页导出 |  |  |  |
| E3 | CSV BOM/转义/UTC |  |  |  |
| D1 | 列表搜索/排序/分组 |  |  |  |
| D2 | 两种空状态 |  |  |  |
| K1 | 深色图表与控件 |  |  |  |
| Q1 | 配对码生成/倒计时/复制 |  |  |  |
| Q2 | 重新生成使旧码失效 |  |  |  |
| Q3 | 接管保留本地数据并重传 |  |  |  |
| Q4 | 404/429/网络错误文案 |  |  |  |
| Q5 | 账号导出分页与分享 |  |  |  |
| Q6 | 注销清理云端与本机 |  |  |  |
| F1 | 后台采集通知与条数 |  |  |  |
| F2 | 停止条件与开关持久化 |  |  |  |
| F3 | Android 14+ CONNECTED_DEVICE 权限 |  |  |  |
| F4 | Android 13+ 拒绝通知权限降级 |  |  |  |
| F5 | Android 12 启动限制 |  |  |  |
| L1 | Release 构建 |  |  |  |
| L2 | Release 安装 |  |  |  |
| L3 | 签名验证 |  |  |  |
| L4 | R8 生效 |  |  |  |

## 10. 已知无法在本仓库内自证的部分

以下结论必须来自真机/真实账号，代码仓库与 CI 只能证明「已实现、能构建、单测通过」：

- 真实 BLE 扫描、RSSI 变化、GATT 连接与断开时序；
- Android 16 两个受保护广播是否实际投递；
- 各厂商 ROM 的权限弹窗行为与设置页跳转；
- Room v1→v2→v3→v4→v5→v6→v7 在真实旧数据上的迁移（本仓库已导出 schema 与 v6→v7 SQLite 迁移测试，但未跑真机迁移）；
- 真实 BLE notify 读数投递与各厂商固件字节格式（心率/电池/温度/NUS 的实际报文需按 §7.6 真机验收）；
- WorkManager 在真实网络切换下的调度与退避；
- 生产后端域名、真实账号体系与跨设备同步。
